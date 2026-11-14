package it.agrilloit.turegistri.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.agrilloit.turegistri.ui.theme.CyanDone
import it.agrilloit.turegistri.ui.theme.OrangeFx
import it.agrilloit.turegistri.ui.theme.SurfaceElevated
import it.agrilloit.turegistri.ui.theme.TextSecondary

/**
 * Foglio modale stile iOS (dai screenshot di riferimento):
 *  - header FISSO: capsula chevron a sinistra, titolo centrato, capsula "Done" a destra;
 *  - contenuto scrollabile sotto (la "Done" resta sempre visibile);
 *  - navigationBarsPadding (i controlli non finiscono sotto la gesture bar);
 *  - altezza massima del contenuto limitata per non coprire l'intera schermata.
 *
 * Colori iOS: Done = ciano, effetti/tool colore = arancio (tinte per tool da
 * EditorTools.swift iOS).
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun SheetScaffold(
    title: String,
    onDismiss: () -> Unit,
    doneColor: Color = CyanDone,
    maxHeightFraction: Float = 0.86f,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0E0E10),
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            SheetHeader(title = title, onDismiss = onDismiss, doneColor = doneColor)
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = (640 * maxHeightFraction).dp)
                    .verticalScroll(rememberScrollState())
            ) {
                content()
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

/** Header fisso: chevron sinistro, titolo centrato, Done a destra (capsule iOS). */
@Composable
fun SheetHeader(title: String, onDismiss: () -> Unit, doneColor: Color = CyanDone) {
    Box(Modifier.fillMaxWidth()) {
        Text(
            title,
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Capsula chevron-down a sinistra (iOS)
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(SurfaceElevated)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("⌄", color = Color.White, fontSize = 15.sp)
            }
            Spacer(Modifier.width(8.dp))
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(SurfaceElevated)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            ) {
                Text(
                    "Done",
                    color = doneColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Slider stile iOS dal screenshot "Color Correction": track sottile colorato,
 * manopola bianca, etichetta a sinistra e valore a destra.
 */
@Composable
fun IosSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    tint: Color = OrangeFx,
    valueText: String = "%.2f".format(value),
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text(valueText, color = Color.White, fontSize = 15.sp)
        }
        Spacer(Modifier.height(2.dp))
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = tint,
                inactiveTrackColor = Color(0xFF2A2A2C),
            ),
            modifier = Modifier.fillMaxWidth().height(26.dp),
        )
    }
}

/** Tinte dei tool (port di EditorTools.swift iOS). */
object ToolTints {
    val subtitles = Color(0xFF40C8E0)
    val text = Color(0xFFAF7BF0)
    val speed = Color(0xFF3E7BFA)
    val color = Color(0xFFFF5E93)
    val stabilize = Color(0xFF40C8B0)
    val pauses = Color.White
    val crop = Color(0xFF8F8AF4)
    val mirror = Color(0xFF40D0C8)
    val image = OrangeFx
    val transition = OrangeFx
    val videoEffects = Color(0xFF40C8E0)
    val audio = Color(0xFF40C8B0)
    val addVideo = Color(0xFF40C8E0)
    val vibe = Color(0xFFFF5E93)
}
