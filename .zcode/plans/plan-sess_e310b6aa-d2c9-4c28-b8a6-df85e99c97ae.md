# TuRegistri — app Android per registrazione simultanea multi-camera + multi-mic

## Contesto
App (nuova, greenfield in `/home/ubuntu/src/AI/TuRegistri`) che registra **contemporaneamente le telecamere dello smartphone** (front + back, stile BeReal: split verticale/orizzontale, picture-in-picture) **e tutti i segnali microfonici separati**, salvando:
- un **video composito** con l'effetto già inciso (pronto da condividere), con audio dal microfono
- i **file separati per ogni camera** + **WAV separati/multicanale per ogni microfono** per la postproduzione
- un **manifest `session.json`** con i timestamp di inizio di ogni flusso per allinearli in post

Scelte confermate: output composito + singoli, minSdk **30 (Android 11+)**, compilazione qui con consegna APK, test manuale dell'utente. UI in italiano.

## Toolchain (già presente e testata nel progetto TuRace — riuso identico)
- SDK `/home/ubuntu/android-sdk` (platform android-35, build-tools 35.0.0) via `local.properties`
- Gradle 8.9 standalone `/home/ubuntu/gradle-8.9/bin/gradle` (cache `~/.gradle` già popolata)
- AGP **8.7.3**, Kotlin **2.0.20**, JDK 17, compileSdk 35, targetSdk 35, minSdk **30**

## Architettura
**Camera2 diretto (niente CameraX)**: CameraX non supporta doppio VideoCapture concorrente; Camera2 dà il controllo necessario.
- `ConcurrentCameraDetector`: usa `CameraManager.concurrentCameraIds` (API 30) per trovare le combinazioni realmente simultanee; sceglie preferibilmente **front+back**, altrimenti due posteriori (wide+ultrawide), altrimenti modalità single-camera con messaggio chiaro.
- Per ogni camera: 2 stream PRIV @ ≤720p (garanzia ufficiale in modalità concorrente: 2×s720p per camera): 1 `SurfaceTexture` per il preview/composito + 1 `MediaRecorder` per il file individuale. Default **720p** (garantito), opzione 1080p best-effort con fallback automatico.
- `RecordingService` (foreground, tipo `camera|microphone`) orchestra start/stop atomico di tutto.

**Composito OpenGL (`CompositeRenderer`)**: un contesto EGL con le 2 `SurfaceTexture` renderizza verso il surface del preview e verso `MediaRecorder.setInputSurface()` (videoSource SURFACE, H.264+AAC MP4 720×1280 @30fps).
- Layout: **split verticale** (sx/dx), **split orizzontale** (sopra/sotto), **picture-in-picture**, **single A/B**; divider/inserto trascinabili, center-crop per riempire, front camera specchiata (toggle).

**Audio (`MicRecorder` + `WavWriter`)**: 48 kHz PCM 16-bit → WAV.
1. Se un device mic espone N canali → 1 `AudioRecord` con `CHANNEL_INDEX_MASK` → 1 WAV multicanale (canali sample-allineati, ideale per post)
2. Altrimenti N mic distinti → 1 `AudioRecord` per mic con `setPreferredDevice` → N WAV mono
3. Fallback → 1 `AudioRecord` sorgente MIC
- Composito con audio sorgente MIC (toggle nelle impostazioni; fallback video-only se la cattura simultanea fallisce su quel device).

**Sessione/manifest**: cartella `TuRegistri/sessioni/20261031-153000/` con `cam0.mp4`, `cam1.mp4`, `composito.mp4`, `mic_0.wav…`, `session.json` (modello device, ID/lane/risoluzione/orientamento camere, info mic, elapsedRealtimeNanos + epoch di start di ogni file, layout, fps).

## Struttura progetto (nuovo, in `/home/ubuntu/src/AI/TuRegistri`)
```
settings.gradle.kts · build.gradle.kts · gradle.properties · local.properties
app/src/main/
  AndroidManifest.xml            (permessi CAMERA, RECORD_AUDIO, POST_NOTIFICATIONS, FGS camera|microphone)
  java/it/agrilloit/turegistri/
    MainActivity.kt              (preview GL fullscreen, REC, selettore layout, richieste permessi)
    SessionsActivity.kt          (elenco sessioni + condivisione file)
    camera/CameraController.kt · ConcurrentCameraDetector.kt
    gl/CompositeRenderer.kt · GlProgram.kt · LayoutEngine.kt
    record/RecordingEngine.kt · VideoRecorder.kt (per-camera) · CompositeRecorder.kt
    audio/MicEnumerator.kt · MicRecorder.kt · WavWriter.kt
    service/RecordingService.kt
    model/SessionManifest.kt
  res/ (layout, strings it, themes Material, icone)
```
Dipendenze minime: core-ktx, appcompat, material, constraintlayout, lifecycle-runtime-ktx.

## Flussi chiave
- **REC**: permessi → service foreground → apre camera in parallelo → configura sessioni+recorder → avvia mics, poi recorder camere e composito nello stesso istante → timer notifica.
- **STOP**: stop in ordine inverso, scrittura `session.json`, scan MediaStore, toast riepilogo.
- **Degrado**: 2ª camera non apre → single-camera avvisata; sessione fallisce a 1080p → retry 720p; mic non disponibili → registra comunque video.

## Build e consegna
- `gradle assembleDebug` (debug APK installabile, firmato con debug key auto-generata — TuRace non ha keystore release)
- Copia in **`/mnt/agrilloit/TuReg/`** come `TuRegistri-v1.0-debug.apk` (se la cartella non esiste la creo; se il mount manca, l'APK resta in `app/build/outputs/apk/debug/` e lo segnalo)
- L'utente testa manualmente su device.

## Verifica
- Build Gradle verde; unit test JUnit per `WavWriter` (header RIFF), `LayoutEngine` (matrici crop per i 3 layout) e generatore manifest.
- APK consegnato + checklist di test su device reale: (1) app apre entrambe le camera in split, (2) switch layout V/H/PiP, (3) REC produce 4 file + session.json, (4) WAV riproducibili e allineati, (5) composito condivisibile da WhatsApp.

## Limiti hardware noti (non superabili via codice)
- Non tutti i device permettono front+back simultanei: dove `concurrentCameraIds` è vuoto si degrada a single-camera con spiegazione.
- In modalità concorrente 4K non garantito: default 720p, 1080p best-effort.
- Mics fisicamente separati dipendono dall'HAL: la strategia a 3 livelli gestisce tutti i casi.