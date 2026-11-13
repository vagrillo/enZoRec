package it.agrilloit.turegistri.ui.record

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import android.net.Uri
import androidx.lifecycle.viewModelScope
import it.agrilloit.turegistri.data.ProjectsRepository
import it.agrilloit.turegistri.model.LayoutEngine
import it.agrilloit.turegistri.model.LayoutMode
import it.agrilloit.turegistri.record.RecordingEngine
import it.agrilloit.turegistri.service.RecordingService
import it.agrilloit.turegistri.ui.Routes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FrontLightMode { BORDER, EXTREME }

/** Configurazione del "front light": cornice luminosa o softbox estremo. */
data class FrontLightConfig(
    val enabled: Boolean = false,
    val mode: FrontLightMode = FrontLightMode.EXTREME,
    val widthDp: Int = 26, // thin 14 / medium 26 / wide 44
    val colorArgb: Long = 0xFFFFFFFF,
    val opacity: Float = 1f,
)

class RecordViewModel(app: Application) : AndroidViewModel(app) {

    val engine = RecordingEngine.get(app)
    val repository = ProjectsRepository(app)
    val context: Application = app

    private val prefs = app.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    val engineState = engine.stateFlow
    val comboLabel = MutableStateFlow(engine.comboLabel())
    val cameraCount = MutableStateFlow(engine.cameraCount())
    val micLabel = MutableStateFlow(engine.micLabel())

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<RecordingEngine.Settings> = _settings.asStateFlow()

    private val _layout = MutableStateFlow(engine.layoutState)
    val layout: StateFlow<LayoutEngine.LayoutState> = _layout.asStateFlow()

    private val _frontLight = MutableStateFlow(FrontLightConfig())
    val frontLight: StateFlow<FrontLightConfig> = _frontLight.asStateFlow()

    /** Zoom per stream A/B (frazione, applicata con CONTROL_ZOOM_RATIO). */
    val zoomA = MutableStateFlow(1f)
    val zoomB = MutableStateFlow(1f)

    /** MF per stream: [manual, diopter]. */
    val focusA = MutableStateFlow(false to 0f)
    val focusB = MutableStateFlow(false to 0f)

    val markers = MutableStateFlow<List<Long>>(emptyList())

    private val photosRepo = it.agrilloit.turegistri.data.PhotosRepository(app)

    /** Ultima foto scattata (per la miniatura accanto al REC). */
    val lastPhoto = MutableStateFlow<Uri?>(null)

    // Stato flash unificato: l'"intenzione" (acceso/spento) segue la camera attiva.
    //  - camera frontale  => softbox dello schermo (livello 1..4: thin/medium/wide/extrawide)
    //  - camera posteriore => torcia LED reale
    val frontActive = MutableStateFlow(true)
    val flashOn = MutableStateFlow(false)
    val softboxLevel = MutableStateFlow(1)
    val torchOn = MutableStateFlow(false)
    val effectiveFlashOn = MutableStateFlow(false)


    val hasPermissions: Boolean
        get() = engine.cameraCount() > 0

    init {
        // Applica le impostazioni persistite (incluso last camera / dual mode).
        engine.applySettings(_settings.value)
        engine.setLayout(_layout.value)
        // Ticker: timecode e livello microfono.
        viewModelScope.launch {
            while (isActive) {
                _elapsedMs.value = engine.elapsedRecordingMs()
                _micLevel.value = engine.micLevel()
                delay(200)
            }
        }
        // Stato camere: aggiorna etichette quando pronte.
        viewModelScope.launch {
            engine.stateFlow.collect {
                comboLabel.value = engine.comboLabel()
                cameraCount.value = engine.cameraCount()
                micLabel.value = engine.micLabel()
            }
        }
        viewModelScope.launch {
            delay(1500)
            comboLabel.value = engine.comboLabel()
            cameraCount.value = engine.cameraCount()
            micLabel.value = engine.micLabel()
        }
        // Ultima foto scattata (per la miniatura).
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            lastPhoto.value = photosRepo.latest()
        }
        // Il flash segue la faccia attiva (fonte: motore camera).
        viewModelScope.launch {
            engine.frontActiveFlow.collect { f ->
                frontActive.value = f
                refreshFlash()
            }
        }
    }

    /**
     * Foto: da fermi usa la camera attiva alla MASSIMA risoluzione JPEG;
     * in registrazione cattura il composito live (GL) come frame corrente.
     */
    fun capturePhoto(onResult: (Boolean) -> Unit) {
        if (engine.state == RecordingEngine.State.IDLE) {
            viewModelScope.launch {
                val bytes = engine.captureStillPhotoSuspend(0)
                if (bytes != null) {
                    val uri = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        photosRepo.saveBytes(bytes)
                    }
                    lastPhoto.value = uri ?: lastPhoto.value
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        mainToast(if (uri != null) "Foto salvata (max risoluzione)" else "Foto non riuscita")
                        onResult(uri != null)
                    }
                } else {
                    // Camera occupata o JPEG non disponibile: cattura GL del preview.
                    glCapture(onResult)
                }
            }
        } else {
            glCapture(onResult)
        }
    }

    private fun glCapture(onResult: (Boolean) -> Unit) {
        engine.renderer.captureStill { bmp ->
            if (bmp == null) {
                mainToast("Foto non riuscita")
                onResult(false)
                return@captureStill
            }
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val uri = photosRepo.save(bmp)
                lastPhoto.value = uri ?: lastPhoto.value
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    mainToast(if (uri != null) "Foto salvata in galleria" else "Foto non riuscita")
                    onResult(uri != null)
                }
            }
        }
    }

    fun photos(): List<Uri> = photosRepo.list()

    fun deletePhoto(uri: Uri) {
        photosRepo.delete(uri)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            lastPhoto.value = photosRepo.latest()
        }
    }

    private fun mainToast(msg: String) {
        android.widget.Toast.makeText(getApplication(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun startRecording(startService: () -> Unit) {
        if (engine.isRecording) return
        startService()
        engine.startRecording()
    }

    fun stopRecording() {
        engine.stopRecording()
        markers.value = emptyList()
    }

    fun togglePause() {
        if (!engine.pauseRecording()) engine.resumeRecording()
    }

    fun addMarker() {
        val ms = engine.addMarker()
        markers.value = markers.value + ms
    }

    fun setLayout(mode: LayoutMode) {
        _layout.value = _layout.value.copy(mode = mode)
        engine.setLayout(_layout.value)
    }

    fun setLayoutState(state: LayoutEngine.LayoutState) {
        _layout.value = state
        engine.setLayout(state)
    }

    fun updateSettings(transform: (RecordingEngine.Settings) -> RecordingEngine.Settings) {
        _settings.value = transform(_settings.value)
        persist(_settings.value)
        engine.applySettings(_settings.value)
    }

    /** Persiste "ultima camera usata" quando le camere risultano pronte. */
    fun persistLastCamera() {
        val id = engine.firstActiveCameraId() ?: return
        val s = _settings.value
        if (s.lastCameraId == id) return
        _settings.value = s.copy(lastCameraId = id)
        persist(_settings.value)
    }

    private fun loadSettings(): RecordingEngine.Settings {
        val p = prefs
        return RecordingEngine.Settings(
            recordWidth = p.getInt(KEY_WIDTH, 1280),
            mirrorFront = p.getBoolean(KEY_MIRROR, true),
            compositeAudio = p.getBoolean(KEY_COMP_AUDIO, true),
            fitFrame = p.getBoolean(KEY_FIT, true),
            stabilize = p.getBoolean(KEY_STAB, true),
            noiseSuppression = p.getBoolean(KEY_NR_LIVE, true),
            dualCamera = p.getBoolean(KEY_DUAL, false),
            cameraIdA = p.getString(KEY_CAM_A, null),
            cameraIdB = p.getString(KEY_CAM_B, null),
            lastCameraId = p.getString(KEY_LAST_CAM, null),
        )
    }

    private fun persist(s: RecordingEngine.Settings) {
        prefs.edit()
            .putInt(KEY_WIDTH, s.recordWidth)
            .putBoolean(KEY_MIRROR, s.mirrorFront)
            .putBoolean(KEY_COMP_AUDIO, s.compositeAudio)
            .putBoolean(KEY_FIT, s.fitFrame)
            .putBoolean(KEY_STAB, s.stabilize)
            .putBoolean(KEY_NR_LIVE, s.noiseSuppression)
            .putBoolean(KEY_DUAL, s.dualCamera)
            .putString(KEY_CAM_A, s.cameraIdA)
            .putString(KEY_CAM_B, s.cameraIdB)
            .putString(KEY_LAST_CAM, s.lastCameraId)
            .apply()
    }

    private companion object {
        const val PREFS = "yourec_settings"
        const val KEY_WIDTH = "record_width"
        const val KEY_MIRROR = "mirror_front"
        const val KEY_COMP_AUDIO = "composite_audio"
        const val KEY_FIT = "fit_frame"
        const val KEY_STAB = "stabilize"
        const val KEY_NR_LIVE = "noise_suppression"
        const val KEY_DUAL = "dual_camera"
        const val KEY_CAM_A = "camera_id_a"
        const val KEY_CAM_B = "camera_id_b"
        const val KEY_LAST_CAM = "last_camera_id"
    }

    fun setFrontLight(config: FrontLightConfig) {
        _frontLight.value = config
    }

    /**
     * Il pulsante flash cicla: OFF -> THIN -> MEDIUM -> WIDE -> EXTRA-WIDE -> OFF.
     * EXTRA-WIDE = schermo tutto bianco con l'anteprima ridotta proporzionale al centro.
     */
    /** Il pulsante flash cicla in base alla faccia attiva (vedi refreshFlash). */
    fun cycleFlash() {
        if (frontActive.value) {
            if (!flashOn.value) {
                flashOn.value = true
                softboxLevel.value = 1
            } else if (softboxLevel.value < 4) {
                softboxLevel.value++
            } else {
                flashOn.value = false
            }
        } else {
            flashOn.value = !flashOn.value
        }
        refreshFlash()
    }

    /** Applica il flash alla faccia attiva: softbox (front) o torcia (rear). */
    fun refreshFlash() {
        val front = frontActive.value
        if (front) {
            val lvl = softboxLevel.value.coerceIn(1, 4)
            val widths = intArrayOf(14, 26, 44, 44)
            _frontLight.value = FrontLightConfig(
                enabled = flashOn.value,
                mode = if (lvl >= 4) FrontLightMode.EXTREME else FrontLightMode.BORDER,
                widthDp = widths[lvl - 1],
                colorArgb = _frontLight.value.colorArgb,
                opacity = 1f,
            )
            if (torchOn.value) {
                torchOn.value = false
                engine.setTorch(false)
            }
        } else {
            torchOn.value = flashOn.value
            engine.setTorch(torchOn.value)
            _frontLight.value = _frontLight.value.copy(enabled = false)
        }
        effectiveFlashOn.value = if (front) _frontLight.value.enabled else torchOn.value
    }

    /** Chiamato a ogni cambio faccia/camere: il flash segue la camera. */
    fun onFacingChanged() {
        frontActive.value = engine.isFrontActive()
        refreshFlash()
    }


    fun applyZoom(lane: Int, ratio: Float) {
        engine.applyZoom(lane, ratio)
        (if (lane == 0) zoomA else zoomB).value = ratio
    }

    fun setFocus(lane: Int, manual: Boolean, diopter: Float = 0f) {
        engine.setFocusMode(lane, manual, diopter)
        (if (lane == 0) focusA else focusB).value = manual to diopter
    }

    fun tapToFocus(lane: Int, x: Float, y: Float) {
        engine.tapToFocus(lane, x, y)
    }

    /**
     * Determina su quale stream è avvenuto il tap e le coordinate normalizzate
     * all'interno della regione di quello stream (per il tap-to-focus).
     */
    fun mapTapToCamera(tapX: Float, tapY: Float, viewW: Float, viewH: Float): Pair<Int, Pair<Float, Float>>? {
        val aspects = if (engine.cameraCount() >= 2) {
            listOf(16f / 9f, 16f / 9f)
        } else {
            listOf(16f / 9f)
        }
        val viewportAspect = viewW / viewH
        val placements = LayoutEngine.compute(_layout.value, aspects, viewportAspect, engine.cameraCount())
        val nx = tapX / viewW
        val ny = tapY / viewH
        for (p in placements) {
            val r = p.region
            if (nx >= r.x && nx <= r.x2 && ny >= r.y && ny <= r.y2) {
                val localX = (nx - r.x) / r.w
                val localY = (ny - r.y) / r.h
                // La vista della camera è in orientamento sensore (landscape): ruota.
                return p.cameraIndex to Pair(localX, localY)
            }
        }
        return null
    }

    /** Scambio immediato frontale <-> posteriore. Ritorna false se in registrazione. */
    fun swapCameras(): Boolean {
        val ok = engine.swapCameras()
        if (ok) {
            persistLastCamera()
            onFacingChanged()
        }
        return ok
    }

    /** Elenco microfoni integrati per il selettore: (address, descrizione). */
    fun micDevices(): List<Pair<String, String>> = engine.micDevices()

    /** Selezione microfono (null = automatico); applicato al prossimo take. */
    fun setPreferredMic(address: String?) {
        engine.setPreferredMic(address)
        micLabel.value = engine.micLabel()
    }

    /** true se la modalità Screen display è attiva nelle impostazioni. */
    val screenDisplayActive: Boolean get() = engine.settings.screenDisplay

    fun hasScreenProjection(): Boolean = engine.hasScreenProjection()

    fun setScreenProjection(resultCode: Int, data: android.content.Intent) =
        engine.setScreenProjection(resultCode, data)

    /** Entra in modalità Screen display: solo frontale. */
    fun enterScreenDisplay(frontId: String) {
        updateSettings { st ->
            st.copy(screenDisplay = true, dualCamera = false, cameraIdA = frontId, cameraIdB = null)
        }
    }

    /** Esce dalla modalità Screen display: torna alla configurazione automatica. */
    fun exitScreenDisplay() {
        engine.releaseScreenProjection()
        updateSettings { st ->
            st.copy(screenDisplay = false, dualCamera = true, cameraIdA = null, cameraIdB = null)
        }
    }

    fun startForegroundService() {
        androidx.core.content.ContextCompat.startForegroundService(
            context, RecordingService.startIntent(context)
        )
    }

    /** Salva il take nel progetto corrente e ritorna l'id progetto. */
    suspend fun saveTake(result: RecordingEngine.SessionResult): String =
        repository.onTakeSaved(result)
}
