package it.agrilloit.turegistri.ui.record

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import it.agrilloit.turegistri.gl.LiveEffectCatalog
import it.agrilloit.turegistri.ui.theme.BackgroundDark
import it.agrilloit.turegistri.ui.theme.GreenBadge
import it.agrilloit.turegistri.ui.theme.PrimaryAccent
import it.agrilloit.turegistri.ui.theme.SurfaceDark
import it.agrilloit.turegistri.ui.theme.SurfaceElevated
import it.agrilloit.turegistri.ui.editor.SheetScaffold
import it.agrilloit.turegistri.ui.theme.TextSecondary

/**
 * Pannello effetti live (port LiveEffectsPanel iOS): le voci live-capable del
 * catalogo video applicate in anteprima e nel composito registrato via shader.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveEffectsSheet(
    activeId: String?,
    strength: Float,
    peaking: Boolean,
    onPick: (String, Float) -> Unit,
    onPeaking: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var strengthLocal by remember(activeId) { mutableStateOf(strength) }

    SheetScaffold(
        title = "Effetti live",
        onDismiss = onDismiss,
        doneColor = it.agrilloit.turegistri.ui.theme.CyanDone,
    ) {
            Text(
                "Applicati in anteprima e registrati nel composito (video effects live).",
                color = TextSecondary, fontSize = 12.sp,
            )

            Spacer(Modifier.height(12.dp))
            LiveEffectCatalog.ENTRIES.chunked(2).forEach { rowEntries ->
                Row {
                    rowEntries.forEach { e ->
                        val sel = activeId == e.id
                        Box(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 3.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (sel) GreenBadge.copy(alpha = 0.2f) else SurfaceElevated
                                )
                                .clickable {
                                    strengthLocal = 1f
                                    onPick(e.id, strengthLocal)
                                }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                e.label,
                                color = if (sel) GreenBadge else Color.White,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    if (rowEntries.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
            }

            if (activeId != null && activeId != "none") {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Intensità", color = Color.White, fontSize = 12.sp)
                    Spacer(Modifier.padding(horizontal = 8.dp))
                    Slider(
                        value = strengthLocal,
                        onValueChange = {
                            strengthLocal = it
                            onPick(activeId, it)
                        },
                        valueRange = 0.2f..1f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(strengthLocal * 100).toInt()}%",
                        color = TextSecondary, fontSize = 11.sp,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Focus peaking", color = Color.White, fontSize = 13.sp)
                    Text("Bordi a fuoco evidenziati in rosso", color = TextSecondary, fontSize = 11.sp)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (peaking) GreenBadge.copy(alpha = 0.25f) else SurfaceElevated)
                        .clickable { onPeaking(!peaking) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (peaking) "ON" else "OFF",
                        color = if (peaking) GreenBadge else TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }