package com.netino.desktop.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netino.vpn.data.PingKind

// Same palette as the Android app
val BrandBlue = Color(0xFF1E88E5)
val BrandBlueLight = Color(0xFF42A5F5)
val BrandGreen = Color(0xFF7CC531)
val BrandGreenDeep = Color(0xFF4E9A12)
val Good = Color(0xFF22A447)
val Warn = Color(0xFFF59E0B)
val Bad = Color(0xFFE5484D)
val BrandGradient = Brush.linearGradient(listOf(BrandBlue, BrandBlueLight, BrandGreen))

private val Light = lightColorScheme(
    primary = Color(0xFF1A73D9), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E7FF), onPrimaryContainer = Color(0xFF0A3A73),
    // Darker than BrandGreenDeep so green text passes AA on light surfaces
    secondary = Color(0xFF3B7A0C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF3C8), onSecondaryContainer = Color(0xFF173800),
    background = Color(0xFFF6F8FC), onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF6F8FC), onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE6ECF5), onSurfaceVariant = Color(0xFF4B5567),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEFF3F9), surfaceContainerHigh = Color(0xFFE6ECF5), surfaceContainerHighest = Color(0xFFDCE3EE),
    outline = Color(0xFFC3CDDC), outlineVariant = Color(0xFFDCE3EE),
    error = Color(0xFFC2272D), onError = Color.White,
    errorContainer = Color(0xFFFDE4E5), onErrorContainer = Color(0xFF6B0F13),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF6AB0FF), onPrimary = Color(0xFF00315F),
    primaryContainer = Color(0xFF0D3F78), onPrimaryContainer = Color(0xFFD6E7FF),
    secondary = Color(0xFF9BE05A), onSecondary = Color(0xFF173800),
    secondaryContainer = Color(0xFF22420A), onSecondaryContainer = Color(0xFFDDF3C8),
    background = Color(0xFF0A0F1C), onBackground = Color(0xFFE5EAF3),
    surface = Color(0xFF0A0F1C), onSurface = Color(0xFFE5EAF3),
    surfaceVariant = Color(0xFF1A2438), onSurfaceVariant = Color(0xFF9AA6BA),
    surfaceContainerLowest = Color(0xFF0F1626), surfaceContainerLow = Color(0xFF0F1626),
    surfaceContainer = Color(0xFF131B2D), surfaceContainerHigh = Color(0xFF1A2438), surfaceContainerHighest = Color(0xFF222C42),
    outline = Color(0xFF3A4660), outlineVariant = Color(0xFF253047),
    error = Color(0xFFFF7A7E), onError = Color(0xFF3D0A0C),
    errorContainer = Color(0xFF3D1416), onErrorContainer = Color(0xFFFFB4B6),
)

// Status colors: fill for shapes, text for words on surfaces, container behind pills and banners
@Immutable
data class StatusColors(
    val good: Color, val goodText: Color, val goodContainer: Color,
    val warn: Color, val warnText: Color, val warnContainer: Color,
    val bad: Color, val badText: Color, val badContainer: Color,
)

private val LightStatus = StatusColors(
    good = Good, goodText = Color(0xFF1B7F38), goodContainer = Color(0xFFE3F5E8),
    warn = Warn, warnText = Color(0xFF9A5B00), warnContainer = Color(0xFFFEF3DC),
    bad = Bad, badText = Color(0xFFC2272D), badContainer = Color(0xFFFDE4E5),
)
private val DarkStatus = StatusColors(
    good = Good, goodText = Color(0xFF4ADE80), goodContainer = Color(0xFF123A22),
    warn = Warn, warnText = Color(0xFFFBBF24), warnContainer = Color(0xFF3A2A06),
    bad = Bad, badText = Color(0xFFFF7A7E), badContainer = Color(0xFF3D1416),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

val MaterialTheme.status: StatusColors
    @Composable @ReadOnlyComposable get() = LocalStatusColors.current

// Motion: same durations and easings as the Android app (docs/design/Motion)
@Immutable
data class Motion(val loops: Boolean, val instant: Boolean) {
    fun ms(duration: Int) = if (instant) 0 else duration
    fun <T> spec(duration: Int, easing: Easing = Standard) = androidx.compose.animation.core.tween<T>(ms(duration), easing = easing)

    companion object {
        const val Press = 100
        const val Fast = 150
        const val Medium = 250
        const val Slow = 300
        const val Wave = 600
        const val HaloConnecting = 1600
        const val HaloConnected = 2400
        const val Shimmer = 1200
        val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    }
}

val LocalMotion = staticCompositionLocalOf { Motion(loops = true, instant = false) }

val Vazirmatn = FontFamily(
    Font("font/vazirmatn_regular.ttf", FontWeight.Normal),
    Font("font/vazirmatn_medium.ttf", FontWeight.Medium),
    Font("font/vazirmatn_semibold.ttf", FontWeight.SemiBold),
    Font("font/vazirmatn_bold.ttf", FontWeight.Bold),
)

private val AppTypography = Typography().run {
    fun TextStyle.v(size: Int? = null, line: Int? = null, w: FontWeight? = null) = copy(
        fontFamily = Vazirmatn,
        fontSize = size?.sp ?: fontSize, lineHeight = line?.sp ?: lineHeight, fontWeight = w ?: fontWeight,
    )
    copy(
        displayLarge = displayLarge.v(), displayMedium = displayMedium.v(), displaySmall = displaySmall.v(30, 40, FontWeight.Bold),
        headlineLarge = headlineLarge.v(w = FontWeight.Bold), headlineMedium = headlineMedium.v(w = FontWeight.Bold),
        headlineSmall = headlineSmall.v(24, 34, FontWeight.SemiBold),
        titleLarge = titleLarge.v(22, 30, FontWeight.Bold), titleMedium = titleMedium.v(18, 26, FontWeight.SemiBold),
        titleSmall = titleSmall.v(15, 22, FontWeight.SemiBold),
        bodyLarge = bodyLarge.v(15, 24), bodyMedium = bodyMedium.v(14, 21), bodySmall = bodySmall.v(13, 19),
        labelLarge = labelLarge.v(15, 22, FontWeight.SemiBold), labelMedium = labelMedium.v(13, 18, FontWeight.SemiBold),
        labelSmall = labelSmall.v(11, 16, FontWeight.Medium),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun AppTheme(theme: String, fa: Boolean, reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, shapes = AppShapes) {
        // Right to left for Persian
        CompositionLocalProvider(
            LocalLayoutDirection provides if (fa) LayoutDirection.Rtl else LayoutDirection.Ltr,
            LocalStatusColors provides if (dark) DarkStatus else LightStatus,
            LocalMotion provides remember(reduceMotion) { Motion(loops = !reduceMotion, instant = reduceMotion) },
            content = content,
        )
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

fun formatSpeed(bps: Long): String = formatBytes(bps) + "/s"

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
}

fun pingColor(ms: Long, kind: PingKind = PingKind.TCP): Color {
    if (ms < 0) return if (ms == -1L) Color.Gray else Bad
    val (good, ok) = if (kind == PingKind.REAL) 600L to 1500L else 200L to 500L
    return when {
        ms <= good -> Good
        ms <= ok -> Warn
        else -> Bad
    }
}
