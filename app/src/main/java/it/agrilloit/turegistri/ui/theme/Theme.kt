package it.agrilloit.turegistri.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette YouRec (spec design system)
val BackgroundDark = Color(0xFF0D0E10)
val SurfaceDark = Color(0xFF16181D)
val SurfaceElevated = Color(0xFF22252D)
val PrimaryAccent = Color(0xFFFF3B30)
val TimecodeRed = Color(0xFF8E1F1F) // rosso carmine pillola timecode stile YouRec
val RecordDiscRed = Color(0xFFE53935)
val SecondaryAccent = Color(0xFF007AFF)
val GreenBadge = Color(0xFF30D158)
val CyanAccent = Color(0xFF64D2FF)
val CyanWave = Color(0xFF30B0C7)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFF8E8E93)
val DividerDark = Color(0xFF2C2C2E)
val TrackVideo = Color(0xFF1C1C1E)
val CyanDone = Color(0xFF3ED1E6)   // "Done" e stati attivi dei settings (iOS)
val OrangeFx = Color(0xFFF09132)   // effetti video / strumenti colore (iOS)
val TrackAudio = Color(0xFF12242B)

private val YouRecColors = darkColorScheme(
    primary = PrimaryAccent,
    onPrimary = TextPrimary,
    secondary = SecondaryAccent,
    onSecondary = TextPrimary,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = DividerDark,
)

@Composable
fun YouRecTheme(content: @Composable () -> Unit) {
    // Dark mode nativa: il design è pensato dark-only.
    MaterialTheme(
        colorScheme = YouRecColors,
        content = content,
    )
}
