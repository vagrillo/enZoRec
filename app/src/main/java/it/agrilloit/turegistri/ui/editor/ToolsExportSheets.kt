package it.agrilloit.turegistri.ui.editor

import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import it.agrilloit.turegistri.ui.theme.BackgroundDark
import it.agrilloit.turegistri.ui.theme.CyanAccent
import it.agrilloit.turegistri.ui.theme.GreenBadge
import it.agrilloit.turegistri.ui.theme.PrimaryAccent
import it.agrilloit.turegistri.ui.theme.SecondaryAccent
import it.agrilloit.turegistri.ui.theme.SurfaceDark
import it.agrilloit.turegistri.ui.theme.SurfaceElevated
import it.agrilloit.turegistri.ui.theme.TextSecondary
import java.io.File

/** Drawer "Tools" (spec 2.7): voci implementate attive, altre segnalate come in arrivo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsDrawer(
    onAddVideo: (android.net.Uri) -> Unit,
    snap: Boolean,
    onSnapToggle: () -> Unit,
    nrEnabled: Boolean,
    nrStrength: Float,
    onNoiseReductionToggle: (Boolean) -> Unit,
    onNoiseReductionStrength: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var showEnhanceAudio by remember { mutableStateOf(false) }
    val addVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onAddVideo) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tools", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "Done",
                    color = PrimaryAccent,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("Add media", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SecondaryAccent)
                        .clickable { addVideoLauncher.launch(arrayOf("video/*")) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Add video", color = Color.White, fontSize = 13.sp)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceElevated)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Add audio · in arrivo",
                        color = TextSecondary.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Adjust", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            // Enhance audio: attivo (noise reduction ML-style)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (nrEnabled) GreenBadge.copy(alpha = 0.15f) else SurfaceElevated)
                    .clickable { showEnhanceAudio = !showEnhanceAudio }
                    .padding(vertical = 12.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Enhance audio · Noise reduction",
                    color = if (nrEnabled) GreenBadge else Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (nrEnabled) "ON" else "OFF",
                    color = if (nrEnabled) GreenBadge else TextSecondary,
                    fontSize = 12.sp,
                )
            }
            if (showEnhanceAudio) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(BackgroundDark)
                        .padding(12.dp),
                ) {
                    Text(
                        "Riduzione del rumore ML-style: stima adattiva del profilo spettrale " +
                            "del rumore e gating spettrale. Applicata all'export.",
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Attiva", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Switch(checked = nrEnabled, onCheckedChange = onNoiseReductionToggle,
                            colors = SwitchDefaults.colors(checkedTrackColor = GreenBadge))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Intensità", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        listOf("Soft" to 0.8f, "Medium" to 1.5f, "Strong" to 2.5f).forEach { (label, v) ->
                            val selected = kotlin.math.abs(nrStrength - v) < 0.01f
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) GreenBadge.copy(alpha = 0.25f) else SurfaceElevated)
                                    .clickable { onNoiseReductionStrength(v) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    label,
                                    color = if (selected) GreenBadge else TextSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Adjust · in arrivo", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            val tools = listOf(
                "Video effects", "Stabilize", "Crop", "Mirror",
                "Color LUTs", "Speed", "Pauses",
                "Censor audio", "Subtitles",
            )
            tools.chunked(2).forEach { rowTools ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowTools.forEach { t ->
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceElevated)
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$t · in arrivo",
                                color = TextSecondary.copy(alpha = 0.65f),
                                fontSize = 12.sp,
                            )
                        }
                    }
                    if (rowTools.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Snap to cuts", color = Color.White, fontSize = 14.sp)
                    Text("Il playhead aggancia i tagli", color = TextSecondary, fontSize = 11.sp)
                }
                Switch(checked = snap, onCheckedChange = { onSnapToggle() }, colors = SwitchDefaults.colors(checkedTrackColor = CyanAccent))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Match listening levels", color = Color.White, fontSize = 14.sp)
                    Text("Uniforma i volumi dei clip · in arrivo", color = TextSecondary, fontSize = 11.sp)
                }
                Switch(checked = false, onCheckedChange = { }, colors = SwitchDefaults.colors(checkedTrackColor = CyanAccent))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Sheet di export (spec 2.8): progress, share e salvataggio in galleria. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    vm: EditorViewModel,
    clips: List<EditClip>,
    progress: Float?,
    exportPath: String?,
    error: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val totalMs = clips.sumOf { it.effectiveMs() }

    fun share(path: String) {
        val file = File(path)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Condividi export"))
    }

    fun saveToGallery(path: String) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, File(path).name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val item = resolver.insert(collection, values) ?: return
            resolver.openOutputStream(item)?.use { out ->
                File(path).inputStream().use { it.copyTo(out) }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Export", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "${clips.size} clips · ${formatTimecode(totalMs)}",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            }

            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BackgroundDark)
                    .padding(14.dp),
            ) {
                Column {
                    Text("Impostazioni export", color = Color.White, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "H.264 · AAC · 30 fps · risoluzione sorgente",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            when {
                progress != null && progress < 1f -> {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = PrimaryAccent,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Esportazione… ${(progress * 100).toInt()}%",
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                }

                progress == 1f && exportPath != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✓ Exported", color = GreenBadge, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        Text(
                            formatTimecode(totalMs),
                            color = TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(PrimaryAccent)
                            .clickable { share(exportPath) }
                            .padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Share latest export", color = Color.White, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SecondaryAccent)
                            .clickable { saveToGallery(exportPath) }
                            .padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Save to Photos", color = Color.White, fontSize = 14.sp)
                    }
                }

                error != null -> {
                    Text("Errore: $error", color = PrimaryAccent, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(PrimaryAccent)
                            .clickable { vm.startExport() }
                            .padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Riprova", color = Color.White, fontSize = 14.sp)
                    }
                }

                else -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(PrimaryAccent)
                            .clickable(enabled = clips.isNotEmpty()) { vm.startExport() }
                            .padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (clips.isEmpty()) "Nessun clip da esportare" else "Export",
                            color = Color.White,
                            fontSize = 14.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
        }
    }
}
