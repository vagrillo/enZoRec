# Screen display mode — Picture-in-Picture recording (step-by-step guide)

This mode shrinks YouRec to a small floating window (~20% of the screen, draggable
anywhere) and lets you run **any other app below** — the browser, YouTube, a live
timing page — while YouRec records **four simultaneous streams**.

Typical use: film yourself with the front camera while reading a live page or a
video below, capturing everything for post-production.

## 1. Enable the mode (one-time configuration)

1. In YouRec tap the **⚙️** button (top-right of the Record screen)
2. Toggle **"Screen display"**
   (described as *"also records the screen and other apps' audio"*)
3. Tap **Done** — the setting is remembered

## 2. Enter the mode

1. Go back to the **Record** screen
2. In the control bar (the one with ⇄ and ⊞) tap the last icon: **Screen
   display** (picture-in-picture icon)
3. Android asks *"Start screen recording?"* → confirm with **Start now**
   (one confirmation per recording session, required by Android 14+)
4. YouRec shrinks to a **floating window** and the launcher fills the screen behind
5. **Drag the window** anywhere (top corners are recommended); **pinch** with two
   fingers to resize it

The badge **"Screen display · split"** above the window means screen + camera +
audio capture is armed.

## 3. Record — 4 simultaneous streams

Tap the **red circle** (REC) inside the floating window:

| File | Stream |
|---|---|
| `composito.mp4` | Front camera video (H.264) |
| `schermo.mp4` | The **whole screen**, via MediaProjection |
| `app_audio.wav` | **Other apps' audio** (e.g. a YouTube video in split screen), 48 kHz mono |
| `mic_0.wav`, `mic_1.wav` | Your **microphones**, 48 kHz mono |
| `session.json` | Per-stream start timestamps and metadata |

Stop with the **square** (the same button turns into a stop square while recording).

## 4. Exit the mode

Tap the **expand button** (⟐, next to the REC button): YouRec expands back to
full screen and returns to the normal camera configuration. You can also tap the
floating window once and use the system expand control.

## Controls inside the floating window

- **REC / Stop** — the only buttons shown, to keep the window clean
- Tap the **window itself** once → Android shows the system PiP controls
  (expand / close)
- While expanded to full screen you get all the other controls (layout, zoom,
  focus, flash, teleprompter…) — press ⛉ again to go back into the floating window

## Notes and limitations

- The **screen-capture confirmation** appears per session: imposed by Android 14+
  for privacy, it cannot be disabled.
- **App audio**: DRM-protected players (Netflix, Prime Video, Sky) block capture —
  `app_audio.wav` stays empty. Regular web players and non-DRM video work fine.
- In this mode YouRec records the **front camera only** to keep the CPU load low
  (the other app runs live in the other half).
- Recording keeps running if you move the floating window or switch the app below.
- The camera needs a moment to reopen when entering the mode: the first REC press
  automatically **waits up to 6 seconds** for the cameras to become ready.

## Troubleshooting

| Problem | Solution |
|---|---|
| Nothing happens when pressing REC | The cameras are still reopening — wait a couple of seconds and press again (the app now waits for you) |
| `app_audio.wav` empty | The other app blocks audio capture (DRM) or nothing was playing |
| The floating window disappeared | Check notifications: "YouRec — Recording in progress" → tap to reopen |
| Split screen doesn't trigger | This build uses Picture-in-Picture (public API, works everywhere); the accessibility-based split remains as a fallback |
