package it.agrilloit.turegistri.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import it.agrilloit.turegistri.model.CubeLut
import it.agrilloit.turegistri.model.CubeLutData
import it.agrilloit.turegistri.ui.theme.BackgroundDark
import it.agrilloit.turegistri.ui.theme.GreenBadge
import it.agrilloit.turegistri.ui.theme.PrimaryAccent
import it.agrilloit.turegistri.ui.theme.SecondaryAccent
import it.agrilloit.turegistri.ui.theme.SurfaceDark
import it.agrilloit.turegistri.ui.theme.SurfaceElevated
import it.agrilloit.turegistri.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.io.File

/**
 * Tool "Color LUTs" (port iOS): import .cube (1D/3D), lista preferiti,
 * applicazione al clip selezionato.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LutSheet(
    vm: EditorViewModel,
    onDismiss: () -> Unit,
) {
    val clips by vm.clips.collectAsState()
    val selection by vm.selection.collectAsState()
    val clip = clips.firstOrNull { it.id == selection }

    var luts by remember { mutableStateOf(vm.importedLuts()) }
    var status by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            refreshing = true
            status = null
        }
        // l'import è asincrono sotto
        if (uri != null) {
            kotlinx.coroutines.MainScope().launch {
                val path = vm.importLut(uri)
                luts = vm.importedLuts()
                status = if (path != null) "LUT importata" else "File .cube non valido"
                refreshing = false
            }
        }
    }

    LaunchedEffect(clip) { luts = vm.importedLuts() }

    SheetScaffold(
        title = "Color LUTs",
        onDismiss = onDismiss,
        doneColor = ToolTints.videoEffects,
    ) {
        Text(
            "", color = Color.Transparent, fontSize = 1.sp,
        )
            Text(
                if (clip == null) "Seleziona un clip per applicare una LUT"
                else "Applica al clip selezionato (ordine effetti)",
                color = TextSecondary,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SecondaryAccent.copy(alpha = 0.18f))
                    .clickable { importLauncher.launch(arrayOf("*/*")) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Importa .cube (1D o 3D)", color = Color.White, fontSize = 13.sp)
            }
            if (status != null) {
                Spacer(Modifier.height(6.dp))
                Text(status!!, color = if (refreshing) TextSecondary else GreenBadge, fontSize = 11.sp)
            }

            Spacer(Modifier.height(12.dp))
            if (luts.isEmpty()) {
                Text(
                    "Nessuna LUT importata. Esporta una .cube da DaVinci/Lightroom e importala.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            }
            luts.forEach { path ->
                val applied = clip?.effects?.any { it.defId == "lut3d" && it.dataRef == path }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (applied == true) GreenBadge.copy(alpha = 0.14f) else BackgroundDark)
                        .clickable(enabled = clip != null && applied != true) {
                            vm.applyLut(clip!!.id, path)
                            status = "LUT applicata al clip"
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(File(path).nameWithoutExtension, color = Color.White, fontSize = 12.sp)
                        val kind = runCatching {
                            val parsed = CubeLut.parse(File(path).readText())
                            when (val l = parsed.lut) {
                                is CubeLutData.OneD -> "1D · ${l.size} punti"
                                is CubeLutData.ThreeD -> "3D · ${l.size}³"
                                else -> "—"
                            }
                        }.getOrDefault("—")
                        Text(kind, color = TextSecondary, fontSize = 10.sp)
                    }
                    if (applied == true) {
                        Text("✓ applicata", color = GreenBadge, fontSize = 11.sp)
                    } else if (clip != null) {
                        Text(
                            "Applica",
                            color = SecondaryAccent,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
