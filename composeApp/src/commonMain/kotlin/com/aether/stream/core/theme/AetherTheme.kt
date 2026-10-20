package com.aether.stream.core.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object AetherColors {
    val ObsidianBlack = Color(0xFF07090E)
    val DeepSpaceNavy = Color(0xFF0D131F)
    val GlassSurface = Color(0xCC111927)
    val GlassSurfaceHighlight = Color(0xE6182438)

    val CyberCyan = Color(0xFF00F0FF)
    val CyberCyanGlow = Color(0x6600F0FF)
    val IceBlue = Color(0xFF38BDF8)
    val DeepCyanBorder = Color(0xFF0284C7)

    val NeonEmerald = Color(0xFF10B981)
    val LaserAmber = Color(0xFFF59E0B)
    val CrimsonAlert = Color(0xFFEF4444)

    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF475569)
}

@Immutable
data class AetherExtendedColors(
    val glassBackground: Color = AetherColors.GlassSurface,
    val glassFocusedBackground: Color = AetherColors.GlassSurfaceHighlight,
    val focusBorder: Color = AetherColors.CyberCyan,
    val focusGlow: Color = AetherColors.CyberCyanGlow,
    val subtleBorder: Color = Color(0x3338BDF8)
)

val LocalAetherColors = staticCompositionLocalOf { AetherExtendedColors() }

private val AetherDarkColorScheme = darkColorScheme(
    primary = AetherColors.CyberCyan,
    onPrimary = AetherColors.ObsidianBlack,
    secondary = AetherColors.IceBlue,
    onSecondary = AetherColors.ObsidianBlack,
    background = AetherColors.ObsidianBlack,
    onBackground = AetherColors.TextPrimary,
    surface = AetherColors.DeepSpaceNavy,
    onSurface = AetherColors.TextPrimary,
    error = AetherColors.CrimsonAlert
)

val AetherTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        letterSpacing = 1.2.sp,
        color = AetherColors.TextPrimary
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        letterSpacing = 0.8.sp,
        color = AetherColors.TextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = 0.4.sp,
        color = AetherColors.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        color = AetherColors.TextSecondary
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = AetherColors.CyberCyan
    )
)

@Composable
fun AetherTheme(
    content: @Composable () -> Unit
) {
    val extendedColors = AetherExtendedColors()

    CompositionLocalProvider(LocalAetherColors provides extendedColors) {
        MaterialTheme(
            colorScheme = AetherDarkColorScheme,
            typography = AetherTypography
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                AetherColors.DeepSpaceNavy,
                                AetherColors.ObsidianBlack
                            ),
                            center = Offset(800f, 200f),
                            radius = 1600f
                        )
                    )
            ) {
                content()
            }
        }
    }
}