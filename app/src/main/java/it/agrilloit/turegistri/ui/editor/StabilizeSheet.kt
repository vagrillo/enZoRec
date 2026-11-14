package it.agrilloit.turegistri.ui.editor

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
import it.agrilloit.turegistri.model.Stabilizer
import it.agrilloit.turegistri.ui.theme.BackgroundDark
import it.agrilloit.turegistri.ui.theme.GreenBadge
import it.agrilloit.turegistri.ui.theme.PrimaryAccent
import it.agrilloit.turegistri.ui.theme.SecondaryAccent
import it.agrilloit.turegistri.ui.theme.SurfaceDark
import it.agrilloit.turegistri.ui.theme.SurfaceElevated
import it.agrilloit.turegistri.ui.theme.TextSecondary

/**
 * Tool "Stabilize" (port StabilizationEditor iOS): livelli Off/Light/Standard/
 * Strong, analisi con progresso, crop-factor readout, cache per clip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StabilizeSheet(
    vm: EditorViewModel,
    onDismiss: () -> Unit,
) {
    val clips by vm.clips.collectAsState()
    val selection by vm.selection.collectAsState()
    val clip = clips.firstOrNull { it.id == selection }
    val levels by vm.stabilizationLevels.collectAsState()
    val running by vm.stabilizationRunning.collectAsState()

    SheetScaffold(
        title = "Stabilize",
        onDismiss = onDismiss,
        doneColor = ToolTints.stabilize,
    ) {
            Text(
                "Analisi offline del movimento (tracker per-frame, correzione dx/dy/" +
                    "rotazione/scala attenuata) applicata all'export con crop dedicato.",
                color = TextSecondary, fontSize = 12.sp,
            )

            Spacer(Modifier.height(12.dp))
            if (clip == null) {
                Text("Seleziona un clip nella timeline.", color = TextSecondary, fontSize = 12.sp)
            } else {
                val current = levels[clip.id] ?: 0
                Text("Clip: ${clip.path.substringAfterLast('/')}", color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    Stabilizer.LEVEL_LABELS.forEachIndexed { i, label ->
                        val sel = current == i
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    if (sel) GreenBadge.copy(alpha = 0.25f) else SurfaceElevated
                                )
                                .clickable {
                                    vm.setStabilization(clip.id, i)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                label,
                                color = if (sel) GreenBadge else TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    running != null -> Text(
                        "Analisi… ${( (running!! * 100).toInt() )}% (frame 256px @ 8fps)",
                        color = TextSecondary, fontSize = 11.sp,
                    )

                    current > 0 -> {
                        val res = vm.stabilizationResult(clip.id)
                        Text(
                            if (res != null) "Pronto · crop zoom ×%.3f".format(res.cropZoom)
                            else "Analisi in coda o non ancora eseguita",
                            color = if (res != null) GreenBadge else TextSecondary,
                            fontSize = 11.sp,
                        )
                    }

                    else -> Text("Livello Off: nessuna correzione.", color = TextSecondary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "L'analisi viene eseguita subito dopo la scelta del livello e messa " +
                        "in cache per il clip (riusata all'export).",
                    color = TextSecondary, fontSize = 10.sp,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }