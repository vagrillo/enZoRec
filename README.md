# enZoRec

**enZoRec** (formerly YouRecPlay) is an Android app for **simultaneous multi-camera video recording** with separated audio tracks, live dual-camera layouts, a built-in **non-linear timeline editor**, ML-style audio noise reduction, a teleprompter, and MP4 export — all in one app. The project starts from an idea by **Salvatore Sanfilippo (Antirez)** and his **YouRec for iOS** (ref.: [https://github.com/antirez/YouRecApp](https://github.com/antirez/YouRecApp)), reimagined for Android. Licensed under the **MIT License** — the same license used by the original iOS project (see [LICENSE](LICENSE)).

Designed for mobile journalism, vlogging and live event coverage: record with the front and rear cameras at the same time (BeReal-style split screen, picture-in-picture), keep every audio source on its own track for post-production, then edit and export without ever leaving the app.

## Features

### Recording
- **Simultaneous dual-camera capture** (front + rear, or two rear lenses) using
  Camera2 concurrent streaming APIs. Automatically degrades to single camera if the
  device doesn't support concurrent streaming.
- **Live layouts**: vertical split, horizontal split, picture-in-picture
  (rectangular or circular inset, draggable, resizable), instant camera swap —
  even while recording.
- **Per-stream camera selection**: pick any available lens (ultrawide, main, tele,
  front) for each stream, with zoom presets (0.5x–6.0x), auto/manual focus and
  tap-to-focus.
- **Every audio source on its own track**: multichannel WAV when the hardware
  supports it, otherwise one WAV per built-in microphone (48 kHz, PCM 16-bit).
- **Flash modes**: real rear LED torch, or a front "softbox" made of the screen
  itself with 4 intensity levels (up to a full-white screen at 100% brightness
  with a proportional preview window).
- **Photos** at the camera's maximum JPEG resolution, saved to `Pictures/YouRec`.
- Foreground service: recording keeps running with the screen off or the app in
  background. Pause/resume and in-recording markers included.

### Screen display mode (Picture-in-Picture recording)
A special mode that shrinks YouRec to a small floating window (~20% of the screen)
and lets you run **any other app below** (browser, YouTube, a live timing page…)
while recording **four simultaneous streams**:

| File | Stream |
|---|---|
| `composito.mp4` | Front camera (H.264) |
| `schermo.mp4` | The whole screen via MediaProjection |
| `app_audio.wav` | Audio played by **other apps** (AudioPlaybackCapture) |
| `mic_0.wav`, `mic_1.wav`… | Microphones |
| `session.json` | Per-stream start timestamps and metadata |

See [PIP_GUIDE.md](PIP_GUIDE.md) for the step-by-step guide.

### Timeline editor
- Multi-track timeline with **video thumbnails** and **waveform rendering**
  (computed from the recorded WAV files).
- Free scrubbing with auto-scroll, **pinch-to-zoom** (14–320 px/s) with
  finger-anchored zoom, trim in/out, split at playhead, clip reorder, undo/redo
  (30 levels), snap-to-cuts.
- Playback preview powered by Media3/ExoPlayer with clipped playlists.
- **Noise reduction** (ML-style adaptive spectral gating via a custom FFT
  AudioProcessor) with Soft/Medium/Strong presets — applied at export time.
- **Export** with Media3 Transformer (H.264 + AAC), progress reporting, share
  via system sheet or save to the device gallery.

### Teleprompter
Floating overlay with auto-scrolling text: paste it, load a `.txt` file or fetch
it from a URL. Adjustable speed, font size, background opacity; tap to
play/pause, drag to scroll, hide-with-resume pill, text persists across sessions.

## Session files (post-production friendly)

Every take is saved in its own folder under
`Android/data/it.agrilloit.yourec/files/sessioni/<timestamp>/`:

```
cam0.mp4          rear camera, video only
cam1.mp4          front camera, video only
composito.mp4     the live layout (split/PiP) with microphone audio — share-ready
mic_0.wav         microphone 1 (48 kHz PCM 16-bit)
mic_1.wav         microphone 2
schermo.mp4       screen recording (Screen display mode only)
app_audio.wav     other apps' audio (Screen display mode only)
session.json      per-stream start timestamps, camera/lens info, orientation,
                  mic layout, resolution — everything you need to align and
                  relink tracks in your NLE
```

The multi-track WAVs keep channels sample-aligned, and `session.json` records
`elapsedRealtimeNanos` + epoch timestamps per stream, so all tracks can be
aligned frame-accurately in post-production.

## Requirements

- Android 11 (API 30) or newer
- A device whose camera HAL supports concurrent camera streaming for dual-camera
  recording (most 2020+ devices do); otherwise single-camera mode is used

## Building

```bash
git clone https://github.com/vagrillo/YouRecPlay.git
cd YouRecPlay
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug        # or: gradle assembleDebug
```

- Kotlin 2.0, Jetpack Compose (Material 3), Room, Media3 (ExoPlayer + Transformer)
- Camera2 + OpenGL ES 2 for the multi-camera composited preview
- Min SDK 30 (Android 11) · Target SDK 35 · Java 17
- Android Studio Ladybug or newer works out of the box

## Architecture overview

```
ui/                     Jetpack Compose screens (Record, Projects, Editor, Photos)
  record/               camera screen: overlays, flash, teleprompter entry point
  editor/               timeline canvas, ExoPlayer preview, export pipeline
  teleprompter/         auto-scrolling prompter overlay
  projects/             project dashboard
  photos/               photo gallery
camera/                 Camera2 concurrent streaming, lens catalog (zoom labels)
gl/                     OpenGL ES compositor (2 external textures → preview +
                        encoder surface, per-camera rotation/mirror matrix)
audio/                  MicEnumerator (3-tier capture strategy), WavWriter,
                        NoiseReducer (FFT spectral gating AudioProcessor)
record/                 RecordingEngine (orchestrates everything), VideoRecorder,
                        ScreenStreams (MediaProjection screen + app-audio capture)
data/                   Room database (projects/takes), repositories
service/                Foreground service (camera|microphone|mediaProjection)
```

The recording engine survives activity recreation and runs in a foreground
service, so recordings continue with the screen off or while other apps are used.

## Hardware limitations (by design, not fixable in software)

- Not every device exposes concurrent camera combinations: YouRec reads
  `CameraManager.concurrentCameraIds` and falls back to single-camera mode.
- In concurrent mode the guaranteed per-camera stream is 720p; 1080p/4K are
  best-effort (the app automatically retries at 720p).
- DRM-protected app audio (Netflix, Prime…) cannot be captured by
  AudioPlaybackCapture — the stream stays empty.
- Distinct microphone channels depend on the device HAL; the 3-tier capture
  strategy (multichannel mask → per-mic routing → default source) covers all cases.

## License

All rights reserved. You may inspect and fork for personal use; contact the
author for redistribution or commercial use.
