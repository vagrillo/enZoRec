package it.agrilloit.turegistri.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.agrilloit.turegistri.audio.AudioChainProcessor
import it.agrilloit.turegistri.audio.CensorProcessor
import it.agrilloit.turegistri.audio.ChainKind
import it.agrilloit.turegistri.audio.ChainStage as AudioChainStage
import it.agrilloit.turegistri.audio.GainProcessor
import it.agrilloit.turegistri.audio.NoiseReducer
import it.agrilloit.turegistri.audio.SilenceMuterProcessor
import it.agrilloit.turegistri.audio.VoiceEnhanceProcessor
import it.agrilloit.turegistri.data.ProjectsRepository
import it.agrilloit.turegistri.data.TakeEntity
import it.agrilloit.turegistri.model.ColorGrade
import it.agrilloit.turegistri.vibe.VibeDecision
import it.agrilloit.turegistri.vibe.VibeDirector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs

/**
 * Clip dell'editor: un video sorgente (take composito/cam o importato) con trim.
 */
data class EditClip(
    val id: String,
    val path: String,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val sourceDurationMs: Long,
    /** false se il file sorgente non contiene traccia audio (es. fallback cam). */
    val hasAudioTrack: Boolean = true,
    /** Volume 0..2 (1 = neutro). */
    val volume: Float = 1f,
    /** Muto (volume 0) — switch separato dallo slider. */
    val muted: Boolean = false,
    /** Velocità di riproduzione (1 = neutro, 0.25..4) — audio e video sincronizzati. */
    val speed: Float = 1f,
    /** Specchia orizzontalmente il clip. */
    val mirror: Boolean = false,
    /** Color correction: exposure EV, contrast, saturation, warmth. */
    val exposure: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val warmth: Float = 0f,
) {
    fun effectiveMs(): Long = ((trimEndMs - trimStartMs) / speed.coerceAtLeast(0.1f)).toLong().coerceAtLeast(0)
}

class EditorViewModel(app: Application, private val projectId: String) : AndroidViewModel(app) {

    val repository = ProjectsRepository(app)
    private val context: Application = app

    private val _clips = MutableStateFlow<List<EditClip>>(emptyList())
    val clips: StateFlow<List<EditClip>> = _clips.asStateFlow()

    val selection = MutableStateFlow<String?>(null)
    val playheadMs = MutableStateFlow(0L)
    val playing = MutableStateFlow(false)
    val snapToCuts = MutableStateFlow(true)

    private val _thumbnails = MutableStateFlow<Map<String, List<Bitmap>>>(emptyMap())
    val thumbnails: StateFlow<Map<String, List<Bitmap>>> = _thumbnails.asStateFlow()

    private val _waveforms = MutableStateFlow<Map<String, List<Float>>>(emptyMap())
    val waveforms: StateFlow<Map<String, List<Float>>> = _waveforms.asStateFlow()

    /** Noise reduction (ML-style spectral gating) applicata all'export. */
    val noiseReductionEnabled = MutableStateFlow(false)
    val noiseReductionStrength = MutableStateFlow(1.5f) // 0.8 soft / 1.5 medium / 2.5 strong

    val exportProgress = MutableStateFlow<Float?>(null)
    val exportPath = MutableStateFlow<String?>(null)
    val exportError = MutableStateFlow<String?>(null)
    val exportFps = MutableStateFlow(30)

    /** Enhance audio: gain normalizzato + soft limiter dopo la noise reduction. */
    val enhanceAudio = MutableStateFlow(false)
    /** Match listening levels: equalizza i volumi dei clip sui waveform. */
    val matchLevels = MutableStateFlow(false)
    /** Rimuovi pause: taglia i segmenti silenziosi della timeline. */
    val removePauses = MutableStateFlow(false)

    /** Bookmark dell'editor (ms sulla timeline modificata). */
    val bookmarks = MutableStateFlow<List<Long>>(emptyList())

    // --- Catena audio ordinata (port di AudioEffect iOS) ---
    data class ChainStage(
        val id: String,
        val kind: ChainKind,
        val enabled: Boolean = true,
        val amount: Double = 0.0,
        val gainDb: Double = 6.0,
        val frequency: Double = 1800.0,
    )

    val audioChain = MutableStateFlow<List<ChainStage>>(
        listOf(
            ChainStage("hp1", ChainKind.HIGH_PASS, true, 0.0, 0.0, 120.0),
            ChainStage("eq1", ChainKind.EQUALIZER, true, 0.0, 3.0, 3000.0),
        )
    )

    // --- Color look di progetto (iOS ColorSettings) ---
    data class ColorLook(
        val exposure: Float = 0f, // EV
        val highlights: Float = 0f, // −1..1 (recupero luci, approx lineare)
        val warmth: Float = 0f, // −1..1
        val tint: Float = 0f, // −1..1 (verde/magenta)
        val contrast: Float = 1f,
        val saturation: Float = 1f,
        val preset: String = "natural", // natural | noir | bleachBypass | dayForNight
    ) {
        fun isIdentity(): Boolean =
            exposure == 0f && highlights == 0f && warmth == 0f && tint == 0f &&
                contrast == 1f && saturation == 1f && preset == "natural"
    }

    val colorLook = MutableStateFlow(ColorLook())

    // --- Censor audio (finestre silenziate all'export) ---
    val censorRanges = MutableStateFlow<List<Pair<Long, Long>>>(emptyList())

    // --- Overlay di testo (progetto: uno per l'export) ---
    val textOverlayText = MutableStateFlow("")
    val textOverlayBottom = MutableStateFlow(true)

    /** Applica la catena audio ordinata (biquad) all'export. */
    fun buildAudioChainProcessor(): androidx.media3.common.audio.AudioProcessor =
        AudioChainProcessor(
            audioChain.value.map {
                AudioChainStage(
                    it.kind, it.enabled, it.amount, it.gainDb, it.frequency
                )
            },
            48000,
            2,
        )

    /** Matrice colore di progetto (ColorLook) come float[16] per l'anteprima. */
    fun colorLookMatrix(): FloatArray = when (colorLook.value.preset) {
        "noir" -> ColorGrade.noir()
        "bleachBypass" -> ColorGrade.bleachBypass()
        "dayForNight" -> ColorGrade.dayForNight()
        else -> ColorGrade.applyControls(
            colorLook.value.exposure, colorLook.value.contrast,
            colorLook.value.saturation, colorLook.value.warmth,
        )
    }

    private val undoStack = ArrayDeque<List<EditClip>>()
    private val redoStack = ArrayDeque<List<EditClip>>()
    private val undoAvailable = MutableStateFlow(false)
    private val redoAvailable = MutableStateFlow(false)
    fun canUndo(): StateFlow<Boolean> = undoAvailable
    fun canRedo(): StateFlow<Boolean> = redoAvailable

    var projectName = "Project"

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val project = repository.get(projectId)
            projectName = project?.name ?: projectName
            val takes = repository.takes(projectId)

            // Sorgente clip: composito; se illeggibile (0 byte / senza moov) prova cam0/cam1.
            fun sourceHasAudio(path: String): Boolean {
                // Il composito registra AAC; le cam individuali sono video-only.
                return path.endsWith("composito.mp4")
            }

            fun sourceFor(take: TakeEntity): Pair<String, Long>? {
                val candidates = ArrayList<String>()
                // Il composito su disco è la sorgente giusta (con audio): alcuni take
                // salvati col vecchio engine puntavano a cam0.
                val compositeOnDisk = java.io.File(take.dirPath, "composito.mp4")
                if (compositeOnDisk.exists() && compositeOnDisk.length() > 48) {
                    candidates.add(compositeOnDisk.absolutePath)
                }
                take.compositePath?.let { candidates.add(it) }
                val cam0 = java.io.File(take.dirPath, "cam0.mp4")
                if (cam0.exists()) candidates.add(cam0.absolutePath)
                val cam1 = java.io.File(take.dirPath, "cam1.mp4")
                if (cam1.exists()) candidates.add(cam1.absolutePath)
                for (path in candidates) {
                    val d = runCatching { repository.videoDurationMs(path) }.getOrDefault(0L)
                    if (d > 0) return Pair(path, d)
                }
                return null
            }

            data class Loaded(val clip: EditClip, val take: TakeEntity)
            val loaded = takes.mapNotNull { take ->
                val (path, duration) = sourceFor(take) ?: return@mapNotNull null
                Loaded(
                    EditClip(
                        id = UUID.randomUUID().toString(),
                        path = path,
                        trimStartMs = 0,
                        trimEndMs = duration,
                        sourceDurationMs = duration,
                        hasAudioTrack = sourceHasAudio(path),
                    ),
                    take,
                )
            }
            _clips.value = loaded.map { it.clip }
            // Ripristina l'editing salvato nel progetto (clips in ordine, trims).
            project?.editJson?.takeIf { it.isNotBlank() }?.let { json ->
                runCatching {
                    val arr = org.json.JSONArray(json)
                    val restored = ArrayList<EditClip>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val path = o.getString("path")
                        val srcDur = runCatching { repository.videoDurationMs(path) }.getOrDefault(0L)
                        if (srcDur <= 0) continue
                        val vol = o.optDouble("volume", 1.0).toFloat()
                        val rms = o.optDouble("rms", 0.0).toFloat().takeIf { it > 0f }
                        val gain = if (matchLevels.value && rms != null)
                            (0.35f / rms).coerceIn(0.25f, 4f) * vol else vol
                        restored.add(
                            EditClip(
                                id = o.optString("id", UUID.randomUUID().toString()),
                                path = path,
                                trimStartMs = o.getLong("trimStartMs"),
                                trimEndMs = o.getLong("trimEndMs"),
                                sourceDurationMs = srcDur,
                                hasAudioTrack = o.optBoolean("hasAudioTrack", true),
                                volume = gain,
                                speed = o.optDouble("speed", 1.0).toFloat().coerceIn(0.25f, 4f),
                                mirror = o.optBoolean("mirror", false),
                                exposure = o.optDouble("exposure", 0.0).toFloat(),
                                contrast = o.optDouble("contrast", 1.0).toFloat(),
                                saturation = o.optDouble("saturation", 1.0).toFloat(),
                                warmth = o.optDouble("warmth", 0.0).toFloat(),
                            )
                        )
                    }
                    if (restored.isNotEmpty()) _clips.value = restored
                }
            }
            runCatching {
                val take = repository.takes(projectId).lastOrNull()
                val mk = org.json.JSONArray(take?.markersJson ?: "[]")
                val bms = ArrayList<Long>()
                for (i in 0 until mk.length()) bms.add(mk.getLong(i))
                bookmarks.value = bms
            }.onFailure { bookmarks.value = emptyList() }
            loaded.forEach {
                android.util.Log.d("YouRecEdit", "clip: ${it.clip.path} hasAudio=${it.clip.hasAudioTrack} dur=${it.clip.sourceDurationMs}")
            }

            // Miniature
            for (l in loaded) {
                _thumbnails.value = _thumbnails.value + (l.clip.id to
                    repository.timelineThumbnails(l.clip.path))
            }

            // Waveform allineata + RMS per match levels.
            for (l in loaded) {
                val wav = java.io.File(l.take.dirPath, "mic_0.wav")
                if (!wav.exists() || wav.length() <= 44) continue
                val wavDur = repository.wavDurationMs(wav.absolutePath)
                if (wavDur <= 0) continue
                val offset = audioOffsetMs(l.take)
                val videoDur = l.clip.sourceDurationMs
                val peaks = repository.waveformPeaks(wav.absolutePath)
                if (peaks.isEmpty()) continue
                // Finestra [offset, offset+videoDur] in frazioni del WAV.
                val from = (offset.toDouble() / wavDur * peaks.size).toInt().coerceIn(0, peaks.size - 1)
                val to = ((offset + videoDur).toDouble() / wavDur * peaks.size).toInt().coerceIn(from + 1, peaks.size)
                val window = peaks.subList(from, to).toList()
                _waveforms.value = _waveforms.value + (l.clip.id to window)
            }
        }
    }

    /** Offset (ms) di inizio audio rispetto alle camere, dal session.json del take. */
    private fun audioOffsetMs(take: TakeEntity): Long = runCatching {
        val manifest = java.io.File(take.dirPath, "session.json")
        if (!manifest.exists()) return@runCatching 0L
        val json = org.json.JSONObject(manifest.readText())
        val audioStart = json.optJSONObject("audio")?.optLong("startedEpochMs", 0L) ?: 0L
        var camStart = Long.MAX_VALUE
        val cams = json.optJSONArray("cameras")
        if (cams != null) {
            for (i in 0 until cams.length()) {
                val t = cams.optJSONObject(i)?.optLong("startedEpochMs", Long.MAX_VALUE) ?: Long.MAX_VALUE
                if (t in 1 until camStart) camStart = t
            }
        }
        if (audioStart in 1 until camStart) camStart - audioStart else 0L
    }.getOrDefault(0L)

    fun totalMs(): Long = _clips.value.sumOf { it.effectiveMs() }

    /** Indice del clip e posizione locale (ms effettivi) per un istante globale. */
    fun locate(globalMs: Long): Pair<Int, Long> {
        var acc = 0L
        _clips.value.forEachIndexed { i, clip ->
            val eff = clip.effectiveMs()
            if (globalMs < acc + eff || i == _clips.value.size - 1) {
                return i to (globalMs - acc).coerceIn(0, eff)
            }
            acc += eff
        }
        return 0 to 0L
    }

    fun globalStartOf(index: Int): Long =
        _clips.value.take(index).sumOf { it.effectiveMs() }

    private fun pushUndo() {
        undoStack.addLast(_clips.value)
        if (undoStack.size > 30) undoStack.removeFirst()
        redoStack.clear()
        undoAvailable.value = true
        redoAvailable.value = false
        saveEditJson()
    }

    /** Serializza l'edit corrente nel campo editJson del progetto. */
    fun saveEditJson() {
        val clipsNow = _clips.value
        val bms = bookmarks.value
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val arr = org.json.JSONArray()
                for (c in clipsNow) {
                    arr.put(
                        org.json.JSONObject()
                            .put("id", c.id)
                            .put("path", c.path)
                            .put("trimStartMs", c.trimStartMs)
                            .put("trimEndMs", c.trimEndMs)
                            .put("hasAudioTrack", c.hasAudioTrack)
                            .put("volume", c.volume.toDouble())
                            .put("speed", c.speed.toDouble())
                            .put("mirror", c.mirror)
                            .put("exposure", c.exposure.toDouble())
                            .put("contrast", c.contrast.toDouble())
                            .put("saturation", c.saturation.toDouble())
                            .put("warmth", c.warmth.toDouble())
                    )
                }
                repository.saveEditJson(projectId, arr.toString())
                if (bms.isNotEmpty()) {
                    val mk = org.json.JSONArray()
                    for (m in bms) mk.put(m)
                    repository.saveMarkers(projectId, mk.toString())
                }
            }
        }
    }

    fun addBookmark() {
        bookmarks.value = bookmarks.value + vm_positionSnapshot()
        saveEditJson()
    }

    private fun vm_positionSnapshot(): Long = playheadMs.value

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_clips.value)
        _clips.value = prev
        undoAvailable.value = undoStack.isNotEmpty()
        redoAvailable.value = true
        selection.value = null
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_clips.value)
        _clips.value = next
        undoAvailable.value = undoStack.isNotEmpty()
        redoAvailable.value = redoStack.isNotEmpty()
        selection.value = null
    }

    fun select(clipId: String?) {
        selection.value = if (selection.value == clipId) null else clipId
    }

    fun trim(clipId: String, newStart: Long, newEnd: Long) {
        pushUndo()
        _clips.value = _clips.value.map {
            if (it.id == clipId) it.copy(
                trimStartMs = newStart.coerceIn(0, it.sourceDurationMs - 100),
                trimEndMs = newEnd.coerceIn(newStart + 100, it.sourceDurationMs),
            ) else it
        }
    }

    /** Split del clip che contiene il playhead alla posizione globale data. */
    fun splitAt(globalMs: Long) {
        val (index, local) = locate(globalMs)
        val clip = _clips.value.getOrNull(index) ?: return
        val sourcePos = clip.trimStartMs + local
        if (local < 200 || local > clip.effectiveMs() - 200) return
        pushUndo()
        val left = clip.copy(trimEndMs = sourcePos)
        val right = clip.copy(id = UUID.randomUUID().toString(), trimStartMs = sourcePos)
        _clips.value = _clips.value.toMutableList().apply {
            removeAt(index)
            add(index, right)
            add(index, left)
        }
    }

    fun delete(clipId: String) {
        pushUndo()
        _clips.value = _clips.value.filterNot { it.id == clipId }
        selection.value = null
    }

    /** Sposta il clip selezionato di [delta] posizioni nella sequenza. */
    fun move(clipId: String, delta: Int) {
        val list = _clips.value.toMutableList()
        val from = list.indexOfFirst { it.id == clipId }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.size - 1)
        if (from == to) return
        pushUndo()
        val item = list.removeAt(from)
        list.add(to, item)
        _clips.value = list
    }

    /** Snap del playhead sui tagli adiacenti se lo snap è attivo. */
    fun snapped(globalMs: Long): Long {
        if (!snapToCuts.value) return globalMs
        val threshold = 350L
        var acc = 0L
        val cuts = mutableListOf(0L)
        _clips.value.dropLast(1).forEach {
            acc += it.effectiveMs()
            cuts.add(acc)
        }
        cuts.add(totalMs())
        return cuts.minByOrNull { kotlin.math.abs(it - globalMs) }
            ?.takeIf { kotlin.math.abs(it - globalMs) <= threshold } ?: globalMs
    }

    fun addVideoClip(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = uri.toString()
            val duration = runCatching { repository.videoDurationMs(path) }.getOrDefault(0L)
            if (duration <= 0) return@launch
            pushUndo()
            val clip = EditClip(
                id = UUID.randomUUID().toString(),
                path = path,
                trimStartMs = 0,
                trimEndMs = duration,
                sourceDurationMs = duration,
            )
            _clips.value = _clips.value + clip
            _thumbnails.value = _thumbnails.value + (clip.id to repository.timelineThumbnails(path))
        }
    }

    // ------------------------------------------------------------ export

    /**
     * Match listening levels: gain per clip calcolato dai waveform peaks
     * (RMS medio) verso un target comune, clampato per sicurezza.
     */
    fun matchLevelGains(clips: List<EditClip>): Map<String, Float> {
        if (!matchLevels.value) return emptyMap()
        val out = mutableMapOf<String, Float>()
        val target = 0.14f
        for (c in clips) {
            val peaks = waveforms.value[c.id] ?: continue
            if (peaks.isEmpty()) continue
            var acc = 0f
            for (p in peaks) acc += p * p
            val rms = kotlin.math.sqrt(acc / peaks.size).coerceAtLeast(0.005f)
            out[c.id] = (target / rms).coerceIn(0.3f, 3.5f)
        }
        return out
    }

    /** Effetti video per clip: mirror, color correction, zoom da Vibe Director. */
    fun clipVideoEffects(clip: EditClip): List<androidx.media3.common.Effect> {
        val fx = mutableListOf<androidx.media3.common.Effect>()
        if (clip.mirror) {
            fx.add(
                androidx.media3.effect.ScaleAndRotateTransformation.Builder()
                    .setScale(-1f, 1f).build()
            )
        }
        if (abs(clip.exposure) > 0.01f || abs(clip.contrast - 1f) > 0.01f ||
            abs(clip.saturation - 1f) > 0.01f || abs(clip.warmth) > 0.01f
        ) {
            fx.add(
                ColorGradeMatrix(
                    ColorGrade.applyControls(
                        clip.exposure, clip.contrast, clip.saturation, clip.warmth
                    )
                )
            )
        }
        // Zoom del Vibe Director (scale animata via presentation time)
        val vibe = vibeDecisions.value.filter { it.enabled && it.appliedToClipId == clip.id }
        for (d in vibe) {
            if (d.type == "zoom") {
                fx.add(
                    VibeZoomEffect(
                        clip.trimStartMs, clip.trimEndMs,
                        d.startMs, d.endMs,
                        d.cx, d.cy, d.strength
                    )
                )
            }
        }
        return fx
    }

    /** Decisioni del Vibe Director (vedi vibe/VibeDirector.kt). */
    val vibeDecisions = MutableStateFlow<List<VibeDecision>>(emptyList())
    val vibeRunning = MutableStateFlow(false)
    val vibeSkill = MutableStateFlow("intervista")
    val vibePrompt = MutableStateFlow("")

    fun runVibeDirector() {
        if (vibeRunning.value) return
        vibeRunning.value = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val decisions = VibeDirector.direct(
                clips = _clips.value,
                waveforms = waveforms.value,
                skill = vibeSkill.value,
                prompt = vibePrompt.value,
            )
            vibeDecisions.value = decisions
            applyVibeDecisions(decisions)
            vibeRunning.value = false
        }
    }

    /**
     * Applica le decisioni del Vibe Director al MONTAGGIO (niente rendering:
     * quello resta all'export YouRec):
     *  - "zoom"  → memorizzato come effetto per clip (VibeZoomEffect a export)
     *  - "color" → color correction per clip
     *  - "cut"   → split reale del clip al tempo della decisione
     */
    fun applyVibeDecisions(decisions: List<VibeDecision>) {
        pushUndo()
        val active = decisions.filter { it.enabled }
        // 1. Tagli: split dei clip ai confini delle decisioni "cut".
        var clipsNow = _clips.value
        for (d in active.filter { it.type == "cut" }) {
            val idx = clipsNow.indexOfFirst { it.id == d.appliedToClipId }
            if (idx < 0) continue
            val clip = clipsNow[idx]
            val localMs = (d.startMs - clipStartOf(clipsNow, idx)).coerceIn(
                200L, clip.effectiveMs() - 200L
            )
            if (localMs <= 200 || localMs >= clip.effectiveMs() - 200) continue
            val srcCut = clip.trimStartMs + localMs
            val left = clip.copy(trimEndMs = srcCut)
            val right = clip.copy(id = UUID.randomUUID().toString(), trimStartMs = srcCut)
            clipsNow = clipsNow.toMutableList().apply {
                removeAt(idx); add(idx, right); add(idx, left)
            }
        }
        // Rinumera le decisioni sui nuovi id (le slice mantengono l'ordine).
        // 2. Color: aggiusta contrast/saturation sui clip toccati da decisioni "color".
        val updated = clipsNow.map { c ->
            var out = c
            for (d in active.filter { it.type == "color" && it.appliedToClipId == c.id }) {
                out = out.copy(
                    contrast = (out.contrast * (1f + d.strength * 0.3f)).coerceIn(0.5f, 2f),
                    saturation = (out.saturation * (1f + d.strength * 0.4f)).coerceIn(0f, 2f),
                )
            }
            out
        }
        _clips.value = updated
        // Le decisioni zoom restano legate ai clip per id: aggiorna gli id che sono cambiati.
        vibeDecisions.value = active.filter { it.type != "cut" }
        saveEditJson()
    }

    private fun clipStartOf(clips: List<EditClip>, index: Int): Long =
        clips.take(index).sumOf { it.effectiveMs() }

    fun startExport() {
        // I clip ultracorti (<100 ms) possono rompere il muxer: li scartiamo.
        _clips.value = _clips.value.filter { it.effectiveMs() >= 100 }
        val clipsNow = _clips.value
        if (clipsNow.isEmpty() || exportProgress.value != null) return
        exportError.value = null
        exportProgress.value = 0f
        startExportInternal(noiseReductionEnabled.value)
    }

    private fun startExportInternal(useNr: Boolean) {
        val clipsNow = _clips.value
        val nrStrength = noiseReductionStrength.value
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // Preparazione dati su IO (CPU): file, items, composition.
                val outDir = ProjectsRepository.sessionsRoot(context)
                    .resolve("export").apply { mkdirs() }
                val outFile = java.io.File(outDir, "export_${System.currentTimeMillis()}.mp4")
                val matchGainFor = matchLevelGains(clipsNow)
                val items = clipsNow.mapIndexed { index, clip ->
                    val mediaItem = androidx.media3.common.MediaItem.Builder()
                        .setUri(Uri.parse(clip.path))
                        .setClippingConfiguration(
                            androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(clip.trimStartMs)
                                .setEndPositionMs(clip.trimEndMs)
                                .build()
                        )
                        .build()
                    val outDur = (clip.effectiveMs() * 1000L)
                    val audioProcs = mutableListOf<androidx.media3.common.audio.AudioProcessor>()
                    if (index == 0) {
                        // Catena audio ordinata (una volta sola, all'inizio della sequence)
                        audioProcs.add(buildAudioChainProcessor())
                        if (censorRanges.value.isNotEmpty()) audioProcs.add(
                            CensorProcessor(censorRanges.value)
                        )
                        if (removePauses.value) audioProcs.add(
                            SilenceMuterProcessor()
                        )
                    }
                    if (useNr) audioProcs.add(
                        NoiseReducer.Processor(nrStrength)
                    )
                    if (enhanceAudio.value) audioProcs.add(
                        VoiceEnhanceProcessor()
                    )
                    val clipGain = if (clip.muted) 0f else (clip.volume * (matchGainFor[clip.id] ?: 1f))
                    if (abs(clipGain - 1f) > 0.01f) audioProcs.add(
                        GainProcessor(clipGain)
                    )
                    // (le pause si rimuovono col tool "Pauses" che modifica il montaggio)
                    if (abs(clip.speed - 1f) > 0.01f) {
                        val sonic = androidx.media3.common.audio.SonicAudioProcessor()
                        sonic.setSpeed(clip.speed.coerceIn(0.25f, 4f))
                        audioProcs.add(sonic)
                    }
                    val videoFx = clipVideoEffects(clip)
                    val builder = androidx.media3.transformer.EditedMediaItem.Builder(mediaItem)
                        .setDurationUs(outDur)
                    builder.setEffects(
                        androidx.media3.transformer.Effects(audioProcs, videoFx)
                    )
                    builder.build()
                }
                val composition = androidx.media3.transformer.Composition.Builder(
                    androidx.media3.transformer.EditedMediaItemSequence(items)
                ).build()

                // Build/start/poll del Transformer SOLO sul main thread (media3 lo richiede).
                withContext(Dispatchers.Main) {
                    val transformer =
                        androidx.media3.transformer.Transformer.Builder(context)
                            .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
                            .setAudioMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC)
                            .addListener(object : androidx.media3.transformer.Transformer.Listener {
                                override fun onCompleted(
                                    composition: androidx.media3.transformer.Composition,
                                    exportResult: androidx.media3.transformer.ExportResult,
                                ) {
                                    exportProgress.value = 1f
                                    exportPath.value = outFile.absolutePath
                                    viewModelScope.launch {
                                        repository.setExported(projectId, outFile.absolutePath)
                                    }
                                }

                                override fun onError(
                                    composition: androidx.media3.transformer.Composition,
                                    exportResult: androidx.media3.transformer.ExportResult,
                                    exportException: androidx.media3.transformer.ExportException,
                                ) {
                                    if (useNr) {
                                        android.util.Log.e(
                                            "YouRecExport",
                                            "Export con NR fallito: ${exportException.message}"
                                        )
                                        exportProgress.value = 0f
                                        startExportInternal(false)
                                    } else {
                                        exportProgress.value = null
                                        exportError.value =
                                            (exportException.cause?.message ?: exportException.message)
                                                ?: "Errore di esportazione"
                                    }
                                }
                            })
                            .build()
                    transformer.start(composition, outFile.absolutePath)

                    // Poll del progresso sul main thread.
                    val progressHolder = androidx.media3.transformer.ProgressHolder()
                    while (exportPath.value == null && exportError.value == null) {
                        val state = transformer.getProgress(progressHolder)
                        if (state == androidx.media3.transformer.Transformer.PROGRESS_STATE_AVAILABLE) {
                            exportProgress.value = progressHolder.progress / 100f
                        }
                        kotlinx.coroutines.delay(300)
                    }
                }
            }.onFailure {
                exportProgress.value = null
                exportError.value = it.message ?: "Errore di esportazione"
            }
    }
    }
}