package it.agrilloit.turegistri.ui.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.agrilloit.turegistri.ui.theme.PrimaryAccent
import it.agrilloit.turegistri.ui.theme.SecondaryAccent
import it.agrilloit.turegistri.ui.theme.SurfaceDark
import it.agrilloit.turegistri.ui.theme.TextSecondary

/**
 * Selettore del microfono (badge in alto a sinistra): automatico (strategia a
 * 3 livelli) oppure uno dei microfoni integrati del telefono.
 */
@Composable
fun MicPickerDialog(
    vm: RecordViewModel,
    onDismiss: () -> Unit,
) {
    val devices = remember { vm.micDevices() }
    var current by remember { mutableStateOf(vm.engine.preferredMicAddress()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scegli microfono", color = Color.White) },
        text = {
            Column {
                Text(
                    "Usato dal prossimo take. Il livello live resta nel badge.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            current = null
                            vm.setPreferredMic(null)
                            onDismiss()
                        }
                        .padding(vertical = 12.dp),
                ) {
                    Text(
                        "Automatico (consigliato)",
                        color = if (current == null) SecondaryAccent else Color.White,
                        fontSize = 14.sp,
                    )
                }
                devices.forEach { (address, label) ->
                    if (address.isBlank()) return@forEach
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                current = address
                                vm.setPreferredMic(address)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(
                            label,
                            color = if (current == address) SecondaryAccent else Color.White,
                            fontSize = 14.sp,
                        )
                    }
                }
                if (devices.none { it.first.isNotBlank() }) {
                    Text(
                        "Nessun altro microfono integrato disponibile",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            Text(
                "Chiudi",
                color = PrimaryAccent,
                modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp),
            )
        },
        containerColor = SurfaceDark,
    )
}
