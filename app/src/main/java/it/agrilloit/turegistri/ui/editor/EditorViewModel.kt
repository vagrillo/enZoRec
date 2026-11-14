package it.agrilloit.turegistri.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.agrilloit.turegistri.audio.AudioChainProcessor
import it.agrilloit.turegistri.audio.AudioMuxer
import it.agrilloit.turegistri.subtitles.Cue
import it.agrilloit.turegistri.subtitles.SubtitleStyle
import it.agrilloit.turegistri.subtitles.SubtitlesEngine
import it.agrilloit.turegistri.audio.OfflineAudioMixer
import it.agrilloit.turegistri.audio.PauseDetector
import it.agrilloit.turegistri.audio.ChainKind
import it.agrilloit.turegistri.audio.ChainStage as AudioChainStage
import it.agrilloit.turegistri.audio.GainProcessor
import it.agrilloit.turegistri.audio.NoiseReducer
import it.agrilloit.turegistri.audio.SilenceMuterProcessor
import it.agrilloit.turegistri.audio.VoiceEnhanceProcessor
import it.agrilloit.turegistri.data.ProjectsRepository
import it.agrilloit.turegistri.data.TakeEntity
import it.agrilloit.turegistri.gl.VideoEffectGlEffect
import it.agrilloit.turegistri.model.CubeLut
import it.agrilloit.turegistri.model.ColorGrade
import it.agrilloit.turegistri.model.OtioExport
import it.agrilloit.turegistri.model.Stabilizer
import it.agrilloit.turegistri.model.TitleDesign
import it.agrilloit.turegistri.model.VideoEffectCatalog
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
    /** Dissolvenze audio per-clip (ms su inizio/fine clip). */
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    /** Audio staccato dal video (link/unlink stile iOS): vive come traccia separata. */
    val audioDetached: Boolean = false,
    /** Il clip è un'immagine (foto o freeze frame): durata fissa, niente audio. */
    val isImage: Boolean = false,
    /** Effetti video del catalogo ([VideoEffectCatalog]) applicati in ordine. */
    val effects: List<VideoEffectCatalog.ClipEffect> = emptyList(),
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
    /** Canvas export: "original" | "16:9" | "9:16" | "1:1" | "4:5". */
    val exportCanvas = MutableStateFlow("original")
    /** Codec video: "h264" | "h265". */
    val exportCodec = MutableStateFlow("h264")
    /** Picco audio dell'ultimo export (0..1) per l'avviso distorsione. */
    val exportAudioPeak = MutableStateFlow(0f)

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

    // --- Censor audio: beep generati sul mixdown (port iOS AudioCensor) ---
    val censorBeeps = MutableStateFlow<List<OfflineAudioMixer.CensorBeep>>(emptyList())

    // --- Tracce audio aggiunte + voiceover (multi-track mix) ---
    data class AudioTrackItem(
        val id: String,
        val path: String, // file locale (copiato in filesDir/audio o voiceover m4a)
        val name: String,
        val startOnTimelineMs: Long,
        val volume: Float = 1f,
        val muted: Boolean = false,
        /** Audio staccato da un clip (link/unlink iOS): mantiene trim e speed. */
        val linkedClipId: String? = null,
        val srcTrimStartMs: Long = 0,
        val srcTrimEndMs: Long = -1, // -1 = intero file
        val speed: Float = 1f,
    )

    val audioTracks = MutableStateFlow<List<AudioTrackItem>>(emptyList())

    // --- Voiceover (registrazione da editor) ---
    val voiceoverRecording = MutableStateFlow(false)
    private var voiceoverRecorder: android.media.MediaRecorder? = null
    private var voiceoverFile: java.io.File? = null

    fun startVoiceover() {
        if (voiceoverRecording.value) return
        runCatching {
            val dir = java.io.File(context.filesDir, "voiceover").apply { mkdirs() }
            val f = java.io.File(dir, "vo_${System.currentTimeMillis()}.m4a")
            val rec = if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.media.MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaRecorder()
            }
            rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(48000)
            rec.setAudioChannels(1)
            rec.setAudioEncodingBitRate(128000)
            rec.setOutputFile(f.absolutePath)
            rec.prepare()
            rec.start()
            voiceoverRecorder = rec
            voiceoverFile = f
            voiceoverRecording.value = true
        }
    }

    /** Ferma la registrazione e aggiunge il voiceover come traccia al playhead. */
    fun stopVoiceover() {
        val rec = voiceoverRecorder ?: return
        runCatching { rec.stop() }
        runCatching { rec.release() }
        voiceoverRecorder = null
        voiceoverRecording.value = false
        val f = voiceoverFile ?: return
        if (f.exists() && f.length() > 1024) {
            audioTracks.value = audioTracks.value + AudioTrackItem(
                id = UUID.randomUUID().toString(),
                path = f.absolutePath,
                name = "Voiceover",
                startOnTimelineMs = playheadMs.value,
            )
            saveEditJson()
        }
        voiceoverFile = null
    }

    fun addAudioTrack(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(context.filesDir, "audio").apply { mkdirs() }
                val f = java.io.File(dir, "aud_${System.currentTimeMillis()}.dat")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    f.outputStream().use { input.copyTo(it) }
                } ?: return@launch
                audioTracks.value = audioTracks.value + AudioTrackItem(
                    id = UUID.randomUUID().toString(),
                    path = f.absolutePath,
                    name = "Audio ${audioTracks.value.size + 1}",
                    startOnTimelineMs = playheadMs.value,
                )
                saveEditJson()
            }
        }
    }

    /** Link/unlink (port iOS): stacca l'audio del clip come traccia indipendente. */
    fun unlinkClipAudio(clipId: String) {
        val idx = _clips.value.indexOfFirst { it.id == clipId }
        val clip = _clips.value.getOrNull(idx) ?: return
        if (!clip.hasAudioTrack || clip.audioDetached) return
        audioTracks.value = audioTracks.value + AudioTrackItem(
            id = UUID.randomUUID().toString(),
            path = clip.path,
            name = "Audio da clip ${idx + 1}",
            startOnTimelineMs = globalStartOf(idx),
            volume = if (clip.muted) 0f else clip.volume,
            muted = clip.muted,
            linkedClipId = clip.id,
            srcTrimStartMs = clip.trimStartMs,
            srcTrimEndMs = clip.trimEndMs,
            speed = clip.speed,
        )
        _clips.value = _clips.value.map {
            if (it.id == clipId) it.copy(audioDetached = true) else it
        }
        saveEditJson()
    }

    /** Ricollega l'audio staccato di un clip (rimuove la traccia e riattacca). */
    fun relinkClipAudioByClip(clipId: String) {
        val track = audioTracks.value.firstOrNull { it.linkedClipId == clipId } ?: return
        audioTracks.value = audioTracks.value.filterNot { it.id == track.id }
        _clips.value = _clips.value.map {
            if (it.id == clipId) it.copy(audioDetached = false) else it
        }
        saveEditJson()
    }

    fun removeAudioTrack(id: String) {
        audioTracks.value = audioTracks.value.filterNot { it.id == id }
        saveEditJson()
    }

    fun updateAudioTrack(id: String, volume: Float? = null, muted: Boolean? = null, startMs: Long? = null) {
        audioTracks.value = audioTracks.value.map { t ->
            when (t.id) {
                id -> t.copy(
                    volume = volume ?: t.volume,
                    muted = muted ?: t.muted,
                    startOnTimelineMs = startMs ?: t.startOnTimelineMs,
                )

                else -> t
            }
        }
        saveEditJson()
    }

    // --- Pause reali: rilevazione dai waveform + tagli a livello clip ---
    val pauseCandidates = MutableStateFlow<Map<String, List<PauseDetector.PauseWindow>>>(emptyMap())

    fun detectPauses(minSilenceMs: Long = 700) {
        val out = HashMap<String, List<PauseDetector.PauseWindow>>()
        for (c in _clips.value) {
            val peaks = waveforms.value[c.id] ?: continue
            if (peaks.isEmpty()) continue
            val windows = PauseDetector.detect(peaks, c.effectiveMs(), PauseDetector.Params(minSilenceMs = minSilenceMs))
            if (windows.isNotEmpty()) out[c.id] = windows
        }
        pauseCandidates.value = out
    }

    /** Applica i tagli reali: rimuove le finestre di pausa dai clip indicati. */
    fun applyPauseCuts(clipIds: Set<String>) {
        pushUndo()
        val newClips = ArrayList<EditClip>()
        for (c in _clips.value) {
            val windows = pauseCandidates.value[c.id]
            if (c.id !in clipIds || windows.isNullOrEmpty()) {
                newClips.add(c)
                continue
            }
            // Complemento delle finestre sulla timeline effettiva del clip.
            val dur = c.effectiveMs()
            val segments = ArrayList<Pair<Long, Long>>()
            var cursor = 0L
            for (w in windows.sortedBy { it.startMs }) {
                val s = w.startMs.coerceIn(0, dur)
                val e = w.endMs.coerceIn(0, dur)
                if (s > cursor) segments.add(cursor to s)
                cursor = maxOf(cursor, e)
            }
            if (cursor < dur) segments.add(cursor to dur)
            val kept = segments.filter { (s, e) -> e - s >= 400 }
            if (kept.isEmpty()) {
                newClips.add(c) // non cancellare tutto il clip
                continue
            }
            val srcOffset = { localMs: Long -> (localMs * c.speed).toLong() }
            kept.forEachIndexed { i, (s, e) ->
                val piece = c.copy(
                    id = if (i == 0) c.id else UUID.randomUUID().toString(),
                    trimStartMs = c.trimStartMs + srcOffset(s),
                    trimEndMs = c.trimStartMs + srcOffset(e),
                )
                newClips.add(piece)
            }
        }
        _clips.value = newClips
        pauseCandidates.value = emptyMap()
        saveEditJson()
    }

    // --- Title designer + overlay immagine (port iOS) ---
    val titleDesign = MutableStateFlow<TitleDesign?>(null)
    val imageOverlay = MutableStateFlow<ImageOverlayDesign?>(null)

    fun setTitle(d: TitleDesign?) {
        titleDesign.value = d
        saveEditJson()
    }

    fun setImageOverlay(d: ImageOverlayDesign?) {
        imageOverlay.value = d
        saveEditJson()
    }

    fun importOverlayImage(uri: Uri, onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching {
                val dir = java.io.File(context.filesDir, "overlays").apply { mkdirs() }
                val f = java.io.File(dir, "ov_${System.currentTimeMillis()}.png")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    f.outputStream().use { input.copyTo(it) }
                } ?: null
                f.absolutePath
            }.getOrNull()
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(res) }
        }
    }

    // --- Sottotitoli (ASR on-device vosk, port Subtitles.swift iOS) ---
    val subtitleCues = MutableStateFlow<List<Cue>>(emptyList())
    val subtitleStyle = MutableStateFlow(SubtitleStyle())
    val subtitleLang = MutableStateFlow("it")
    val subtitleModels = MutableStateFlow<List<String>>(emptyList())
    val subtitleDownloading = MutableStateFlow<Float?>(null)
    val subtitleTranscribing = MutableStateFlow<Float?>(null)
    val subtitleError = MutableStateFlow<String?>(null)

    fun refreshSubtitleModels() {
        subtitleModels.value = SubtitlesEngine.downloadedLangs(context)
    }

    fun deleteSubtitleModel(lang: String) {
        SubtitlesEngine.LANGUAGES.firstOrNull { it.lang == lang }?.let {
            SubtitlesEngine.deleteModel(context, it)
        }
        refreshSubtitleModels()
    }

    fun downloadSubtitleModel(lang: String) {
        val model = SubtitlesEngine.LANGUAGES.firstOrNull { it.lang == lang } ?: return
        if (subtitleDownloading.value != null) return
        subtitleDownloading.value = 0f
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                SubtitlesEngine.downloadModel(context, model) { p ->
                    subtitleDownloading.value = p
                }
            }.onSuccess { ok ->
                subtitleDownloading.value = null
                if (!ok) subtitleError.value = "Download/unzip modello fallito"
                refreshSubtitleModels()
            }.onFailure {
                subtitleDownloading.value = null
                subtitleError.value = it.message ?: "Download fallito"
            }
        }
    }

    /** Trascrive l'intera timeline (per clip, con offset) in cue editabili. */
    fun transcribeSubtitles() {
        if (subtitleTranscribing.value != null) return
        val lang = subtitleLang.value
        if (lang !in SubtitlesEngine.downloadedLangs(context)) {
            subtitleError.value = "Scarica prima il modello $lang"
            return
        }
        subtitleTranscribing.value = 0f
        subtitleError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val mixer = OfflineAudioMixer(sampleRate = 16000, channels = 1)
                val allCues = ArrayList<Cue>()
                var clipStart = 0L
                val clips = _clips.value
                for ((i, c) in clips.withIndex()) {
                    if (c.isImage || !c.hasAudioTrack) {
                        clipStart += c.effectiveMs()
                        continue
                    }
                    val pcm48 = mixer.decodeSegment(stripUriScheme(c.path), c.trimStartMs, c.trimEndMs)
                    val pcm16 = toPcm16kMono(pcm48)
                    val words = SubtitlesEngine.transcribePcm(context, lang, pcm16) { p ->
                        val per = 1f / maxOf(1, clips.size)
                        subtitleTranscribing.value =
                            per * i + per * p * 0.95f
                    }
                    allCues.addAll(
                        SubtitlesEngine.groupCues(words, clipStart)
                    )
                    clipStart += c.effectiveMs()
                }
                withContext(Dispatchers.Main) {
                    subtitleCues.value = allCues
                    subtitleTranscribing.value = null
                    saveEditJson()
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    subtitleTranscribing.value = null
                    subtitleError.value = it.message ?: "Trascrizione fallita"
                }
            }
        }
    }

    private fun toPcm16kMono(pcm48stereo: FloatArray): ShortArray {
        // 48000 stereo → 16000 mono (decimazione x3 con media canali)
        val out = ArrayList<Short>((pcm48stereo.size / 6) + 1)
        var f = 0
        val frames = pcm48stereo.size / 2
        while (f + 2 < frames) {
            val v = (pcm48stereo[f * 2] + pcm48stereo[f * 2 + 1] +
                pcm48stereo[(f + 1) * 2] + pcm48stereo[(f + 1) * 2 + 1] +
                pcm48stereo[(f + 2) * 2] + pcm48stereo[(f + 2) * 2 + 1]) / 6f
            out.add((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            f += 3
        }
        val arr = ShortArray(out.size)
        for (i in out.indices) arr[i] = out[i]
        return arr
    }

    fun updateCueText(id: String, text: String) {
        subtitleCues.value = subtitleCues.value.map { if (it.id == id) it.copy(text = text) else it }
        saveEditJson()
    }

    fun toggleCue(id: String) {
        subtitleCues.value = subtitleCues.value.map {
            if (it.id == id) it.copy(enabled = !it.enabled) else it
        }
        saveEditJson()
    }

    fun clearSubtitles() {
        subtitleCues.value = emptyList()
        saveEditJson()
    }

    // --- Stabilizzazione offline (port VideoStabilizer iOS) ---
    val stabilizationLevels = MutableStateFlow<Map<String, Int>>(emptyMap())
    val stabilizationRunning = MutableStateFlow<Float?>(null)
    private val stabResults = HashMap<String, Stabilizer.Result>()

    fun setStabilization(clipId: String, level: Int) {
        stabilizationLevels.value = stabilizationLevels.value + (clipId to level)
        saveEditJson()
        if (level > 0) analyzeStabilization(clipId, level)
    }

    fun stabilizationResult(clipId: String): Stabilizer.Result? = stabResults[clipId]

    fun analyzeStabilization(clipId: String, level: Int) {
        val clip = _clips.value.firstOrNull { it.id == clipId } ?: return
        if (clip.isImage || stabilizationRunning.value != null) return
        stabilizationRunning.value = 0f
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val key = "${abs(clip.path.hashCode())}_${clip.trimStartMs}_${clip.trimEndMs}_$level"
                val cached = Stabilizer.loadCache(context, key, level, clip.trimStartMs, clip.trimEndMs)
                val res = cached ?: Stabilizer.analyze(
                    stripUriScheme(clip.path), clip.trimStartMs, clip.trimEndMs, level,
                ) { p -> stabilizationRunning.value = p }
                if (res != null) {
                    stabResults[clipId] = res
                    if (cached == null) Stabilizer.saveCache(context, key, res)
                }
            }
            withContext(Dispatchers.Main) {
                stabilizationRunning.value = null
                saveEditJson()
            }
        }
    }

    // --- Transizioni tra clip (port iOS Transitions.swift) ---
    val transitions = MutableStateFlow<List<TimelineTransition>>(emptyList())

    /** Imposta (o rimuove con style=null) la transizione sul confine tra due clip. */
    fun setTransition(leftId: String, rightId: String, style: TransitionStyle?, durMs: Long, fadeWhite: Boolean = false) {
        val key = "$leftId->$rightId"
        val now = transitions.value.filterNot { it.key == key }.toMutableList()
        if (style != null) {
            now.add(TimelineTransition(key, leftId, rightId, style, durMs.coerceIn(150, 3000), fadeWhite))
        }
        transitions.value = now
        saveEditJson()
    }

    fun transitionAt(leftId: String): TimelineTransition? =
        transitions.value.firstOrNull { it.leftId == leftId }

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
                    // Formato nuovo: {"clips":[...], "transitions":[...]}; vecchio: array clip.
                    val arr: org.json.JSONArray
                    if (json.trimStart().startsWith("{")) {
                        val root = org.json.JSONObject(json)
                        root.optJSONArray("transitions")?.let { trArr ->
                            val trs = ArrayList<TimelineTransition>()
                            for (i in 0 until trArr.length()) {
                                val t = trArr.getJSONObject(i)
                                val style = TransitionStyle.values()
                                    .firstOrNull { it.id == t.optString("style") }
                                    ?: TransitionStyle.DISSOLVE
                                trs.add(
                                    TimelineTransition(
                                        t.getString("key"),
                                        t.getString("leftId"),
                                        t.getString("rightId"),
                                        style,
                                        t.optLong("durationMs", 500),
                                        t.optBoolean("fadeWhite", false),
                                    )
                                )
                            }
                            transitions.value = trs
                        }
                        root.optJSONObject("title")?.let { t ->
                            titleDesign.value = TitleDesign(
                                text = t.optString("text"),
                                preset = t.optString("preset", "clean"),
                                fontId = t.optString("fontId", "system"),
                                fontSizeSp = t.optDouble("fontSizeSp", 32.0).toFloat(),
                                colorStart = t.optInt("colorStart", -1),
                                colorEnd = t.optInt("colorEnd", -1),
                                gradient = t.optBoolean("gradient", false),
                                outlineWidthPx = t.optDouble("outlineWidthPx", 0.0).toFloat(),
                                plate = t.optString("plate", "none"),
                                motion = t.optString("motion", "none"),
                                motionDurMs = t.optLong("motionDurMs", 400),
                                startMs = t.optLong("startMs", 0),
                                endMs = t.optLong("endMs", 3000),
                                posX = t.optDouble("posX", 0.5).toFloat(),
                                posY = t.optDouble("posY", 0.82).toFloat(),
                            )
                        }
                        root.optJSONObject("imageOverlay")?.let { im ->
                            imageOverlay.value = ImageOverlayDesign(
                                path = im.getString("path"),
                                startMs = im.optLong("startMs", 0),
                                endMs = im.optLong("endMs", 3000),
                                posX = im.optDouble("posX", 0.85).toFloat(),
                                posY = im.optDouble("posY", 0.12).toFloat(),
                                scale = im.optDouble("scale", 0.2).toFloat(),
                                alpha = im.optDouble("alpha", 1.0).toFloat(),
                            )
                        }
                        root.optJSONObject("subtitles")?.let { sub ->
                            subtitleStyle.value = SubtitleStyle(
                                preset = sub.optString("preset", "clean"),
                                color = sub.optInt("color", -1),
                                fontSizeSp = sub.optDouble("fontSizeSp", 26.0).toFloat(),
                                fontId = sub.optString("fontId", "modern"),
                                posY = sub.optDouble("posY", 0.9).toFloat(),
                                burnIn = sub.optBoolean("burnIn", true),
                            )
                            sub.optJSONArray("cues")?.let { cArr ->
                                val cues = ArrayList<Cue>()
                                for (i in 0 until cArr.length()) {
                                    val c = cArr.getJSONObject(i)
                                    cues.add(
                                        Cue(
                                            id = c.optString("id", UUID.randomUUID().toString()),
                                            startMs = c.getLong("startMs"),
                                            endMs = c.getLong("endMs"),
                                            text = c.getString("text"),
                                            enabled = c.optBoolean("enabled", true),
                                        )
                                    )
                                }
                                subtitleCues.value = cues
                            }
                        }
                        root.optJSONArray("audioTracks")?.let { atArr ->
                            val ats = ArrayList<AudioTrackItem>()
                            for (i in 0 until atArr.length()) {
                                val t = atArr.getJSONObject(i)
                                ats.add(
                                    AudioTrackItem(
                                        id = t.optString("id", UUID.randomUUID().toString()),
                                        path = t.getString("path"),
                                        name = t.optString("name", "Audio"),
                                        startOnTimelineMs = t.optLong("startOnTimelineMs", 0),
                                        volume = t.optDouble("volume", 1.0).toFloat(),
                                        muted = t.optBoolean("muted", false),
                                        linkedClipId = t.optString("linkedClipId", "").takeIf { it.isNotEmpty() },
                                        srcTrimStartMs = t.optLong("srcTrimStartMs", 0),
                                        srcTrimEndMs = t.optLong("srcTrimEndMs", -1),
                                        speed = t.optDouble("speed", 1.0).toFloat(),
                                    )
                                )
                            }
                            audioTracks.value = ats
                        }
                        root.optJSONArray("censorBeeps")?.let { cbArr ->
                            val cbs = ArrayList<OfflineAudioMixer.CensorBeep>()
                            for (i in 0 until cbArr.length()) {
                                val b = cbArr.getJSONObject(i)
                                cbs.add(
                                    OfflineAudioMixer.CensorBeep(
                                        startMs = b.getLong("startMs"),
                                        endMs = b.getLong("endMs"),
                                        freqHz = b.optInt("freqHz", 1000),
                                        wave = OfflineAudioMixer.BeepWave.values()[
                                            b.optInt("wave", 0).coerceIn(0, 2)],
                                        levelDb = b.optDouble("levelDb", -12.0).toFloat(),
                                    )
                                )
                            }
                            censorBeeps.value = cbs
                        }
                        arr = root.getJSONArray("clips")
                    } else {
                        arr = org.json.JSONArray(json)
                    }
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
                                fadeInMs = o.optLong("fadeInMs", 0),
                                fadeOutMs = o.optLong("fadeOutMs", 0),
                                audioDetached = o.optBoolean("audioDetached", false),
                                effects = runCatching {
                                    val fxArr = o.getJSONArray("effects")
                                    val fxs = ArrayList<VideoEffectCatalog.ClipEffect>()
                                    for (j in 0 until fxArr.length()) {
                                        val f = fxArr.getJSONObject(j)
                                        val pm = org.json.JSONObject(f.getJSONObject("params").toString())
                                        val params = HashMap<String, Float>()
                                        for (k in pm.keys()) params[k] = pm.getDouble(k).toFloat()
                                        fxs.add(
                                            VideoEffectCatalog.ClipEffect(
                                                uid = f.optString("uid", UUID.randomUUID().toString()),
                                                defId = f.getString("defId"),
                                                params = params,
                                                enabled = f.optBoolean("enabled", true),
                                                dataRef = f.optString("dataRef", "").takeIf { it.isNotEmpty() },
                                            )
                                        )
                                    }
                                    fxs
                                }.getOrDefault(emptyList()),
                            )
                        )
                    }
                    if (restored.isNotEmpty()) _clips.value = restored
                    val levels = HashMap<String, Int>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val lvl = o.optInt("stabLevel", 0)
                        if (lvl > 0) {
                            o.optString("id").takeIf { it.isNotEmpty() }?.let { levels[it] = lvl }
                        }
                    }
                    if (levels.isNotEmpty()) stabilizationLevels.value = levels
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

            // Il restore da editJson può avere id di clip diversi da quelli
            // generati in `loaded`: rimappa miniature/waveform per PATH sui clip
            // finali, altrimenti timeline senza miniature e senza waveform.
            val finalClips = _clips.value
            if (finalClips.isNotEmpty() && loaded.isNotEmpty()) {
                val oldIdToPath = loaded.associate { it.clip.id to it.clip.path }
                val thumbsByPath = HashMap<String, List<Bitmap>>()
                _thumbnails.value.forEach { (id, bms) ->
                    oldIdToPath[id]?.let { thumbsByPath[it] = bms }
                }
                val wavesByPath = HashMap<String, List<Float>>()
                _waveforms.value.forEach { (id, peaks) ->
                    oldIdToPath[id]?.let { wavesByPath[it] = peaks }
                }
                val finalThumbs = HashMap<String, List<Bitmap>>()
                val finalWaves = HashMap<String, List<Float>>()
                for (c in finalClips) {
                    thumbsByPath[c.path]?.let { finalThumbs[c.id] = it }
                    wavesByPath[c.path]?.let { finalWaves[c.id] = it }
                }
                if (finalThumbs.isNotEmpty()) _thumbnails.value = finalThumbs
                if (finalWaves.isNotEmpty()) _waveforms.value = finalWaves
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
        val trs = transitions.value
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
                            .put("fadeInMs", c.fadeInMs)
                            .put("fadeOutMs", c.fadeOutMs)
                            .put("stabLevel", stabilizationLevels.value[c.id] ?: 0)
                            .put("audioDetached", c.audioDetached)
                    )
                    if (c.effects.isNotEmpty()) {
                        val fxArr = org.json.JSONArray()
                        for (fx in c.effects) {
                            val p = org.json.JSONObject()
                            for ((k, v) in fx.params) p.put(k, v.toDouble())
                            fxArr.put(
                                org.json.JSONObject()
                                    .put("uid", fx.uid)
                                    .put("defId", fx.defId)
                                    .put("enabled", fx.enabled)
                                    .put("params", p)
                                    .put("dataRef", fx.dataRef ?: "")
                            )
                        }
                        (arr.get(arr.length() - 1) as org.json.JSONObject)
                            .put("effects", fxArr)
                    }
                }
                val trArr = org.json.JSONArray()
                for (t in trs) {
                    trArr.put(
                        org.json.JSONObject()
                            .put("key", t.key)
                            .put("leftId", t.leftId)
                            .put("rightId", t.rightId)
                            .put("style", t.style.id)
                            .put("durationMs", t.durationMs)
                            .put("fadeWhite", t.fadeWhite)
                    )
                }
                val atArr = org.json.JSONArray()
                for (t in audioTracks.value) {
                    atArr.put(
                        org.json.JSONObject()
                            .put("id", t.id)
                            .put("path", t.path)
                            .put("name", t.name)
                            .put("startOnTimelineMs", t.startOnTimelineMs)
                            .put("volume", t.volume.toDouble())
                            .put("muted", t.muted)
                            .put("linkedClipId", t.linkedClipId ?: "")
                            .put("srcTrimStartMs", t.srcTrimStartMs)
                            .put("srcTrimEndMs", t.srcTrimEndMs)
                            .put("speed", t.speed.toDouble())
                    )
                }
                val cbArr = org.json.JSONArray()
                for (b in censorBeeps.value) {
                    cbArr.put(
                        org.json.JSONObject()
                            .put("startMs", b.startMs)
                            .put("endMs", b.endMs)
                            .put("freqHz", b.freqHz)
                            .put("wave", b.wave.ordinal)
                            .put("levelDb", b.levelDb.toDouble())
                    )
                }
                val root = org.json.JSONObject()
                    .put("clips", arr)
                    .put("transitions", trArr)
                    .put("audioTracks", atArr)
                    .put("censorBeeps", cbArr)
                titleDesign.value?.let { t ->
                    root.put("title", org.json.JSONObject()
                        .put("text", t.text).put("preset", t.preset).put("fontId", t.fontId)
                        .put("fontSizeSp", t.fontSizeSp.toDouble())
                        .put("colorStart", t.colorStart).put("colorEnd", t.colorEnd)
                        .put("gradient", t.gradient).put("outlineWidthPx", t.outlineWidthPx.toDouble())
                        .put("plate", t.plate).put("motion", t.motion)
                        .put("motionDurMs", t.motionDurMs)
                        .put("startMs", t.startMs).put("endMs", t.endMs)
                        .put("posX", t.posX.toDouble()).put("posY", t.posY.toDouble()))
                }
                imageOverlay.value?.let { im ->
                    root.put("imageOverlay", org.json.JSONObject()
                        .put("path", im.path)
                        .put("startMs", im.startMs).put("endMs", im.endMs)
                        .put("posX", im.posX.toDouble()).put("posY", im.posY.toDouble())
                        .put("scale", im.scale.toDouble()).put("alpha", im.alpha.toDouble()))
                }
                root.put("subtitles", org.json.JSONObject()
                    .put("preset", subtitleStyle.value.preset)
                    .put("color", subtitleStyle.value.color)
                    .put("fontSizeSp", subtitleStyle.value.fontSizeSp.toDouble())
                    .put("fontId", subtitleStyle.value.fontId)
                    .put("posY", subtitleStyle.value.posY.toDouble())
                    .put("burnIn", subtitleStyle.value.burnIn)
                    .put("cues", org.json.JSONArray().apply {
                        subtitleCues.value.forEach { c ->
                            put(org.json.JSONObject()
                                .put("id", c.id).put("startMs", c.startMs)
                                .put("endMs", c.endMs).put("text", c.text)
                                .put("enabled", c.enabled))
                        }
                    }))
                repository.saveEditJson(projectId, root.toString())
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

    fun removeBookmarkAt(ms: Long) {
        bookmarks.value = bookmarks.value.filter { abs(it - ms) > 100 }
        saveEditJson()
    }

    /** Step del playhead di [deltaMs] (frame step: ±33ms a 30fps). */
    fun stepPlayhead(deltaMs: Long) {
        playheadMs.value = (playheadMs.value + deltaMs).coerceIn(0, totalMs())
    }

    /** Vai al bookmark precedente (-1) / successivo (+1). */
    fun seekBookmark(dir: Int): Boolean {
        val bms = bookmarks.value.sorted()
        val cur = playheadMs.value
        val target = if (dir > 0) {
            bms.firstOrNull { it > cur + 100 }
        } else {
            bms.lastOrNull { it < cur - 100 }
        }
        return if (target != null) {
            playheadMs.value = target
            true
        } else {
            false
        }
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

    /** Un tool applicato al progetto (chip toccabile, port del tools-scope iOS). */
    data class AppliedTool(
        val key: String,
        val tool: String, // videoEffects|luts|transition|stabilize|clipAudio|audioTrack|subtitles|title|imageOverlay|censor
        val label: String,
        val clipId: String? = null,
    )

    /** Elenco di TUTTI i tool applicati, per la riga di chip sopra la timeline. */
    fun appliedTools(): List<AppliedTool> {
        val out = mutableListOf<AppliedTool>()
        _clips.value.forEachIndexed { i, c ->
            val base = "clip ${i + 1}"
            for (fx in c.effects) {
                if (fx.defId == "lut3d") {
                    out.add(AppliedTool("lut_${fx.uid}", "luts", "LUT · $base", c.id))
                } else {
                    VideoEffectCatalog.byId(fx.defId)?.let {
                        out.add(AppliedTool("fx_${fx.uid}", "videoEffects", "${it.label} · $base", c.id))
                    }
                }
            }
            if ((stabilizationLevels.value[c.id] ?: 0) > 0) {
                out.add(AppliedTool("stab_${c.id}", "stabilize", "Stabilize · $base", c.id))
            }
            if (c.audioDetached) {
                out.add(AppliedTool("unl_${c.id}", "clipAudio", "Audio scollegato · $base", c.id))
            }
        }
        transitions.value.forEach { t ->
            out.add(AppliedTool("tr_${t.key}", "transition", "Transizione ${t.style.label}", t.leftId))
        }
        if (censorBeeps.value.isNotEmpty()) {
            out.add(AppliedTool("censor", "censor", "Censor · ${censorBeeps.value.size} beep", null))
        }
        if (subtitleCues.value.isNotEmpty()) {
            out.add(AppliedTool("subs", "subtitles", "Sottotitoli · ${subtitleCues.value.size}", null))
        }
        titleDesign.value?.let {
            if (it.text.isNotBlank()) {
                out.add(AppliedTool("title", "title", "Titolo: ${it.text.take(14)}", null))
            }
        }
        imageOverlay.value?.let {
            out.add(AppliedTool("imgov", "imageOverlay", "Overlay immagine", null))
        }
        audioTracks.value.filter { it.linkedClipId == null }.forEach { t ->
            out.add(AppliedTool("at_${t.id}", "audioTrack", t.name, null))
        }
        return out
    }

    fun select(clipId: String?) {
        selection.value = if (selection.value == clipId) null else clipId
    }

    /** Aggiorna le proprietà del clip (pannello "Edit clip"). */
    fun updateClip(
        clipId: String,
        volume: Float? = null,
        muted: Boolean? = null,
        speed: Float? = null,
        mirror: Boolean? = null,
        exposure: Float? = null,
        contrast: Float? = null,
        saturation: Float? = null,
        warmth: Float? = null,
        fadeInMs: Long? = null,
        fadeOutMs: Long? = null,
    ) {
        pushUndo()
        _clips.value = _clips.value.map { c ->
            if (c.id != clipId) c else c.copy(
                volume = volume ?: c.volume,
                muted = muted ?: c.muted,
                speed = speed?.coerceIn(0.25f, 4f) ?: c.speed,
                mirror = mirror ?: c.mirror,
                exposure = exposure ?: c.exposure,
                contrast = contrast ?: c.contrast,
                saturation = saturation ?: c.saturation,
                warmth = warmth ?: c.warmth,
                fadeInMs = fadeInMs?.coerceIn(0, 5000) ?: c.fadeInMs,
                fadeOutMs = fadeOutMs?.coerceIn(0, 5000) ?: c.fadeOutMs,
            )
        }
        saveEditJson()
    }

    /** Finestra censor sul playhead (se un clip è selezionato): timeline export. */
    fun censorWindowAtPlayhead(windowMs: Long = 1500): Pair<Long, Long>? {
        val id = selection.value ?: return null
        val idx = _clips.value.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val clip = _clips.value[idx]
        val global = playheadMs.value
        val clipStart = globalStartOf(idx)
        val local = (global - clipStart).coerceIn(0, clip.effectiveMs())
        val s = (local - windowMs / 2).coerceAtLeast(0)
        val e = (s + windowMs).coerceAtMost(clip.effectiveMs())
        return (clipStart + s) to (clipStart + e)
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

    // ------------------------------------------- foto / freeze frame (image clips)

    private fun imagesDir(): java.io.File =
        java.io.File(context.filesDir, "imageclips").apply { mkdirs() }

    /**
     * Inserisce un clip immagine (foto o freeze frame) al playhead o in coda.
     * media3 gestisce le immagini come item con durata fissa (image clips).
     */
    private suspend fun insertImageClip(jpegPath: String, durationMs: Long, beforeClipId: String?) {
        withContext(Dispatchers.Main) { pushUndo() }
        val clip = EditClip(
            id = UUID.randomUUID().toString(),
            path = jpegPath,
            trimStartMs = 0,
            trimEndMs = durationMs,
            sourceDurationMs = durationMs,
            hasAudioTrack = false,
            isImage = true,
        )
        withContext(Dispatchers.Main) {
            val list = _clips.value.toMutableList()
            val at = if (beforeClipId != null) {
                val idx = list.indexOfFirst { it.id == beforeClipId }
                if (idx >= 0) idx + 1 else list.size
            } else list.size
            list.add(at, clip)
            _clips.value = list
            saveEditJson()
        }
        val bmp = android.graphics.BitmapFactory.decodeFile(jpegPath)
        if (bmp != null) {
            val scaled = android.graphics.Bitmap.createScaledBitmap(
                bmp, 240, (240f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true
            )
            _thumbnails.value = _thumbnails.value + (clip.id to listOf(scaled))
        }
    }

    /** Freeze frame: congela il frame alla posizione di playhead per [durationMs]. */
    fun addFreezeFrame(durationMs: Long = 2000) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val id = selection.value ?: _clips.value.lastOrNull()?.id ?: return@runCatching
                val idx = _clips.value.indexOfFirst { it.id == id }
                val clip = _clips.value.getOrNull(idx) ?: return@runCatching
                if (clip.isImage) return@runCatching
                val global = playheadMs.value
                val localMs = (global - globalStartOf(idx)).coerceIn(0, clip.effectiveMs())
                val srcMs = clip.trimStartMs + (localMs * clip.speed).toLong()
                val retriever = android.media.MediaMetadataRetriever()
                val bmp = runCatching {
                    retriever.setDataSource(stripUriScheme(clip.path))
                    retriever.getFrameAtTime(
                        srcMs * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                }.getOrNull()
                retriever.release()
                val finalBmp = bmp ?: return@runCatching
                val f = java.io.File(imagesDir(), "freeze_${System.currentTimeMillis()}.jpg")
                f.outputStream().use { finalBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                insertImageClip(f.absolutePath, durationMs, clip.id)
            }
        }
    }

    /** Foto dalla galleria come clip di 3 s silenzioso (port PhotoClip iOS). */
    fun addPhotoClip(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val f = java.io.File(imagesDir(), "photo_${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    f.outputStream().use { input.copyTo(it) }
                } ?: return@runCatching
                val afterId = selection.value ?: _clips.value.lastOrNull()?.id
                insertImageClip(f.absolutePath, 3000, afterId)
            }
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

    /** Effetti video per clip: stabilizzazione, mirror, color correction, zoom Vibe. */
    fun clipVideoEffects(clip: EditClip): List<androidx.media3.common.Effect> {
        val fx = mutableListOf<androidx.media3.common.Effect>()
        stabilizationLevels.value[clip.id]?.takeIf { it > 0 }?.let { lvl ->
            stabilizationResult(clip.id)?.let { res ->
                fx.add(StabilizeEffect(res, clip.trimStartMs, clip.speed, clip.effectiveMs()))
            }
        }
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
        // Effetti video del catalogo (shader GL, in ordine).
        for (fxe in clip.effects.filter { it.enabled }) {
            if (fxe.defId == "lut3d" && fxe.dataRef != null) {
                lutCubeFromCache(fxe.dataRef)?.let { fx.add(it) }
                continue
            }
            val def = VideoEffectCatalog.byId(fxe.defId) ?: continue
            fx.add(
                VideoEffectGlEffect(
                    def,
                    VideoEffectCatalog.effectiveParams(def, fxe.params),
                    clip.effectiveMs(),
                )
            )
        }
        // Transizioni: fase out/in come shader per-clip.
        val outTr = transitions.value.firstOrNull { it.leftId == clip.id }
        val inTr = transitions.value.firstOrNull { it.rightId == clip.id }
        if (outTr != null && outTr.durationMs > 50) {
            fx.add(
                TransitionEffect(outTr.style, TransitionPhase.OUT, outTr.durationMs, outTr.fadeWhite, clip.effectiveMs())
            )
        }
        if (inTr != null && inTr.durationMs > 50) {
            fx.add(
                TransitionEffect(inTr.style, TransitionPhase.IN, inTr.durationMs, inTr.fadeWhite, clip.effectiveMs())
            )
        }
        return fx
    }

    /** Cache dei cubi LUT già parsati (per export e anteprima). */
    private val lutCache = HashMap<String, androidx.media3.effect.SingleColorLut>()

    private fun lutCubeFromCache(path: String): androidx.media3.effect.SingleColorLut? {
        lutCache[path]?.let { return it }
        return runCatching {
            val file = java.io.File(path)
            if (!file.exists()) return null
            val parsed = CubeLut.parse(file.readText())
            androidx.media3.effect.SingleColorLut.createFromCube(CubeLut.toMedia3Cube(parsed.lut))
                .also { lutCache[path] = it }
        }.getOrNull()
    }

    // ------------------------------------------------- effetti video (tools)

    /** Sostituisce la lista effetti di un clip (con undo). */
    fun setClipEffects(clipId: String, effects: List<VideoEffectCatalog.ClipEffect>) {
        pushUndo()
        _clips.value = _clips.value.map {
            if (it.id == clipId) it.copy(effects = effects) else it
        }
    }

    fun addClipEffect(clipId: String, defId: String) {
        val def = VideoEffectCatalog.byId(defId) ?: return
        val clip = _clips.value.firstOrNull { it.id == clipId } ?: return
        val newFx = VideoEffectCatalog.ClipEffect(
            uid = UUID.randomUUID().toString(),
            defId = defId,
            params = def.params.associate { it.id to it.default },
        )
        setClipEffects(clipId, clip.effects + newFx)
    }

    fun updateClipEffectParam(clipId: String, uid: String, paramId: String, value: Float) {
        _clips.value = _clips.value.map { c ->
            if (c.id != clipId) c else c.copy(
                effects = c.effects.map { fxe ->
                    if (fxe.uid != uid) fxe else fxe.copy(params = fxe.params + (paramId to value))
                }
            )
        }
    }

    fun toggleClipEffect(clipId: String, uid: String) {
        _clips.value = _clips.value.map { c ->
            if (c.id != clipId) c else c.copy(
                effects = c.effects.map { fxe ->
                    if (fxe.uid == uid) fxe.copy(enabled = !fxe.enabled) else fxe
                }
            )
        }
        saveEditJson()
    }

    fun removeClipEffect(clipId: String, uid: String) {
        val clip = _clips.value.firstOrNull { it.id == clipId } ?: return
        setClipEffects(clipId, clip.effects.filterNot { it.uid == uid })
    }

    // ------------------------------------------------------------ LUT .cube

    /** Directory delle LUT importate (copie locali dei file .cube). */
    fun lutsDir(): java.io.File =
        java.io.File(context.filesDir, "luts").apply { mkdirs() }

    fun importedLuts(): List<String> =
        lutsDir().listFiles { f -> f.name.endsWith(".cube") }
            ?.map { it.absolutePath }?.sorted() ?: emptyList()

    /** Copia e valida un LUT .cube scelto via SAF; ritorna il percorso locale. */
    suspend fun importLut(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().decodeToString()
            } ?: return@runCatching null
            CubeLut.parse(text) // valida prima di salvare
            val name = "lut_${System.currentTimeMillis()}.cube"
            val out = java.io.File(lutsDir(), name)
            out.writeText(text)
            out.absolutePath
        }.getOrNull()
    }

    fun applyLut(clipId: String, lutPath: String) {
        val clip = _clips.value.firstOrNull { it.id == clipId } ?: return
        val newFx = VideoEffectCatalog.ClipEffect(
            uid = UUID.randomUUID().toString(),
            defId = "lut3d",
            dataRef = lutPath,
        )
        setClipEffects(clipId, clip.effects + newFx)
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
                val ts = System.currentTimeMillis()
                val videoOnly = java.io.File(outDir, "videoonly_$ts.mp4")
                val outFile = java.io.File(outDir, "export_$ts.mp4")
                val matchGainFor = matchLevelGains(clipsNow)
                val items = clipsNow.mapIndexed { index, clip ->
                    val mediaItem = if (clip.isImage) {
                        androidx.media3.common.MediaItem.Builder()
                            .setUri(android.net.Uri.fromFile(java.io.File(stripUriScheme(clip.path))))
                            .setMimeType(androidx.media3.common.MimeTypes.IMAGE_JPEG)
                            .build()
                    } else {
                        androidx.media3.common.MediaItem.Builder()
                            .setUri(Uri.parse(clip.path))
                            .setClippingConfiguration(
                                androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(clip.trimStartMs)
                                    .setEndPositionMs(clip.trimEndMs)
                                    .build()
                            )
                            .build()
                    }
                    val outDur = (clip.effectiveMs() * 1000L)
                    val videoFx = clipVideoEffects(clip).toMutableList()
                    // Canvas export (ultima applicazione: riquadra il frame finale)
                    when (exportCanvas.value) {
                        "16:9" -> videoFx.add(
                            VideoEffectGlEffect(VideoEffectCatalog.CANVAS, mapOf("uTargetVal" to 1.7778f), clip.effectiveMs())
                        )
                        "9:16" -> videoFx.add(
                            VideoEffectGlEffect(VideoEffectCatalog.CANVAS, mapOf("uTargetVal" to 0.5625f), clip.effectiveMs())
                        )
                        "1:1" -> videoFx.add(
                            VideoEffectGlEffect(VideoEffectCatalog.CANVAS, mapOf("uTargetVal" to 1f), clip.effectiveMs())
                        )
                        "4:5" -> videoFx.add(
                            VideoEffectGlEffect(VideoEffectCatalog.CANVAS, mapOf("uTargetVal" to 0.8f), clip.effectiveMs())
                        )
                    }
                    // Overlay titolo / immagine proiettati sulla finestra dell'item.
                    val clipStartGlobal = clipsNow.take(index).sumOf { it.effectiveMs() }
                    val clipEndGlobal = clipStartGlobal + clip.effectiveMs()
                    titleDesign.value?.let { t ->
                        if (t.text.isNotBlank() && t.startMs < clipEndGlobal && t.endMs > clipStartGlobal) {
                            videoFx.add(
                                androidx.media3.effect.OverlayEffect(
                                    com.google.common.collect.ImmutableList.of(
                                        TitleOverlay(t, clipStartGlobal)
                                    )
                                )
                            )
                        }
                    }
                    imageOverlay.value?.let { im ->
                        if (im.startMs < clipEndGlobal && im.endMs > clipStartGlobal) {
                            videoFx.add(
                                androidx.media3.effect.OverlayEffect(
                                    com.google.common.collect.ImmutableList.of(
                                        ImageOverlay(im, clipStartGlobal)
                                    )
                                )
                            )
                        }
                    }
                    val activeCues = subtitleCues.value.filter { it.enabled }
                    if (subtitleStyle.value.burnIn && activeCues.isNotEmpty()) {
                        videoFx.add(
                            androidx.media3.effect.OverlayEffect(
                                com.google.common.collect.ImmutableList.of(
                                    SubtitlesOverlay(subtitleStyle.value, activeCues, clipStartGlobal)
                                )
                            )
                        )
                    }
                    val builder = androidx.media3.transformer.EditedMediaItem.Builder(mediaItem)
                        .setDurationUs(outDur)
                        .setFrameRate(exportFps.value)
                        .setRemoveAudio(true) // l'audio è mixato offline (OfflineAudioMixer)
                    builder.setEffects(
                        androidx.media3.transformer.Effects(emptyList(), videoFx)
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
                            .setVideoMimeType(
                                if (exportCodec.value == "h265") androidx.media3.common.MimeTypes.VIDEO_H265
                                else androidx.media3.common.MimeTypes.VIDEO_H264
                            )
                            .addListener(object : androidx.media3.transformer.Transformer.Listener {
                                override fun onCompleted(
                                    composition: androidx.media3.transformer.Composition,
                                    exportResult: androidx.media3.transformer.ExportResult,
                                ) {
                                    // Video pronto: mix audio offline + mux finale su IO.
                                    viewModelScope.launch(Dispatchers.IO) {
                                        runCatching {
                                            finishExportWithAudio(videoOnly, outFile, clipsNow, useNr, nrStrength, matchGainFor)
                                        }.onFailure { err ->
                                            exportProgress.value = null
                                            exportError.value = err.message ?: "Errore mix audio"
                                        }
                                    }
                                }

                                override fun onError(
                                    composition: androidx.media3.transformer.Composition,
                                    exportResult: androidx.media3.transformer.ExportResult,
                                    exportException: androidx.media3.transformer.ExportException,
                                ) {
                                    exportProgress.value = null
                                    exportError.value =
                                        (exportException.cause?.message ?: exportException.message)
                                            ?: "Errore di esportazione"
                                }
                            })
                            .build()
                    transformer.start(composition, videoOnly.absolutePath)

                    // Poll del progresso sul main thread (video: 0..0.8).
                    val progressHolder = androidx.media3.transformer.ProgressHolder()
                    while (exportError.value == null && exportProgress.value != null && exportPath.value == null) {
                        val state = transformer.getProgress(progressHolder)
                        if (state == androidx.media3.transformer.Transformer.PROGRESS_STATE_AVAILABLE) {
                            exportProgress.value = progressHolder.progress / 100f * 0.8f
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

    /** Post-export: mixer audio offline + mux video/audio → file finale. */
    private suspend fun finishExportWithAudio(
        videoOnly: java.io.File,
        outFile: java.io.File,
        clips: List<EditClip>,
        useNr: Boolean,
        nrStrength: Float,
        matchGainFor: Map<String, Float>,
    ) {
        val mixer = OfflineAudioMixer()
        val clipsAudio = clips.map { c ->
            val outTr = transitions.value.firstOrNull { it.leftId == c.id }
            val inTr = transitions.value.firstOrNull { it.rightId == c.id }
            OfflineAudioMixer.ClipAudio(
                clipId = c.id,
                path = stripUriScheme(c.path),
                trimStartMs = c.trimStartMs,
                trimEndMs = c.trimEndMs,
                speed = c.speed,
                volume = if (c.muted) 0f else c.volume * (matchGainFor[c.id] ?: 1f),
                fadeInMs = maxOf(c.fadeInMs, inTr?.durationMs ?: 0),
                fadeOutMs = maxOf(c.fadeOutMs, outTr?.durationMs ?: 0),
                hasAudioTrack = c.hasAudioTrack && !c.audioDetached,
            )
        }
        val added = audioTracks.value.map {
            OfflineAudioMixer.AddedTrack(
                it.path, it.startOnTimelineMs, it.volume, it.muted,
                it.srcTrimStartMs, it.srcTrimEndMs, it.speed,
            )
        }
        val processors = mutableListOf<androidx.media3.common.audio.AudioProcessor>()
        if (audioChain.value.any { it.enabled }) processors.add(buildAudioChainProcessor())
        if (useNr) processors.add(NoiseReducer.Processor(nrStrength))
        if (enhanceAudio.value) processors.add(VoiceEnhanceProcessor())
        if (removePauses.value) processors.add(SilenceMuterProcessor())

        val wav = java.io.File(videoOnly.parentFile, "mix_${videoOnly.nameWithoutExtension}.wav")
        mixer.mix(
            wav, clipsAudio, added, censorBeeps.value, processors,
        ) { p ->
            exportProgress.value = 0.8f + 0.15f * p
        }
        exportProgress.value = 0.95f
        val ok = AudioMuxer.mux(videoOnly.absolutePath, wav.absolutePath, outFile.absolutePath)
        if (!ok) throw IllegalStateException("Mux video+audio fallito")
        exportAudioPeak.value = mixer.lastPeak
        exportProgress.value = 1f
        exportPath.value = outFile.absolutePath
        repository.setExported(projectId, outFile.absolutePath)

        // Timeline.otio (conform DaVinci) + receipt JSON accanto all'export.
        runCatching {
            val otioClips = clips.mapIndexed { i, c ->
                OtioExport.OtioClip(
                    name = "clip_${i + 1}",
                    sourcePath = stripUriScheme(c.path),
                    startMs = c.trimStartMs,
                    durationMs = c.effectiveMs(),
                    timelineStartMs = 0,
                    speed = c.speed,
                )
            }
            val otio = OtioExport.build(
                projectName = "YouRec export",
                videoClips = otioClips,
                markers = bookmarks.value.map { OtioExport.OtioMarker("Bookmark", it) },
                fps = exportFps.value.toDouble(),
            )
            java.io.File(outFile.parentFile, outFile.nameWithoutExtension + ".otio").writeText(otio)
            val receipt = org.json.JSONObject()
                .put("file", outFile.name)
                .put("timelineMs", clips.sumOf { it.effectiveMs() })
                .put("clips", clips.size)
                .put("fps", exportFps.value)
                .put("codec", exportCodec.value)
                .put("canvas", exportCanvas.value)
                .put("audioPeak", mixer.lastPeak.toDouble())
                .put("audioPeakWarning", mixer.lastPeak > 0.98f)
            java.io.File(outFile.parentFile, outFile.nameWithoutExtension + "_receipt.json")
                .writeText(receipt.toString(2))
            if (subtitleCues.value.isNotEmpty()) {
                java.io.File(outFile.parentFile, outFile.nameWithoutExtension + ".srt")
                    .writeText(SubtitlesEngine.toSrt(subtitleCues.value.filter { it.enabled }))
            }
        }
        wav.delete()
        videoOnly.delete()
    }

    private fun stripUriScheme(path: String): String =
        if (path.startsWith("file://")) path.removePrefix("file://") else path}
