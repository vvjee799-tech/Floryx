package com.flowchat.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ThemeContractTest {
    @Test fun textAndControlContrastIsReadableInBothThemes() {
        for (scheme in listOf(LightColors, DarkColors)) {
            val textPairs = listOf(
                scheme.onBackground to scheme.background,
                scheme.onSurface to scheme.surface,
                scheme.onSurfaceVariant to scheme.surface,
                scheme.onSurfaceVariant to scheme.surfaceContainerLow,
                scheme.onSurfaceVariant to scheme.surfaceContainerHigh,
                scheme.onPrimary to scheme.primary,
                scheme.primary to scheme.surface,
                scheme.primary to scheme.primaryContainer,
                scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.onSecondaryContainer to scheme.secondaryContainer,
                scheme.tertiary to scheme.surface,
                scheme.onErrorContainer to scheme.errorContainer
            )
            for ((foreground, background) in textPairs) {
                assertTrue("Text contrast ${contrast(foreground, background)}: $foreground on $background",
                    contrast(foreground, background) >= 4.5)
            }
            assertTrue("Field boundaries", contrast(scheme.outline, scheme.surface) >= 3.0)
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        fun luminance(c: Color): Double {
            fun channel(v: Float): Double = if (v <= .04045f) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
            return .2126 * channel(c.red) + .7152 * channel(c.green) + .0722 * channel(c.blue)
        }
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
}
