package com.netino.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
    primaryContainer = Color(0xFFD6E7FF), onPrimaryContainer = Color(0xFF00315F),
    secondary = BrandGreenDeep, onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF5C6), onSecondaryContainer = Color(0xFF173800),
    background = Color(0xFFF6F8FC), onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF6F8FC), onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE6ECF5), onSurfaceVariant = Color(0xFF4B5567),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEFF3F9), surfaceContainerHigh = Color(0xFFE8EEF7), surfaceContainerHighest = Color(0xFFE1E8F2),
    outline = Color(0xFFB7C1D1), outlineVariant = Color(0xFFDCE3EE),
    error = Bad,
)
private val Dark = darkColorScheme(
    primary = Color(0xFF6AB0FF), onPrimary = Color(0xFF002F5C),
    primaryContainer = Color(0xFF0D3F78), onPrimaryContainer = Color(0xFFD6E7FF),
    secondary = Color(0xFF9BE05A), onSecondary = Color(0xFF173800),
    secondaryContainer = Color(0xFF2B5208), onSecondaryContainer = Color(0xFFDDF5C6),
    background = Color(0xFF0A0F1C), onBackground = Color(0xFFE5EAF3),
    surface = Color(0xFF0A0F1C), onSurface = Color(0xFFE5EAF3),
    surfaceVariant = Color(0xFF1C2638), onSurfaceVariant = Color(0xFF9AA6BA),
    surfaceContainerLowest = Color(0xFF060A14), surfaceContainerLow = Color(0xFF0F1626),
    surfaceContainer = Color(0xFF131B2D), surfaceContainerHigh = Color(0xFF1A2337), surfaceContainerHighest = Color(0xFF222C42),
    outline = Color(0xFF3A4760), outlineVariant = Color(0xFF253047),
    error = Color(0xFFFF6B6F),
)

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
        displayLarge = displayLarge.v(), displayMedium = displayMedium.v(), displaySmall = displaySmall.v(w = FontWeight.Bold),
        headlineLarge = headlineLarge.v(w = FontWeight.Bold), headlineMedium = headlineMedium.v(w = FontWeight.Bold),
        headlineSmall = headlineSmall.v(w = FontWeight.Bold),
        titleLarge = titleLarge.v(22, 30, FontWeight.Bold), titleMedium = titleMedium.v(16, 24, FontWeight.SemiBold),
        titleSmall = titleSmall.v(14, 20, FontWeight.SemiBold),
        bodyLarge = bodyLarge.v(15, 24), bodyMedium = bodyMedium.v(14, 21), bodySmall = bodySmall.v(12, 18),
        labelLarge = labelLarge.v(14, 20, FontWeight.SemiBold), labelMedium = labelMedium.v(12, 17, FontWeight.Medium),
        labelSmall = labelSmall.v(11, 16, FontWeight.Medium),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AppTheme(theme: String, fa: Boolean, content: @Composable () -> Unit) {
    val dark = when (theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, shapes = AppShapes) {
        // Right to left for Persian
        CompositionLocalProvider(LocalLayoutDirection provides if (fa) LayoutDirection.Rtl else LayoutDirection.Ltr, content = content)
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
