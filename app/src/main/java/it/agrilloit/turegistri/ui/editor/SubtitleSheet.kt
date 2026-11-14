package it.agrilloit.turegistri.ui.editor

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
import androidx.compose.runtime.LaunchedEffect
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
import it.agrilloit.turegistri.subtitles.SubtitleStyle
import it.agrilloit.turegistri.subtitles.SubtitlesEngine
import it.agrilloit.turegistri.ui.theme.BackgroundDark
import it.agrilloit.turegistri.ui.theme.GreenBadge
import it.agrilloit.turegistri.ui.theme.PrimaryAccent
import it.agrilloit.turegistri.ui.theme.SecondaryAccent
import it.agrilloit.turegistri.ui.theme.SurfaceDark
import it.agrilloit.turegistri.ui.theme.SurfaceElevated
import it.agrilloit.turegistri.ui.theme.TextSecondary
import java.io.File

/**
 * Editor sottotitoli (port SubtitleEditor iOS): gestione modelli ASR
 * (download/remove), trascrizione della timeline, lista cue editabile,
 * stile (4 preset × colori × dimensione), burn-in export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleSheet(
    vm: EditorViewModel,
    onDismiss: () -> Unit,
) {
    val cues by vm.subtitleCues.collectAsState()
    val style by vm.subtitleStyle.collectAsState()
    val lang by vm.subtitleLang.collectAsState()
    val models by vm.subtitleModels.collectAsState()
    val downloading by vm.subtitleDownloading.collectAsState()
    val transcribing by vm.subtitleTranscribing.collectAsState()
    val error by vm.subtitleError.collectAsState()

    LaunchedEffect(Unit) { vm.refreshSubtitleModels() }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SurfaceDark, tonalElevation = 0.dp) {
        Column(
            Modifier
                .padding(horizontal = 18.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Subtitles", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "Done", color = PrimaryAccent, fontSize = 15.sp,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp),
                )
            }
            Text(
                "ASR on-device (vosk, offline): modelli ~40 MB scaricati una volta. " +
                    "I tempi sono stime dalla trascrizione; le cue sono editabili.",
                color = TextSecondary, fontSize = 11.sp,
            )

            // --- Modelli ---
            Spacer(Modifier.height(12.dp))
            Text("Modello ASR", color = Color.White, fontSize = 13.sp)
            SubtitlesEngine.LANGUAGES.forEach { lm ->
                val downloaded = lm.lang in models
                val selected = lang == lm.lang
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) GreenBadge.copy(alpha = 0.12f) else BackgroundDark)
                        .clickable { vm.subtitleLang.value = lm.lang }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        lm.label + if (downloaded) " ✓" else "",
                        color = if (selected) Color.White else TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (downloading != null) {
                        Text(
                            "download ${( (downloading!! * 100).toInt() )}%",
                            color = TextSecondary, fontSize = 10.sp,
                        )
                    } else if (downloaded) {
                        Text(
                            "Rimuovi",
                            color = PrimaryAccent,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { vm.deleteSubtitleModel(lm.lang) }
                                .padding(6.dp),
                        )
                    } else {
                        Text(
                            "Scarica",
                            color = SecondaryAccent,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { vm.downloadSubtitleModel(lm.lang) }
                                .padding(6.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(SecondaryAccent)
                        .clickable(enabled = transcribing == null) { vm.transcribeSubtitles() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            transcribing != null -> "Trascrizione ${( (transcribing!! * 100).toInt() )}%"
                            else -> "🎙 Trascrivi timeline"
                        },
                        color = Color.White, fontSize = 13.sp,
                    )
                }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(SurfaceElevated)
                        .clickable(enabled = cues.isNotEmpty()) { vm.clearSubtitles() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Rimuovi tutto", color = TextSecondary, fontSize = 13.sp) }
            }
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(error!!, color = PrimaryAccent, fontSize = 11.sp)
            }

            // --- Stile ---
            Spacer(Modifier.height(14.dp))
            Text("Stile", color = Color.White, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("clean" to "Clean", "bold" to "Bold", "box" to "Box", "highlight" to "Highlight").forEach { (id, label) ->
                    val sel = style.preset == id
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (sel) SecondaryAccent.copy(alpha = 0.3f) else SurfaceElevated)
                            .clickable { vm.subtitleStyle.value = style.copy(preset = id); vm.saveEditJson() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) { Text(label, color = if (sel) Color.White else TextSecondary, fontSize = 10.sp) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    0xFFFFFFFF.toInt() to "Bianco", 0xFFFFD60A.toInt() to "Giallo",
                    0xFF64D2FF.toInt() to "Azzurro", 0xFF000000.toInt() to "Nero",
                ).forEach { (c, label) ->
                    val sel = style.color == c
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (sel) SecondaryAccent.copy(alpha = 0.3f) else SurfaceElevated)
                            .clickable { vm.subtitleStyle.value = style.copy(color = c); vm.saveEditJson() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) { Text(label, color = if (sel) Color.White else TextSecondary, fontSize = 10.sp) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dimensione", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(88.dp))
                Slider(
                    value = style.fontSizeSp,
                    onValueChange = { vm.subtitleStyle.value = style.copy(fontSizeSp = it) },
                    onValueChangeFinished = { vm.saveEditJson() },
                    valueRange = 14f..48f, modifier = Modifier.weight(1f),
                )
                Text("${style.fontSizeSp.toInt()}", color = TextSecondary, fontSize = 10.sp, modifier = Modifier.width(30.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Posizione Y", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(88.dp))
                Slider(
                    value = style.posY,
                    onValueChange = { vm.subtitleStyle.value = style.copy(posY = it) },
                    onValueChangeFinished = { vm.saveEditJson() },
                    valueRange = 0.1f..0.95f, modifier = Modifier.weight(1f),
                )
                Text("%.2f".format(style.posY), color = TextSecondary, fontSize = 10.sp, modifier = Modifier.width(30.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Burn-in export", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (style.burnIn) GreenBadge.copy(alpha = 0.25f) else SurfaceElevated)
                        .clickable { vm.subtitleStyle.value = style.copy(burnIn = !style.burnIn); vm.saveEditJson() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (style.burnIn) "ON" else "OFF",
                        color = if (style.burnIn) GreenBadge else TextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }

            // --- Cue ---
            Spacer(Modifier.height(14.dp))
            Text("Trascrizione (${cues.size} frasi)", color = Color.White, fontSize = 13.sp)
            cues.forEach { c ->
                if (editingId == c.id) {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                        minLines = 1,
                        maxLines = 3,
                        trailingIcon = {
                            Text(
                                "OK",
                                color = SecondaryAccent,
                                modifier = Modifier.clickable {
                                    vm.updateCueText(c.id, editText)
                                    editingId = null
                                }.padding(8.dp),
                            )
                        },
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (c.enabled) BackgroundDark else SurfaceElevated)
                            .clickable {
                                editingId = c.id
                                editText = c.text
                            }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        Text(
                            "${c.startMs / 1000}s",
                            color = TextSecondary, fontSize = 10.sp, modifier = Modifier.width(44.dp),
                        )
                        Text(
                            c.text,
                            color = if (c.enabled) Color.White else TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (c.enabled) "✓" else "—",
                            color = if (c.enabled) GreenBadge else TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { vm.toggleCue(c.id) }.padding(4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
