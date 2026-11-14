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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
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
import it.agrilloit.turegistri.ui.theme.BackgroundDark
import it.agrilloit.turegistri.ui.theme.CyanAccent
import it.agrilloit.turegistri.ui.theme.GreenBadge
import it.agrilloit.turegistri.ui.theme.PrimaryAccent
import it.agrilloit.turegistri.ui.theme.SurfaceDark
import it.agrilloit.turegistri.ui.theme.SurfaceElevated
import it.agrilloit.turegistri.ui.theme.TextSecondary

/**
 * Editor transizioni (port TransitionEditor iOS): per ogni confine tra clip,
 * stile + durata. Le dissolvenze audio corrispondenti sono automatiche.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransitionSheet(
    vm: EditorViewModel,
    onDismiss: () -> Unit,
) {
    val clips by vm.clips.collectAsState()
    val transitions by vm.transitions.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier
                .padding(horizontal = 18.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Transitions", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "Done",
                    color = PrimaryAccent,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp),
                )
            }
            Text(
                "Stile sul confine tra clip; audio in dissolvenza automatica. " +
                    "Tocca di nuovo lo stile attivo per rimuovere la transizione.",
                color = TextSecondary,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(12.dp))
            if (clips.size < 2) {
                Text("Servono almeno 2 clip per le transizioni.", color = TextSecondary, fontSize = 12.sp)
            }
            for (i in 0 until clips.size - 1) {
                val left = clips[i]
                val right = clips[i + 1]
                val tr = transitions.firstOrNull { it.leftId == left.id && it.rightId == right.id }
                var dur by remember(tr?.key, tr?.durationMs) { mutableStateOf((tr?.durationMs ?: 500).toFloat()) }
                var fadeWhite by remember(tr?.key) { mutableStateOf(tr?.fadeWhite ?: false) }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(BackgroundDark)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        "Clip ${i + 1} → ${i + 2}",
                        color = Color.White,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        TransitionStyle.values().forEach { st ->
                            val sel = tr?.style == st
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (sel) GreenBadge.copy(alpha = 0.25f) else SurfaceElevated
                                    )
                                    .clickable {
                                        if (sel) {
                                            vm.setTransition(left.id, right.id, null, 0)
                                        } else {
                                            vm.setTransition(left.id, right.id, st, dur.toLong(), fadeWhite)
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    st.label,
                                    color = if (sel) GreenBadge else TextSecondary,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                    }
                    if (tr != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Durata", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(64.dp))
                            Slider(
                                value = dur,
                                onValueChange = {
                                    dur = it
                                    vm.setTransition(left.id, right.id, tr.style, it.toLong(), tr.fadeWhite)
                                },
                                valueRange = 150f..3000f,
                                modifier = Modifier.weight(1f),
                            )
                            Text("%.1fs".format(dur / 1000f), color = TextSecondary, fontSize = 10.sp)
                        }
                        if (tr.style == TransitionStyle.FADE_COLOR) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Attraverso il bianco", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = fadeWhite,
                                    onCheckedChange = {
                                        fadeWhite = it
                                        vm.setTransition(left.id, right.id, tr.style, dur.toLong(), it)
                                    },
                                    colors = SwitchDefaults.colors(checkedTrackColor = CyanAccent),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
