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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import it.agrilloit.turegistri.audio.OfflineAudioMixer
import it.agrilloit.turegistri.ui.theme.BackgroundDark
import it.agrilloit.turegistri.ui.theme.CyanAccent
import it.agrilloit.turegistri.ui.theme.GreenBadge
import it.agrilloit.turegistri.ui.theme.PrimaryAccent
import it.agrilloit.turegistri.ui.theme.SurfaceElevated
import it.agrilloit.turegistri.ui.theme.TextSecondary

private fun redDanger(): Color = PrimaryAccent

/** Pannello "Edit clip": volume, mute, fades, speed, mirror, color per clip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipEditorSheet(vm: EditorViewModel, onDismiss: () -> Unit) {
    val clips by vm.clips.collectAsState()
    val selection by vm.selection.collectAsState()
    val clip = clips.firstOrNull { it.id == selection }

    SheetScaffold(title = "Edit clip", onDismiss = onDismiss, doneColor = ToolTints.audio) {
        if (clip == null) {
            Text("Seleziona un clip nella timeline.", color = TextSecondary, fontSize = 12.sp)
            return@SheetScaffold
        }

        @Composable
        fun ParamSlider(
            label: String,
            value: Float,
            range: ClosedFloatingPointRange<Float>,
            fmt: String = "%.2f",
            onChange: (Float) -> Unit,
        ) {
            IosSlider(
                label = label,
                value = value,
                valueRange = range,
                tint = ToolTints.audio,
                valueText = fmt.format(value),
                onValueChange = onChange,
            )
        }

        ParamSlider("Volume", clip.volume, 0f..2f) { vm.updateClip(clip.id, volume = it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Muto", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = clip.muted,
                onCheckedChange = { vm.updateClip(clip.id, muted = it) },
                colors = SwitchDefaults.colors(checkedTrackColor = redDanger()),
            )
        }
        ParamSlider("Fade in (s)", clip.fadeInMs / 1000f, 0f..5f, "%.1f") {
            vm.updateClip(clip.id, fadeInMs = (it * 1000).toLong())
        }
        ParamSlider("Fade out (s)", clip.fadeOutMs / 1000f, 0f..5f, "%.1f") {
            vm.updateClip(clip.id, fadeOutMs = (it * 1000).toLong())
        }
        ParamSlider("Velocità", clip.speed, 0.25f..4f) { vm.updateClip(clip.id, speed = it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Specchia", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = clip.mirror,
                onCheckedChange = { vm.updateClip(clip.id, mirror = it) },
                colors = SwitchDefaults.colors(checkedTrackColor = CyanAccent),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("Color correction", color = Color.White, fontSize = 16.sp)
        ParamSlider("Esposizione (EV)", clip.exposure, -4f..4f, "%.1f") { vm.updateClip(clip.id, exposure = it) }
        ParamSlider("Contrasto", clip.contrast, 0.5f..2f) { vm.updateClip(clip.id, contrast = it) }
        ParamSlider("Saturazione", clip.saturation, 0f..2f) { vm.updateClip(clip.id, saturation = it) }
        ParamSlider("Calore", clip.warmth, -1f..1f) { vm.updateClip(clip.id, warmth = it) }
        Text(
            "Per ombre/luci/temperatura/tinta/vibrance/tonalità usa Video effects → Color Correction.",
            color = TextSecondary, fontSize = 10.sp,
        )
    }
}

/** Tool "Add audio": import da file, voiceover registrato al playhead, lista tracce. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAudioSheet(vm: EditorViewModel, onDismiss: () -> Unit) {
    val tracks by vm.audioTracks.collectAsState()
    val recording by vm.voiceoverRecording.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.addAudioTrack(it) }
    }

    SheetScaffold(title = "Add audio", onDismiss = onDismiss, doneColor = ToolTints.audio) {
        Text(
            "Le tracce si mixano nell'export alla posizione indicata " +
                "(musica di sottofondo, voiceover…).",
            color = TextSecondary, fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(ToolTints.audio)
                    .clickable { launcher.launch(arrayOf("audio/*", "application/octet-stream")) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Importa file", color = Color.White, fontSize = 13.sp) }
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(if (recording) PrimaryAccent else GreenBadge)
                    .clickable { if (recording) vm.stopVoiceover() else vm.startVoiceover() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (recording) "■ Stop voiceover" else "🎙 Registra voiceover",
                    color = Color.White, fontSize = 13.sp,
                )
            }
        }
        Text(
            "Il voiceover parte dal playhead corrente.",
            color = TextSecondary, fontSize = 10.sp,
        )
        Spacer(Modifier.height(14.dp))
        tracks.forEach { t ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BackgroundDark)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, color = Color.White, fontSize = 12.sp)
                        Text(
                            "a ${t.startOnTimelineMs / 1000}s" +
                                if (t.muted) " · muto" else "",
                            color = TextSecondary, fontSize = 10.sp,
                        )
                    }
                    Text(
                        "✕", color = TextSecondary, fontSize = 14.sp,
                        modifier = Modifier.clickable { vm.removeAudioTrack(t.id) }.padding(6.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(64.dp))
                    Slider(
                        value = t.volume,
                        onValueChange = { vm.updateAudioTrack(t.id, volume = it) },
                        valueRange = 0f..2f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = ToolTints.audio,
                            inactiveTrackColor = Color(0xFF2A2A2C),
                        ),
                    )
                    Text("%.2f".format(t.volume), color = TextSecondary, fontSize = 10.sp, modifier = Modifier.width(40.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Muto", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = t.muted,
                        onCheckedChange = { vm.updateAudioTrack(t.id, muted = it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = redDanger()),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** Tool "Pauses": rileva i silenzi e applica tagli reali (port PauseSettings iOS). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PausesSheet(vm: EditorViewModel, onDismiss: () -> Unit) {
    val candidates by vm.pauseCandidates.collectAsState()
    val clips by vm.clips.collectAsState()
    val selected = remember { mutableStateOf(setOf<String>()) }

    SheetScaffold(title = "Pauses", onDismiss = onDismiss, doneColor = ToolTints.pauses) {
        Text(
            "Rileva i silenzi sui waveform e taglia davvero i clip (audio+video). " +
                "Soglia automatica dal room tone, padding 120/150 ms.",
            color = TextSecondary, fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ToolTints.audio)
                .clickable { vm.detectPauses() }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Rileva pause", color = Color.White, fontSize = 13.sp) }

        Spacer(Modifier.height(12.dp))
        if (candidates.isEmpty()) {
            Text(
                "Nessuna rilevazione. Premi \"Rileva pause\".",
                color = TextSecondary, fontSize = 12.sp,
            )
        }
        candidates.forEach { (clipId, windows) ->
            val clip = clips.firstOrNull { it.id == clipId }
            Text(
                "Clip: ${clip?.path?.substringAfterLast('/') ?: clipId} · ${windows.size} pause",
                color = Color.White, fontSize = 12.sp,
            )
            windows.forEach { w ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Text(
                        "${w.startMs / 1000}s → ${w.endMs / 1000}s  " +
                            "(${(w.endMs - w.startMs) / 1000}s)",
                        color = TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f),
                    )
                    val on = clipId in selected.value
                    Text(
                        if (on) "TAGLIA" else "—",
                        color = if (on) GreenBadge else TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable {
                            selected.value =
                                if (on) selected.value - clipId else selected.value + clipId
                        }.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (candidates.isNotEmpty()) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(GreenBadge)
                    .clickable {
                        vm.applyPauseCuts(if (selected.value.isEmpty()) candidates.keys else selected.value)
                        onDismiss()
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (selected.value.isEmpty()) "Applica tutti i tagli" else "Applica tagli selezionati",
                    color = Color.White, fontSize = 13.sp,
                )
            }
        }
    }
}

/** Tool "Censor audio": beep generati all'export (port AudioCensor iOS). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CensorSheet(vm: EditorViewModel, onDismiss: () -> Unit) {
    val beeps by vm.censorBeeps.collectAsState()

    SheetScaffold(title = "Censor audio", onDismiss = onDismiss, doneColor = ToolTints.audio) {
        Text(
            "Posiziona il playhead, poi \"Aggiungi beep\": la finestra selezionata " +
                "viene sostituita da un beep (sine/tri/square) all'export.",
            color = TextSecondary, fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ToolTints.audio)
                .clickable {
                    val w = vm.censorWindowAtPlayhead(1500)
                    if (w != null) {
                        vm.censorBeeps.value = vm.censorBeeps.value + OfflineAudioMixer.CensorBeep(
                            startMs = w.first, endMs = w.second,
                        )
                        vm.saveEditJson()
                    }
                }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Aggiungi beep al playhead (±0.75s)", color = Color.White, fontSize = 13.sp) }
        Spacer(Modifier.height(12.dp))
        beeps.forEachIndexed { i, b ->
            var wave by remember(b) { mutableStateOf(b.wave.ordinal) }
            var freq by remember(b) { mutableStateOf(b.freqHz.toFloat()) }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BackgroundDark)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Beep ${i + 1}: ${b.startMs / 1000}s → ${b.endMs / 1000}s",
                        color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f),
                    )
                    Text(
                        "✕", color = TextSecondary, fontSize = 14.sp,
                        modifier = Modifier.clickable {
                            vm.censorBeeps.value = vm.censorBeeps.value.filterIndexed { j, _ -> j != i }
                            vm.saveEditJson()
                        }.padding(6.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Sine", "Tri", "Square").forEachIndexed { wi, label ->
                        val sel = wave == wi
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (sel) GreenBadge.copy(alpha = 0.25f) else SurfaceElevated)
                                .clickable {
                                    wave = wi
                                    vm.censorBeeps.value = vm.censorBeeps.value.mapIndexed { j, bb ->
                                        if (j == i) bb.copy(wave = OfflineAudioMixer.BeepWave.values()[wi]) else bb
                                    }
                                    vm.saveEditJson()
                                }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        ) { Text(label, color = if (sel) GreenBadge else TextSecondary, fontSize = 10.sp) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hz", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(40.dp))
                    Slider(
                        value = freq,
                        onValueChange = { freq = it },
                        onValueChangeFinished = {
                            vm.censorBeeps.value = vm.censorBeeps.value.mapIndexed { j, bb ->
                                if (j == i) bb.copy(freqHz = freq.toInt().coerceIn(100, 4000)) else bb
                            }
                            vm.saveEditJson()
                        },
                        valueRange = 100f..4000f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = ToolTints.audio,
                            inactiveTrackColor = Color(0xFF2A2A2C),
                        ),
                    )
                    Text("${freq.toInt()} Hz", color = TextSecondary, fontSize = 10.sp, modifier = Modifier.width(64.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
