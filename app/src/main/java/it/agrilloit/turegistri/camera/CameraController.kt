package it.agrilloit.turegistri.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import it.agrilloit.turegistri.gl.CompositeRenderer

/**
 * Apre le camere della combinazione simultanea, una alla volta, configurando per ognuna
 * una sola stream SurfaceTexture (anteprima/composito).
 *
 * Alla pressione di REC le sessioni vengono RI-CONFIGURATE aggiungendo il surface
 * del MediaRecorder già preparato di ogni camera ([beginRecording]); allo stop si
 * torna alla sessione di sola anteprima ([endRecording]). Questo è il pattern
 * standard preview+record: le superfici persistenti di MediaCodec senza encoder
 * configurato vengono rifiutate dalla sessione camera ("Unsupported set of
 * inputs/outputs").
 *
 * In modalità concorrente la garanzia hardware è 2 stream per camera a 720p:
 * se la risoluzione richiesta non è configurabile, l'intero pipeline riparte a 720p
 * (una sola volta), poi le camere non supportate vengono semplicemente saltate.
 */
class CameraController(
    context: Context,
    private val renderer: CompositeRenderer,
    private val listener: Listener,
) {

    interface Listener {
        /** Tutte le camere possibili sono aperte e in streaming. */
        fun onCamerasReady(setups: List<CameraSetup>)

        /** Messaggio non fatale (es. risoluzione ridotta, camera saltata). */
        fun onCameraWarning(message: String)

        /** Nessuna camera utilizzabile. */
        fun onCameraFatal(message: String)
    }

    data class CameraSetup(
        /** Indice della camera nel flusso di composizione (0 = A, 1 = B). */
        val index: Int,
        val id: String,
        val isFront: Boolean,
        val sensorOrientation: Int,
        val recordSize: Size,
        val previewSize: Size,
        /** Orientamento da passare a MediaRecorder.setOrientationHint. */
        val orientationHint: Int,
    )

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("TuRegistriCameras").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = java.util.concurrent.Executor { runnable -> handler.post(runnable) }

    private class CamWorker(val id: String, val index: Int) {
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var recordSize: Size? = null
        var active = false
        var retriedOpen = false
        /** Builder corrente per aggiornare zoom/focus senza riconfigurare. */
        var requestBuilder: CaptureRequest.Builder? = null
        var zoomRatio = 1f
        var manualFocus = false
        var focusDiopter = 0f
    }

    private var workers = listOf<CamWorker>()
    private var mirrorFront = true
    private var closed = true
    private var requestedWidth = 1280
    private var retried720 = false
    private var surfaces = listOf<Surface>()
    private var explicitIds: List<String>? = null
    private var stabilizeHint = true
    @Volatile
    private var torchOn = false
    @Volatile
    private var torchViaSetTorchMode = false

    /**
     * Scatta una foto JPEG alla MASSIMA risoluzione disponibile sulla camera
     * [cameraIndex]: ri-configura la sessione con un ImageReader JPEG, scatta
     * con TEMPLATE_STILL_CAPTURE e torna all'anteprima. Solo con stato IDLE
     * (in registrazione il recorder è attaccato alla sessione).
     */
    fun captureStillPhoto(cameraIndex: Int, onResult: (ByteArray?) -> Unit) {
        handler.post {
            val worker = workers.getOrNull(cameraIndex)
            val device = worker?.device
            if (worker == null || !worker.active || device == null || worker.index >= surfaces.size) {
                onResult(null)
                return@post
            }
            try {
                val chars = cameraManager.getCameraCharacteristics(worker.id)
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                val biggest = sizes?.maxByOrNull { it.width.toLong() * it.height }
                if (biggest == null) {
                    onResult(null)
                    return@post
                }
                android.util.Log.d(
                    "YouRecPhoto",
                    "foto max res su camera=${worker.id}: ${biggest.width}x${biggest.height}"
                )
                val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val front = isFront(worker.id)
                val jpegOrientation = if (front) {
                    (sensorOrientation) % 360
                } else {
                    (sensorOrientation) % 360
                }
                val reader = android.media.ImageReader.newInstance(
                    biggest.width, biggest.height, android.graphics.ImageFormat.JPEG, 2
                )
                var delivered = false
                val stSurface = surfaces[worker.index]

                fun restorePreview() {
                    reconfigureSession(worker, listOf(stSurface)) { }
                }

                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage()
                    var bytes: ByteArray? = null
                    if (image != null) {
                        runCatching {
                            val buffer = image.planes[0].buffer
                            bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        }
                        image.close()
                    }
                    if (!delivered) {
                        delivered = true
                        restorePreview()
                        onResult(bytes)
                    }
                }, handler)

                // Fail-safe: se in 5 s non arriva nulla, ripristina e segnala.
                handler.postDelayed({
                    if (!delivered) {
                        delivered = true
                        restorePreview()
                        onResult(null)
                    }
                }, 5000)

                reconfigureSession(worker, listOf(stSurface, reader.surface)) { ok ->
                    if (!ok) {
                        if (!delivered) {
                            delivered = true
                            restorePreview()
                            onResult(null)
                        }
                        return@reconfigureSession
                    }
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader.surface)
                            set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                            set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                            )
                        }
                        worker.session?.capture(request.build(), null, handler)
                    } catch (e: Exception) {
                        if (!delivered) {
                            delivered = true
                            restorePreview()
                            onResult(null)
                        }
                    }
                }
            } catch (e: Exception) {
                onResult(null)
            }
        }
    }

    /** Torcia LED sulle camere posteriori attive (FLASH_MODE_TORCH). */
    fun setTorch(enabled: Boolean) {
        torchOn = enabled
        handler.post { applyTorch() }
    }

    private fun applyTorch() {
        for (worker in workers.filter { it.active && it.requestBuilder != null }) {
            if (isFront(worker.id)) continue
            try {
                val chars = cameraManager.getCameraCharacteristics(worker.id)
                val flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                if (!flashAvailable) {
                    android.util.Log.d("YouRecTorch", "camera=${worker.id} senza unità flash")
                    continue
                }
                if (torchOn) {
                    // Meccanismo preferito: torcia di sistema (affidabile su Samsung).
                    var viaSystem = false
                    try {
                        cameraManager.setTorchMode(worker.id, true)
                        viaSystem = true
                    } catch (e: Exception) {
                        android.util.Log.d("YouRecTorch", "setTorchMode rifiutato: ${e.message}")
                    }
                    torchViaSetTorchMode = viaSystem
                    if (!viaSystem) {
                        // Fallback: FLASH_MODE_TORCH nella request in streaming.
                        worker.requestBuilder!!.set(
                            CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO
                        )
                        worker.requestBuilder!!.set(
                            CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH
                        )
                        worker.session?.setRepeatingRequest(
                            worker.requestBuilder!!.build(), null, handler
                        )
                    }
                    android.util.Log.d(
                        "YouRecTorch", "torcia ON camera=${worker.id} via=${if (viaSystem) "setTorchMode" else "request"}"
                    )
                } else {
                    runCatching { cameraManager.setTorchMode(worker.id, false) }
                    torchViaSetTorchMode = false
                    worker.requestBuilder!!.set(
                        CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF
                    )
                    worker.session?.setRepeatingRequest(
                        worker.requestBuilder!!.build(), null, handler
                    )
                    android.util.Log.d("YouRecTorch", "torcia OFF camera=${worker.id}")
                }
            } catch (e: Exception) {
                android.util.Log.e("YouRecTorch", "torcia fallita su camera=${worker.id}: ${e.message}")
            }
        }
    }

    /** Hint di stabilizzazione video applicato alle sessioni successive. */
    fun setStabilization(enabled: Boolean) {
        handler.post {
            stabilizeHint = enabled
            for (worker in workers.filter { it.active && it.requestBuilder != null }) {
                try {
                    worker.requestBuilder!!.set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        if (enabled) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                        else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    )
                    worker.session?.setRepeatingRequest(worker.requestBuilder!!.build(), null, handler)
                } catch (e: Exception) {
                    // Non tutte le camere supportano l'hint: ignorato.
                }
            }
        }
    }

    val activeCameraCount: Int get() = workers.count { it.active }
    val isClosed: Boolean get() = closed

    @SuppressLint("MissingPermission")
    fun start(
        recordWidth: Int,
        mirrorFront: Boolean,
        explicitIds: List<String>? = null,
    ) {
        handler.post {
            this.mirrorFront = mirrorFront
            this.closed = false
            this.requestedWidth = recordWidth
            this.explicitIds = explicitIds
            beginOpening(recordWidth)
        }
    }

    fun stop() {
        handler.post {
            closed = true
            closeAllWorkers()
            workers = emptyList()
        }
    }

    fun release() {
        stop()
        thread.quitSafely()
    }

    /**
     * Ri-configura le sessioni delle camere attive aggiungendo il surface del
     * recorder di ogni camera. Al termine chiama [onReady] con gli indici delle
     * camere effettivamente pronte (già in streaming verso il recorder).
     *
     * @param recorderSurfaces mappa indice camera -> surface di MediaRecorder.
     */
    fun beginRecording(
        recorderSurfaces: Map<Int, Surface>,
        onReady: (Set<Int>) -> Unit,
    ) {
        handler.post {
            val active = workers.filter { it.active && it.device != null }
            val ready = mutableSetOf<Int>()
            var pending = active.size
            if (pending == 0) {
                onReady(ready)
                return@post
            }
            for (worker in active) {
                val recorderSurface = recorderSurfaces[worker.index]
                if (recorderSurface == null || worker.index >= surfaces.size) {
                    pending--
                    continue
                }
                reconfigureSession(worker, listOf(surfaces[worker.index], recorderSurface)) { ok ->
                    if (ok) ready.add(worker.index)
                    pending--
                    if (pending == 0) onReady(ready.toSet())
                }
            }
        }
    }

    /** Torna alla sessione di sola anteprima (dopo lo stop dei recorder). */
    fun endRecording() {
        handler.post {
            for (worker in workers.filter { it.active && it.device != null }) {
                if (worker.index < surfaces.size) {
                    reconfigureSession(worker, listOf(surfaces[worker.index])) { ok ->
                        if (!ok) {
                            listener.onCameraWarning(
                                "Anteprima camera ${worker.index + 1} non ripristinata dopo la registrazione."
                            )
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ interno

    @SuppressLint("MissingPermission")
    private fun beginOpening(recordWidth: Int) {
        // Combinazione esplicita scelta dall'utente (se valida), altrimenti automatica.
        val explicit = explicitIds
            ?.let { ConcurrentCameraDetector.fromIds(cameraManager, it) }
        if (explicitIds != null && explicit == null) {
            listener.onCameraWarning(
                "Le camere scelte non possono funzionare insieme su questo telefono: combinazione automatica."
            )
        }
        val combo = explicit ?: ConcurrentCameraDetector.detect(cameraManager)
        if (combo.kind == ConcurrentCameraDetector.ComboKind.NONE) {
            listener.onCameraFatal("Nessuna camera disponibile sul dispositivo.")
            return
        }

        // Calcola le risoluzioni PRIMA di aprire: le superfici ST vanno create una volta sola.
        val sizes = ArrayList<Size>(combo.ids.size)
        for (id in combo.ids) {
            val size = pickSizeFor(id, recordWidth)
            if (size == null) {
                listener.onCameraWarning("Camera $id senza risoluzioni valide: ignorata.")
            }
            sizes.add(size ?: Size(1280, 720))
        }

        workers = combo.ids.mapIndexed { i, id -> CamWorker(id, i) }
        surfaces = renderer.prepareCameraInputs(sizes)
        if (surfaces.size != workers.size) {
            listener.onCameraFatal("Preparazione superfici camera fallita.")
            return
        }
        openCameraAt(0)
    }

    private fun pickSizeFor(id: String, recordWidth: Int): Size? {
        return try {
            val chars = cameraManager.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val target = Size(recordWidth, recordWidth * 9 / 16)
            pickSize(map.getOutputSizes(SurfaceTexture::class.java), target)
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraAt(index: Int) {
        if (closed) return
        if (index >= workers.size) {
            finishOpening()
            return
        }
        val worker = workers[index]
        try {
            cameraManager.openCamera(worker.id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (closed) {
                        device.close()
                        return
                    }
                    worker.device = device
                    worker.active = true
                    configurePreviewSession(worker)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (!closed) handleCameraFailure(worker, "Camera disconnessa", resolutionCause = false)
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (!closed) handleCameraFailure(worker, "Errore camera ($error)", resolutionCause = false, allowOpenRetry = true)
                }
            }, handler)
        } catch (e: CameraAccessException) {
            if (!closed) {
                handleCameraFailure(
                    worker, "Impossibile aprire la camera (${e.reason})",
                    resolutionCause = false, allowOpenRetry = true,
                )
            }
        } catch (e: SecurityException) {
            if (!closed) listener.onCameraFatal("Permesso camera negato.")
        }
    }

    private fun configurePreviewSession(worker: CamWorker) {
        val device = worker.device ?: return
        val index = worker.index
        try {
            val chars = cameraManager.getCameraCharacteristics(worker.id)
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val recordSize = workerSize(index)
                ?: throw IllegalStateException("Nessuna risoluzione valida")

            worker.recordSize = recordSize

            reconfigureSession(worker, listOf(surfaces[index])) { ok ->
                if (!ok) {
                    handleCameraFailure(worker, "Configurazione sessione fallita", resolutionCause = true)
                    return@reconfigureSession
                }
                renderer.setStreamInfo(
                    index,
                    CompositeRenderer.StreamInfo(
                        recordSize.width, recordSize.height,
                        sensorOrientation,
                        mirrorFront && isFront(worker.id),
                    )
                )
                openCameraAt(index + 1)
            }
        } catch (e: IllegalArgumentException) {
            handleCameraFailure(worker, e.message ?: "Configurazione non supportata", resolutionCause = true)
        } catch (e: Exception) {
            handleCameraFailure(worker, e.message ?: "Errore camera", resolutionCause = false)
        }
    }

    /**
     * Crea una nuova sessione per il worker con gli output dati e riparte con la
     * repeating request. Chiude la sessione precedente quando la nuova è pronta.
     */
    private fun reconfigureSession(
        worker: CamWorker,
        outputs: List<Surface>,
        onDone: (Boolean) -> Unit,
    ) {
        val device = worker.device ?: run {
            onDone(false)
            return
        }
        try {
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs.map { OutputConfiguration(it) },
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (closed) {
                            runCatching { session.close() }
                            onDone(false)
                            return
                        }
                        val previous = worker.session
                        worker.session = session
                        try {
                            val request =
                                device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                    for (target in outputs) addTarget(target)
                                    set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                                    )
                                    if (stabilizeHint) {
                                        set(
                                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                                        )
                                    }
                                }
                            worker.requestBuilder = request
                            if (torchOn && !isFront(worker.id)) {
                                handler.post { applyTorch() }
                            }
                            session.setRepeatingRequest(request.build(), null, handler)
                            runCatching { previous?.close() }
                            onDone(true)
                        } catch (e: Exception) {
                            runCatching { previous?.close() }
                            onDone(false)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        runCatching { session.close() }
                        onDone(false)
                    }
                }
            )
            device.createCaptureSession(config)
        } catch (e: Exception) {
            onDone(false)
        }
    }

    /**
     * Gestisce il fallimento di una camera. Se la causa è la risoluzione e non abbiamo
     * già provato 720p, fa ripartire l'intero pipeline a 720p; altrimenti salta la camera.
     */
    private fun handleCameraFailure(
        worker: CamWorker,
        message: String,
        resolutionCause: Boolean,
        allowOpenRetry: Boolean = false,
    ) {
        closeWorker(worker)
        worker.active = false

        // Gli OPENING_FAILED (spesso temporanei su Samsung) riprovano una volta.
        if (allowOpenRetry && !worker.retriedOpen && !closed) {
            worker.retriedOpen = true
            listener.onCameraWarning("Riprovo la camera ${worker.index + 1}…")
            handler.postDelayed({ if (!closed) openCameraAt(worker.index) }, 450)
            return
        }

        if (resolutionCause && !retried720 && requestedWidth > FALLBACK_WIDTH) {
            retried720 = true
            closeAllWorkers()
            listener.onCameraWarning("Risoluzione non supportata in simultanea: passo a 720p.")
            requestedWidth = FALLBACK_WIDTH
            beginOpening(FALLBACK_WIDTH)
            return
        }

        listener.onCameraWarning("Camera ${worker.index + 1} non disponibile: $message")
        openCameraAt(worker.index + 1)
    }

    private fun finishOpening() {
        if (closed) return
        val setups = workers.filter { it.active }.mapNotNull { w ->
            val recordSize = w.recordSize ?: return@mapNotNull null
            val sensorOrientation = runCatching {
                cameraManager.getCameraCharacteristics(w.id)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            }.getOrDefault(90)
            CameraSetup(
                index = w.index,
                id = w.id,
                isFront = isFront(w.id),
                sensorOrientation = sensorOrientation,
                recordSize = recordSize,
                previewSize = recordSize,
                orientationHint = sensorOrientation,
            )
        }
        if (setups.isEmpty()) {
            listener.onCameraFatal("Nessuna camera configurata.")
        } else {
            listener.onCamerasReady(setups)
        }
    }

    private fun workerSize(index: Int): Size? {
        if (index !in surfaces.indices) return null
        val worker = workers.getOrNull(index) ?: return null
        return try {
            val chars = cameraManager.getCameraCharacteristics(worker.id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            val target = Size(requestedWidth, requestedWidth * 9 / 16)
            pickSize(map.getOutputSizes(SurfaceTexture::class.java), target)
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ controlli live

    /**
     * Applica lo zoom (CONTROL_ZOOM_RATIO, API 30) alla camera indicata.
     * Il rapporto viene ristretto all'intervallo supportato dal sensore.
     */
    fun applyZoom(cameraIndex: Int, ratio: Float) {
        handler.post {
            val worker = workers.getOrNull(cameraIndex) ?: return@post
            val builder = worker.requestBuilder ?: return@post
            val range = runCatching {
                cameraManager.getCameraCharacteristics(worker.id)
                    .get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            }.getOrNull()
            val clamped = range?.let { r -> ratio.coerceIn(r.lower, r.upper) } ?: 1f
            worker.zoomRatio = clamped
            try {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clamped)
                worker.session?.setRepeatingRequest(builder.build(), null, handler)
            } catch (e: Exception) {
                listener.onCameraWarning("Zoom non applicabile: ${e.message}")
            }
        }
    }

    /** Zoom attuale della camera (per sincronizzare la UI). */
    fun currentZoom(cameraIndex: Int): Float =
        workers.getOrNull(cameraIndex)?.zoomRatio ?: 1f

    /**
     * Focus: manuale (distanza in diottrie, 0 = infinito) oppure autofocus continuo.
     */
    fun setFocusMode(cameraIndex: Int, manual: Boolean, diopter: Float = 0f) {
        handler.post {
            val worker = workers.getOrNull(cameraIndex) ?: return@post
            val builder = worker.requestBuilder ?: return@post
            worker.manualFocus = manual
            worker.focusDiopter = diopter
            try {
                if (manual) {
                    val max = runCatching {
                        cameraManager.getCameraCharacteristics(worker.id)
                            .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                    }.getOrNull() ?: 10f
                    builder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_OFF
                    )
                    builder.set(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        diopter.coerceIn(0f, max)
                    )
                } else {
                    builder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    )
                }
                worker.session?.setRepeatingRequest(builder.build(), null, handler)
            } catch (e: Exception) {
                listener.onCameraWarning("Focus non applicabile: ${e.message}")
            }
        }
    }

    /**
     * Tap-to-focus: finestra di misurazione AF/AE attorno al punto (frazioni
     * 0..1 relative alla vista della camera) + trigger AF singolo.
     */
    fun tapToFocus(cameraIndex: Int, x: Float, y: Float) {
        handler.post {
            val worker = workers.getOrNull(cameraIndex) ?: return@post
            val builder = worker.requestBuilder ?: return@post
            val size = worker.recordSize ?: return@post
            try {
                val half = (minOf(size.width, size.height) / 5).coerceAtLeast(48)
                val cx = (x * size.width).toInt().coerceIn(half, size.width - half)
                val cy = (y * size.height).toInt().coerceIn(half, size.height - half)
                val rect = android.graphics.Rect(cx - half, cy - half, cx + half, cy + half)
                // CONTROL_AF/AE_REGIONS sono int[]: x0,y0,x1,y1,weight per ogni area.
                val regions = intArrayOf(rect.left, rect.top, rect.right, rect.bottom, 900)
                @Suppress("UNCHECKED_CAST")
                val afKey = CaptureRequest.CONTROL_AF_REGIONS as CaptureRequest.Key<IntArray>
                @Suppress("UNCHECKED_CAST")
                val aeKey = CaptureRequest.CONTROL_AE_REGIONS as CaptureRequest.Key<IntArray>
                builder.set(afKey, regions)
                builder.set(aeKey, regions)
                if (worker.manualFocus) {
                    // In MF il tap sposta la lente: mappa la Y sulla scala diottrica.
                    val max = runCatching {
                        cameraManager.getCameraCharacteristics(worker.id)
                            .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                    }.getOrNull() ?: 10f
                    val diopter = (1f - y) * max
                    worker.focusDiopter = diopter
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopter)
                } else {
                    builder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_AUTO
                    )
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                    worker.session?.capture(builder.build(), null, handler)
                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                }
                worker.session?.setRepeatingRequest(builder.build(), null, handler)
            } catch (e: Exception) {
                listener.onCameraWarning("Tap-to-focus non disponibile: ${e.message}")
            }
        }
    }

    /** True se la camera indicata è la frontale. */
    fun isFrontCamera(cameraIndex: Int): Boolean =
        workers.getOrNull(cameraIndex)?.let { isFront(it.id) } ?: false

    private fun isFront(id: String): Boolean {
        return runCatching {
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
        }.getOrDefault(false)
    }

    private fun closeWorker(worker: CamWorker) {
        runCatching { worker.session?.close() }
        runCatching { worker.device?.close() }
        worker.session = null
        worker.device = null
    }

    private fun closeAllWorkers() {
        for (w in workers) closeWorker(w)
    }

    private fun pickSize(sizes: Array<Size>?, target: Size): Size? {
        if (sizes.isNullOrEmpty()) return null
        val targetAspect = target.width.toFloat() / target.height
        // Distanza di aspect (accetta anche l'inverso: device con buffer già portrait),
        // poi l'area più vicina. Funziona con qualsiasi elenco di risoluzioni.
        return sizes.minByOrNull { s ->
            val aspect = s.width.toFloat() / s.height
            val aspectDist = kotlin.math.min(
                kotlin.math.abs(aspect - targetAspect),
                kotlin.math.abs(1f / aspect - targetAspect),
            )
            val areaDist = kotlin.math.abs(
                s.width.toLong() * s.height - target.width.toLong() * target.height
            )
            aspectDist * 1_000_000_000f + areaDist
        }
    }

    private companion object {
        const val FALLBACK_WIDTH = 1280
    }
}
