package it.agrilloit.turegistri.ui.record

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import it.agrilloit.turegistri.ui.theme.PrimaryAccent
import it.agrilloit.turegistri.ui.theme.SurfaceDark
import it.agrilloit.turegistri.ui.theme.SurfaceElevated
import it.agrilloit.turegistri.ui.theme.TextSecondary

private val LIGHT_WIDTHS = listOf("Thin" to 14, "Medium" to 26, "Wide" to 44)
private val LIGHT_COLORS = listOf(
    "White" to 0xFFFFFFFF,
    "Warm" to 0xFFFFD9A0,
    "Cool" to 0xFFBFE0FF,
)

/** Pannello "Front light": cornice luminosa dello schermo come softbox per il selfie. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrontLightPanel(
    config: FrontLightConfig,
    onApply: (FrontLightConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var widthDp by remember { mutableStateOf(config.widthDp) }
    var colorArgb by remember { mutableStateOf(config.colorArgb) }
    var opacity by remember { mutableStateOf(config.opacity) }
    var modeSel by remember { mutableStateOf(config.mode) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Front light", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "X",
                    color = TextSecondary,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("Modalità", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Bordo luminoso" to FrontLightMode.BORDER, "Estremo (schermo bianco)" to FrontLightMode.EXTREME)
                    .forEach { (label, mode) ->
                        val selected = config.mode == mode
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) PrimaryAccent.copy(alpha = 0.25f) else SurfaceElevated)
                                .clickable { widthDp = widthDp; modeSel = mode }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(label, color = if (selected) Color.White else TextSecondary, fontSize = 12.sp)
                        }
                    }
            }
            if (modeSel == FrontLightMode.BORDER) {
                Spacer(Modifier.height(16.dp))
                Text("Border width", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LIGHT_WIDTHS.forEach { (label, dp) ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (widthDp == dp) PrimaryAccent.copy(alpha = 0.25f) else SurfaceElevated)
                                .clickable { widthDp = dp }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text(label, color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Color", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LIGHT_COLORS.forEach { (label, argb) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    3.dp,
                                    if (colorArgb == argb) Color.White else Color.Transparent,
                                    CircleShape,
                                )
                                .clickable { colorArgb = argb },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(label, color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Opacity · ${(opacity * 100).toInt()}%", color = Color.White, fontSize = 14.sp)
            Slider(
                value = opacity,
                onValueChange = { opacity = it },
                colors = SliderDefaults.colors(thumbColor = PrimaryAccent),
            )

            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PrimaryAccent)
                    .clickable {
                        onApply(FrontLightConfig(enabled = true, mode = modeSel, widthDp = widthDp, colorArgb = colorArgb, opacity = opacity))
                        onDismiss()
                    }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Turn on front light", color = Color.White, fontSize = 14.sp)
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceElevated)
                    .clickable {
                        onApply(FrontLightConfig(enabled = false))
                        onDismiss()
                    }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Turn off front light", color = Color.White, fontSize = 14.sp)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
