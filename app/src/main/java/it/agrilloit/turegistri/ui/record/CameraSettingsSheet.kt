package it.agrilloit.turegistri.ui.record

import android.content.Context
import android.hardware.camera2.CameraManager
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
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
import it.agrilloit.turegistri.camera.CameraCatalog
import it.agrilloit.turegistri.ui.theme.BackgroundDark
import it.agrilloit.turegistri.ui.theme.CyanAccent
import it.agrilloit.turegistri.ui.theme.DividerDark
import it.agrilloit.turegistri.ui.theme.PrimaryAccent
import it.agrilloit.turegistri.ui.theme.SecondaryAccent
import it.agrilloit.turegistri.ui.theme.SurfaceDark
import it.agrilloit.turegistri.ui.theme.SurfaceElevated
import it.agrilloit.turegistri.ui.theme.TextSecondary

private val QUICK_TOGGLES = listOf("Stabilize", "Follow face", "Front light")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSettingsSheet(
    vm: RecordViewModel,
    onDismiss: () -> Unit,
) {
    val settings by vm.settings.collectAsState()
    val frontLight by vm.frontLight.collectAsState()
    val layout by vm.layout.collectAsState()
    var showPickerA by remember { mutableStateOf(false) }
    var showPickerB by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val catalog = remember { CameraCatalog.catalog(context.getSystemService(Context.CAMERA_SERVICE) as CameraManager) }
    fun labelOf(id: String?): String =
        id?.let { wanted -> catalog.firstOrNull { it.id == wanted }?.label } ?: "Auto"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Spacer(Modifier.weight(1f))
                Text("Camera settings", color = Color.White, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "Done",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceElevated)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            // Griglia toggle rapidi
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickToggle(
                    label = "Stabilize",
                    active = settings.stabilize,
                    enabled = true,
                ) { vm.updateSettings { s -> s.copy(stabilize = !s.stabilize) } }
                QuickToggle(
                    label = "Follow face",
                    active = false,
                    enabled = false,
                ) { }
                QuickToggle(
                    label = "Front light",
                    active = frontLight.enabled,
                    enabled = true,
                    onClick = { /* il flash si controlla solo dal pulsante flash */ },
                )
            }

            Spacer(Modifier.height(16.dp))

            // Dual camera
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use two cameras", color = Color.White, fontSize = 14.sp)
                    Text(
                        "Registrazione simultanea di due camere",
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = settings.dualCamera,
                    onCheckedChange = { on ->
                        vm.updateSettings { s -> s.copy(dualCamera = on) }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = CyanAccent),
                )
            }
            if (settings.dualCamera) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DropdownPill("First: ${labelOf(settings.cameraIdA)}") { showPickerA = true }
                    DropdownPill("Second: ${labelOf(settings.cameraIdB)}") { showPickerB = true }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Profilo colore
            Text("Recording color", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("SDR", "HDR", "Log").forEach { profile ->
                    val active = profile == "SDR"
                    val enabled = profile == "SDR"
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) CyanAccent.copy(alpha = 0.25f) else SurfaceElevated)
                            .clickable(enabled = enabled) { }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    ) {
                        Text(
                            profile,
                            color = if (enabled) Color.White else TextSecondary.copy(alpha = 0.5f),
                            fontSize = 13.sp,
                        )
                    }
                }
                Text(
                    "HDR/Log: in arrivo",
                    color = TextSecondary.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }

            Spacer(Modifier.height(16.dp))

            // Qualità
            Text("Recording quality", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityChip("720p · 30 fps", settings.recordWidth == 1280) {
                    vm.updateSettings { s -> s.copy(recordWidth = 1280) }
                }
                QualityChip("1080p · 30 fps", settings.recordWidth == 1920) {
                    vm.updateSettings { s -> s.copy(recordWidth = 1920) }
                }
                QualityChip("4K · 30 fps", settings.recordWidth == 3840) {
                    vm.updateSettings { s -> s.copy(recordWidth = 3840) }
                }
            }

            Spacer(Modifier.height(14.dp))

            SwitchRow(
                "Adatta il fotogramma intero",
                "Niente ritaglio nei pannelli (bande nere)",
                settings.fitFrame,
            ) { vm.updateSettings { s -> s.copy(fitFrame = !s.fitFrame) } }
            SwitchRow(
                "Specchia anteprima frontale",
                "Selfie naturale nell'anteprima",
                settings.mirrorFront,
            ) { vm.updateSettings { s -> s.copy(mirrorFront = !s.mirrorFront) } }
            SwitchRow(
                "Audio nel composito",
                "Traccia AAC nel video pronto da condividere",
                settings.compositeAudio,
            ) { vm.updateSettings { s -> s.copy(compositeAudio = !s.compositeAudio) } }
            SwitchRow(
                "Screen display",
                "Registra anche schermo e audio delle altre app (richiede conferma a ogni take)",
                settings.screenDisplay,
            ) { vm.updateSettings { s -> s.copy(screenDisplay = !s.screenDisplay) } }
            SwitchRow(
                "Noise suppression live",
                "Soppressore di sistema (ML) sui microfoni in registrazione",
                settings.noiseSuppression,
            ) { vm.updateSettings { s -> s.copy(noiseSuppression = !s.noiseSuppression) } }

            Spacer(Modifier.height(18.dp))
        }
    }

    if (showPickerA) {
        CameraPickerDialog(
            context = context,
            excludeId = settings.cameraIdB,
            currentId = settings.cameraIdA,
            otherId = settings.cameraIdB,
            onPick = { id ->
                vm.updateSettings { s -> s.copy(cameraIdA = id) }
                showPickerA = false
            },
            onDismiss = { showPickerA = false },
        )
    }
    if (showPickerB) {
        CameraPickerDialog(
            context = context,
            excludeId = settings.cameraIdA,
            currentId = settings.cameraIdB,
            otherId = settings.cameraIdA,
            onPick = { id ->
                vm.updateSettings { s -> s.copy(cameraIdB = id) }
                showPickerB = false
            },
            onDismiss = { showPickerB = false },
        )
    }
}

@Composable
private fun QuickToggle(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) CyanAccent.copy(alpha = 0.18f) else SurfaceElevated)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            color = if (enabled) {
                if (active) CyanAccent else Color.White
            } else {
                TextSecondary.copy(alpha = 0.5f)
            },
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun DropdownPill(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text("$text ▾", color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun QualityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) PrimaryAccent.copy(alpha = 0.25f) else SurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) Color.White else TextSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = { onChange() }, colors = SwitchDefaults.colors(checkedTrackColor = CyanAccent))
    }
}

/** Dialog di scelta camera per uno stream (con verifica simultaneità). */
@Composable
fun CameraPickerDialog(
    context: Context,
    excludeId: String?,
    currentId: String?,
    otherId: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val options = remember { CameraCatalog.catalog(manager) }
    var message by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scegli camera", color = Color.White) },
        text = {
            Column {
                if (message != null) {
                    Text(message!!, color = PrimaryAccent, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }
                PickerRow("Automatica (consigliata)", currentId == null) { onPick(null) }
                options.filter { it.id != excludeId }.forEach { opt ->
                    PickerRow(opt.label, currentId == opt.id) {
                        if (otherId != null && !CameraCatalog.canRunTogether(manager, opt.id, otherId)) {
                            message = "Non simultanea con l'altra camera su questo telefono"
                        } else {
                            onPick(opt.id)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Text("Annulla", color = SecondaryAccent, modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
        },
        containerColor = SurfaceDark,
    )
}

@Composable
private fun PickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (selected) SecondaryAccent else Color.Transparent)
                .border(2.dp, if (selected) SecondaryAccent else DividerDark, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Text(label, color = Color.White, fontSize = 14.sp)
    }
}
