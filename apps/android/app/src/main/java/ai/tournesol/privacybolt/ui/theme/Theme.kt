package ai.tournesol.privacybolt.ui.theme

import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Atelier: warm paper surfaces, clay actions and brown ink.
// Existing token names retain their roles: Ink is background, Paper is foreground.
val Sunflower = Color(0xFF9A4A32)
val SunflowerDim = Color(0xFF895339)
val Ink = Color(0xFFF7F2E9)        // desktop --bg
val InkSoft = Color(0xFFFFFDF8)    // desktop panel
val InkCard = Color(0xFFEEE5D7)    // desktop raised panel
val Paper = Color(0xFF42392F)      // desktop paper
val PaperDim = Color(0xFF75614F)   // desktop dim
val BubbleMine = Color(0xFFEEDED0)     // warm-tinted "mine" bubble (sits above InkCard)
val BubbleTheirs = Color(0xFFEEE5D7)   // = InkCard

// Semantic status tokens (adopting the desktop values). Use these instead of scattering
// raw hex: Danger for destructive/error, Success for the secure "you are protected"
// state, Divider for hairline separators. Clay (Sunflower) is reserved for ACTIONABLE
// controls — so a steady-state "secure" signal reads as Success, not as a call to act.
val Danger = Color(0xFFB3261E)
val Success = Color(0xFF526B39)
val Divider = Color(0xFFE1D6C6)
val Outline = Color(0xFF968572)

private val PpColors = lightColorScheme(
    primary = Sunflower,
    onPrimary = Ink,
    primaryContainer = BubbleMine,
    onPrimaryContainer = Paper,
    inversePrimary = Color(0xFFEFB395),
    secondary = SunflowerDim,
    onSecondary = Ink,
    secondaryContainer = BubbleMine,
    onSecondaryContainer = Paper,
    tertiary = Success,
    onTertiary = Ink,
    tertiaryContainer = InkCard,
    onTertiaryContainer = Paper,
    background = Ink,
    onBackground = Paper,
    surface = InkSoft,
    onSurface = Paper,
    surfaceVariant = InkCard,
    onSurfaceVariant = PaperDim,
    surfaceTint = Sunflower,
    inverseSurface = Paper,
    inverseOnSurface = Ink,
    surfaceBright = InkCard,
    surfaceDim = Ink,
    surfaceContainer = InkSoft,
    surfaceContainerHigh = InkCard,
    surfaceContainerHighest = BubbleMine,
    surfaceContainerLow = InkSoft,
    surfaceContainerLowest = Ink,
    outline = Outline,
    outlineVariant = Divider,
    error = Danger,
    onError = Ink,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF6D211A),
)

@Composable
fun PrivacyBoltTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PpColors, shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp)), content = content)
}
