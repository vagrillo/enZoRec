# AGENTS.md — Guide for coding agents working on enZoRec

> Project renamed from YouRecPlay (app identity `it.agrilloit.yourec` kept for
> compatibility with installed devices). Licensed MIT — see LICENSE.

This file condenses **what YouRec is, how it must look/behave, and every
non-obvious finding** discovered while building it. Read it before changing
anything: several behaviours are counter-intuitive and were learned the hard
way on a Samsung Galaxy S23 Ultra (OneUI / Android 14+).

---

## 1. What YouRec is (functional spec)

An Android app that records **multiple video/audio streams simultaneously**
and edits them. Two generations exist; this repo is the second (YouRec,
applicationId `it.agrilloit.yourec`, minSdk 30). The legacy first-generation
app (`it.agrilloit.turegistri`, v1.2.x) is kept only as a release APK.

### 1.1 Recording (Record screen)

- **Camera model**: read `CameraManager.getConcurrentCameraIds()` (API 30+).
  It returns `Set<Set<String>>` — *combinations* that can run together, NOT a
  flat set. Prefer a combo containing back+front. Any single camera also works
  (non-concurrent mode restores full capabilities).
- **Default startup mode**: single camera = **last camera used** (persisted in
  SharedPreferences `yourec_settings`, key `last_camera_id`). First launch =
  main rear camera.
- **Two modes**:
  - *Single*: one camera (`cameraIdA` or `lastCameraId`).
  - *Dual*: two cameras (explicit pair must pass the concurrency check; if only
    one is chosen, the engine completes the pair with a concurrent partner).
- **Per-stream resolution**: guaranteed 720p per camera in concurrent mode;
  1080p/4K best-effort. If session configuration fails at a higher resolution,
  **retry the whole pipeline once at 720p** (`retried720` flag).
- **Layouts** (live, composited in GL): SPLIT_V, SPLIT_H, PIP (rect/circle
  inset), SINGLE_A, SINGLE_B. All geometry is normalized fractions
  (`LayoutEngine.compute`) — never hardcode pixels.
- **Camera swap**:
  - dual mode → swap lanes via `LayoutState.swapped` (pure render swap,
    works **while recording**);
  - single mode → physically reopen the other facing (requires IDLE).
- **Zoom** per lane via `CaptureRequest.CONTROL_ZOOM_RATIO` clamped to the
  camera's `CONTROL_ZOOM_RATIO_RANGE` (read it from *Characteristics*, not
  from CaptureRequest — the latter does not exist).
- **Focus**: AF = `CONTROLS_AF_MODE_CONTINUOUS_VIDEO`; MF = `AF_MODE_OFF` +
  `LENS_FOCUS_DISTANCE` (diopters, 0..10 slider). Tap-to-focus sets
  AF/AE regions (they are `int[]`: x0,y0,x1,y1,weight — cast the key) and
  triggers `CONTROL_AF_MODE_AUTO` + AF_TRIGGER_START, then returns to
  continuous.
- **Flash** is facing-dependent:
  - rear camera → real LED via `CaptureRequest.FLASH_MODE_TORCH`
    (see §3.4 for the reliability workaround);
  - front camera → screen softbox, 4 levels (THIN 14dp / MEDIUM 26dp / WIDE
    44dp / EXTRA = full-white screen at 100% window brightness with the
    preview shrunk into a proportional window covering 10% of the area).
  - **State follows the facing**: swap front↔rear → torch off, softbox on,
    and vice-versa (`refreshFlash()` / `frontActiveFlow`).
- **Photos**: from IDLE, reconfigure the session with a JPEG `ImageReader`
  at the camera's **max resolution** (`TEMPLATE_STILL_CAPTURE`), then restore
  the preview session. Save bytes to MediaStore `Pictures/YouRec` without
  re-encoding. While RECORDING, fall back to a GL `glReadPixels` capture of
  the composited preview (the session cannot be reconfigured mid-recording).
- **Microphones** (`MicEnumerator`, 3-tier strategy, verified by actually
  initializing an AudioRecord):
  1. one device with a multichannel `channelIndexMask` → one multichannel WAV
     (channels sample-aligned — best for post);
  2. several built-in mics → one `AudioRecord` per mic routed with
     `setPreferredDevice` (one mono WAV each);
  3. fallback to `AudioSource.MIC`.
  Optional system `NoiseSuppressor.create(audioSessionId)` on each track
  (toggle in settings).
- **Session output** (`files/sessioni/<timestamp>/`):
  `cam0.mp4`, `cam1.mp4` (video only), `composito.mp4` (live layout + AAC
  audio, share-ready), `mic_*.wav`, optional `schermo.mp4` + `app_audio.wav`
  (Screen display mode), `session.json` (per-stream epoch +
  `elapsedRealtimeNanos` start timestamps, lens info, orientation) and
  `thumb.jpg`. `session.json` is what makes post-production alignment
  possible — keep it accurate.

### 1.2 Screen display mode (PiP recording)

User story: YouRec shrinks to a small floating window (front camera + REC/Stop)
and the user runs **any other app below** (browser, YouTube, live timing…),
while YouRec records **4 streams**: front camera MP4 + screen MP4
(MediaProjection) + other-apps audio WAV (AudioPlaybackCapture) + mics WAVs.

- Entry (`enterScreenDisplay`):
  1. **Ask MediaProjection consent FIRST, in fullscreen** (`createScreenCaptureIntent`),
     *before* entering PiP — launching the consent activity from a PiP window
     kicks the app out of PiP.
  2. Create the `MediaProjection` immediately and keep it (do NOT stop it
     between takes — it is reusable for multiple VirtualDisplays until
     `projection.stop()`; you get one consent per mode entry).
  3. Set settings: `screenDisplay=true`, `dualCamera=false`,
     `cameraIdA=<front id>`; apply (engine restarts cameras front-only).
  4. Enter **Picture-in-Picture** (`enterPictureInPictureMode`, aspect
     9:15) and launch the home intent so the launcher fills the screen behind.
- **Minimal PiP UI, nothing else**: camera preview (~90% of the window),
  compact REC/Stop (26dp), nothing more. Hide *everything* else (top bar,
  bottom column, gesture overlay, **and the Scaffold bottom nav** — drive it
  from `engine.screenDisplayFlow`). If any control remains visible/clickable
  it will steal taps from the teleprompter/preview (this bug happened twice).
- Exit (⟐ button): expand the activity (`startActivity` of self with
  `FLAG_ACTIVITY_REORDER_TO_FRONT`), `engine.releaseScreenProjection()`,
  restore automatic dual-camera settings.
- Recording in this mode = engine.startRecording as usual; the engine creates
  a `VirtualDisplay` + MediaRecorder (screen) and an
  `AudioPlaybackCaptureConfiguration` AudioRecord (other apps' audio) from the
  kept projection. **Do not call `projection.stop()` between takes.**
- Note: Android 14+ requires the FGS with type `mediaProjection` to be started
  *before* `createVirtualDisplay` is called, and the consent result to be
  recent. The service composes its types conditionally from
  `engine.screenDisplayWanted`.

### 1.3 Teleprompter

Floating overlay (zIndex above everything) with auto-scrolling text.
Sources: paste/type inline, `.txt` file picker, plain-text URL (download on
IO dispatcher). Controls: play/pause (tap), manual scroll (drag), speed slider
(0.2–8, shown as px/s), font size slider (16–56), background opacity slider,
hide button (pause + collapse to a small "Teleprompter" pill that reopens it),
change-source button. **Text and settings persist** in SharedPreferences
(`teleprompter`): they must survive hide, close and app restart.
While the overlay is open, the host screen must render **no clickable
controls** — any surviving button steals taps (this bug happened twice too).

### 1.4 Projects & Editor

- Room DB: `projects` + `takes` (a take = one recording session folder).
  The take points to `composito.mp4` (editor source) and stores markers.
- New takes attach to the **most recent project** ("Continue recording"
  touches its updatedAt).
- Editor (`EditorScreen` + `EditorViewModel`):
  - clips = one per take (source = `composito.mp4`, fallback cam0/cam1 if
    the composite is unreadable — old/crashed sessions);
  - trim in/out, split at playhead, delete, move L/R, undo/redo (30),
    snap-to-cuts, add video via SAF;
  - playback: ExoPlayer with **clipped MediaItems** (ClippingConfiguration),
    playhead = global time reconstructed from current window;
  - timeline: Canvas with ruler, thumbnail strips (MediaMetadataRetriever),
    **waveform peaks computed streaming from the take's `mic_0.wav`**;
  - **waveforms must be time-aligned**: mics start/stop around the videos —
    read the offset from `session.json` (`audio.startedEpochMs` vs min of
    `cameras[].startedEpochMs`) and slice the peak array to the video window;
  - export: Media3 `Transformer` + `Composition.Builder(sequence)` with
    optional `NoiseReducer.Processor`; clips < 100 ms are dropped (they break
    the muxer); if export with NR fails, retry once without NR and surface the
    real error.
- Photos gallery: MediaStore query on `Pictures/YouRec`, grid + viewer with
  share/delete.

## 2. UI spec (visual system — replicate the iOS reference)

Dark theme only. Palette: background `#0D0E10`, surface/cards `#16181D`,
elevated controls `#22252D`, primary red `#FF3B30`, timecode pill
**carmine `#8E1F1F`**, blue `#007AFF`, green badge `#30D158`, cyan waveform
`#30B0C7`, secondary text `#8E8E93`, divider `#2C2C2E`.

- **Record screen**: top = mic badge (live RMS level bar + source name +
  dropdown), carmine timecode pill (center), flash button, effects + settings
  icons. Bottom = "Start new project" pill, then **one** control bar
  (camera icon | lens `⌄` | zoom presets | vertical dividers | AF | MF),
  then a small pill row (⇄ swap | ⊞ layout | ⛉ screen display), then the
  shutter with Photo / Teleprompter / Mark around it. No labels under side
  buttons (iOS style).
- **Screen display minimal UI**: preview only + compact REC/Stop (26dp) +
  nothing else. The exit/fullscreen affordance is *not* in the PiP window
  (system PiP controls provide expand; the ⟐ button exists when expanded).
- **Projects**: header logo **"you" (white) + "rec" (red)**, "PROJECTS" label,
  project cards (thumbnail, date, takes count, exported badge, ⋮ menu, red
  "Continue recording" + blue "Edit" pills). Bottom nav **3 tabs**:
  Record / Projects / Edit (Edit opens the latest project's editor).
- **Sheets**: centered title + pill "Done" on the right, grabber on top.
- **Buttons must be readable on ANY video background**: black fill with
  **white borders** (white fill/black border for the OFF flash button,
  red fill/white border for ON). Never rely on the video behind them.
- **Status/nav insets**: respect system bars — the app draws edge-to-edge;
  navigation bar insets must be applied to the bottom bar and editor action
  bar or buttons end up under the gesture bar.

## 2-bis. Spec visiva iOS (da RecorderView/EditorView/EditorTools + screenshot)
- **Accent rossi**: `Color(red:1, green:0.25, blue:0.29)` = #FF4049 (record/editor);
  **Done e stati attivi settings**: **ciano** (#3ED1E6 ≈ SwiftUI .cyan);
  **effetti video/strumenti colore**: **arancio** #F09132 (slider + toggle power).
- **Foglio modale iOS (SheetScaffold)**: header FISSO — capsula chevron "⌄" a
  sinistra, titolo centrato semibold, capsula "Done" a destra (ciano o bianco);
  contenuto scrollabile sotto (max ~86% schermo), dragHandle disattivato,
  navigationBarsPadding. Mai far scorrere via la "Done".
- **Slider iOS**: track sottile colorato (arancio/mint) + manopola bianca rotonda,
  etichetta a sinistra e valore a destra della riga.
- **Tinte tool** (EditorTools.swift): subtitles ciano, text viola, speed blu,
  color rosa, stabilize mint, pauses bianco, crop indigo, mirror teal, image/
  transition/placement arancio, videoEffects/addVideo ciano, audio mint.
- Capsule controlli record: nero 65% + bordo bianco 30%; controlPlate nero 52%
  (o bianco 14% con front light).

## 3. Findings — read before touching these areas

### 3.1 Camera2
- `getConcurrentCameraIds()` returns **combinations** (`Set<Set<String>>`).
- Samsung throws spurious **`OPENING_FAILED`** on the second camera; the
  controller retries each camera **once** after 450 ms and that always
  recovers it. Never give up on first failure.
- Opening camera 1 (front) while camera 0 streams works only sequentially —
  the controller opens cameras one at a time and chains `openCameraAt(i+1)`
  from the previous camera's `onConfigured`.
- Do **not** use `MediaCodec.createPersistentInputSurface()` as a camera
  target before an encoder is configured — OneUI rejects the session
  ("Unsupported set of inputs/outputs"). The working pattern: session with
  preview only → at REC, **reconfigure** adding `MediaRecorder.getSurface()`
  (recorder `prepare()`d first) → at stop, reconfigure back to preview-only.
- `CONTROL_AF_REGIONS`/`AE_REGIONS` keys need an explicit
  `as CaptureRequest.Key<IntArray>` cast in Kotlin.

### 3.2 OpenGL compositor
- **One EGL context on a dedicated HandlerThread.** Always keep *some* surface
  current (a 1×1 pbuffer "dummy"): if you leave the context with `EGL_NO_SURFACE`
  between passes, `SurfaceTexture.updateTexImage()` throws
  "Unable to update texture contents" and kills the process. Wrap the
  update loop in try/catch anyway — the GL thread must **never** crash during
  a recording.
- `eglCreateWindowSurface` on the encoder surface needs
  `EGL_RECORDABLE_ANDROID` (`EGLExt`, not `EGL14`).
- `GL_TEXTURE_EXTERNAL_OES` lives in `GLES11Ext`.
- **Texture transform is per-camera and device-dependent.** Do not hardcode
  rotation steps: at the first frame, extract the 2-D affine from
  `SurfaceTexture.getTransformMatrix()`, **invert it** (L⁻¹), compose with the
  sensor-orientation rotation W (`M = L⁻¹·W`) and, if mirrored, compose a
  mirror matrix on the right. Verify W closes the rotation group — the 270°
  sign error produced an upside-down front camera and cost a debugging cycle.
- `discard` is legal **only in fragment shaders** — a mask in the vertex shader
  fails compilation and (without protection) crashes the app.
- The GL context must be current when creating textures/programs: keep the
  pbuffer current on the thread at all times.

### 3.3 MediaRecorder
- Call order is strict: **sources → `setOutputFormat` → output file →
  encoders → (orientation) → (`setInputSurface`) → prepare**. Wrong order =
  "called in an invalid state".
- `MediaRecorder.pause()/resume()` works (API 24+) — used for pause.
- The **composito** and per-camera recorders use `recorder.getSurface()`
  after `prepare()`; pass that surface into the camera session.

### 3.4 Torch
- `FLASH_MODE_TORCH` in the repeating request is **unreliable on OneUI**.
  Working approach: `CameraManager.setTorchMode(cameraId, true)` (system
  torch), falling back to the request-based torch; re-apply after every
  session reconfiguration. Turn torch off automatically when the active
  camera is the front one.

### 3.5 MediaProjection (Screen display)
- Consent (`createScreenCaptureIntent`) must happen **in fullscreen**; calling
  it from a PiP window expands/kicks the window. Ask once at mode entry and
  keep the projection alive for the whole mode (do not `stop()` between
  takes — the token is reusable for multiple VirtualDisplays).
- On Android 14+ the FGS with type `mediaProjection` must be started
  **before** `createVirtualDisplay`. The service composes its FGS types
  conditionally (`camera|microphone[|mediaProjection]`) from
  `engine.screenDisplayWanted`.
- Recording screen: `VirtualDisplay(AUTO_MIRROR)` + MediaRecorder SURFACE at
  native resolution, H.264 10 Mbps.
- Other apps' audio: `AudioPlaybackCaptureConfiguration.Builder(projection)`
  + `addMatchingUsage(MEDIA/GAME/UNKNOWN)` → `AudioRecord.Builder`
  (`.setContext(context)` required) → WavWriter. DRM players produce silence
  — not fixable.

### 3.6 Media3
- **Transformer is thread-confined**: build, `start` and `getProgress` must
  all run on the **same thread with a Looper** (main). Doing it on an IO
  dispatcher throws "Transformer is accessed on the wrong thread".
  Pattern: prepare clips/composition on IO, then `withContext(Dispatchers.Main)`
  for build/start/progress-poll.
- API 1.4.1 signatures: `Transformer.start(Composition, String)`,
  `Composition.Builder(EditedMediaItemSequence)`,
  `EditedMediaItemSequence(List<EditedMediaItem>)`,
  `Transformer.Listener.onError(Composition, ExportResult, ExportException)`,
  effects = `androidx.media3.transformer.Effects(List<AudioProcessor>, List<Effect>)`.
- ExoPlayer preview of clipped clips: `setMediaItems` with
  `ClippingConfiguration`, `playWhenReady` driven by state; global playhead =
  `globalStartOf(currentMediaItemIndex) + currentPosition`.
- Set explicit `AudioAttributes` (USAGE_MEDIA) on the preview player or audio
  may stay silent on some devices.

### 3.7 Lifecycle & state races
- The recording engine is a **singleton** (survives Activity recreation).
  Every `start()`/init must be idempotent (the very first crash was
  "Renderer già avviato" on second launch).
- When swapping cameras or changing settings **while the pipeline reopens**,
  never read stale in-memory state: expose **StateFlows from the engine**
  (e.g. `frontActiveFlow`) and let the UI collect them. Reading "current
  facing" at swap time returned the old cameras and inverted the flash.
- `startRecording` must **wait up to 6 s** for cameras to be ready instead of
  failing with "Camere non ancora pronte" (cameras reopen when the mode
  changes and users press REC during that window).
- After a **force-stop**, Android does **not** rebind accessibility services
  on process restart: `instance` stays null. `SplitScreenService.rebindIfNeeded()`
  rewrites `ENABLED_ACCESSIBILITY_SERVICES` (needs `WRITE_SECURE_SETTINGS`,
  grantable via `adb shell pm grant`) and waits for `onServiceConnected`.
  On OneUI, `GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN` returns false — the screen
  display mode uses **Picture-in-Picture instead** (public API, always works).
- Samsung blocks accessibility for **adb-installed apps** until the user runs
  App info → ⋮ → "Allow restricted settings". Expect the denial dialog.

### 3.8 Compose
- `collectAsState()` + `remember(activity)` for engine flows at app level.
- Trailing-lambda composable signatures: optional non-lambda parameters must
  come **before** the trailing `onClick: () -> Unit` or call sites break.
- In PiP, render **only** the minimal UI: any leftover visible/clickable
  control steals touches from the prompter/preview (happened twice).
- Insets: apply `WindowInsets.navigationBars` to the bottom bar / action bar;
  the app draws edge-to-edge.

### 3.9 Kotlin/JVM tests
- `WavWriter` RIFF header: RIFF size = 36 + dataBytes, write via a separate
  `FileChannel` handle (`buffered().channel` is uncommitted until flush).
- Layout tests: `fitInside` keeps the region center; cover-crop visible-area
  math — derive expected values from aspect identities, not vibes.
- NoiseReducer: a pure stationary tone IS removed by any spectral denoiser
  (indistinguishable from stationary noise) — test "voice over noise survives
  ≥2× the denoised-noise RMS" instead of "tone preserved".

### 3.10 Device testing
- Wireless ADB port **rotates** on every re-enable: the stable fix is one
  `adb tcpip 5555` over USB (lost on phone reboot).
- Samsung rejects accessibility for adb-installed apps until "Allow restricted
  settings" is run from App info.
- `adb shell settings put secure split_view_by_tapping 1` enables OneUI's
  two-finger split gesture; `GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN` returns false
  on OneUI regardless.
- Don't force-stop the app during tests unless necessary: it kills the
  accessibility binding until rebind.

### 3.11 Media3 effects (v3.4.0 recipes)
- Custom fragment shader: subclass `BaseGlShaderProgram(false, 1)`, override
  `configure`→`Size(w,h)` and `drawFrame` (use + setSamplerTexIdUniform +
  bindAttributesAndUniforms + glDrawArrays TRIANGLE_STRIP 0,4). Vertex shader:
  attribute `aFramePosition`, uniforms `uTransformationMatrix`/`uTexTransformationMatrix`,
  varying `vTexSamplingCoord`. `GlProgram(String, String)` takes RAW shader strings.
- **Kotlin 2.0.20 (K2) BUG su questa toolchain: `data class` dentro una
  `sealed class` genera "Argument type mismatch: actual X.A, expected X" anche
  se il codice è corretto. Usare `abstract class` (o class normali) — verificato
  con un caso minimo.**
- LUT 3D native: `SingleColorLut.createFromCube(int[N][N][N])` — cubo indicizzato
  `[red][green][blue]` con colore packed; il `.cube` Adobe ha ROSSO che varia più
  velocemente (linea i → r=i%N, g=(i/N)%N, b=i/N²).
- Overlay dinamici (sottotitoli/titoli): `TextureOverlay` custom — override
  `getTextureId(t)`/`getTextureSize(t)`/`getOverlaySettings(t)`, texture creata
  con `GlUtil.createTexture(bitmap)` (GL thread), cache per step 33 ms, texture
  1×1 trasparente quando fuori finestra. `OverlayEffect(ImmutableList.of(...))`
  per item; il presentationTime è RELATIVO all'item → passare l'offset globale.
- `EditedMediaItem.Builder.setFrameRate(int)` esiste; `TransformationRequest.setResolution`
  è package-private (niente downscale pubblico in 1.4.1).
- **Unit test e org.json**: android.jar nei test JVM è stub (put/toString(2)
  ritornano null) → `testImplementation("org.json:json:20231013")`; NON concatenare
  `.put().put()` (semantica Map.put su JVM), evitare `toString(2)`.

### 3.12 Sottotitoli ASR (vosk)
- `com.alphacephei:vosk-android` su Maven Central solo fino a 0.3.75 (le 0.5.x
  non sono su alcun repo pubblico; alphacephei.com/maven è morto — 404).
  Serve anche `net.java.dev.jna:jna:5.13.0@aar`. Modelli runtime da
  alphacephei.com/vosk/models (small-it ~41MB, small-en-us ~40MB), unzip in
  filesDir/vosk/<dir>. PCM 16 kHz mono.

### 3.13 Audio export (v3.4.0)
- Export = Transformer video-only (`setRemoveAudio(true)`) + `OfflineAudioMixer`
  (decode MediaExtractor/MediaCodec → speed via Sonic standalone → volume/fade →
  timeline → beep censor → tracce aggiunte → catena AudioProcessor offline →
  WAV 48k stereo) + `AudioMuxer` (PCM→AAC su m4a temporaneo, poi copia video
  samples + audio nel MediaMuxer finale). Il video non viene ricodificato.

## 4. Where things live

```
app/src/main/java/it/agrilloit/turegistri/
  camera/    ConcurrentCameraDetector, CameraCatalog (zoom labels),
             CameraController (open/retry/zoom/focus/torch/photo/still)
  gl/        CompositeRenderer (EGL + 2 external textures + ST-matrix math),
             VideoEffectShaderProgram (motore shader effetti), LiveEffects
  subtitles/ SubtitlesEngine (vosk ASR, grouping, SRT)
  vibe/      VibeDirector (euristico) + VibeTools (registro tool)
  model/     LayoutEngine, ColorGrade, VideoEffectCatalog (21 effetti + GLSL),
             CubeLut, OtioExport, Stabilizer, TitleDesign
  audio/     MicEnumerator, MicRecorder, WavWriter, NoiseReducer
  record/    RecordingEngine (orchestrator/singleton), VideoRecorder,
             ScreenStreams (MediaProjection)
  service/   RecordingService (FGS), SplitScreenService (accessibility)
  data/      Room (projects/takes), ProjectsRepository, PhotosRepository
  ui/        Compose: record/, editor/, projects/, photos/, teleprompter/,
             theme/
  model/     LayoutEngine (pure), LayoutMode, SessionManifest (JSON writer)
```

Engine state machine: `IDLE → PREPARING → RECORDING ⇄ PAUSED → STOPPING → IDLE`.
All state changes go through `_stateFlow`; UI must never read stale fields
directly.

## 4-bis. Segreti — regola assoluta

**Mai committare chiavi API, token, password o .env.** Se una funzione ha
bisogno di un provider esterno (es. LLM director via OpenRouter):
- la chiave si inserisce a runtime nelle impostazioni in-app e si salva SOLO
  in SharedPreferences locali (mai in file tracciati);
- per costi di build si può usare `local.properties` (già gitignored) letto
  via `BuildConfig` solo se strettamente necessario;
- prima di ogni push, scansione: `git grep -iE "api_key|sk-|Bearer "`.
- Il progetto di riferimento web TuDirector ha un `.env` con chiavi: NON
  copiarlo mai in questo repo.

## 5. Definition of done (per change)

- `gradle assembleDebug testDebugUnitTest` green (28 tests: WAV header, layout
  geometry incl. fit-mode, manifest JSON, noise reducer, LUT .cube 1D/3D,
  pause detector, subtitles grouping/SRT, OTIO export).
- No `FATAL EXCEPTION` in logcat after: launch → REC → background → reopen →
  stop (run this cycle after touching camera/GL/engine).
- Verify on device over `adb connect <ip>:5555` (fixed port, set once with
  `adb tcpip 5555` over USB — wireless debug ports rotate otherwise).
- Deliver the APK to `/mnt/agrilloit/TuReg/` (user's shared folder).
