package com.flowchat.app.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowchat.app.R
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.PaperWordmark
import kotlin.math.min

@Composable
internal fun BloomSplashContent(
    elapsedMillis: Float,
    appAppearance: AppAppearance,
    modifier: Modifier = Modifier
) {
    val frame = bloomSplashFrame(elapsedMillis)
    val dark = when (appAppearance) {
        AppAppearance.System -> isSystemInDarkTheme()
        AppAppearance.Dark -> true
        AppAppearance.Light -> false
    }
    val colors = if (dark) DarkBloomColors else LightBloomColors
    val title = stringResource(R.string.app_name)

    BoxWithConstraints(
        modifier.fillMaxSize()
            .graphicsLayer { alpha = frame.splashAlpha }
            .background(MaterialTheme.colorScheme.background)
            .testTag("bloom_splash")
            .semantics { paneTitle = title }
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val compact = maxHeight < 400.dp
        val fontScale = LocalDensity.current.fontScale
        val wordmarkSize = min(if (compact) 32f else 48f,
            (maxWidth.value - 56f).coerceAtLeast(0f) / (3.4f * fontScale)).sp

        Column(
            Modifier.fillMaxWidth().align(Alignment.Center)
                .offset(y = if (compact) 0.dp else -maxHeight * .07f)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BloomFlower(frame, colors, Modifier.size(if (compact) 48.dp else 88.dp))
            Spacer(Modifier.height(if (compact) 12.dp else 24.dp))
            Text(
                text = title,
                style = PaperWordmark.copy(
                    fontSize = wordmarkSize, lineHeight = wordmarkSize * 1.25f,
                    letterSpacing = (-1).sp
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().testTag("bloom_wordmark").graphicsLayer {
                    alpha = frame.wordmarkProgress
                    translationY = 6.dp.toPx() * (1f - frame.wordmarkProgress)
                }
            )
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            Text(
                text = stringResource(R.string.splash_tagline),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = (if (compact) 12 else 14).sp,
                    lineHeight = (if (compact) 18 else 22).sp,
                    lineBreak = LineBreak.Heading
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("bloom_tagline").graphicsLayer {
                    alpha = frame.taglineProgress
                }
            )
        }
    }
}

@Composable
private fun BloomFlower(frame: BloomSplashFrame, colors: BloomColors, modifier: Modifier) {
    // Same outline as docs/assets/floryx-title-v2.svg and the approved preview.
    val petal = remember {
        Path().apply {
            moveTo(0f, 0f)
            cubicTo(-30f, -13f, -48f, -56f, -31f, -82f)
            cubicTo(-21f, -99f, -3f, -104f, 9f, -91f)
            cubicTo(34f, -65f, 27f, -25f, 0f, 0f)
            close()
        }
    }
    Canvas(modifier.testTag("bloom_flower").clearAndSetSemantics {}) {
        translate(center.x, center.y) {
            scale(size.minDimension / 224f, pivot = Offset.Zero) {
                frame.petalProgress.forEachIndexed { index, progress ->
                    rotate(-8f + index * 60f, pivot = Offset.Zero) {
                        scale(.12f + .88f * progress, pivot = Offset.Zero) {
                            drawPath(petal, if (index % 2 == 0) colors.pink else colors.apricot,
                                alpha = progress)
                        }
                    }
                }
                scale(.7f + .3f * frame.seedProgress, pivot = Offset.Zero) {
                    // Keep a small seed visible on the very first application frame.
                    drawCircle(colors.center, radius = 19f, center = Offset.Zero,
                        alpha = .35f + .65f * frame.seedProgress)
                    val pollenAlpha = .75f * frame.seedProgress
                    drawCircle(colors.pollen, 2f, Offset(-6f, -5f), alpha = pollenAlpha)
                    drawCircle(colors.pollen, 2f, Offset(6f, -7f), alpha = pollenAlpha)
                    drawCircle(colors.pollen, 2f, Offset(2f, 5f), alpha = pollenAlpha)
                    drawCircle(colors.pollen, 1.5f, Offset(-7f, 7f), alpha = pollenAlpha)
                }
            }
        }
    }
}

private data class BloomColors(val pink: Color, val apricot: Color, val center: Color, val pollen: Color)

private val LightBloomColors = BloomColors(Color(0xFFE8BCAC), Color(0xFFF0D5BF), Color(0xFFB97955), Color(0xFFF9F7F2))
private val DarkBloomColors = BloomColors(Color(0xFFC58F7C), Color(0xFFD9B497), Color(0xFFBC8967), Color(0xFFF1DFC8))
