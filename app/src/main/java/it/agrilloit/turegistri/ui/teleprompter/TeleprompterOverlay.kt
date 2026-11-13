package it.agrilloit.turegistri.ui.teleprompter

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Teleprompter: overlay di testo scorrevole con velocità regolabile.
 * Sorgenti: testo incollato, file .txt (picker), URL testuale.
 * Gestura: tap = play/pausa, drag verticale = scroll manuale, slider = velocità.
 */
object TeleprompterStore {

    private const val PREFS = "teleprompter"

    /** Salva il testo e le impostazioni: il testo sopravvive a chiusura app. */
    fun save(context: Context, text: String, speed: Float, fontSize: Int, bgAlpha: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("text", text)
            .putFloat("speed", speed)
            .putInt("font_size", fontSize)
            .putFloat("bg_alpha", bgAlpha)
            .apply()
    }

    fun loadText(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("text", "") ?: ""

    fun loadSpeed(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("speed", 1f)

    fun loadFontSize(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("font_size", 28)

    fun loadBgAlpha(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("bg_alpha", 0.35f)

    /** Contenuto della clipboard (se testuale). */
    fun clipboardText(context: Context): String? = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Carica testo da un file scelto col picker. */
    fun readTextFromUri(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    /** Scarica testo da un URL (http/https, testo semplice). */
    fun fetchUrl(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "text/plain,text/*,*/*")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        text
    }.getOrNull()
}

/**
 * Overlay teleprompter: richiamabile sopra il viewport camera.
 * Larghezza/posizione pensate per la lettura a distanza (testo grande, centrato).
 */
@Composable
fun TeleprompterOverlay(
    initialText: String,
    onDismiss: () -> Unit,
    onHide: () -> Unit,
    hidden: Boolean,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(initialText.ifBlank { TeleprompterStore.loadText(context) }) }
    var editMode by remember { mutableStateOf(text.isBlank()) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(TeleprompterStore.loadSpeed(context)) }
    var fontSize by remember { mutableStateOf(TeleprompterStore.loadFontSize(context)) }
    var bgAlpha by remember { mutableStateOf(TeleprompterStore.loadBgAlpha(context)) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var showSourceMenu by remember { mutableStateOf(text.isBlank()) }
    var pasteMode by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var loadingUrl by remember { mutableStateOf(false) }
    var pasteFromClipboard by remember { mutableStateOf(false) }

    // Persistenza automatica di testo e impostazioni.
    LaunchedEffect(text, speed, fontSize, bgAlpha) {
        TeleprompterStore.save(context, text, speed, fontSize, bgAlpha)
    }

    // Nascosto: non renderizza nulla (la UI ospite mostra la pillola riapri).
    if (hidden) return

    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            TeleprompterStore.readTextFromUri(context, it)?.let { t ->
                text = t
                editMode = false
            }
        }
    }

    // Scrolling automatico
    LaunchedEffect(playing, speed, text) {
        if (!playing || text.isBlank()) return@LaunchedEffect
        while (isActive && playing) {
            listState.scrollBy(speed)
            // Stop automatico alla fine del documento.
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            if (last != null && last.index == info.totalItemsCount - 1 &&
                last.offset + last.size <= info.viewportEndOffset
            ) {
                playing = false
                break
            }
            kotlinx.coroutines.delay(16)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = (0.15f + bgAlpha).coerceIn(0.15f, 0.95f)))
            // Consuma TUTTI i tocchi: nessun pulsante sottostante deve rispondere
            // mentre il teleprompter è aperto.
            .clickable(indication = null, interactionSource = remember {
                androidx.compose.foundation.interaction.MutableInteractionSource()
            }) { }
            .pointerInput(Unit) {
                detectTapGestures {
                    if (!editMode) playing = !playing
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    if (!editMode) {
                        change.consume()
                        scope.launch { listState.scrollBy(-dragAmount.y) }
                    }
                }
            },
    ) {
        // Colonna testo
        if (editMode) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Teleprompter", color = Color.White, fontSize = 17.sp)
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Chiudi",
                        tint = Color.White,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))

                if (pasteMode) {
                    androidx.compose.material3.OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        placeholder = { Text("Incolla o digita il testo…", color = Color(0xFF8E8E93)) },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color.White, fontSize = 14.sp,
                        ),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF007AFF),
                            unfocusedBorderColor = Color(0xFF2C2C2E),
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "USA QUESTO TESTO",
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF007AFF))
                            .clickable {
                                pasteMode = false
                                editMode = false
                                showSourceMenu = false
                            }
                            .padding(vertical = 12.dp)
                            .let { it },
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Annulla",
                        color = Color(0xFF8E8E93),
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { pasteMode = false }
                            .padding(vertical = 8.dp),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        "Scegli la sorgente del testo",
                        color = Color(0xFF8E8E93),
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    // Clipboard: proposta solo se contiene testo.
                    val clip = remember { TeleprompterStore.clipboardText(context) }
                    if (clip != null) {
                        SourceButton("Usa la clipboard: \"${clip.take(32)}…\"") {
                            text = clip
                            editMode = false
                            showSourceMenu = false
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    SourceButton("Incolla o digita testo") { pasteMode = true }
                    Spacer(Modifier.height(8.dp))
                    SourceButton("Apri un file .txt") {
                        filePicker.launch(arrayOf("text/plain", "text/*"))
                    }
                    Spacer(Modifier.height(8.dp))
                    SourceButton("Carica da URL") {
                        urlInput = " "
                        loadingUrl = false
                    }

                    // Input URL inline
                    if (urlInput.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("https://… (file di testo)", color = Color(0xFF8E8E93)) },
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF007AFF),
                                unfocusedBorderColor = Color(0xFF2C2C2E),
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (loadingUrl) "Scarico…" else "CARICA",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (loadingUrl) Color(0xFF2C2C2E) else Color(0xFF007AFF))
                                .clickable {
                                    if (!loadingUrl) {
                                        loadingUrl = true
                                        scope.launch {
                                            val t = kotlinx.coroutines.withContext(
                                                kotlinx.coroutines.Dispatchers.IO
                                            ) {
                                                TeleprompterStore.fetchUrl(urlInput.trim())
                                            }
                                            loadingUrl = false
                                            if (t != null) {
                                                text = t
                                                editMode = false
                                            }
                                        }
                                    }
                                }
                                .padding(vertical = 12.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Spacer(Modifier.weight(1f))
                if (!pasteMode && text.isNotBlank()) {
                    Text(
                        "LEGGI QUESTO TESTO",
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF30D158))
                            .clickable {
                                editMode = false
                                showSourceMenu = false
                            }
                            .padding(vertical = 12.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            // Modalità lettura
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // (lo sfondo del teleprompter è gestito dall'ospite con bgAlpha)
                item { Spacer(Modifier.height(120.dp)) }
                item {
                    Text(
                        text,
                        color = Color.White,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.45).sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
                item { Spacer(Modifier.height(300.dp)) }
            }

            // Barra di controllo in basso
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
            ) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xCC16181D))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Pausa" else "Play",
                            tint = Color.White,
                            modifier = Modifier
                                .size(26.dp)
                                .clickable { playing = !playing },
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Vel", color = Color(0xFF8E8E93), fontSize = 12.sp)
                        Slider(
                            value = speed,
                            onValueChange = { speed = it },
                            valueRange = 0.2f..8f,
                            colors = SliderDefaults.colors(thumbColor = Color.White),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${(speed * 60).roundToInt()} px/s",
                            color = Color(0xFF8E8E93),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Aa", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Slider(
                            value = fontSize.toFloat(),
                            onValueChange = { fontSize = it.roundToInt() },
                            valueRange = 16f..56f,
                            colors = SliderDefaults.colors(thumbColor = Color.White),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Sf", color = Color(0xFF8E8E93), fontSize = 12.sp)
                        Slider(
                            value = bgAlpha,
                            onValueChange = { bgAlpha = it },
                            valueRange = 0f..0.85f,
                            colors = SliderDefaults.colors(thumbColor = Color.White),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.VisibilityOff,
                            contentDescription = "Nascondi (pausa)",
                            tint = Color.White,
                            modifier = Modifier
                                .size(22.dp)
                                .clickable {
                                    playing = false
                                    onHide()
                                },
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Cambia testo",
                            tint = Color.White,
                            modifier = Modifier
                                .size(22.dp)
                                .clickable { editMode = true },
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Chiudi teleprompter",
                            tint = Color.White,
                            modifier = Modifier
                                .size(22.dp)
                                .clickable(onClick = onDismiss),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (playing) "In lettura · tap per pausa" else "Tap per leggere · trascina per scorrere",
                    color = Color(0xFF8E8E93),
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun SourceButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF22252D))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
            .padding(horizontal = 14.dp),
    )
}
