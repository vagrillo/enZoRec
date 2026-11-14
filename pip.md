# PiP.md — Modalità "Screen display" (Picture-in-Picture)

Questa modalità ti permette di avere **YouRec ridotto a una finestra flottante**
(camera frontale attiva) mentre il resto dello schermo mostra il **launcher o
qualunque altra app** (Chrome, YouTube, il tuo sito di timing…), registrando
**tutto contemporaneamente**.

## 1. Attivare la modalità (una configurazione, poi resta memorizzata)

1. In YouRec, tocca il pulsante **⚙️** (in alto a destra)
2. Attiva lo switch **"Screen display"**
   (è la voce con descrizione *"Registra anche schermo e audio delle altre app"*)
3. Chiudi con **Done**

## 2. Entrare in modalità Screen display

1. Torna alla schermata **Record**
2. Nella barra dei controlli (quella con ⇄ e ⊞) tocca l'ultima icona:
   **⛉ Screen display**
3. YouRec si **riduce a finestra flottante** (Picture-in-Picture) e il **launcher**
   torna a schermo pieno dietro
4. **Trascina la finestra** dove preferisci (in alto a destra è la posizione
   consigliata: fuori dai piedi, sempre visibile)
5. Puoi ingrandire/rimpicciolire la finestra **pizzicando** con due dita

Sopra la finestra compare il badge **"Screen display · split"**: significa che
stai registrando schermo + camera + audio.

## 3. Registrare (4 flussi simultanei)

1. Dalla finestra flottante, tocca il **cerchio rosso** (REC)
2. Android chiede *"Avviare la registrazione dello schermo?"* → conferma con
   **Avvia ora / Ricarica ora** (una conferma per ogni take, è una regola di
   sistema di Android 14+)
3. Da questo momento YouRec cattura **4 flussi insieme**:

| File | Cosa contiene |
|---|---|
| `composito.mp4` | La **camera frontale** (video H.264) |
| `schermo.mp4` | **Lo schermo intero**: il launcher, Chrome, YouTube, i Recenti… |
| `app_audio.wav` | **L'audio delle altre app**: il suono del video che guardi in split (48 kHz mono) |
| `mic_0.wav`, `mic_1.wav` | I **microfoni** separati (48 kHz mono) |
| `session.json` | Il manifest con i timestamp di ogni flusso (sezione `"screenDisplay"`) |

4. Per fermare: tocca di nuovo il **quadrato rosso** (Stop)

## 4. Uscire dalla modalità Screen display

- Nella finestra flottante tocca il testo rosso **"Schermo ✕"** (nel badge in alto)
- YouRec si **espande a schermo intero**, ripristina la configurazione normale
  (dual camera automatica) e lo split viene chiuso

## Controlli disponibili dentro la finestra flottante

- **Shutter rosso** — avvia/ferma la registrazione
- **+ Nuovo progetto** — crea un progetto nuovo per il prossimo take
- **"Frontale ⌄"** — cambia la camera usata dalla finestra
- **0.5x – 3.0x** — zoom
- **AF / MF** — autofocus o messa a fuoco manuale (con slider)
- **⇄** — scambia le camere (in questa modalità: frontale ↔ posteriore, da fermi)
- **⊞** — regola il layout dell'anteprima
- **Teleprompter** (≡, a sinistra) — testo scorrevole durante la ripresa

## Limiti e note

- La **conferma schermo** compare a ogni registrazione: è imposto da Android 14+
  per la privacy, non è disattivabile.
- **Audio delle app**: se guardi un video **DRM** (Netflix, Prime Video, Sky),
  l'audio non è catturabile per protezione dei contenuti — il file `app_audio.wav`
  risulterà vuoto. TuRace, YouTube (non-DRM) e i normali siti funzionano.
- In questa modalità YouRec registra **solo la frontale** per non sovraccaricare
  la CPU (il launcher e le altre app girano nello stesso momento).
- La finestra PiP, se la tocchi e premi **espandi**, riporta YouRec a schermo
  intero senza chiudere la registrazione.
- Se il telefono va in **riavvio**, riattiva il debug wireless o ricollega USB
  (vale per adb, non per l'uso normale dell'app).

## Problemi frequenti

| Problema | Soluzione |
|---|---|
| Il pulsante ⛉ non fa nulla | È la modalità PiP non supportata? Assicurati di aver installato l'ultima versione (v3.1.0+) |
| `app_audio.wav` vuoto | L'app in split blocca la cattura audio (DRM) o non sta riproducendo nulla |
| La registrazione si ferma uscendo dal PiP | Non accade: il servizio foreground continua finché non premi Stop |
| Non vedo la finestra flottante | Guarda nelle notifiche: "YouRec — Registrazione in corso" → tap per riaprirla |
