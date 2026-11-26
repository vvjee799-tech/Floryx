package com.flowchat.app.presentation.chat

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.flowchat.app.R
import com.flowchat.app.domain.model.ContextWindowUsage
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/** A small, stationary context-budget ring with a concise occupancy popup. */
@Composable
internal fun ContextWindowIndicator(
    usage: ContextWindowUsage?,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var lastDismissedAtMs by remember { mutableStateOf(0L) }
    LaunchedEffect(enabled) {
        if (!enabled) expanded = false
    }

    val title = stringResource(R.string.context_window_title)
    val percentage = usage?.let {
        stringResource(R.string.context_window_percent, it.usedPercent)
    }
    val valueDescription = percentage ?: stringResource(R.string.context_window_unknown)
    val description = "$title, $valueDescription"
    val state = if (usage?.isCompacting == true) {
        stringResource(R.string.context_window_compacting)
    } else {
        valueDescription
    }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val popupMaxWidth = (screenWidth - 24.dp).coerceAtLeast(1.dp).coerceAtMost(280.dp)
    val popupShape = RoundedCornerShape(14.dp)
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    val usedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val fraction = usage?.fraction?.coerceIn(0f, 1f) ?: 0f

    BackHandler(enabled = expanded) {
        expanded = false
        lastDismissedAtMs = SystemClock.uptimeMillis()
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (expanded) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f) else Color.Transparent)
            .testTag("context_window_indicator")
            .clickable(enabled = enabled, role = Role.Button) {
                if (expanded) {
                    expanded = false
                    lastDismissedAtMs = SystemClock.uptimeMillis()
                } else if (SystemClock.uptimeMillis() - lastDismissedAtMs > 250L) {
                    expanded = true
                }
            }
            .semantics {
                contentDescription = description
                stateDescription = state
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(16.dp)
                .testTag("context_window_ring")
        ) {
            val strokeWidth = 2.dp.toPx()
            val ringSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val ringOffset = Offset(strokeWidth / 2f, strokeWidth / 2f)
            val ringStyle = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = ringOffset,
                size = ringSize,
                style = ringStyle
            )
            if (fraction > 0f) {
                drawArc(
                    color = usedColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = ringOffset,
                    size = ringSize,
                    style = ringStyle
                )
            }
        }

        if (expanded && enabled) {
            Popup(
                onDismissRequest = {
                    expanded = false
                    lastDismissedAtMs = SystemClock.uptimeMillis()
                },
                popupPositionProvider = rememberContextWindowPopupPositionProvider(),
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = true
                )
            ) {
                val compactionHint = if (usage?.isCompacting == true) {
                    stringResource(R.string.context_window_compacting)
                } else {
                    null
                }
                Surface(
                    modifier = Modifier
                        .widthIn(max = popupMaxWidth)
                        .testTag("context_window_popup"),
                    shape = popupShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shadowElevation = 4.dp,
                    tonalElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (usage == null) {
                            Text(
                                text = stringResource(R.string.context_window_unknown),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.context_window_percent, usage.usedPercent),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = stringResource(
                                    R.string.context_window_tokens,
                                    compactContextWindowTokens(usage.usedTokens),
                                    compactContextWindowTokens(usage.totalTokens)
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                        compactionHint?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Formats large token counts compactly without locale-dependent decimal separators. */
internal fun compactContextWindowTokens(tokens: Int): String {
    val count = tokens.coerceAtLeast(0).toLong()
    if (count < 1_000L) return NumberFormat.getIntegerInstance(Locale.ROOT).format(count)

    val (scale, suffix) = when {
        count < 1_000_000L -> 1_000L to "k"
        count < 1_000_000_000L -> 1_000_000L to "m"
        else -> 1_000_000_000L to "b"
    }
    val formatter = DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ROOT))
    return formatter.format(count.toDouble() / scale) + suffix
}

@Composable
private fun rememberContextWindowPopupPositionProvider(): PopupPositionProvider {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val marginPx = with(density) { 8.dp.roundToPx() }
    val gapPx = with(density) { 8.dp.roundToPx() }
    return remember(marginPx, gapPx) {
        ContextWindowPopupPositionProvider(marginPx, gapPx)
    }
}

private class ContextWindowPopupPositionProvider(
    private val marginPx: Int,
    private val gapPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
            .coerceIn(marginPx, maxX)
        val maxY = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
        val aboveAnchor = anchorBounds.top - popupContentSize.height - gapPx
        val y = aboveAnchor.coerceIn(marginPx, maxY)
        return IntOffset(x, y)
    }
}
