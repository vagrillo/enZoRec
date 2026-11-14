package it.agrilloit.turegistri.record

import android.content.Context
import android.media.MediaCodec
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import it.agrilloit.turegistri.audio.MicEnumerator
import it.agrilloit.turegistri.audio.MicRecorder
import it.agrilloit.turegistri.camera.CameraController
import it.agrilloit.turegistri.gl.CompositeRenderer
import it.agrilloit.turegistri.model.LayoutEngine
import it.agrilloit.turegistri.model.SessionManifest
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Motore di registrazione: un singleton che vive oltre l'Activity e coordina
 * camere (anteprima composita GL), microfoni separati, recorder individuali
 * e il video composito. La UI e il servizio foreground si agganciano via [Callbacks].
 */
class RecordingEngine private constructor(appContext: Context) {

    companion object {
        const val COMPOSITE_WIDTH = 720
        const val COMPOSITE_HEIGHT = 1280
        const val FPS = 30

        @Volatile
        private var instance: RecordingEngine? = null

        fun get(context: Context): RecordingEngine =
            instance ?: synchronized(this) {
                instance ?: RecordingEngine(context.applicationContext).also { instance = it }
            }
    }

    enum class State { IDLE, PREPARING, RECORDING, PAUSED, STOPPING }

    interface Callbacks {
        fun onCamerasReady(cameraCount: Int, comboLabel: String, micLabel: String)
        fun onCameraWarning(message: String)
        fun onCameraFatal(message: String)
        fun onRecordingStarted()
        fun onRecordingStopped(session: SessionResult)
        fun onRecordingError(message: String)
    }

    data class Settings(
        val recordWidth: Int = 1280,
        val mirrorFront: Boolean = true,
        val compositeAudio: Boolean = true,
        val fitFrame: Boolean = true,
        /** Stabilizzazione video (hint hardware, se supportata). */
        val stabilize: Boolean = true,
        /** Soppressione rumore di sistema (NoiseSuppressor) sulle catture microfono. */
        val noiseSuppression: Boolean = true,
        /** false = registra con una sola camera (l'ultima usata o la principale). */
        val dualCamera: Boolean = false,
        /** Modalità "Screen display": schermo + camera frontale + microfoni + audio app. */
        val screenDisplay: Boolean = false,
        /** Ultima camera usata: usata all'avvio in modalità singola. */
        val lastCameraId: String? = null,
        /** Camera per lo stream A (null = automatica). */
        val cameraIdA: String? = null,
        /** Camera per lo stream B (null = automatica). */
        val cameraIdB: String? = null,
    )

    data class SessionResult(
        val dir: File,
        val files: List<File>,
        val notes: List<String>,
        val markers: List<Long> = emptyList(),
    )

    private val context = appContext
    private val thread = HandlerThread("TuRegistriEngine").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = CopyOnWriteArrayList<Callbacks>()
    private val recordingFlag = AtomicBoolean(false)

    val renderer = CompositeRenderer()
    private var cameraController: CameraController? = null

    @Volatile
    var settings: Settings = Settings()
        private set

    @Volatile
    var layoutState: LayoutEngine.LayoutState = LayoutEngine.LayoutState()
        private set

    private val _stateFlow = kotlinx.coroutines.flow.MutableStateFlow(State.IDLE)
    val stateFlow: kotlinx.coroutines.flow.StateFlow<State> = _stateFlow

    /** Faccia della camera attiva (stream A): vera quando le camere sono pronte,
     *  ottimisticamente anticipata allo swap. */
    private val _frontActiveFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
    val frontActiveFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _frontActiveFlow

    /** Modalità Screen display attiva (per nascondere la bottom nav nell'app). */
    private val _screenDisplayFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    val screenDisplayFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _screenDisplayFlow
    val state: State
        get() = _stateFlow.value

    @Volatile
    private var cameraSetupsInternal = listOf<CameraController.CameraSetup>()

    // Consenso MediaProjection per la modalità Screen display (schermo + audio app).
    @Volatile
    var screenDisplayWanted = false
        private set
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var screenStreams: it.agrilloit.turegistri.record.ScreenStreams? = null
    @Volatile
    private var screenOkThisSession = false

    /** true se la MediaProjection è pronta (consenso già dato). */
    fun hasScreenProjection(): Boolean = mediaProjection != null

    /** Crea la MediaProjection dal consenso: una sola volta per sessione modalità. */
    fun setScreenProjection(resultCode: Int, data: android.content.Intent) {
        if (mediaProjection != null) return
        runCatching {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, data)
            screenDisplayWanted = true
        }.onFailure {
            android.util.Log.e("YouRecScreen", "getMediaProjection: ${it.message}")
        }
    }

    /** Rilascia la projection (uscita dalla modalità Screen display). */
    fun releaseScreenProjection() {
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        screenDisplayWanted = false
    }
    private var micPlan: MicEnumerator.Plan? = null

    @Volatile
    private var preferredMicAddress: String? = null
    private var cameraSetups = listOf<CameraController.CameraSetup>()
    private var comboLabel = ""

    // Stato della sessione in corso
    private class CamTrack(
        val setup: CameraController.CameraSetup,
        val recorder: VideoRecorder,
        var startedEpochMs: Long = 0,
        var startedElapsedNanos: Long = 0,
    )

    private var sessionDir: File? = null
    private var camTracks = listOf<CamTrack>()
    private var compositeRecorder: VideoRecorder? = null
    private var compositeFile: File? = null
    private var compositeHasAudio = false
    private var compositeStartedEpochMs = 0L
    private var compositeStartedElapsedNanos = 0L
    private var micRecorder: MicRecorder? = null
    private val notes = ArrayList<String>()

    var startedAtRealtimeMs: Long = 0
        private set

    // Pausa: istante della pausa corrente e totale accumulato (per il timecode).
    private var pausedAtRealtimeMs = 0L
    private var pausedTotalMs = 0L

    /** Marker aggiunti durante la registrazione (ms dalla timeline di registrazione). */
    private val markers = mutableListOf<Long>()

    fun elapsedRecordingMs(): Long {
        if (_stateFlow.value == State.IDLE || startedAtRealtimeMs == 0L) return 0
        val now = if (pausedAtRealtimeMs > 0) pausedAtRealtimeMs else SystemClock.elapsedRealtime()
        return (now - startedAtRealtimeMs - pausedTotalMs).coerceAtLeast(0)
    }

    /** Pausa tutte le sorgenti (recorder e microfoni). Ritorna false se non in registrazione. */
    fun pauseRecording(): Boolean {
        if (_stateFlow.value != State.RECORDING || pausedAtRealtimeMs > 0) return false
        pausedAtRealtimeMs = SystemClock.elapsedRealtime()
        camTracks.forEach { runCatching { it.recorder.pause() } }
        micRecorder?.pause()
        _stateFlow.value = State.PAUSED
        return true
    }

    fun resumeRecording(): Boolean {
        if (_stateFlow.value != State.PAUSED || pausedAtRealtimeMs == 0L) return false
        pausedTotalMs += SystemClock.elapsedRealtime() - pausedAtRealtimeMs
        pausedAtRealtimeMs = 0
        camTracks.forEach { runCatching { it.recorder.resume() } }
        micRecorder?.resume()
        _stateFlow.value = State.RECORDING
        return true
    }

    /** Aggiunge un marker al take corrente; ritorna il ms sulla timeline. */
    fun addMarker(): Long {
        val ms = elapsedRecordingMs()
        markers.add(ms)
        return ms
    }

    fun takeMarkers(): List<Long> = markers.toList()

    val isRecording: Boolean get() = _stateFlow.value == State.RECORDING

    // ------------------------------------------------------------------ ciclo di vita

    fun ensureStarted() {
        renderer.start()
    }

    fun addCallbacks(c: Callbacks) {
        callbacks.add(c)
    }

    fun removeCallbacks(c: Callbacks) {
        callbacks.remove(c)
    }

    fun setPreviewSurface(surface: Surface?, width: Int, height: Int) {
        renderer.setPreviewSurface(surface, width, height)
    }

    fun setLayout(state: LayoutEngine.LayoutState) {
        layoutState = state
        renderer.setLayout(state)
    }

    fun applySettings(s: Settings) {
        val old = settings
        settings = s
        _screenDisplayFlow.value = s.screenDisplay
        if (old.stabilize != s.stabilize) {
            cameraController?.setStabilization(s.stabilize)
        }
        val camerasChanged = old.recordWidth != s.recordWidth ||
            old.cameraIdA != s.cameraIdA ||
            old.cameraIdB != s.cameraIdB ||
            old.dualCamera != s.dualCamera
        if (camerasChanged && _stateFlow.value == State.IDLE) {
            handler.post { startCamerasInternal() }
        }
    }

    /** Avvia/riavvia le camere con le impostazioni correnti. */
    fun startCameras() {
        handler.post { startCamerasInternal() }
    }

    fun shutdown() {
        handler.post {
            if (state == State.RECORDING) stopRecordingInternal()
            cameraController?.release()
            cameraController = null
            renderer.release()
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------ registrazione

    fun startRecording() {
        handler.post {
            if (state != State.IDLE) {
                notifyError("Registrazione già in corso.")
                return@post
            }
            if (cameraSetups.isEmpty()) {
                // Le camere potrebbero stare riaprendo (es. cambio modalità):
                // attendi fino a 6 s invece di fallire subito.
                android.util.Log.d("YouRecRec", "camere non pronte: attendo…")
                var waited = 0
                while (cameraSetups.isEmpty() && waited < 6000) {
                    Thread.sleep(200)
                    waited += 200
                }
                if (cameraSetups.isEmpty()) {
                    notifyError("Camere non ancora pronte.")
                    return@post
                }
                android.util.Log.d("YouRecRec", "camere pronte dopo ${waited}ms")
            }
            _stateFlow.value = State.PREPARING
            recordingFlag.set(true)
            notes.clear()
            markers.clear()
            screenOkThisSession = false  // azzerato: sarà true solo se i flussi partono
            pausedAtRealtimeMs = 0
            pausedTotalMs = 0

            val sdf = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            val dir = File(sessionsRoot(), sdf.format(Date()))
            if (!dir.exists() && !dir.mkdirs()) {
                _stateFlow.value = State.IDLE
                notifyError("Impossibile creare la cartella di sessione.")
                return@post
            }
            sessionDir = dir

            // 1. Recorder individuali per camera: prepare() crea le surface
            //    (recorder.getSurface) che verranno aggiunte alle sessioni camera.
            val tracks = ArrayList<CamTrack>()
            val recorderSurfaces = HashMap<Int, Surface>()
            cameraSetups.forEach { setup ->
                val recorder = VideoRecorder(
                    file = File(dir, "cam${setup.index}.mp4"),
                    videoSize = setup.recordSize,
                    fps = FPS,
                    bitrate = if (setup.recordSize.width >= 1920) 10_000_000 else 6_000_000,
                    orientationHint = setup.orientationHint,
                    withAudio = false,
                )
                try {
                    recorder.prepare()
                    recorderSurfaces[setup.index] = recorder.inputSurface
                        ?: error("Surface recorder non disponibile")
                    tracks.add(CamTrack(setup, recorder))
                } catch (e: Exception) {
                    recorder.release()
                    notes.add("Camera ${setup.index} non registrata: ${e.message}")
                }
            }
            camTracks = tracks

            // 1-bis. Schermo + audio app (modalità Screen display).
            var screenOk = false
            val projection = mediaProjection
            if (settings.screenDisplay && projection != null) {
                try {
                    val streams = it.agrilloit.turegistri.record.ScreenStreams.create(
                        context, projection, dir,
                    ) { msg -> notes.add(msg) }
                    if (streams != null && streams.start()) {
                        screenStreams = streams
                        screenOk = true
                        screenOkThisSession = true
                    } else {
                        streams?.stop()
                        notes.add("Cattura schermo non riuscita.")
                    }
                } catch (e: Exception) {
                    notes.add("Cattura schermo: ${e.message}")
                }
            } else if (settings.screenDisplay) {
                notes.add("Screen display senza consenso schermo: flusso schermo saltato.")
            }

            // 2. Composito (con audio se previsto; fallback senza audio).
            //    Usa recorder.getSurface() come le camere: il pattern della surface
            //    persistente di MediaCodec non riceveva frame dal GL su questo device.
            compositeHasAudio = false
            var composite: VideoRecorder? = null
            if (settings.compositeAudio) {
                composite = try {
                    buildComposite(dir, withAudio = true).also {
                        compositeHasAudio = true
                    }
                } catch (e: Exception) {
                    notes.add("Composito senza audio (cattura audio non disponibile).")
                    null
                }
            }
            if (composite == null) {
                composite = try {
                    buildComposite(dir, withAudio = false)
                } catch (e: Exception) {
                    notes.add("Composito non registrato: ${e.message}")
                    null
                }
            }
            compositeRecorder = composite
            if (composite != null) {
                val compInput = composite.inputSurface
                if (compInput != null) {
                    renderer.startEncode(compInput, COMPOSITE_WIDTH, COMPOSITE_HEIGHT)
                } else {
                    notes.add("Composito non registrato: surface non disponibile.")
                }
            }

            // 3. Ri-configura le sessioni camera aggiungendo i recorder.
            if (tracks.isEmpty() && composite == null) {
                renderer.stopEncode()
                _stateFlow.value = State.IDLE
                notifyError("Nessun flusso registrabile.")
                return@post
            }
            cameraController?.beginRecording(recorderSurfaces) { readyIndexes ->
                // Callback sul thread camere.
                if (state != State.PREPARING) return@beginRecording

                // Camere non riconfigurate: scarta i loro recorder.
                val ready = ArrayList<CamTrack>()
                for (track in tracks) {
                    if (track.setup.index !in readyIndexes) {
                        track.recorder.stop()
                        notes.add("Camera ${track.setup.index} non riconfigurata per la registrazione.")
                    } else {
                        ready.add(track)
                    }
                }
                camTracks = ready

                // 4. Microfoni separati.
                micRecorder = null
                micPlan?.let { plan ->
                    val mic = MicRecorder(plan, dir, { msg -> notes.add(msg) }, settings.noiseSuppression)
                    if (mic.start()) {
                        micRecorder = mic
                    } else {
                        notes.add("Microfoni non disponibili.")
                    }
                }

                // 5. Start di tutto, nello stesso istante.
                if (ready.isEmpty() && composite == null) {
                    micRecorder?.stop()
                    micRecorder = null
                    renderer.stopEncode()
                    _stateFlow.value = State.IDLE
                    notifyError("Nessun flusso registrabile.")
                    return@beginRecording
                }
                val started = ArrayList<CamTrack>()
                for (track in ready) {
                    try {
                        track.startedElapsedNanos = track.recorder.start()
                        track.startedEpochMs = System.currentTimeMillis()
                        started.add(track)
                    } catch (e: Exception) {
                        track.recorder.stop()
                        notes.add("Camera (${track.setup.id}) non avviata: ${e.message}")
                    }
                }
                camTracks = started
                if (composite != null) {
                    try {
                        compositeStartedElapsedNanos = composite.start()
                        compositeStartedEpochMs = System.currentTimeMillis()
                    } catch (e: Exception) {
                        notes.add("Composito non avviato: ${e.message}")
                        renderer.stopEncode()
                        composite.stop()
                        compositeRecorder = null
                        compositeFile = null
                        compositeHasAudio = false
                    }
                }

                _stateFlow.value = State.RECORDING
                startedAtRealtimeMs = SystemClock.elapsedRealtime()
                mainHandler.post { callbacks.forEach { it.onRecordingStarted() } }
            }
        }
    }

    fun stopRecording() {
        handler.post { stopRecordingInternal() }
    }

    // ------------------------------------------------------------------ interno

    private fun stopRecordingInternal() {
        if (state != State.RECORDING) return
        _stateFlow.value = State.STOPPING
        recordingFlag.set(false)

        // Dati per il manifest, raccolti PRIMA del teardown.
        val streams = screenStreams
        screenStreams = null
        val screenOkSnapshot = screenOkThisSession
        val tracksSnapshot = camTracks
        val mic = micRecorder
        val micTracksSnapshot = mic?.trackInfos ?: emptyList()
        val micLabel = mic?.label ?: ""
        val micEpoch = mic?.startedAtEpochMs ?: 0
        val micNanos = mic?.startedAtElapsedNanos ?: 0
        val compositeActive = compositeRecorder != null
        val compFile = compositeFile
        val compAudio = compositeHasAudio
        val compEpoch = compositeStartedEpochMs
        val compNanos = compositeStartedElapsedNanos

        // 1. Composito.
        val compositeOk = compositeRecorder?.stop() ?: false
        if (compositeActive && !compositeOk) {
            notes.add("Composito non finalizzato.")
        }
        renderer.stopEncode()
        compositeRecorder = null
        runCatching { streams?.stop() }

        // 2. Composito tra i file salvati (era mancante: il take risultava senza audio).
        val files = ArrayList<File>()
        if (compositeOk) {
            compositeFile?.let { files.add(it) }
        }
        // 3. Camere individuali.
        for (track in tracksSnapshot) {
            if (track.recorder.stop()) {
                files.add(track.recorder.file)
            } else {
                notes.add("Traccia camera (${track.setup.id}) vuota, scartata.")
            }
        }
        camTracks = emptyList()

        if (screenOkSnapshot) {
            sessionDir?.let { d ->
                File(d, "schermo.mp4").takeIf { it.exists() }?.let(files::add)
                File(d, "app_audio.wav").takeIf { it.exists() }?.let(files::add)
            }
        }

        // 3. Torna alle sessioni di sola anteprima (i recorder sono chiusi).
        cameraController?.endRecording()

        // 4. Microfoni.
        mic?.stop()
        files.addAll(micTracksSnapshot.map { it.file })
        micRecorder = null

        // 4. Manifest + notifica.
        val dir = sessionDir
        if (dir != null) {
            val manifest = SessionManifest.build(
                appVersion = appVersion(),
                manufacturer = android.os.Build.MANUFACTURER,
                model = android.os.Build.MODEL,
                androidRelease = android.os.Build.VERSION.RELEASE ?: "",
                sdkInt = android.os.Build.VERSION.SDK_INT,
                createdAtEpochMs = System.currentTimeMillis(),
                layout = layoutState.mode.name,
                cameras = tracksSnapshot.map { track ->
                    SessionManifest.CameraEntry(
                        cameraId = track.setup.id,
                        isFront = track.setup.isFront,
                        sensorOrientation = track.setup.sensorOrientation,
                        width = track.setup.recordSize.width,
                        height = track.setup.recordSize.height,
                        fps = FPS,
                        file = track.recorder.file.name,
                        startedEpochMs = track.startedEpochMs,
                        startedElapsedNanos = track.startedElapsedNanos,
                        orientationHint = track.setup.orientationHint,
                    )
                },
                audio = if (mic != null && micTracksSnapshot.isNotEmpty()) {
                    SessionManifest.AudioEntry(
                        strategy = micLabel,
                        startedEpochMs = micEpoch,
                        startedElapsedNanos = micNanos,
                        tracks = micTracksSnapshot.map {
                            SessionManifest.AudioTrackEntry(
                                file = it.file.name,
                                channels = it.channels,
                                sampleRate = MicEnumerator.SAMPLE_RATE,
                                micDescription = it.micDescription,
                            )
                        },
                    )
                } else {
                    null
                },
                screen = if (screenOkSnapshot) {
                    SessionManifest.ScreenEntry(
                        file = "schermo.mp4",
                        appAudioFile = if (micTracksSnapshot.isNotEmpty() || true) "app_audio.wav" else null,
                    )
                } else {
                    null
                },
                composite = if (compositeActive && compositeOk && compFile != null) {
                    SessionManifest.CompositeEntry(
                        file = compFile.name,
                        width = COMPOSITE_WIDTH,
                        height = COMPOSITE_HEIGHT,
                        fps = FPS,
                        hasAudio = compAudio,
                        layout = layoutState.mode.name,
                        startedEpochMs = compEpoch,
                        startedElapsedNanos = compNanos,
                    )
                } else {
                    null
                },
            )
            try {
                File(dir, "session.json").writeText(manifest)
                files.add(File(dir, "session.json"))
            } catch (e: Exception) {
                notes.add("Manifest non scritto: ${e.message}")
            }
            MediaScannerConnection.scanFile(
                context,
                files.map { it.absolutePath }.toTypedArray(),
                null,
                null
            )
            val result = SessionResult(dir, files.filter { it.exists() }, notes.toList(), markers.toList())
            startedAtRealtimeMs = 0
            pausedAtRealtimeMs = 0
            pausedTotalMs = 0
            _stateFlow.value = State.IDLE
            mainHandler.post { callbacks.forEach { it.onRecordingStopped(result) } }
        } else {
            _stateFlow.value = State.IDLE
        }
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }

    private fun buildComposite(dir: File, withAudio: Boolean): VideoRecorder {
        val recorder = VideoRecorder(
            file = File(dir, "composito.mp4"),
            videoSize = Size(COMPOSITE_WIDTH, COMPOSITE_HEIGHT),
            fps = FPS,
            bitrate = 8_000_000,
            orientationHint = 0,
            withAudio = withAudio,
        )
        recorder.prepare()
        compositeFile = recorder.file
        compositeHasAudio = withAudio
        return recorder
    }

    private fun startCamerasInternal() {
        if (state == State.RECORDING || state == State.PREPARING) return
        cameraController?.release()
        val controller = CameraController(context, renderer, object : CameraController.Listener {
            override fun onCamerasReady(setups: List<CameraController.CameraSetup>) {
                handler.post {
                    cameraSetups = setups
                    cameraSetupsInternal = setups
                    _frontActiveFlow.value = setups.firstOrNull()?.isFront ?: false
                    comboLabel = describeCombo(setups)
                    mainHandler.post {
                        callbacks.forEach {
                            it.onCamerasReady(setups.size, comboLabel, micPlan?.label ?: "microfono")
                        }
                    }
                }
            }

            override fun onCameraWarning(message: String) {
                mainHandler.post { callbacks.forEach { it.onCameraWarning(message) } }
            }

            override fun onCameraFatal(message: String) {
                cameraSetups = emptyList()
                mainHandler.post { callbacks.forEach { it.onCameraFatal(message) } }
            }
        })
        cameraController = controller
        ensureMicPlan()
        controller.setStabilization(settings.stabilize)
        // Modalità di avvio: singola con l'ultima camera usata (default) oppure
        // doppia con la coppia scelta/completata con una partner simultanea.
        val ids = listOfNotNull(settings.cameraIdA, settings.cameraIdB)
        val explicit: List<String>? = when {
            !settings.dualCamera -> {
                val single = settings.cameraIdA
                    ?: settings.lastCameraId
                        ?.takeIf { it in cameraManager().cameraIdList }
                    ?: it.agrilloit.turegistri.camera.CameraCatalog
                        .catalog(cameraManager()).firstOrNull { !it.isFront }?.id
                single?.let { listOf(it) }
            }

            ids.size == 2 -> ids
            ids.size == 1 -> {
                // Dual richiesto con una sola camera scelta: aggiungi una partner simultanea.
                concurrentPartner(ids[0])?.let { listOf(ids[0], it) }
            }

            else -> null
        }
        controller.start(
            settings.recordWidth,
            settings.mirrorFront,
            explicit,
        )
    }

    /** L'altra camera della prima combinazione simultanea che contiene [cameraId]. */
    private fun concurrentPartner(cameraId: String): String? {
        val combos: Set<Set<String>> = cameraManager().concurrentCameraIds
        return combos.firstOrNull { it.contains(cameraId) }
            ?.firstOrNull { it != cameraId }
    }

    private fun cameraManager(): android.hardware.camera2.CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager

    private fun ensureMicPlan() {
        if (micPlan == null) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            micPlan = try {
                MicEnumerator.plan(am)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun describeCombo(setups: List<CameraController.CameraSetup>): String {
        if (setups.isEmpty()) return ""
        if (setups.size == 1) return "singola camera"
        val hasFront = setups.any { it.isFront }
        val hasBack = setups.any { !it.isFront }
        return when {
            hasFront && hasBack -> "posteriore + frontale"
            else -> "${setups.size} camere posteriori"
        }
    }

    private fun notifyError(message: String) {
        mainHandler.post { callbacks.forEach { it.onRecordingError(message) } }
    }

    private fun sessionsRoot(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "sessioni")
    }

    // ------------------------------------------------------------------ API per la UI YouRec

    fun comboLabel(): String = comboLabel
    fun cameraCount(): Int = cameraController?.activeCameraCount ?: 0
    fun applyZoom(index: Int, ratio: Float) = cameraController?.applyZoom(index, ratio)
    fun currentZoom(index: Int): Float = cameraController?.currentZoom(index) ?: 1f
    fun setFocusMode(index: Int, manual: Boolean, diopter: Float = 0f) =
        cameraController?.setFocusMode(index, manual, diopter)

    fun tapToFocus(index: Int, x: Float, y: Float) = cameraController?.tapToFocus(index, x, y)

    /** Livello RMS 0..1 del microfono (prima traccia) per il badge live. */
    fun micLevel(): Float = micRecorder?.level?.get() ?: 0f

    fun micLabel(): String = micRecorder?.label
        ?: micPlan?.label
        ?: "microfono"

    /** Elenco dei microfoni integrati: (address, descrizione) per la scelta in UI. */
    fun micDevices(): List<Pair<String, String>> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return it.agrilloit.turegistri.audio.MicEnumerator.builtinMics(am)
            .map { (it.address ?: "") to "Microfono ${it.address ?: it.id}" }
    }

    /**
     * Seleziona il microfono per il prossimo take (null = automatico).
     * Se non in registrazione riapplica subito il piano per aggiornare l'etichetta.
     */
    fun setPreferredMic(address: String?) {
        preferredMicAddress = address
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        micPlan = try {
            MicEnumerator.plan(am, address)
        } catch (e: Exception) {
            null
        }
        if (_stateFlow.value != State.RECORDING) {
            ensureMicPlan()
            if (_stateFlow.value == State.IDLE && micPlan != null && cameraSetups.isNotEmpty()) {
                // Solo etichetta: la cattura riparte col prossimo take.
                mainHandler.post {
                    callbacks.forEach {
                        it.onCamerasReady(cameraSetups.size, comboLabel, micPlan?.label ?: "microfono")
                    }
                }
            }
        }
    }

    fun preferredMicAddress(): String? = preferredMicAddress

    /**
     * Scambio camere:
     *  - dual: scambia le LANES (A<->B) a livello di composizione — istantaneo,
     *    funziona ANCHE in registrazione (il composito riflette lo scambio);
     *  - singola: cambia fisicamente camera (frontale <-> posteriore), solo da fermi.
     */
    fun swapCameras(): Boolean {
        if (settings.dualCamera) {
            layoutState = layoutState.copy(swapped = !layoutState.swapped)
            renderer.setLayout(layoutState)
            return true
        }
        if (_stateFlow.value != State.IDLE) return false
        val catalog = it.agrilloit.turegistri.camera.CameraCatalog.catalog(cameraManager())
        val activeA = cameraSetupsInternal.getOrNull(0)?.id ?: settings.cameraIdA
        val current = settings.cameraIdA ?: activeA ?: return false
        val currentFront = catalog.firstOrNull { it.id == current }?.isFront ?: false
        val other = catalog.firstOrNull { it.isFront != currentFront }?.id ?: return false
        settings = settings.copy(cameraIdA = other, cameraIdB = null, lastCameraId = other)
        handler.post { startCamerasInternal() }
        return true
    }

    /** Prima camera attualmente attiva (per persistere "ultima camera usata"). */
    fun firstActiveCameraId(): String? = cameraSetupsInternal.firstOrNull()?.id

    /** true se la camera attiva (stream A) è la frontale. */
    fun isFrontActive(): Boolean = cameraSetupsInternal.firstOrNull()?.isFront ?: false

    /** Torcia LED sulle camere posteriori attive. */
    /** Effetto live corrente (uniform per lane nel CompositeRenderer). */
    fun applyLiveEffect(fx: it.agrilloit.turegistri.gl.LiveEffect?) {
        renderer.setLiveEffect(fx)
    }

    fun applyPeaking(enabled: Boolean) {
        renderer.setPeaking(enabled)
    }

    fun setTorch(enabled: Boolean) {
        cameraController?.setTorch(enabled)
    }

    /**
     * Foto JPEG a massima risoluzione dalla camera attiva (solo da fermi).
     * Ritorna i byte JPEG o null (in registrazione / errore).
     */
    suspend fun captureStillPhotoSuspend(cameraIndex: Int): ByteArray? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            cameraController?.captureStillPhoto(cameraIndex) { bytes ->
                if (cont.isActive) cont.resume(bytes, null)
            }
        }
}
