package com.flowchat.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Paper: warm neutral surfaces, ink-like text and terracotta/sage accents.
internal val LightColors = lightColorScheme(
    primary = Color(0xFFA84727), onPrimary = Color.White,
    primaryContainer = Color(0xFFF3E6DC), onPrimaryContainer = Color(0xFF5A2514),
    secondary = Color(0xFF6E685C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFECE3), onSecondaryContainer = Color(0xFF2C2924),
    tertiary = Color(0xFF2D604F), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE2F0E9), onTertiaryContainer = Color(0xFF20483A),
    background = Color(0xFFF9F7F2), onBackground = Color(0xFF2C2924),
    surface = Color(0xFFF9F7F2), onSurface = Color(0xFF2C2924),
    surfaceVariant = Color(0xFFEFECE3), onSurfaceVariant = Color(0xFF635E53),
    surfaceContainerLowest = Color(0xFFFDFCF9), surfaceContainerLow = Color(0xFFF2EFE7),
    surfaceContainer = Color(0xFFEFECE3), surfaceContainerHigh = Color(0xFFEAE6DC),
    surfaceContainerHighest = Color(0xFFDFDBD0),
    outline = Color(0xFF817B6D), outlineVariant = Color(0xFFEAE6DC),
    error = Color(0xFFB94742), onError = Color.White,
    errorContainer = Color(0xFFFCE9E7), onErrorContainer = Color(0xFF8E211B),
    inverseSurface = Color(0xFF2C2924), inverseOnSurface = Color(0xFFF9F7F2), scrim = Color.Black
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFE08262), onPrimary = Color(0xFF231914),
    primaryContainer = Color(0xFF342D27), onPrimaryContainer = Color(0xFFECE8DE),
    secondary = Color(0xFFC9C0A8), onSecondary = Color(0xFF2B2821),
    secondaryContainer = Color(0xFF323029), onSecondaryContainer = Color(0xFFECE8DE),
    tertiary = Color(0xFF9AC7B4), onTertiary = Color(0xFF163B2C),
    tertiaryContainer = Color(0xFF233C31), onTertiaryContainer = Color(0xFFD4EDDF),
    background = Color(0xFF21201C), onBackground = Color(0xFFECE8DE),
    surface = Color(0xFF21201C), onSurface = Color(0xFFECE8DE),
    surfaceVariant = Color(0xFF323029), onSurfaceVariant = Color(0xFFADA798),
    surfaceContainerLowest = Color(0xFF2B2924), surfaceContainerLow = Color(0xFF2A2822),
    surfaceContainer = Color(0xFF2E2C26), surfaceContainerHigh = Color(0xFF36342C),
    surfaceContainerHighest = Color(0xFF454239),
    outline = Color(0xFF9B9585), outlineVariant = Color(0xFF35332B),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF482824), onErrorContainer = Color(0xFFFFDAD5),
    inverseSurface = Color(0xFFECE8DE), inverseOnSurface = Color(0xFF2C2924), scrim = Color.Black
)

private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight,
    fontSize = size.sp, lineHeight = height.sp, letterSpacing = 0.sp
)

private val FlowTypography = Typography(
    headlineLarge = type(32, 40, FontWeight.Medium), headlineMedium = type(28, 36, FontWeight.Medium),
    headlineSmall = type(24, 32), titleLarge = type(20, 28, FontWeight.Medium),
    titleMedium = type(20, 28, FontWeight.Medium), titleSmall = type(15, 24, FontWeight.Medium),
    bodyLarge = type(15, 24), bodyMedium = type(14, 22), bodySmall = type(12, 18),
    labelLarge = type(14, 20, FontWeight.Medium), labelMedium = type(12, 18, FontWeight.Medium), labelSmall = type(12, 18)
)
private val FlowShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp)
)

// Only the wordmark uses a serif; conversation text keeps the device UI font.
val PaperWordmark = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal,
    fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp)

@Composable
fun FlowChatTheme(appAppearance: AppAppearance = AppAppearance.Dark, content: @Composable () -> Unit) {
    val useDarkColors = when (appAppearance) {
        AppAppearance.System -> isSystemInDarkTheme()
        AppAppearance.Dark -> true
        AppAppearance.Light -> false
    }
    MaterialTheme(colorScheme = if (useDarkColors) DarkColors else LightColors,
        typography = FlowTypography, shapes = FlowShapes, content = content)
}
