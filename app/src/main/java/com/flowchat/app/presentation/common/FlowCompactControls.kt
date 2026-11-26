package com.flowchat.app.presentation.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowchat.app.R
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun FlowStatusRow(title: String, status: String, modifier: Modifier = Modifier,
    ready: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(status, style = MaterialTheme.typography.bodyMedium,
            color = when {
                ready -> MaterialTheme.colorScheme.tertiary
                onClick != null && status.isNotBlank() -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            })
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,
            stringResource(R.string.open), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Multi-stop segmented control with dual-edge elastic motion:
 * leading edge launches ahead on takeoff and trailing edge catches up on landing,
 * clamped to at most 2 segment widths so wide jumps never over-stretch across the track.
 */
@Composable
internal fun FlowSegmentedChoice(labels: List<String>, selected: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier) {
    val count = labels.size.coerceAtLeast(1)
    val safeSelected = selected.coerceIn(0, count - 1)
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val latestOnSelect by rememberUpdatedState(onSelect)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(4.dp)
    ) {
        val innerWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val segmentWidth = innerWidthPx / count
        val targetLeft = safeSelected * segmentWidth
        val targetRight = targetLeft + segmentWidth

        val leftAnim = remember { Animatable(targetLeft) }
        val rightAnim = remember { Animatable(targetRight) }
        var initialized by remember { mutableStateOf(false) }
        var lastCount by remember { mutableIntStateOf(count) }
        var lastWidthPx by remember { mutableFloatStateOf(innerWidthPx) }
        var isDragging by remember { mutableStateOf(false) }

        LaunchedEffect(safeSelected, count, innerWidthPx) {
            if (!initialized || count != lastCount || abs(innerWidthPx - lastWidthPx) > 1f) {
                leftAnim.snapTo(targetLeft)
                rightAnim.snapTo(targetRight)
                initialized = true
                lastCount = count
                lastWidthPx = innerWidthPx
                return@LaunchedEffect
            }
            if (isDragging) return@LaunchedEffect

            val currentCenter = (leftAnim.value + rightAnim.value) * 0.5f
            val targetCenter = (targetLeft + targetRight) * 0.5f
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

        val scrubModifier = if (count > 1) {
            Modifier.pointerInput(count, innerWidthPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val touchSlop = viewConfiguration.touchSlop
                    var totalDx = 0f
                    var startedDrag = false
                    drag(down.id) { change ->
                        val dx = change.position.x - change.previousPosition.x
                        totalDx += dx
                        if (!startedDrag && abs(totalDx) > touchSlop) {
                            startedDrag = true
                            isDragging = true
                        }
                        if (startedDrag) {
                            change.consume()
                            val clampedLeft = (change.position.x - segmentWidth * 0.5f)
                                .coerceIn(0f, (innerWidthPx - segmentWidth).coerceAtLeast(0f))
                            val clampedRight = clampedLeft + segmentWidth
                            val movingRight = clampedLeft + segmentWidth * 0.5f >= (leftAnim.value + rightAnim.value) * 0.5f
                            val leadSpec = spring<Float>(dampingRatio = 0.86f, stiffness = 920f)
                            val trailSpec = spring<Float>(dampingRatio = 0.86f, stiffness = 295f)
                            scope.launch {
                                if (movingRight) rightAnim.animateTo(clampedRight, leadSpec)
                                else rightAnim.animateTo(clampedRight, trailSpec)
                            }
                            scope.launch {
                                if (movingRight) leftAnim.animateTo(clampedLeft, trailSpec)
                                else leftAnim.animateTo(clampedLeft, leadSpec)
                            }
                        }
                    }
                    if (startedDrag) {
                        isDragging = false
                        val center = (leftAnim.value + rightAnim.value) * 0.5f
                        val snappedIdx = (center / segmentWidth).toInt().coerceIn(0, count - 1)
                        val finalLeft = snappedIdx * segmentWidth
                        val finalRight = finalLeft + segmentWidth
                        val movingRight = finalLeft + segmentWidth * 0.5f >= center
                        val leadSpec = spring<Float>(dampingRatio = 0.80f, stiffness = 640f)
                        val trailSpec = spring<Float>(dampingRatio = 0.86f, stiffness = 235f)
                        scope.launch {
                            if (movingRight) rightAnim.animateTo(finalRight, leadSpec)
                            else leftAnim.animateTo(finalLeft, leadSpec)
                        }
                        scope.launch {
                            delay(36)
                            if (movingRight) leftAnim.animateTo(finalLeft, trailSpec)
                            else rightAnim.animateTo(finalRight, trailSpec)
                        }
                        if (snappedIdx != safeSelected) {
                            latestOnSelect(snappedIdx)
                        }
                    }
                }
            }
        } else Modifier

        // Sliding indicator pill constrained strictly to <= 2 segment widths.
        val rawLeft = leftAnim.value
        val rawRight = rightAnim.value
        val maxSpanWidth = minOf(
            segmentWidth * 1.72f,
            segmentWidth * 2.0f,
            innerWidthPx * if (count <= 2) 0.74f else 0.85f
        ).coerceAtLeast(segmentWidth)
        val rawWidth = rawRight - rawLeft
        val clampedLeft: Float
        val clampedRight: Float
        if (rawWidth > maxSpanWidth) {
            val movingRight = rawRight - targetRight < targetLeft - rawLeft ||
                (rawLeft + rawRight) * 0.5f <= (targetLeft + targetRight) * 0.5f
            if (movingRight) {
                clampedRight = rawRight.coerceIn(maxSpanWidth, innerWidthPx)
                clampedLeft = (clampedRight - maxSpanWidth).coerceAtLeast(0f)
            } else {
                clampedLeft = rawLeft.coerceIn(0f, (innerWidthPx - maxSpanWidth).coerceAtLeast(0f))
                clampedRight = (clampedLeft + maxSpanWidth).coerceAtMost(innerWidthPx)
            }
        } else {
            clampedLeft = rawLeft.coerceIn(0f, innerWidthPx)
            clampedRight = rawRight.coerceIn(clampedLeft, innerWidthPx)
        }
        val pillWidthPx = (clampedRight - clampedLeft).coerceAtLeast(segmentWidth * 0.90f)
        val stretchRatio = (pillWidthPx / segmentWidth.coerceAtLeast(1f)).coerceAtLeast(1f)
        val pillScaleY = 1f - ((stretchRatio - 1f) * 0.10f).coerceAtMost(0.055f)
        val pillWidthDp = with(density) { pillWidthPx.toDp() }

        Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min).then(scrubModifier)) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = RoundedCornerShape(10.dp),
                shadowElevation = 1.dp,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(pillWidthDp)
                    .offset { IntOffset(clampedLeft.roundToInt(), 0) }
                    .graphicsLayer { scaleY = pillScaleY }
            ) {}

            Row(Modifier.fillMaxWidth()) {
                labels.forEachIndexed { index, label ->
                    val isSelected = index == safeSelected
                    val textColor by animateColorAsState(
                        if (isSelected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        tween(140),
                        label = "segText"
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(index) }
                            )
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

internal data class FlowBinaryOption(
    val label: String,
    val description: String? = null,
    val icon: ImageVector,
    val sageTone: Boolean = false,
    val testTag: String? = null
)

/**
 * Parallel non-slider binary selector using Scheme B ("印章染块"):
 * radial ink wash from the tap point, icon micro-hop, and living corner accent dot.
 */
@Composable
internal fun FlowBinaryChoice(
    options: List<FlowBinaryOption>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selected
            val compact = option.description == null
            val cardShape = RoundedCornerShape(if (compact) 12.dp else 14.dp)
            var tapPoint by remember { mutableStateOf<Offset?>(null) }

            val washProgress = remember { Animatable(if (isSelected) 1f else 0f) }
            val iconScale = remember { Animatable(1f) }
            val iconRotate = remember { Animatable(0f) }

            LaunchedEffect(isSelected) {
                if (isSelected) {
                    launch {
                        washProgress.snapTo(0f)
                        washProgress.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
                    }
                    launch {
                        iconScale.snapTo(0.88f)
                        iconScale.animateTo(1.15f, tween(130, easing = FastOutSlowInEasing))
                        iconScale.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = 500f))
                    }
                    launch {
                        iconRotate.snapTo(-10f)
                        iconRotate.animateTo(5f, tween(130, easing = FastOutSlowInEasing))
                        iconRotate.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f))
                    }
                } else {
                    washProgress.animateTo(0f, tween(180))
                }
            }

            val washColor = if (option.sageTone) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.primaryContainer
            val accentColor = if (option.sageTone) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.primary
            val borderColor by animateColorAsState(
                if (isSelected) accentColor.copy(alpha = 0.28f) else Color.Transparent,
                tween(180),
                label = "binaryBorder"
            )
            val iconColor by animateColorAsState(
                if (isSelected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                tween(180),
                label = "binaryIcon"
            )
            val liftDp by animateDpAsState(
                if (isSelected) (-1).dp else 0.dp,
                spring(dampingRatio = 0.8f, stiffness = 520f),
                label = "binaryLift"
            )
            val dotScale by animateFloatAsState(
                if (isSelected) 1f else 0f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 500f),
                label = "binaryDot"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = if (compact) 48.dp else 60.dp)
                    .offset(y = liftDp)
                    .then(if (option.testTag != null) Modifier.testTag(option.testTag) else Modifier)
                    .clip(cardShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer, cardShape)
                    .border(1.dp, borderColor, cardShape)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            tapPoint = down.position
                        }
                    }
                    .drawBehind {
                        val progress = washProgress.value
                        if (progress > 0.001f) {
                            val origin = tapPoint ?: Offset(size.width * 0.5f, size.height * 0.5f)
                            val maxRadius = maxOf(
                                hypot(origin.x, origin.y),
                                hypot(size.width - origin.x, origin.y),
                                hypot(origin.x, size.height - origin.y),
                                hypot(size.width - origin.x, size.height - origin.y)
                            )
                            drawCircle(
                                color = washColor,
                                radius = maxRadius * progress,
                                center = origin,
                                alpha = progress.coerceIn(0f, 1f)
                            )
                        }
                    }
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(index) }
                    )
                    .padding(horizontal = 12.dp, vertical = if (compact) 10.dp else 11.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = if (compact) Alignment.CenterVertically else Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = option.icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier
                            .padding(top = if (compact) 0.dp else 2.dp)
                            .size(if (compact) 18.dp else 19.dp)
                            .graphicsLayer {
                                scaleX = iconScale.value
                                scaleY = iconScale.value
                                rotationZ = iconRotate.value
                            }
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (option.description != null) {
                            Text(
                                text = option.description,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, lineHeight = 15.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .padding(top = if (compact) 0.dp else 5.dp)
                            .size(7.dp)
                            .graphicsLayer {
                                scaleX = dotScale
                                scaleY = dotScale
                                alpha = dotScale.coerceIn(0f, 1f)
                            }
                            .background(accentColor, CircleShape)
                    )
                }
            }
        }
    }
}
