package it.agrilloit.turegistri.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.agrilloit.turegistri.model.TitleDesign
import it.agrilloit.turegistri.ui.theme.BackgroundDark
import it.agrilloit.turegistri.ui.theme.GreenBadge
import it.agrilloit.turegistri.ui.theme.PrimaryAccent
import it.agrilloit.turegistri.ui.theme.SecondaryAccent
import it.agrilloit.turegistri.ui.theme.SurfaceDark
import it.agrilloit.turegistri.ui.theme.SurfaceElevated
import it.agrilloit.turegistri.ui.theme.TextSecondary

private val TITLE_PRESETS = listOf(
    "clean", "filmTitle", "lowerThird", "headline",
    "fieldNote", "dateline", "warmEditorial", "sunsetMono",
)

private val TITLE_FONTS = listOf(
    "system" to "System", "modern" to "Modern", "classic" to "Classic",
    "mono" to "Mono", "narrow" to "Narrow", "script" to "Script", "black" to "Black",
)

private val TITLE_COLORS = listOf(
    0xFFFFFFFF.toInt() to "Bianco",
    0xFFFFD60A.toInt() to "Giallo",
    0xFFFF8A80.toInt() to "Corallo",
    0xFF64D2FF.toInt() to "Azzurro",
    0xFF30D158.toInt() to "Verde",
    0xFF000000.toInt() to "Nero",
)

private fun presetLabel(p: String) = when (p) {
    "filmTitle" -> "Film title"
    "lowerThird" -> "Lower 3rd"
    "headline" -> "Headline"
    "fieldNote" -> "Field note"
    "dateline" -> "Dateline"
    "warmEditorial" -> "Editorial"
    "sunsetMono" -> "Sunset"
    else -> "Clean"
}

/**
 * Title designer (port iOS): testo, preset look, font, colori, plate, motion,
 * durata dal playhead. Tab "Immagine" per l'overlay foto/logo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleSheet(
    vm: EditorViewModel,
    playheadMs: Long,
    onDismiss: () -> Unit,
) {
    val title by vm.titleDesign.collectAsState()
    val imageOverlay by vm.imageOverlay.collectAsState()
    var tab by remember { mutableStateOf(0) }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.importOverlayImage(uri) { path ->
                if (path != null) {
                    vm.setImageOverlay(
                        ImageOverlayDesign(
                            path = path,
                            startMs = playheadMs,
                            endMs = playheadMs + 3000,
                        )
                    )
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceDark, tonalElevation = 0.dp) {
        Column(
            Modifier
                .padding(horizontal = 18.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Titles & overlay", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "Done", color = PrimaryAccent, fontSize = 15.sp,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Titolo", "Immagine").forEachIndexed { i, label ->
                    val sel = tab == i
                    Box(
                        Modifier.clip(RoundedCornerShape(9.dp))
                            .background(if (sel) PrimaryAccent.copy(alpha = 0.3f) else SurfaceElevated)
                            .clickable { tab = i }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) { Text(label, color = if (sel) Color.White else TextSecondary, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (tab == 0) {
                val d = title ?: TitleDesign(
                    text = "", startMs = playheadMs, endMs = playheadMs + 3000,
                )
                var text by remember(d.text) { mutableStateOf(d.text) }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Testo del titolo…", color = TextSecondary, fontSize = 13.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                    minLines = 1,
                    maxLines = 3,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.clip(RoundedCornerShape(9.dp)).background(SecondaryAccent)
                            .clickable {
                                vm.setTitle(
                                    d.copy(text = text, startMs = playheadMs, endMs = playheadMs + 3000)
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { Text("Applica dal playhead (3s)", color = Color.White, fontSize = 11.sp) }
                    if (title != null) {
                        Box(
                            Modifier.clip(RoundedCornerShape(9.dp)).background(SurfaceElevated)
                                .clickable { vm.setTitle(null) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) { Text("Rimuovi", color = TextSecondary, fontSize = 11.sp) }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Preset look", color = Color.White, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TITLE_PRESETS.take(4).forEach { p ->
                        val sel = d.preset == p
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (sel) PrimaryAccent.copy(alpha = 0.3f) else SurfaceElevated)
                                .clickable { vm.setTitle(d.copy(text = text).withPreset(p)) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text(presetLabel(p), color = if (sel) Color.White else TextSecondary, fontSize = 10.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TITLE_PRESETS.drop(4).forEach { p ->
                        val sel = d.preset == p
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (sel) PrimaryAccent.copy(alpha = 0.3f) else SurfaceElevated)
                                .clickable { vm.setTitle(d.copy(text = text).withPreset(p)) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text(presetLabel(p), color = if (sel) Color.White else TextSecondary, fontSize = 10.sp)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("Font", color = Color.White, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TITLE_FONTS.forEach { (id, label) ->
                        val sel = d.fontId == id
                        Box(
                            Modifier.clip(RoundedCornerShape(7.dp))
                                .background(if (sel) SecondaryAccent.copy(alpha = 0.3f) else SurfaceElevated)
                                .clickable { vm.setTitle(d.copy(text = text, fontId = id)) }
                                .padding(horizontal = 7.dp, vertical = 5.dp),
                        ) { Text(label, color = if (sel) Color.White else TextSecondary, fontSize = 10.sp) }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Colore", color = Color.White, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TITLE_COLORS.forEach { (c, label) ->
                        val sel = d.colorStart == c
                        Box(
                            Modifier.size(26.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(androidx.compose.ui.graphics.Color(c))
                                .clickable { vm.setTitle(d.copy(text = text, colorStart = c, gradient = false)) }
                                .padding(2.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Gradiente",
                        color = if (d.gradient) GreenBadge else TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable {
                            vm.setTitle(
                                d.copy(
                                    text = text, gradient = !d.gradient,
                                    colorStart = if (d.colorStart == 0xFFFFFFFF.toInt()) 0xFFFFD9A0.toInt() else d.colorStart,
                                    colorEnd = 0xFFFF8A65.toInt(),
                                )
                            )
                        }
                            .padding(vertical = 6.dp),
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Dimensione", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(88.dp))
                    Slider(
                        value = d.fontSizeSp, onValueChange = { vm.setTitle(d.copy(text = text, fontSizeSp = it)) },
                        valueRange = 14f..72f, modifier = Modifier.weight(1f),
                    )
                    Text("${d.fontSizeSp.toInt()}sp", color = TextSecondary, fontSize = 10.sp, modifier = Modifier.width(36.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Outline", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(88.dp))
                    Slider(
                        value = d.outlineWidthPx, onValueChange = { vm.setTitle(d.copy(text = text, outlineWidthPx = it)) },
                        valueRange = 0f..6f, modifier = Modifier.weight(1f),
                    )
                    Text("%.1f".format(d.outlineWidthPx), color = TextSecondary, fontSize = 10.sp, modifier = Modifier.width(36.dp))
                }

                Spacer(Modifier.height(6.dp))
                Text("Plate", color = Color.White, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("none" to "Nessuno", "block" to "Blocco", "perLine" to "Per riga").forEach { (id, label) ->
                        val sel = d.plate == id
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (sel) SecondaryAccent.copy(alpha = 0.3f) else SurfaceElevated)
                                .clickable { vm.setTitle(d.copy(text = text, plate = id)) }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                        ) { Text(label, color = if (sel) Color.White else TextSecondary, fontSize = 10.sp) }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text("Motion", color = Color.White, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("none" to "Nessuno", "fade" to "Fade", "drift" to "Drift", "scale" to "Scale").forEach { (id, label) ->
                        val sel = d.motion == id
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (sel) SecondaryAccent.copy(alpha = 0.3f) else SurfaceElevated)
                                .clickable { vm.setTitle(d.copy(text = text, motion = id)) }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                        ) { Text(label, color = if (sel) Color.White else TextSecondary, fontSize = 10.sp) }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text("Posizione", color = Color.White, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        0.5f to 0.12f to "Alto", 0.5f to 0.5f to "Centro", 0.5f to 0.86f to "Basso",
                        0.5f to 0.72f to "Lower 3rd",
                    ).forEach { (pos, label) ->
                        val sel = abs(d.posX - pos.first) < 0.01f && abs(d.posY - pos.second) < 0.01f
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (sel) SecondaryAccent.copy(alpha = 0.3f) else SurfaceElevated)
                                .clickable { vm.setTitle(d.copy(text = text, posX = pos.first, posY = pos.second)) }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                        ) { Text(label, color = if (sel) Color.White else TextSecondary, fontSize = 10.sp) }
                    }
                }
            } else {
                // ---- Tab immagine ----
                if (imageOverlay != null) {
                    Text(
                        "Overlay immagine attivo: ${imageOverlay!!.path.substringAfterLast('/')}",
                        color = Color.White, fontSize = 12.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Scala", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                        Slider(
                            value = imageOverlay!!.scale,
                            onValueChange = { vm.setImageOverlay(imageOverlay!!.copy(scale = it)) },
                            valueRange = 0.05f..0.9f, modifier = Modifier.weight(1f),
                        )
                        Text("%.2f".format(imageOverlay!!.scale), color = TextSecondary, fontSize = 10.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Durata", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                        Slider(
                            value = (imageOverlay!!.endMs - imageOverlay!!.startMs).toFloat(),
                            onValueChange = {
                                vm.setImageOverlay(
                                    imageOverlay!!.copy(endMs = imageOverlay!!.startMs + it.toLong())
                                )
                            },
                            valueRange = 500f..30000f, modifier = Modifier.weight(1f),
                        )
                        Text("%.1fs".format((imageOverlay!!.endMs - imageOverlay!!.startMs) / 1000f), color = TextSecondary, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(9.dp)).background(SurfaceElevated)
                            .clickable { vm.setImageOverlay(null) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { Text("Rimuovi overlay", color = TextSecondary, fontSize = 11.sp) }
                } else {
                    Text(
                        "Scegli un'immagine (PNG trasparente per logo/watermark): appare " +
                            "dal playhead per 3 s.",
                        color = TextSecondary, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SecondaryAccent)
                            .clickable { imageLauncher.launch(arrayOf("image/*")) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Scegli immagine", color = Color.White, fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun abs(f: Float): Float = kotlin.math.abs(f)
