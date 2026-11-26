package com.flowchat.app.presentation.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.flowchat.app.R
import com.flowchat.app.ui.theme.PaperWordmark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object FlowLayout {
    val Gutter = 24.dp
    val ContentWidth = 720.dp
    val TouchTarget = 48.dp
}

@Composable
internal fun flowWordmarkStyle() = PaperWordmark.copy(
    color = if (MaterialTheme.colorScheme.background.luminance() > .5f) Color.Black else Color.White,
    shadow = Shadow.None
)

@Composable
fun FlowWordmark(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.app_name), style = flowWordmarkStyle(), maxLines = 1, modifier = modifier)
}

/** Decorative vector mark accompanying the app name. */
@Composable
fun FlowMark(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null, modifier = modifier.size(32.dp),
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.tertiary))
}

@Composable
fun FlowSheetHeader(title: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    FlowDialogSystemBars()
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close), Modifier.size(22.dp)) }
    }
}

/** Dialog windows otherwise inherit stale system-bar colors when the theme changes. */
@Composable
fun FlowDialogSystemBars() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
        ?: (view as? DialogWindowProvider)?.window
    val light = MaterialTheme.colorScheme.surface.luminance() > .5f
    SideEffect {
        if (window != null) WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}

@Composable
fun FlowSectionLabel(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp, bottom = 10.dp))
}

@Composable
fun FlowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), content = content)
}

@Composable
fun FlowToggleRow(title: String, description: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            modifier = modifier
                .sizeIn(minWidth = FlowLayout.TouchTarget, minHeight = FlowLayout.TouchTarget)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    interactionSource = interactionSource,
                    indication = null,
                    onValueChange = onCheckedChange
                )
                .semantics { contentDescription = title },
            contentAlignment = Alignment.CenterEnd
        ) {
            FlowSwitch(checked, enabled = enabled, pressed = pressed)
        }
    }
}

@Composable
fun FlowSwitch(checked: Boolean, enabled: Boolean = true, pressed: Boolean = false) {
    val trackW = 48f
    val trackH = 28f
    val pad = 3f
    val thumbSize = 22f
    val offLeft = pad
    val onLeft = trackW - pad - thumbSize
    val baseLeft = if (checked) onLeft else offLeft

    val leftAnim = remember { Animatable(baseLeft) }
    val rightAnim = remember { Animatable(baseLeft + thumbSize) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(checked, pressed, enabled) {
        if (!initialized) {
            leftAnim.snapTo(baseLeft)
            rightAnim.snapTo(baseLeft + thumbSize)
            initialized = true
            return@LaunchedEffect
        }
        if (pressed && enabled) {
            // Pre-stretch slightly toward the opposite side before launch.
            val stretch = thumbSize * 0.22f
            val preSpec = spring<Float>(dampingRatio = 0.85f, stiffness = 700f)
            if (!checked) {
                launch { leftAnim.animateTo(offLeft, preSpec) }
                launch { rightAnim.animateTo(offLeft + thumbSize + stretch, preSpec) }
            } else {
                launch { leftAnim.animateTo(onLeft - stretch, preSpec) }
                launch { rightAnim.animateTo(onLeft + thumbSize, preSpec) }
            }
            return@LaunchedEffect
        }

        val targetLeft = baseLeft
        val targetRight = baseLeft + thumbSize
        val currentCenter = (leftAnim.value + rightAnim.value) * 0.5f
        val targetCenter = targetLeft + thumbSize * 0.5f
        val leadSpec = spring<Float>(dampingRatio = 0.80f, stiffness = 640f)
        val trailSpec = spring<Float>(dampingRatio = 0.86f, stiffness = 235f)

        if (targetCenter >= currentCenter) {
            launch { rightAnim.animateTo(targetRight, leadSpec) }
            launch {
                delay(36)
                leftAnim.animateTo(targetLeft, trailSpec)
            }
        } else {
            launch { leftAnim.animateTo(targetLeft, leadSpec) }
            launch {
                delay(36)
                rightAnim.animateTo(targetRight, trailSpec)
            }
        }
    }

    val trackColor by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(180),
        label = "switchTrack"
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.surfaceContainerLowest,
        animationSpec = tween(160),
        label = "switchThumb"
    )

    Box(
        modifier = Modifier
            .size(width = trackW.dp, height = trackH.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .background(trackColor)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val minEdge = pad.dp.toPx()
            val maxEdge = (trackW - pad).dp.toPx()
            val restThumbPx = thumbSize.dp.toPx()
            val maxThumbPx = restThumbPx * 1.48f

            val rawL = leftAnim.value.dp.toPx().coerceIn(minEdge, maxEdge)
            val rawR = rightAnim.value.dp.toPx().coerceIn(rawL, maxEdge)
            val clampedL: Float
            val clampedR: Float
            if (rawR - rawL > maxThumbPx) {
                if (checked) {
                    clampedR = rawR.coerceIn(minEdge + maxThumbPx, maxEdge)
                    clampedL = (clampedR - maxThumbPx).coerceAtLeast(minEdge)
                } else {
                    clampedL = rawL.coerceIn(minEdge, maxEdge - maxThumbPx)
                    clampedR = (clampedL + maxThumbPx).coerceAtMost(maxEdge)
                }
            } else {
                clampedL = rawL
                clampedR = rawR
            }

            val widthPx = (clampedR - clampedL).coerceAtLeast(restThumbPx * 0.90f)
            val stretchRatio = (widthPx / restThumbPx.coerceAtLeast(1f)).coerceAtLeast(1f)
            val scaleY = 1f - ((stretchRatio - 1f) * 0.10f).coerceAtMost(0.055f)
            val heightPx = restThumbPx * scaleY
            val topPx = (size.height - heightPx) * 0.5f

            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(clampedL, topPx),
                size = Size(widthPx, heightPx),
                cornerRadius = CornerRadius(heightPx * 0.5f, heightPx * 0.5f)
            )
        }
    }
}

@Composable
fun FlowEmptyState(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun flowFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    cursorColor = MaterialTheme.colorScheme.primary
)
