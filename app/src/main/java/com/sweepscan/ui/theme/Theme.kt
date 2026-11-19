package com.sweepscan.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SweepScan's design system: one dark theme for a dim stock room, built on Material 3.
 *
 * - Brand lime for the main action on a screen, nowhere else.
 * - Amber only for "this is the one you are looking for" (targets, waiting requests), matching the
 *   2D plan and the 3D view.
 * - Status colours (received / looking / sent / not there) always come with a word.
 */
object Palette {
    val Background = Color(0xFF121418)
    val Map = Color(0xFF0E1013)
    val Surface = Color(0xFF171A1E)
    val SurfaceHigh = Color(0xFF1F2329)
    val Outline = Color(0xFF2A3038)
    val Text = Color(0xFFF2F5F7)
    val TextSoft = Color(0xFFB0B8C1)
    val TextMuted = Color(0xFF8B95A1)
    val Brand = Color(0xFFC6E81B)
    val OnBrand = Color(0xFF1B1F24)
    val BrandContainer = Color(0xFF3C4A12)
    val Amber = Color(0xFFFFB300)
    val OnAmber = Color(0xFF241A00)
    val Blue = Color(0xFF56B4E9)
    val Green = Color(0xFF3DDC97)
    val Red = Color(0xFFFF8A80)
}

/** Request states, the same everywhere (queue, shop screen, panel). */
@Immutable
data class StatusColors(val new: Color, val looking: Color, val sent: Color, val missing: Color)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(Palette.Amber, Palette.Blue, Palette.Green, Palette.Red)
}

private val Colors = darkColorScheme(
    primary = Palette.Brand,
    onPrimary = Palette.OnBrand,
    primaryContainer = Palette.BrandContainer,
    onPrimaryContainer = Palette.Text,
    secondary = Palette.Blue,
    onSecondary = Palette.OnBrand,
    secondaryContainer = Color(0xFF243642),
    onSecondaryContainer = Palette.Text,
    tertiary = Palette.Amber,
    onTertiary = Palette.OnAmber,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextSoft,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF262B32),
    surfaceContainerLow = Color(0xFF15181C),
    outline = Palette.Outline,
    outlineVariant = Palette.Outline,
    error = Palette.Red,
    onError = Palette.OnBrand,
)

private val Type = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold),
)

private val Shape = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Spacing steps (dp): 4, 8, 12, 16, 24. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
}

@Composable
fun SweepTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type, shapes = Shape, content = content)
}
