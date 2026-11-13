package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val UcnDarkColorScheme = darkColorScheme(
    primary = CipherCyan,
    onPrimary = OnCipherCyan,
    primaryContainer = CipherCyanContainer,
    onPrimaryContainer = OnCipherCyanContainer,
    secondary = ElectricCobalt,
    onSecondary = Color.White,
    secondaryContainer = CobaltContainer,
    onSecondaryContainer = OnCobaltContainer,
    tertiary = SignalEmerald,
    onTertiary = Color(0xFF002114),
    background = ObsidianBackground,
    onBackground = Color(0xFFEAF2FA),
    surface = ObsidianSurface,
    onSurface = Color(0xFFEAF2FA),
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = Color(0xFF9FB0C4),
    outline = ObsidianOutline,
    error = SignalCrimson,
    onError = Color.White
)

private val UcnHighContrastDarkColorScheme = UcnDarkColorScheme.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF0A0F14),
    surfaceVariant = Color(0xFF141E28),
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFD5E2F0),
    outline = CipherCyan
)

private val UcnLightColorScheme = lightColorScheme(
    primary = DeepTeal,
    onPrimary = Color.White,
    primaryContainer = DeepTealContainer,
    onPrimaryContainer = OnDeepTealContainer,
    secondary = DeepCobalt,
    onSecondary = Color.White,
    secondaryContainer = LightCobaltContainer,
    onSecondaryContainer = OnLightCobaltContainer,
    tertiary = SignalEmerald,
    onTertiary = Color.White,
    background = AlabasterBackground,
    onBackground = Color(0xFF0F172A),
    surface = AlabasterSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = AlabasterSurfaceVariant,
    onSurfaceVariant = Color(0xFF475569),
    outline = AlabasterOutline,
    error = Color(0xFFDC2626),
    onError = Color.White
)

private val UcnHighContrastLightColorScheme = UcnLightColorScheme.copy(
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Color(0xFFE2E8F0),
    onBackground = Color.Black,
    onSurface = Color.Black,
    onSurfaceVariant = Color(0xFF1E293B),
    outline = DeepTeal
)

val UcnShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun UcnTheme(
    darkModePreference: String = "dark",
    highContrast: Boolean = false,
    content: @Composable () -> Unit
) {
    val useDark = when (darkModePreference) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colorScheme = when {
        useDark && highContrast -> UcnHighContrastDarkColorScheme
        useDark -> UcnDarkColorScheme
        highContrast -> UcnHighContrastLightColorScheme
        else -> UcnLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = UcnTypography,
        shapes = UcnShapes,
        content = content
    )
}
