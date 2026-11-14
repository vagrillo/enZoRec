package it.agrilloit.turegistri.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.agrilloit.turegistri.ui.theme.BackgroundDark
import it.agrilloit.turegistri.ui.theme.GreenBadge
import it.agrilloit.turegistri.ui.theme.PrimaryAccent
import it.agrilloit.turegistri.ui.theme.SecondaryAccent
import it.agrilloit.turegistri.ui.theme.SurfaceDark
import it.agrilloit.turegistri.ui.theme.SurfaceElevated
import it.agrilloit.turegistri.ui.theme.TextSecondary
import it.agrilloit.turegistri.vibe.VibeSkill

/**
 * Pannello "Vibe Director": regista automatico ispirato a TuDirector.
 * Scegli la vibe (skill), avvia l'analisi e rivedi le decisioni di regia —
 * ogni decisione è disattivabile e va a modificare il montaggio (tagli,
 * colore) e gli effetti all'export (zoom). Nessun rendering qui: lo fa l'export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VibeDirectorSheet(
    vm: EditorViewModel,
    onDismiss: () -> Unit,
) {
    val decisions by vm.vibeDecisions.collectAsState()
    val running by vm.vibeRunning.collectAsState()
    val skill by vm.vibeSkill.collectAsState()
    val prompt by vm.vibePrompt.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Vibe Director", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "Done",
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp),
                )
            }
            Text(
                "Regia automatica sul montaggio: tagli, zoom, colore. " +
                    "Il rendering resta all'export YouRec.",
                color = TextSecondary,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(14.dp))
            Text("Vibe", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VibeSkill.values().forEach { sk ->
                    val selected = skill == sk.id
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) PrimaryAccent.copy(alpha = 0.28f) else SurfaceElevated)
                            .clickable { vm.vibeSkill.value = sk.id }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        Text(
                            sk.label,
                            color = if (selected) Color.White else TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.OutlinedTextField(
                value = prompt,
                onValueChange = { vm.vibePrompt.value = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        "Nota di regia (opzionale): es. zoom sui momenti chiave…",
                        color = Color(0xFF8E8E93),
                        fontSize = 12.sp,
                    )
                },
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SecondaryAccent,
                    unfocusedBorderColor = Color(0xFF2C2C2E),
                ),
                minLines = 2,
            )

            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (running) SurfaceElevated else GreenBadge)
                    .clickable(enabled = !running) { vm.runVibeDirector() }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (running) "Analisi in corso…" else "🎬 Direggi il montaggio",
                    color = Color.White,
                    fontSize = 14.sp,
                )
            }

            Spacer(Modifier.height(14.dp))
            if (decisions.isNotEmpty()) {
                Text("Decisioni di regia", color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BackgroundDark)
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    decisions.forEach { d ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // toggle: ricrea la lista senza/sì la decisione
                                    val now = vm.vibeDecisions.value
                                    vm.vibeDecisions.value = now.map {
                                        if (it.id == d.id) it.copy(enabled = !it.enabled) else it
                                    }
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(if (d.enabled) GreenBadge else TextSecondary),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    typeLabel(d.type) + " " +
                                        "${d.startMs / 1000}s–${d.endMs / 1000}s",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                )
                                Text(d.why, color = TextSecondary, fontSize = 10.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "APPLICA AL MONTAGGIO",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SecondaryAccent)
                        .clickable {
                            vm.applyVibeDecisions(vm.vibeDecisions.value)
                            onDismiss()
                        }
                        .padding(vertical = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

private fun typeLabel(type: String): String = when (type) {
    "zoom" -> "🔍 Zoom"
    "color" -> "🎨 Colore"
    "cut" -> "✂️ Taglio"
    "camera_switch" -> "🎬 Switch camera"
    else -> type
}
