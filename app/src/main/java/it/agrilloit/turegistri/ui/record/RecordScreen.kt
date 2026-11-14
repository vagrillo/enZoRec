package it.agrilloit.turegistri.ui.record

import android.content.Context
import android.content.Intent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import it.agrilloit.turegistri.model.LayoutEngine
import it.agrilloit.turegistri.model.LayoutMode
import it.agrilloit.turegistri.record.RecordingEngine
import it.agrilloit.turegistri.ui.YouRecActivity
import it.agrilloit.turegistri.ui.theme.BackgroundDark
import it.agrilloit.turegistri.ui.theme.CyanAccent
import it.agrilloit.turegistri.ui.theme.DividerDark
import it.agrilloit.turegistri.ui.theme.PrimaryAccent
import it.agrilloit.turegistri.ui.theme.SecondaryAccent
import it.agrilloit.turegistri.ui.theme.SurfaceDark
import it.agrilloit.turegistri.ui.theme.TimecodeRed
import it.agrilloit.turegistri.ui.theme.SurfaceElevated
import it.agrilloit.turegistri.gl.LiveEffectCatalog
import it.agrilloit.turegistri.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.abs

private val ZOOM_PRESETS = listOf(0.5f, 1f, 2f, 3f, 5f, 6f)

@Composable
fun RecordScreen(
    activity: YouRecActivity,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenEditor: (String) -> Unit,
) {
    val vm: RecordViewModel = viewModel()
    val engineState by vm.engineState.collectAsState()
    val elapsed by vm.elapsedMs.collectAsState()
    val micLevel by vm.micLevel.collectAsState()
    val micLabel by vm.micLabel.collectAsState()
    val comboLabel by vm.comboLabel.collectAsState()
    val layout by vm.layout.collectAsState()
    val frontLight by vm.frontLight.collectAsState()
    val markers by vm.markers.collectAsState()
    val zoomA by vm.zoomA.collectAsState()
    val zoomB by vm.zoomB.collectAsState()
    val focusA by vm.focusA.collectAsState()
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current
    val catalog = remember {
        it.agrilloit.turegistri.camera.CameraCatalog.catalog(
            context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        )
    }

    var showSettings by remember { mutableStateOf(false) }
    var showLayoutPicker by remember { mutableStateOf(false) }
    var showMicPicker by remember { mutableStateOf(false) }
    var showLiveEffects by remember { mutableStateOf(false) }
    var liveEffectId by remember { mutableStateOf("none") }
    var liveStrength by remember { mutableStateOf(1f) }
    var livePeaking by remember { mutableStateOf(false) }
    var pickerLane by remember { mutableStateOf<Int?>(null) }
    var showGallery by remember { mutableStateOf(false) }
    var showTeleprompter by remember { mutableStateOf(false) }
    var screenDisplayOn by remember { mutableStateOf(vm.engine.settings.screenDisplay) }
    var inSplit by remember { mutableStateOf(false) }
    var inPip by remember { mutableStateOf(false) }
    var pendingProjection by remember { mutableStateOf(false) }
    var showSplitHelp by remember { mutableStateOf(false) }

    // Traccia multi-finestra e PiP.
    LaunchedEffect(Unit) {
        while (true) {
            inSplit = activity.isInMultiWindowMode
            inPip = activity.isInPictureInPictureMode
            kotlinx.coroutines.delay(500)
        }
    }

    var afterProjection: (() -> Unit)? = null
    val projectionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val data = res.data
        if (res.resultCode == android.app.Activity.RESULT_OK && data != null) {
            vm.setScreenProjection(res.resultCode, data)
        }
        afterProjection?.invoke()
        afterProjection = null
    }

    var teleprompterHidden by remember { mutableStateOf(false) }
    val lastPhoto by vm.lastPhoto.collectAsState()
    val flashOn by vm.effectiveFlashOn.collectAsState()
    var focusRing by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var statusHint by remember { mutableStateOf<String?>(null) }

    fun requestProjectionThen(action: () -> Unit) {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as android.media.projection.MediaProjectionManager
        afterProjection = action
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    fun tryEnterSplit() {
        // Picture-in-Picture: API pubblica supportata ovunque. L'app diventa una
        // finestra flottante (trascinabile in alto) e dietro parte il launcher.
        val ratio = android.util.Rational(9, 15) // finestra verticale ~proporzionale
        val params = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .build()
        runCatching {
            activity.enterPictureInPictureMode(params)
        }
        // Il launcher (o l'app che l'utente vuole) riempie lo schermo dietro la finestra.
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(home)
    }

    fun enterScreenDisplay() {
        val manager = it.agrilloit.turegistri.camera.CameraCatalog.catalog(
            context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        )
        val front = manager.firstOrNull { it.isFront }?.id
            ?: manager.firstOrNull()?.id ?: return
        // Consenso schermo PRIMA (a schermo pieno), poi PiP + launcher dietro.
        requestProjectionThen {
            vm.enterScreenDisplay(front)
            screenDisplayOn = true
            tryEnterSplit()
        }
    }

    fun exitScreenDisplay() {
        vm.exitScreenDisplay()
        screenDisplayOn = false
        // Espande l'app uscendo dal PiP.
        context.startActivity(
            Intent(context, YouRecActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        statusHint = "Modalità normale ripristinata (schermo intero)."
    }

    val recording = engineState == RecordingEngine.State.RECORDING
    val paused = engineState == RecordingEngine.State.PAUSED

    // Callback motore: aggiorna etichette e salva il take nel progetto corrente.
    LaunchedEffect(Unit) {
        val callback = object : RecordingEngine.Callbacks {
            override fun onCamerasReady(cameraCount: Int, comboLabel: String, micLabel: String) {
                vm.comboLabel.value = comboLabel
                vm.cameraCount.value = cameraCount
                vm.micLabel.value = micLabel
                vm.persistLastCamera()
            }

            override fun onCameraWarning(message: String) {
                statusHint = message
            }

            override fun onCameraFatal(message: String) {
                statusHint = message
            }

            override fun onRecordingStarted() {
                statusHint = null
            }

            override fun onRecordingStopped(session: RecordingEngine.SessionResult) {
                scope.launch {
                    val projectId = vm.saveTake(session)
                    message = "Take salvato · ${session.files.size} file"
                }
            }

            override fun onRecordingError(message: String) {
                statusHint = message
            }
        }
        vm.engine.addCallbacks(callback)
    }

    LaunchedEffect(focusRing) {
        if (focusRing != null) {
            kotlinx.coroutines.delay(900)
            focusRing = null
        }
    }

    LaunchedEffect(statusHint) {
        if (statusHint != null) {
            kotlinx.coroutines.delay(3500)
            statusHint = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BackgroundDark),
    ) {
        if (!permissionsGranted) {
            PermissionGate(onRequestPermissions)
            return@Box
        }

        // ---------------------------------------------------------- viewport camera
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            vm.engine.setPreviewSurface(
                                holder.surface,
                                holder.surfaceFrame.width(),
                                holder.surfaceFrame.height(),
                            )
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {
                            vm.engine.setPreviewSurface(holder.surface, w, h)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            vm.engine.setPreviewSurface(null, 0, 0)
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ---------------------------------------------------------- front light / flash
        LaunchedEffect(frontLight.enabled) {
            // Flash acceso => luminosità della finestra al massimo.
            activity.window.attributes = activity.window.attributes.also {
                it.screenBrightness = if (frontLight.enabled) 1f else -1f
            }
        }
        val flashLevelPx = with(androidx.compose.ui.platform.LocalDensity.current) {
            (frontLight.widthDp * 2).dp.roundToPx()
        }
        LaunchedEffect(frontLight.enabled, frontLight.mode, frontLight.widthDp) {
            when {
                !frontLight.enabled -> vm.engine.renderer.clearPreviewWindow()
                frontLight.mode == FrontLightMode.EXTREME -> { /* gestito nel BoxWithConstraints sotto */ }
                else -> {
                    // Bordo: il preview si restringe dentro il bordo colorato.
                    val activity = context as? android.app.Activity
                    val decor = activity?.window?.decorView
                    val w = decor?.width ?: 0
                    val h = decor?.height ?: 0
                    if (w > 0 && h > 0) {
                        vm.engine.renderer.setPreviewWindow(
                            flashLevelPx, flashLevelPx, w - 2 * flashLevelPx, h - 2 * flashLevelPx
                        )
                    }
                }
            }
        }
        if (frontLight.enabled && frontLight.mode == FrontLightMode.EXTREME) {
            // Softbox estremo: schermo tutto bianco a piena luminosità con un
            // riquadro centrale proporzionale (10% dell'area) che mostra il preview.
            BoxWithConstraints(Modifier.fillMaxSize().zIndex(1f)) {
                // Lo schermo intero ridimensionato proporzionalmente al 10% dell'area.
                val scale = kotlin.math.sqrt(0.10f)
                val winW = maxWidth * scale
                val winH = maxHeight * scale
                val density = androidx.compose.ui.platform.LocalDensity.current
                val winWpx = with(density) { winW.roundToPx() }
                val winHpx = with(density) { winH.roundToPx() }
                val sideHpx = with(density) { ((maxHeight - winH) / 2).roundToPx() }
                val sideWpx = with(density) { ((maxWidth - winW) / 2).roundToPx() }
                LaunchedEffect(winWpx, winHpx) {
                    vm.engine.renderer.setPreviewWindow(sideWpx, sideHpx, winWpx, winHpx)
                }
                DisposableEffect(Unit) {
                    onDispose { vm.engine.renderer.clearPreviewWindow() }
                }
                val white = Color(frontLight.colorArgb).copy(alpha = frontLight.opacity)
                val sideH = (maxHeight - winH) / 2
                val sideW = (maxWidth - winW) / 2
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .size(maxWidth, sideH)
                        .background(white)
                )
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(y = sideH + winH)
                        .size(maxWidth, sideH + 1.dp)
                        .background(white)
                )
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(y = sideH)
                        .size(sideW + 1.dp, winH)
                        .background(white)
                )
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = sideW + winW, y = sideH)
                        .size(sideW + 1.dp, winH)
                        .background(white)
                )
            }
        } else if (frontLight.enabled) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(
                        (frontLight.widthDp * 2).dp,
                        Color(frontLight.colorArgb).copy(alpha = frontLight.opacity),
                    )
                    .zIndex(1f),
            )
        }
        // ---------------------------------------------------------- gesti: tap-focus + drag split/PiP
        var dragging by remember { mutableStateOf(false) }
        if ((!showTeleprompter || teleprompterHidden) && !screenDisplayOn) Box(
            Modifier
                .fillMaxSize()
                .zIndex(2f)
                .pointerInput(vm) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val lastX = down.position.x
                        val lastY = down.position.y
                        dragging = false
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.positionChange() != Offset.Zero) {
                                dragging = true
                                val dx = change.positionChange().x
                                val dy = change.positionChange().y
                                val st = vm.layout.value
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                when (st.mode) {
                                    LayoutMode.SPLIT_V -> vm.setLayoutState(
                                        LayoutEngine.dragSplit(st, true, dx / w, 0f)
                                    )

                                    LayoutMode.SPLIT_H -> vm.setLayoutState(
                                        LayoutEngine.dragSplit(st, false, 0f, dy / h)
                                    )

                                    LayoutMode.PIP -> vm.setLayoutState(
                                        LayoutEngine.dragPip(st, dx / w, dy / h, 9f / 19.5f, 16f / 9f)
                                    )

                                    else -> {}
                                }
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                        if (!dragging) {
                            focusRing = lastX to lastY
                            vm.mapTapToCamera(lastX, lastY, size.width.toFloat(), size.height.toFloat())
                                ?.let { (lane, coords) ->
                                    vm.tapToFocus(lane, coords.first, coords.second)
                                }
                        }
                    }
                },
        )

        focusRing?.let { (fx, fy) ->
            Box(
                Modifier
                    .offset { IntOffset((fx - 60).toInt(), (fy - 60).toInt()) }
                    .size(120.dp)
                    .border(2.dp, CyanAccent, CircleShape)
                    .alpha(0.85f)
                    .zIndex(3f),
            )
        }

        // ---------------------------------------------------------- modalità Screen display (UI minimale)
        // Solo: preview camera, REC/Stop, Exit → fullscreen.
        if (screenDisplayOn) {
            Column(
                Modifier
                    .fillMaxSize()
                    .zIndex(8f)
                    .padding(top = 6.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                ShutterButton(recording, paused, compact = true) {
                    when {
                        recording || paused -> vm.stopRecording()
                        else -> vm.startRecording { vm.startForegroundService() }
                    }
                }
            }
        }

        // ---------------------------------------------------------- top overlay
        if ((!showTeleprompter || teleprompterHidden) && !screenDisplayOn) Row(
            Modifier
                .fillMaxWidth()
                .zIndex(4f)
                .padding(top = 14.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MicBadge(micLabel, micLevel) { showMicPicker = true }
            Spacer(Modifier.weight(1f))
            TimecodePill(elapsed, recording, paused)
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Filled.AutoFixHigh,
                contentDescription = "Effetti live",
                tint = if (liveEffectId != "none") Color.White else TextSecondary,
                modifier = Modifier
                    .size(26.dp)
                    .alpha(if (liveEffectId != "none") 1f else 0.45f)
                    .clickable { showLiveEffects = true },
            )
            Spacer(Modifier.width(14.dp))
            FlashButton(flashOn) { vm.cycleFlash() }
            Spacer(Modifier.width(14.dp))
            Icon(
                Icons.Filled.Tune,
                contentDescription = "Impostazioni camera",
                tint = Color.White,
                modifier = Modifier
                    .size(26.dp)
                    .clickable { showSettings = true },
            )
        }

        if (!showTeleprompter || teleprompterHidden) statusHint?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceElevated)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .zIndex(5f),
            )
        }

        // ---------------------------------------------------------- colonna inferiore
        if ((!showTeleprompter || teleprompterHidden) && !screenDisplayOn) Column(
            Modifier
                .align(Alignment.BottomCenter)
                .zIndex(4f)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NewProjectPill {
                scope.launch {
                    vm.repository.createProject(null)
                    message = "Nuovo progetto: il prossimo take finirà qui"
                }
            }

            Spacer(Modifier.height(10.dp))

            // Pillole swap + layout (stile iOS "⇄ | Layout")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.Black)
                    .border(1.5.dp, Color.White, RoundedCornerShape(22.dp)),
            ) {
                Icon(
                    Icons.Filled.SwapHoriz,
                    contentDescription = "Scambia camere",
                    tint = Color.White,
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                        .size(20.dp)
                        .clickable {
                            if (!vm.swapCameras()) message = "In dual il pulsante ⇄ scambia anche in diretta"
                        },
                )
                VerticalDivider()
                Icon(
                    Icons.Filled.GridOn,
                    contentDescription = "Regola layout",
                    tint = Color.White,
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                        .size(20.dp)
                        .clickable { showLayoutPicker = true },
                )
                VerticalDivider()
                Icon(
                    Icons.Filled.PictureInPictureAlt,
                    contentDescription = "Screen display: split + frontale + schermo",
                    tint = Color.White,
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                        .size(20.dp)
                        .clickable { enterScreenDisplay() },
                )
            }

            Spacer(Modifier.height(10.dp))

            QuickControlsRow(
                vm = vm,
                zoomA = zoomA,
                zoomB = zoomB,
                focusA = focusA,
                labelA = cameraLabel(settings.cameraIdA, catalog),
                labelB = cameraLabel(settings.cameraIdB, catalog),
                onPickCameraA = { pickerLane = 0 },
                onPickCameraB = { pickerLane = 1 },
                onOpenLayoutPicker = { showLayoutPicker = true },
                onMessage = { message = it },
            )

            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SideIcon(Icons.Filled.PhotoCamera, "Foto") { vm.capturePhoto { } }
                    if (lastPhoto != null) {
                        Spacer(Modifier.height(6.dp))
                        it.agrilloit.turegistri.ui.photos.PhotoThumb(
                            uri = lastPhoto!!,
                            sizePx = 96,
                            modifier = Modifier.size(40.dp),
                            onClick = { showGallery = true },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    SideIcon(Icons.AutoMirrored.Filled.Notes, "Teleprompter") { showTeleprompter = true }
                }
                Spacer(Modifier.width(20.dp))
                ShutterButton(recording, paused) {
                    when {
                        recording || paused -> vm.stopRecording()
                        screenDisplayOn && !vm.hasScreenProjection() -> requestProjectionThen { }

                        else -> vm.startRecording { vm.startForegroundService() }
                    }
                }
                Spacer(Modifier.width(20.dp))
                if (recording || paused) {
                    SideIcon(
                        if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        if (paused) "Resume" else "Pause",
                    ) { vm.togglePause() }
                } else {
                    SideIcon(Icons.Filled.BookmarkAdd, "Mark") { vm.addMarker() }
                }
            }

            if (markers.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.Center) {
                    markers.takeLast(4).forEach { m ->
                        Text(
                            "▸ ${formatMs(m)}",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
            }
        }

        message?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceElevated)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .zIndex(6f),
            )
            LaunchedEffect(it) {
                kotlinx.coroutines.delay(2200)
                message = null
            }
        }

        if (showTeleprompter) {
            Box(Modifier.fillMaxSize().zIndex(10f)) {
                it.agrilloit.turegistri.ui.teleprompter.TeleprompterOverlay(
                    initialText = "",
                    hidden = teleprompterHidden,
                    onDismiss = { showTeleprompter = false; teleprompterHidden = false },
                    onHide = { teleprompterHidden = true },
                )
            }
        }
        // Pillola riapri quando il teleprompter è nascosto (in pausa).
        if (showTeleprompter && teleprompterHidden) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 14.dp, start = 12.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(SurfaceElevated.copy(alpha = 0.92f))
                    .border(1.5.dp, Color.Black, RoundedCornerShape(18.dp))
                    .clickable { teleprompterHidden = false }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
                    .zIndex(11f),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Notes,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Teleprompter", color = Color.White, fontSize = 12.sp)
            }
        }
        if (showSettings) {
            CameraSettingsSheet(
                vm = vm,
                onDismiss = { showSettings = false },
            )
        }
        if (showLayoutPicker) {
            LayoutAdjuster(
                vm = vm,
                current = layout,
                onDismiss = { showLayoutPicker = false },
            )
        }
        if (showGallery) {
            val photos = remember(showGallery) { vm.photos() }
            androidx.compose.ui.window.Dialog(onDismissRequest = { showGallery = false }) {
                it.agrilloit.turegistri.ui.photos.PhotoGalleryScreen(
                    photos = photos,
                    onBack = { showGallery = false },
                    onDelete = { uri -> vm.deletePhoto(uri) },
                )
            }
        }
        if (showSplitHelp) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSplitHelp = false },
                title = { Text("Abilita lo split automatico", color = Color.White) },
                text = {
                    Text(
                        "Per entrare automaticamente in split screen, YouRec ha bisogno " +
                            "del servizio accessibilità (una sola volta).\n\n" +
                            "1. Tocca \"Apri impostazioni\"\n" +
                            "2. Attiva \"YouRec Split\"\n\n" +
                            "In alternativa: attiva manualmente lo split dai Recenti " +
                            "(tieni premuta l'icona di YouRec → Split screen).",
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                },
                confirmButton = {
                    Text(
                        "Apri impostazioni",
                        color = SecondaryAccent,
                        modifier = Modifier.clickable {
                            showSplitHelp = false
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.padding(8.dp),
                    )
                },
                dismissButton = {
                    Text(
                        "Faccio a mano",
                        color = TextSecondary,
                        modifier = Modifier.clickable { showSplitHelp = false }.padding(8.dp),
                    )
                },
                containerColor = SurfaceDark,
            )
        }
    if (showLiveEffects) {
        LiveEffectsSheet(
            activeId = liveEffectId,
            strength = liveStrength,
            peaking = livePeaking,
            onPick = { id, strength ->
                liveEffectId = id
                liveStrength = strength
                val entry = LiveEffectCatalog.byId(id)
                vm.engine.applyLiveEffect(
                    if (entry == null || entry.mode == 0) null else LiveEffectCatalog.build(entry, strength)
                )
            },
            onPeaking = { enabled ->
                livePeaking = enabled
                vm.engine.applyPeaking(enabled)
            },
            onDismiss = { showLiveEffects = false },
        )
    }
        if (showMicPicker) {
            MicPickerDialog(
                vm = vm,
                onDismiss = { showMicPicker = false },
            )
        }
        pickerLane?.let { lane ->
            CameraPickerDialog(
                context = context,
                excludeId = if (lane == 0) settings.cameraIdB else settings.cameraIdA,
                currentId = if (lane == 0) settings.cameraIdA else settings.cameraIdB,
                otherId = if (lane == 0) settings.cameraIdB else settings.cameraIdA,
                onPick = { id ->
                    vm.updateSettings { st ->
                        if (lane == 0) st.copy(cameraIdA = id) else st.copy(cameraIdB = id)
                    }
                    pickerLane = null
                },
                onDismiss = { pickerLane = null },
            )
        }
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("YouRec ha bisogno di camera e microfono", color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(14.dp))
            Text(
                "Concedi permessi",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(PrimaryAccent)
                    .clickable(onClick = onRequest)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun MicBadge(label: String, level: Float, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black)
            .border(1.5.dp, Color.White, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(Icons.Filled.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .width(40.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(DividerDark),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(level.coerceIn(0.03f, 1f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (level > 0.85f) PrimaryAccent else CyanAccent),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Text("▾", color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun TimecodePill(elapsedMs: Long, recording: Boolean, paused: Boolean) {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(TimecodeRed)
            .border(1.5.dp, Color.White, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            formatMs(elapsedMs) + if (paused) " ‖" else "",
            color = Color.White,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun cameraLabel(id: String?, catalog: List<it.agrilloit.turegistri.camera.CameraCatalog.CameraOption>): String =
    id?.let { wanted -> catalog.firstOrNull { it.id == wanted }?.label } ?: "Auto"

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
}

@Composable
private fun NewProjectPill(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black)
            .border(1.5.dp, Color.White, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Start new project", color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun QuickControlsRow(
    vm: RecordViewModel,
    zoomA: Float,
    zoomB: Float,
    focusA: Pair<Boolean, Float>,
    labelA: String,
    labelB: String,
    onPickCameraA: () -> Unit,
    onPickCameraB: () -> Unit,
    onOpenLayoutPicker: () -> Unit,
    onMessage: (String) -> Unit,
) {
    var activeLane by remember { mutableStateOf(0) }
    val zoom = if (activeLane == 0) zoomA else zoomB
    Column(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color.Black)
            .border(1.5.dp, Color.White, RoundedCornerShape(22.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val focusNow = if (activeLane == 0) focusA else vm.focusB.collectAsState().value
        // Riga unica stile YouRec iOS: lente | zoom | focus
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Videocam,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            // Dropdown lente per stream attivo (stile iOS "Front Main ⌄")
            Text(
                (if (activeLane == 0) labelA else labelB) + " ⌄",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable {
                        activeLane = if (activeLane == 0) 0 else 1
                        if (activeLane == 0) onPickCameraA() else onPickCameraB()
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            VerticalDivider()
            // Preset zoom
            ZOOM_PRESETS.forEachIndexed { i, z ->
                if (i > 0) Spacer(Modifier.width(2.dp))
                val selected = abs(zoom - z) < 0.05f
                Text(
                    String.format(Locale.US, "%.1fx", z),
                    color = if (selected) PrimaryAccent else Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable { vm.applyZoom(activeLane, z) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            VerticalDivider()
            Text(
                "AF",
                color = if (!focusNow.first) Color.White else TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { vm.setFocus(activeLane, false) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Text(
                "MF",
                color = if (focusNow.first) PrimaryAccent else TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable { vm.setFocus(activeLane, true, focusNow.second) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        if (focusNow.first) {
            Spacer(Modifier.height(4.dp))
            Slider(
                value = focusNow.second,
                onValueChange = { vm.setFocus(activeLane, true, it) },
                valueRange = 0f..10f,
                colors = SliderDefaults.colors(thumbColor = PrimaryAccent),
                modifier = Modifier.width(180.dp),
            )
        }
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(18.dp)
            .background(TextSecondary.copy(alpha = 0.5f)),
    )
}

@Composable
private fun ShutterButton(
    recording: Boolean,
    paused: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val size = if (compact) 26.dp else 84.dp
    val scale by animateFloatAsState(if (recording || paused) 0.85f else 1f, tween(200), label = "shutter")
    val inner = if (compact) {
        size * (if (recording) 0.55f else 0.68f)
    } else {
        (if (recording) 52.dp else 60.dp)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .scale(scale)
            .background(Color.Black, CircleShape)
            .padding(if (compact) 1.dp else 3.dp)
            .border(if (compact) 1.5.dp else 4.dp, Color.White, CircleShape)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .size(inner)
                .clip(if (recording) RoundedCornerShape(if (compact) 5.dp else 10.dp) else CircleShape)
                .background(PrimaryAccent),
        )
    }
}

@Composable
private fun SideIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Color.Black)
            .border(1.5.dp, Color.White, CircleShape)
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun FlashButton(on: Boolean, onToggle: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (on) PrimaryAccent else Color.Black)
            .border(2.dp, if (on) Color.White else Color.White, CircleShape)
            .clickable(onClick = onToggle),
    ) {
        Icon(
            Icons.Filled.FlashlightOn,
            contentDescription = if (on) "Flash acceso" else "Flash spento",
            tint = Color.White,
            modifier = Modifier.size(19.dp),
        )
    }
}
