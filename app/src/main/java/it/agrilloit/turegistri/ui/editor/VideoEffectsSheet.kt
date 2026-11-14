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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
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
import it.agrilloit.turegistri.model.VideoEffectCatalog
import it.agrilloit.turegistri.ui.theme.BackgroundDark
import it.agrilloit.turegistri.ui.theme.GreenBadge
import it.agrilloit.turegistri.ui.theme.PrimaryAccent
import it.agrilloit.turegistri.ui.theme.SecondaryAccent
import it.agrilloit.turegistri.ui.theme.SurfaceDark
import it.agrilloit.turegistri.ui.theme.SurfaceElevated
import it.agrilloit.turegistri.ui.theme.TextSecondary

/**
 * Libreria effetti video (port di VideoEffectLibrary iOS): sezioni per intent,
 * aggiunta al clip selezionato, parametri con slider/preset, toggle e rimozione.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoEffectsSheet(
    vm: EditorViewModel,
    onDismiss: () -> Unit,
) {
    val clips by vm.clips.collectAsState()
    val selection by vm.selection.collectAsState()
    val clip = clips.firstOrNull { it.id == selection }

    var section by remember { mutableStateOf(VideoEffectCatalog.SECTIONS.first()) }
    var expandedUid by remember { mutableStateOf<String?>(null) }

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
                Text("Video effects", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "Done",
                    color = PrimaryAccent,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp),
                )
            }
            Text(
                if (clip == null) "Seleziona un clip nella timeline per applicare effetti"
                else "Clip selezionato: ${clip.effects.size} effetti",
                color = TextSecondary,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(14.dp))
            if (clip != null) {
                // --- Effetti correnti del clip ---
                clip.effects.forEach { fxe ->
                    val def = VideoEffectCatalog.byId(fxe.defId)
                    val isExpanded = expandedUid == fxe.uid
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (fxe.enabled) BackgroundDark else SurfaceElevated)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                def?.label ?: fxe.defId,
                                color = if (fxe.enabled) Color.White else TextSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { expandedUid = if (isExpanded) null else fxe.uid },
                            )
                            Text(
                                if (fxe.enabled) "ON" else "OFF",
                                color = if (fxe.enabled) GreenBadge else TextSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .clickable { vm.toggleClipEffect(clip.id, fxe.uid) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                            Text(
                                "✕",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clickable { vm.removeClipEffect(clip.id, fxe.uid) }
                                    .padding(6.dp),
                            )
                        }
                        if (isExpanded && def != null) {
                            Text(def.desc, color = TextSecondary, fontSize = 10.sp)
                            Spacer(Modifier.height(6.dp))
                            for (p in def.params) {
                                val value = fxe.params[p.id] ?: p.default
                                if (p.presetLabels != null) {
                                    Text(p.label, color = Color.White, fontSize = 11.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        p.presetLabels.forEachIndexed { i, label ->
                                            val sel = value.toInt() == i
                                            Box(
                                                Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        if (sel) SecondaryAccent.copy(alpha = 0.3f)
                                                        else SurfaceElevated
                                                    )
                                                    .clickable {
                                                        vm.updateClipEffectParam(
                                                            clip.id, fxe.uid, p.id, i.toFloat()
                                                        )
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                            ) {
                                                Text(
                                                    label,
                                                    color = if (sel) Color.White else TextSecondary,
                                                    fontSize = 10.sp,
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            p.label,
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            modifier = Modifier.width(110.dp),
                                        )
                                        Slider(
                                            value = value,
                                            onValueChange = {
                                                vm.updateClipEffectParam(clip.id, fxe.uid, p.id, it)
                                            },
                                            onValueChangeFinished = { vm.saveEditJson() },
                                            valueRange = p.min..p.max,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            "%.2f".format(value),
                                            color = TextSecondary,
                                            fontSize = 10.sp,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                // --- Browser per sezione ---
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VideoEffectCatalog.SECTIONS.forEach { s ->
                        val sel = section == s
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    if (sel) PrimaryAccent.copy(alpha = 0.3f) else SurfaceElevated
                                )
                                .clickable { section = s }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                        ) {
                            Text(
                                s.replace(" e ", "/").replace("Camera/","Camera "),
                                color = if (sel) Color.White else TextSecondary,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                VideoEffectCatalog.ALL.filter { it.section == section }
                    .chunked(2)
                    .forEach { rowDefs ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowDefs.forEach { d ->
                                val already = clip.effects.any { it.defId == d.id }
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (already) GreenBadge.copy(alpha = 0.14f)
                                            else SurfaceElevated
                                        )
                                        .border(
                                            1.dp,
                                            if (already) GreenBadge else Color.Transparent,
                                            RoundedCornerShape(10.dp),
                                        )
                                        .clickable { vm.addClipEffect(clip.id, d.id) }
                                        .padding(vertical = 10.dp, horizontal = 8.dp),
                                ) {
                                    Column {
                                        Text(d.label, color = Color.White, fontSize = 12.sp)
                                        Text(
                                            if (already) "già applicato" else "tocca per aggiungere",
                                            color = TextSecondary,
                                            fontSize = 9.sp,
                                        )
                                    }
                                }
                            }
                            if (rowDefs.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
