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

// Paper: warm neutral surfaces, ink-like text and quiet olive controls.
internal val LightColors = lightColorScheme(
    primary = Color(0xFF645E4C), onPrimary = Color.White,
    primaryContainer = Color(0xFFEAE6DB), onPrimaryContainer = Color(0xFF494437),
    secondary = Color(0xFF6F695C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFEBE2), onSecondaryContainer = Color(0xFF38352F),
    tertiary = Color(0xFF356658), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE2F0E9), onTertiaryContainer = Color(0xFF244D3E),
    background = Color(0xFFF9F7F2), onBackground = Color(0xFF38352F),
    surface = Color(0xFFF9F7F2), onSurface = Color(0xFF38352F),
    surfaceVariant = Color(0xFFEFEBE2), onSurfaceVariant = Color(0xFF6A655A),
    surfaceContainerLowest = Color(0xFFFDFCF9), surfaceContainerLow = Color(0xFFF5F2EB),
    surfaceContainer = Color(0xFFEFEBE2), surfaceContainerHigh = Color(0xFFE8E3D8),
    surfaceContainerHighest = Color(0xFFDED9CC),
    outline = Color(0xFF817B6D), outlineVariant = Color(0xFFDED9CC),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFFCE9E7), onErrorContainer = Color(0xFF8E211B),
    inverseSurface = Color(0xFF38352F), inverseOnSurface = Color(0xFFF9F7F2), scrim = Color.Black
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFD8CFAF), onPrimary = Color(0xFF383529),
    primaryContainer = Color(0xFF3B382E), onPrimaryContainer = Color(0xFFE8E4D9),
    secondary = Color(0xFFC9C0A8), onSecondary = Color(0xFF302D24),
    secondaryContainer = Color(0xFF353228), onSecondaryContainer = Color(0xFFE8E4D9),
    tertiary = Color(0xFFA0CCB9), onTertiary = Color(0xFF163B2C),
    tertiaryContainer = Color(0xFF233C31), onTertiaryContainer = Color(0xFFD4EDDF),
    background = Color(0xFF24231F), onBackground = Color(0xFFE8E4D9),
    surface = Color(0xFF24231F), onSurface = Color(0xFFE8E4D9),
    surfaceVariant = Color(0xFF353228), onSurfaceVariant = Color(0xFFB5AF9F),
    surfaceContainerLowest = Color(0xFF2C2B25), surfaceContainerLow = Color(0xFF2A2822),
    surfaceContainer = Color(0xFF302E26), surfaceContainerHigh = Color(0xFF38352C),
    surfaceContainerHighest = Color(0xFF484439),
    outline = Color(0xFF9B9585), outlineVariant = Color(0xFF484439),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF482824), onErrorContainer = Color(0xFFFFDAD5),
    inverseSurface = Color(0xFFE8E4D9), inverseOnSurface = Color(0xFF38352F), scrim = Color.Black
)

private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight,
    fontSize = size.sp, lineHeight = height.sp, letterSpacing = 0.sp
)

private val FlowTypography = Typography(
    headlineLarge = type(32, 40, FontWeight.Medium), headlineMedium = type(28, 36, FontWeight.Medium),
    headlineSmall = type(24, 34), titleLarge = type(23, 32),
    titleMedium = type(18, 26, FontWeight.Medium), titleSmall = type(15, 22, FontWeight.Medium),
    bodyLarge = type(16, 29), bodyMedium = type(14, 24), bodySmall = type(12, 20),
    labelLarge = type(14, 20, FontWeight.Medium), labelMedium = type(12, 18, FontWeight.Medium), labelSmall = type(12, 18)
)
private val FlowShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(18.dp), extraLarge = RoundedCornerShape(22.dp)
)

// Only the wordmark uses a serif; conversation text keeps the device UI font.
val PaperWordmark = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal,
    fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp)

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
