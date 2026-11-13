package it.agrilloit.turegistri.ui.record

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
import it.agrilloit.turegistri.model.LayoutMode
import it.agrilloit.turegistri.ui.theme.PrimaryAccent
import it.agrilloit.turegistri.ui.theme.SurfaceDark
import it.agrilloit.turegistri.ui.theme.SurfaceElevated
import it.agrilloit.turegistri.ui.theme.TextSecondary

/**
 * Layout adjuster (spec 2.5): modalità PiP / Split verticale / Split orizzontale,
 * forma dell'inserto PiP (rettangolo/cerchio) e swap delle due camere.
 * Divider e inserto si regolano trascinando direttamente sull'anteprima.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutAdjuster(
    vm: RecordViewModel,
    current: it.agrilloit.turegistri.model.LayoutEngine.LayoutState,
    onDismiss: () -> Unit,
) {
    var pipCircle by remember { mutableStateOf(vm.engine.renderer.pipCircleMask.radius > 0f) }
    var swapped by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dual camera layout", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "Done",
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeChip("PiP", current.mode == LayoutMode.PIP) { vm.setLayout(LayoutMode.PIP) }
                ModeChip("Split V", current.mode == LayoutMode.SPLIT_V) { vm.setLayout(LayoutMode.SPLIT_V) }
                ModeChip("Split H", current.mode == LayoutMode.SPLIT_H) { vm.setLayout(LayoutMode.SPLIT_H) }
                ModeChip("Swap", swapped) {
                    swapped = !swapped
                    val s = vm.settings.value
                    vm.updateSettings { st -> st.copy(cameraIdA = s.cameraIdB, cameraIdB = s.cameraIdA) }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PiP shape", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                ModeChip("Rectangle", !pipCircle) {
                    pipCircle = false
                    vm.engine.renderer.setPipCircle(false)
                }
                Spacer(Modifier.width(8.dp))
                ModeChip("Circle", pipCircle) {
                    pipCircle = true
                    vm.engine.renderer.setPipCircle(true)
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Trascina sull'anteprima per spostare l'inserto o il divisore · doppio tocco per la dimensione",
                color = TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) PrimaryAccent.copy(alpha = 0.28f) else SurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(label, color = if (selected) Color.White else TextSecondary, fontSize = 13.sp)
    }
}
