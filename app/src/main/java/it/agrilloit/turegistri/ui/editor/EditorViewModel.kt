package it.agrilloit.turegistri.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.agrilloit.turegistri.data.ProjectsRepository
import it.agrilloit.turegistri.data.TakeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

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
) {
    fun effectiveMs(): Long = (trimEndMs - trimStartMs).coerceAtLeast(0)
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
            loaded.forEach {
                android.util.Log.d("YouRecEdit", "clip: ${it.clip.path} hasAudio=${it.clip.hasAudioTrack} dur=${it.clip.sourceDurationMs}")
            }

            // Miniature
            for (l in loaded) {
                _thumbnails.value = _thumbnails.value + (l.clip.id to
                    repository.timelineThumbnails(l.clip.path))
            }

            // Waveform allineata: i mic partono prima dei recorder (offset in
            // session.json) e si fermano dopo -> ritaglia la finestra del video.
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
    }

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
                val items = clipsNow.map { clip ->
                    val mediaItem = androidx.media3.common.MediaItem.Builder()
                        .setUri(Uri.parse(clip.path))
                        .setClippingConfiguration(
                            androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(clip.trimStartMs)
                                .setEndPositionMs(clip.trimEndMs)
                                .build()
                        )
                        .build()
                    val builder = androidx.media3.transformer.EditedMediaItem.Builder(mediaItem)
                        .setDurationUs(clip.effectiveMs() * 1000)
                    if (useNr) {
                        builder.setEffects(
                            androidx.media3.transformer.Effects(
                                listOf(it.agrilloit.turegistri.audio.NoiseReducer.Processor(nrStrength)),
                                emptyList()
                            )
                        )
                    }
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