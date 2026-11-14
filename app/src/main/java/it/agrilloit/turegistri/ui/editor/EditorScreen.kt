package it.agrilloit.turegistri.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import kotlinx.coroutines.launch
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import it.agrilloit.turegistri.ui.theme.BackgroundDark
import it.agrilloit.turegistri.ui.theme.CyanWave
import it.agrilloit.turegistri.ui.theme.GreenBadge
import it.agrilloit.turegistri.ui.theme.PrimaryAccent
import it.agrilloit.turegistri.ui.theme.SecondaryAccent
import it.agrilloit.turegistri.ui.theme.SurfaceDark
import it.agrilloit.turegistri.ui.theme.SurfaceElevated
import it.agrilloit.turegistri.ui.theme.TextSecondary
import it.agrilloit.turegistri.ui.theme.TrackAudio
import it.agrilloit.turegistri.ui.theme.TrackVideo
import java.util.Locale
import kotlin.math.abs

private const val ZOOM_MIN = 14f
private const val ZOOM_MAX = 320f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(projectIdRaw: String, onBack: () -> Unit) {
    val context = LocalContext.current
    // "LATEST" = progetto più recente (tab Edit della bottom nav).
    var projectId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(projectIdRaw) {
        if (projectIdRaw == "LATEST") {
            val repo = it.agrilloit.turegistri.data.ProjectsRepository(
                context.applicationContext
            )
            projectId = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                repo.latestProjectId()
            }
        } else {
            projectId = projectIdRaw
        }
    }
    val resolved = projectId
    if (resolved == null) {
        Box(Modifier.fillMaxSize().background(BackgroundDark))
        return
    }
    val vm: EditorViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = resolved,
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                EditorViewModel(context.applicationContext as android.app.Application, resolved) as T
        },
    )

    val clips by vm.clips.collectAsState()
    val selection by vm.selection.collectAsState()
    val playhead by vm.playheadMs.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()
    val playing by vm.playing.collectAsState()
    val thumbnails by vm.thumbnails.collectAsState()
    val waveforms by vm.waveforms.collectAsState()
    val exportProgress by vm.exportProgress.collectAsState()
    val exportPath by vm.exportPath.collectAsState()
    val exportError by vm.exportError.collectAsState()
    val canUndo by vm.canUndo().collectAsState()
    val canRedo by vm.canRedo().collectAsState()
    val nrEnabled by vm.noiseReductionEnabled.collectAsState()
    val nrStrength by vm.noiseReductionStrength.collectAsState()
    val snap by vm.snapToCuts.collectAsState()

    var showTools by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showVibe by remember { mutableStateOf(false) }
    var fullscreenPreview by remember { mutableStateOf(false) }

    // ------------------------------------------------------------ player
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ false
            )
            volume = 1f
        }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // Playlist: ricostruita quando cambia la STRUTTURA dei clip (trim inclusi).
    val signature = clips.joinToString("|") { "${it.id}:${it.trimStartMs}:${it.trimEndMs}" }
    LaunchedEffect(signature) {
        val wasPlaying = player.isPlaying
        val currentIndex = player.currentMediaItemIndex
        val currentPos = player.currentPosition
        player.setMediaItems(
            clips.map { clip ->
                MediaItem.Builder()
                    .setUri(Uri.parse(clip.path))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.trimStartMs)
                            .setEndPositionMs(clip.trimEndMs)
                            .build()
                    )
                    .build()
            }
        )
        if (currentIndex in clips.indices) {
            player.seekTo(currentIndex, currentPos)
        } else {
            player.seekTo(0, 0)
        }
        player.prepare()
        if (wasPlaying) player.play()
    }
    player.playWhenReady = playing

    // Sync del playhead globale durante la riproduzione.
    LaunchedEffect(playing, clips.size) {
        while (true) {
            if (player.isPlaying) {
                val idx = player.currentMediaItemIndex
                val inWindow = player.currentPosition.coerceAtLeast(0)
                vm.playheadMs.value = vm.globalStartOf(idx) + inWindow
            }
            kotlinx.coroutines.delay(100)
        }
    }

    fun togglePlay() {
        if (playing) {
            vm.playing.value = false
        } else {
            val total = vm.totalMs()
            if (vm.playheadMs.value >= total - 50) vm.playheadMs.value = 0
            val (index, local) = vm.locate(vm.playheadMs.value)
            player.seekTo(index, local)
            vm.playing.value = true
        }
    }

    fun seekTo(globalMs: Long) {
        val pos = vm.snapped(globalMs).coerceIn(0, vm.totalMs())
        vm.playheadMs.value = pos
        val (index, local) = vm.locate(pos)
        player.seekTo(index, local)
    }

    // ------------------------------------------------------------ layout
    Column(
        Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Indietro",
                tint = Color.White,
                modifier = Modifier.clickable(onClick = onBack),
            )
            Spacer(Modifier.width(12.dp))
            Text(vm.projectName, color = Color.White, fontSize = 16.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(
                "1x",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceElevated)
                    .clickable { fullscreenPreview = !fullscreenPreview }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // Preview (con overlay anteprima live: titolo/immagine/sottotitoli)
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            OverlayPreviewLayer(vm = vm, playhead = playhead)
        }

        // Barra controlli timecode
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatTimecode(playhead),
                color = Color.White,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.SkipPrevious, "Inizio", tint = Color.White,
                modifier = Modifier.clickable { seekTo(0) }.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Filled.KeyboardArrowLeft, "-1s", tint = Color.White,
                modifier = Modifier.clickable { seekTo(playhead - 1000) }.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                "Play",
                tint = Color.White,
                modifier = Modifier
                    .size(34.dp)
                    .clickable { togglePlay() },
            )
            Spacer(Modifier.width(14.dp))
            Icon(Icons.Filled.SkipNext, "+1s", tint = Color.White,
                modifier = Modifier.clickable { seekTo(playhead + 1000) }.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Filled.KeyboardArrowRight, "Fine", tint = Color.White,
                modifier = Modifier.clickable { seekTo(vm.totalMs()) }.size(26.dp))
            Spacer(Modifier.weight(1f))
            Text(
                formatTimecode(vm.totalMs()),
                color = TextSecondary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        // Timeline (pxPerSec dinamico: pinch-to-zoom con ancora)
        val scroll = rememberScrollState()
        val scope2 = rememberCoroutineScope()
        var pxPerSec by remember { mutableStateOf(56f) }
        var lastSeekAt by remember { mutableStateOf(0L) }
        val timelineWidth = ((vm.totalMs() / 1000f * pxPerSec) + 200f).toInt().coerceAtLeast(800)

        fun scrubTo(ms: Long, final: Boolean = false) {
            val pos = vm.snapped(ms).coerceIn(0, vm.totalMs())
            vm.playheadMs.value = pos
            val now = System.currentTimeMillis()
            if (final || now - lastSeekAt > 120) {
                lastSeekAt = now
                val (index, local) = vm.locate(pos)
                player.seekTo(index, local)
            }
        }

        fun onZoom(zoom: Float, centroidX: Float) {
            val anchorSec = (scroll.value + centroidX) / pxPerSec
            val newPx = (pxPerSec * zoom).coerceIn(ZOOM_MIN, ZOOM_MAX)
            if (abs(newPx - pxPerSec) < 0.01f) return
            pxPerSec = newPx
            val maxScroll = ((vm.totalMs() / 1000f * newPx) + 200f).toInt()
            val target = (anchorSec * newPx - centroidX).toInt().coerceIn(0, maxScroll)
            scope2.launch { scroll.scrollTo(target) }
        }
        // Zoom timeline: pulsanti espliciti (il pinch resta attivo sul canvas)
        Row(
            Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(horizontal = 12.dp, vertical = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Remove, "Zoom −", tint = Color.White,
                modifier = Modifier
                    .size(30.dp)
                    .clickable { onZoom(0.8f, 400f) }
                    .padding(4.dp),
            )
            Icon(
                Icons.Filled.Add, "Zoom +", tint = Color.White,
                modifier = Modifier
                    .size(30.dp)
                    .clickable { onZoom(1.25f, 400f) }
                    .padding(4.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Pinch o ± per lo zoom",
                color = TextSecondary,
                fontSize = 10.sp,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .horizontalScroll(scroll),
        ) {
            TimelineCanvas(
                clips = clips,
                selection = selection,
                playheadMs = playhead,
                thumbnails = thumbnails,
                waveforms = waveforms,
                widthPx = timelineWidth.toFloat(),
                pxPerSec = pxPerSec,
                scrollState = scroll,
                onScrub = { ms -> scrubTo(ms) },
                onScrubEnd = { ms -> scrubTo(ms, final = true) },
                onZoom = { z, cx -> onZoom(z, cx) },
                onTap = { x, y ->
                    val global = (x / pxPerSec * 1000).toLong()
                    val videoTop = 60f
                    val inClipArea = y >= videoTop && y <= videoTop + 110f + 8f + 70f
                    val clipHit = clips.firstOrNull { c ->
                        val start = vm.globalStartOf(clips.indexOf(c))
                        global >= start && global <= start + c.effectiveMs()
                    }
                    if (inClipArea && clipHit != null) {
                        vm.select(clipHit.id)
                    } else {
                        seekTo(global)
                    }
                },
                onTrimStart = { clipId, newStartMs ->
                    val clip = clips.firstOrNull { it.id == clipId } ?: return@TimelineCanvas
                    vm.trim(clipId, newStartMs, clip.trimEndMs)
                },
                onTrimEnd = { clipId, newEndMs ->
                    val clip = clips.firstOrNull { it.id == clipId } ?: return@TimelineCanvas
                    vm.trim(clipId, clip.trimStartMs, newEndMs)
                },
            )
        }

        // Action bar: 2 righe fisse — tutte le funzioni sempre visibili
        Row(
            Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(vertical = 4.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ActionIcon(Icons.Filled.IosShare, "Export", GreenBadge) { showExport = true }
            ActionIcon(Icons.Filled.Flag, "Mark", if (bookmarksNear(playhead, bookmarks)) GreenBadge else Color.White) {
                vm.addBookmark()
            }
            ActionIcon(Icons.Filled.ContentCut, "Split", Color.White) { vm.splitAt(playhead) }
            ActionIcon(Icons.Filled.Delete, "Del", Color.White) {
                selection?.let { vm.delete(it) }
            }
            ActionIcon(Icons.AutoMirrored.Filled.Undo, "Undo", if (canUndo) Color.White else TextSecondary) { vm.undo() }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(vertical = 4.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ActionIcon(Icons.AutoMirrored.Filled.Redo, "Redo", if (canRedo) Color.White else TextSecondary) { vm.redo() }
            ActionIcon(Icons.Filled.Movie, "Vibe", if (vm.vibeDecisions.value.isNotEmpty()) GreenBadge else Color.White) {
                showVibe = true
            }
            ActionIcon(Icons.Filled.KeyboardArrowLeft, "MoveL", if (selection != null) Color.White else TextSecondary) {
                selection?.let { vm.move(it, -1) }
            }
            ActionIcon(Icons.Filled.KeyboardArrowRight, "MoveR", if (selection != null) Color.White else TextSecondary) {
                selection?.let { vm.move(it, +1) }
            }
            ActionIcon(Icons.Filled.Tune, "Tools", Color.White) { showTools = true }
        }

        // Trasporto compatto: bookmark prev/next + frame step (±1 frame a 30fps)
        Row(
            Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(horizontal = 24.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Icon(Icons.Filled.SkipPrevious, "Bookmark −", tint = Color.White,
                modifier = Modifier.size(30.dp).clickable { vm.seekBookmark(-1) }.padding(4.dp))
            Icon(Icons.Filled.KeyboardArrowLeft, "−1 frame", tint = TextSecondary,
                modifier = Modifier.size(30.dp).clickable { vm.stepPlayhead(-33) }.padding(4.dp))
            Icon(Icons.Filled.KeyboardArrowRight, "+1 frame", tint = TextSecondary,
                modifier = Modifier.size(30.dp).clickable { vm.stepPlayhead(33) }.padding(4.dp))
            Icon(Icons.Filled.SkipNext, "Bookmark +", tint = Color.White,
                modifier = Modifier.size(30.dp).clickable { vm.seekBookmark(1) }.padding(4.dp))
        }

        // Trim del clip selezionato: valori compatti (trim vero dalle maniglie
        // bianche sulla timeline, stile iOS — niente slider giganti)
        selection?.let { sel ->
            val clip = clips.firstOrNull { it.id == sel }
            if (clip != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(SurfaceDark)
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Trim · in ${formatTimecode(clip.trimStartMs)} · out ${formatTimecode(clip.trimEndMs)}",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "trascina le maniglie bianche",
                        color = TextSecondary,
                        fontSize = 9.sp,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ sheets
    if (showVibe) {
        VibeDirectorSheet(vm = vm, onDismiss = { showVibe = false })
    }
    var showVideoEffects by remember { mutableStateOf(false) }
    if (showVideoEffects) {
        VideoEffectsSheet(vm = vm, onDismiss = { showVideoEffects = false })
    }
    var showLuts by remember { mutableStateOf(false) }
    if (showLuts) {
        LutSheet(vm = vm, onDismiss = { showLuts = false })
    }
    var showTransitions by remember { mutableStateOf(false) }
    if (showTransitions) {
        TransitionSheet(vm = vm, onDismiss = { showTransitions = false })
    }
    var showClipEditor by remember { mutableStateOf(false) }
    if (showClipEditor) {
        ClipEditorSheet(vm = vm, onDismiss = { showClipEditor = false })
    }
    var showAddAudio by remember { mutableStateOf(false) }
    if (showAddAudio) {
        AddAudioSheet(vm = vm, onDismiss = { showAddAudio = false })
    }
    var showPauses by remember { mutableStateOf(false) }
    if (showPauses) {
        PausesSheet(vm = vm, onDismiss = { showPauses = false })
    }
    var showCensor by remember { mutableStateOf(false) }
    if (showCensor) {
        CensorSheet(vm = vm, onDismiss = { showCensor = false })
    }
    var showTitles by remember { mutableStateOf(false) }
    if (showTitles) {
        TitleSheet(vm = vm, playheadMs = playhead, onDismiss = { showTitles = false })
    }
    var showSubtitles by remember { mutableStateOf(false) }
    if (showSubtitles) {
        SubtitleSheet(vm = vm, onDismiss = { showSubtitles = false })
    }
    var showStabilize by remember { mutableStateOf(false) }
    if (showStabilize) {
        StabilizeSheet(vm = vm, onDismiss = { showStabilize = false })
    }
    if (showTools) {
        ToolsDrawer(
            onAddVideo = { uri -> vm.addVideoClip(uri) },
            snap = snap,
            onSnapToggle = { vm.snapToCuts.value = !vm.snapToCuts.value },
            nrEnabled = nrEnabled,
            nrStrength = nrStrength,
            onNoiseReductionToggle = { vm.noiseReductionEnabled.value = it },
            onNoiseReductionStrength = { vm.noiseReductionStrength.value = it },
            onOpenVideoEffects = { showVideoEffects = true },
            onOpenLuts = { showLuts = true },
            onOpenTransitions = { showTransitions = true },
            onOpenClipEditor = { showClipEditor = true },
            onOpenTitles = { showTitles = true },
            onOpenAddAudio = { showAddAudio = true },
            onOpenPauses = { showPauses = true },
            onOpenCensor = { showCensor = true },
            onOpenSubtitles = { showSubtitles = true },
            onOpenStabilize = { showStabilize = true },
            onDismiss = { showTools = false },
        )
    }
    if (showExport) {
        ExportSheet(
            vm = vm,
            clips = clips,
            progress = exportProgress,
            exportPath = exportPath,
            error = exportError,
            onDismiss = { showExport = false },
        )
    }
}

/**
 * Anteprima live degli overlay (titolo/immagine/sottotitoli) sopra il player:
 * i bitmap sono renderizzati alla stessa aspect del canvas di export
 * (720×1280) e letterboxati come il video, così le posizioni coincidono
 * con quelle dell'export.
 */
@Composable
private fun OverlayPreviewLayer(vm: EditorViewModel, playhead: Long) {
    val title by vm.titleDesign.collectAsState()
    val imageOverlay by vm.imageOverlay.collectAsState()
    val cues by vm.subtitleCues.collectAsState()
    val style by vm.subtitleStyle.collectAsState()

    val canvasW = 720
    val canvasH = 1280

    Box(Modifier.fillMaxSize()) {
        // Titolo
        title?.let { t ->
            if (t.text.isNotBlank() && playhead in t.startMs..t.endMs) {
                val bmp = remember(t, playhead / 120) {
                    TitleRenderer.render(t, canvasW, canvasH, playhead)
                }
                bmp?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        // Immagine/logo (scala = frazione della larghezza canvas, come all'export)
        imageOverlay?.let { im ->
            if (playhead in im.startMs..im.endMs) {
                val bmp = remember(im.path) {
                    android.graphics.BitmapFactory.decodeFile(im.path)
                }
                bmp?.let {
                    val density = LocalDensity.current.density
                    val wDp = (canvasW * im.scale / density).dp
                    Image(
                        it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .align(
                                BiasAlignment(
                                    ((im.posX * 2f - 1f)).coerceIn(-1f, 1f),
                                    ((im.posY * 2f - 1f)).coerceIn(-1f, 1f),
                                )
                            )
                            .width(wDp)
                            .graphicsLayer { alpha = im.alpha },
                    )
                }
            }
        }
        // Sottotitoli (cue attiva)
        if (style.burnIn) {
            val cue = cues.firstOrNull {
                it.enabled && playhead >= it.startMs && playhead <= it.endMs
            }
            cue?.let { c ->
                val design = style.toTitleDesign(c.text, c.startMs, c.endMs)
                val bmp = remember(c.id, style) {
                    TitleRenderer.render(design, canvasW, canvasH, playhead)
                }
                bmp?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private fun bookmarksNear(playheadMs: Long, bookmarks: List<Long>): Boolean =
    bookmarks.any { abs(it - playheadMs) < 100 }

internal fun formatTimecode(ms: Long): String {
    val m = ms / 60000
    val s = (ms % 60000) / 1000
    val cs = (ms % 1000) / 10
    return String.format(Locale.US, "%d:%02d.%02d", m, s, cs)
}

@Composable
private fun TimelineCanvas(
    clips: List<EditClip>,
    selection: String?,
    playheadMs: Long,
    thumbnails: Map<String, List<Bitmap_alias>>,
    waveforms: Map<String, List<Float>>,
    widthPx: Float,
    pxPerSec: Float,
    scrollState: androidx.compose.foundation.ScrollState,
    onScrub: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    onZoom: (Float, Float) -> Unit,
    onTap: (Float, Float) -> Unit,
    onTrimStart: (String, Long) -> Unit,
    onTrimEnd: (String, Long) -> Unit,
) {
    // Altezza timeline: ruler 40 + video 120 + audio 80 + margini
    val videoTop = 60f
    val videoH = 110f
    val audioTop = videoTop + videoH + 8f
    val audioH = 70f

    Canvas(
        Modifier
            .width(widthPx.dp)
            .height(280.dp)
            .pointerInput(pxPerSec, clips) {
                // Gestore UNICO: pinch a 2 dita = zoom (stile iOS), 1 dito sul
                // righello (y<42) = scrub del playhead, 1 dito sulle tracce =
                // pan (delegato allo scroll orizzontale), tap sulle tracce =
                // selezione/seek. Un solo handler = nessun conflitto di gesture.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val inRuler = down.position.y < 42f
                    var mode = 0 // 0=tracce/tap, 1=scrub, 2=pinch
                    var moved = false
                    var lastX = down.position.x
                    var lastDist = 0f
                    if (inRuler) {
                        onScrub((down.position.x / pxPerSec * 1000).toLong())
                    }
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            if (mode == 1 && inRuler && moved) {
                                onScrubEnd((lastX / pxPerSec * 1000).toLong())
                                moved = false
                            }
                            mode = 2
                            val d = kotlin.math.hypot(
                                pressed[0].position.x - pressed[1].position.x,
                                pressed[0].position.y - pressed[1].position.y,
                            )
                            val cx = (pressed[0].position.x + pressed[1].position.x) / 2f
                            if (lastDist > 0f && d != lastDist) {
                                onZoom(d / lastDist, cx)
                            }
                            lastDist = d
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val change = pressed[0]
                        val delta = change.positionChange()
                        if (mode == 2) continue // dita rimaste dopo il pinch: ignora
                        if (inRuler) {
                            // scrub: mode=1 dal down, consuma tutto
                            mode = 1
                            moved = true
                            var x = change.position.x
                            // Auto-scroll ai bordi per raggiungere tutta la timeline.
                            if (x > size.width - 70) {
                                x += 16f
                                scrollState.dispatchRawDelta(16f)
                            } else if (x < 70 && scrollState.value > 0) {
                                scrollState.dispatchRawDelta(-16f)
                                x -= 16f
                            }
                            lastX = x
                            onScrub((x / pxPerSec * 1000).toLong())
                            change.consume()
                        }
                        // tracce: NON consumare → pan via horizontalScroll;
                        // il tap (selezione/seek) scatta su release come su iOS
                    } while (event.changes.any { it.pressed })
                    if (inRuler && moved) {
                        onScrubEnd((lastX / pxPerSec * 1000).toLong())
                    } else if (!inRuler) {
                        onTap(down.position.x, down.position.y)
                    }
                }
            },
    ) {
        // Sfondo
        drawRect(Color(0xFF101114))

        // Ruler: tacche ogni secondo, label ogni 5 secondi
        var t = 0L
        val totalSec = (widthPx / pxPerSec).toInt() + 1
        while (t <= totalSec) {
            val x = t * pxPerSec
            val major = t % 5 == 0L
            drawLine(
                Color(0xFF3A3A3E),
                Offset(x, if (major) 6f else 22f),
                Offset(x, 36f),
                1.5f,
            )
            if (major) {
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.GRAY
                        textSize = 22f
                        isAntiAlias = true
                    }
                    canvas.nativeCanvas.drawText("$t s", x + 6f, 20f, paint)
                }
            }
            t += 1
        }

        // Clips
        var acc = 0f
        clips.forEachIndexed { index, clip ->
            val w = clip.effectiveMs() / 1000f * pxPerSec
            val selected = clip.id == selection
            // Video track
            drawRoundRect(
                if (selected) Color(0xFF2A3038) else TrackVideo,
                Offset(acc, videoTop),
                Size(w, videoH),
                CornerRadius(8f, 8f),
            )
            if (selected) {
                drawRoundRect(
                    SecondaryAccent,
                    Offset(acc, videoTop),
                    Size(w, videoH),
                    CornerRadius(8f, 8f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(3f),
                )
            }
            // Miniature
            thumbnails[clip.id]?.let { frames ->
                if (frames.isNotEmpty()) {
                    val frameW = videoH * 16f / 9f
                    var fx = acc
                    var fi = 0
                    while (fx < acc + w) {
                        val bmp = frames[fi % frames.size]
                        drawImage(
                            bmp.asImageBitmap(),
                            dstOffset = androidx.compose.ui.unit.IntOffset(fx.toInt(), videoTop.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(
                                frameW.toInt().coerceAtMost((acc + w - fx).toInt()),
                                videoH.toInt(),
                            ),
                        )
                        fx += frameW
                        fi++
                    }
                }
            }
            // Audio waveform
            drawRoundRect(
                if (clip.hasAudioTrack) TrackAudio else Color(0xFF14171B),
                Offset(acc, audioTop),
                Size(w, audioH),
                CornerRadius(8f, 8f),
            )
            if (!clip.hasAudioTrack) {
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#8E8E93")
                        textSize = 26f
                        isAntiAlias = true
                    }
                    canvas.nativeCanvas.drawText("muto · video only", acc + 14f, audioTop + audioH / 2 + 9f, paint)
                }
            }
            waveforms[clip.id]?.let { peaks ->
                if (peaks.isNotEmpty()) {
                    val peaksPerPx = peaks.size / (w.coerceAtLeast(1f))
                    var x = 0f
                    while (x < w) {
                        val peakIdx = (x / w * peaks.size).toInt().coerceIn(0, peaks.size - 1)
                        val h = (peaks[peakIdx] * (audioH - 8)).coerceAtLeast(2f)
                        drawLine(
                            CyanWave,
                            Offset(acc + x, audioTop + audioH / 2 - h / 2),
                            Offset(acc + x, audioTop + audioH / 2 + h / 2),
                            2f,
                        )
                        x += 3f
                    }
                }
            }
            // Handle di trim del clip selezionato
            if (selected) {
                drawRoundRect(
                    Color.White,
                    Offset(acc - 4f, videoTop),
                    Size(8f, videoH),
                    CornerRadius(4f, 4f),
                )
                drawRoundRect(
                    Color.White,
                    Offset(acc + w - 4f, videoTop),
                    Size(8f, videoH),
                    CornerRadius(4f, 4f),
                )
            }
            acc += w
        }

        // Playhead
        val px = playheadMs / 1000f * pxPerSec
        drawLine(Color.White, Offset(px, 4f), Offset(px, audioTop + audioH + 6f), 2.5f)
        drawCircle(Color.White, 6f, Offset(px, 4f))
    }
}

private typealias Bitmap_alias = android.graphics.Bitmap

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = TextSecondary, fontSize = 9.sp)
    }
}
