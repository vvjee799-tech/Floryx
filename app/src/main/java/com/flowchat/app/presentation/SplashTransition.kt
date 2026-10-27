package com.flowchat.app.presentation

import android.animation.ValueAnimator
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.flowchat.app.ui.theme.AppAppearance
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

@Composable
fun SplashTransition(
    appAppearance: AppAppearance,
    launchReady: Boolean = true,
    content: @Composable () -> Unit
) {
    // Consume on entry so activity recreation never restarts a partially played splash.
    var splashConsumed by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var showSplash by remember {
        // ValueAnimator's cached scale may not be synchronized until the first window attaches.
        val systemScale = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
        mutableStateOf(!splashConsumed && systemScale > 0f && ValueAnimator.areAnimatorsEnabled())
    }
    val elapsed = remember { Animatable(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ready = rememberUpdatedState(launchReady)
    var splashDrawn by remember { mutableStateOf(false) }
    var prepareHome by remember { mutableStateOf(!showSplash) }
    var homeDrawn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        splashConsumed = true
        if (!showSplash) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!showSplash) return@repeatOnLifecycle
            // A Compose frame can run behind Android's starting window. Wait for
            // both the platform handoff and a drawn splash before starting its clock.
            snapshotFlow { ready.value && splashDrawn }.first { it }
            withFrameNanos { }
            if (elapsed.value < BloomSplashBrandReadyMillis) {
                elapsed.animateTo(
                    BloomSplashBrandReadyMillis.toFloat(),
                    tween((BloomSplashBrandReadyMillis - elapsed.value).roundToInt(), easing = LinearEasing)
                )
            }
            // Keep the completed flower visible while first composing the chat/DI
            // graph. Cold-start work must not consume the opening animation or fade.
            prepareHome = true
            snapshotFlow { homeDrawn }.first { it }
            withFrameNanos { }
            elapsed.animateTo(
                BloomSplashDurationMillis.toFloat(),
                tween((BloomSplashDurationMillis - elapsed.value).roundToInt().coerceAtLeast(0), easing = LinearEasing)
            )
            showSplash = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (prepareHome) {
            Box(
                (if (showSplash) Modifier.clearAndSetSemantics {} else Modifier)
                    .drawWithContent {
                        drawContent()
                        if (!homeDrawn) homeDrawn = true
                    }
            ) {
                content()
            }
        }
        if (showSplash) {
            BloomSplashContent(
                elapsedMillis = elapsed.value,
                appAppearance = appAppearance,
                modifier = Modifier.drawWithContent {
                    drawContent()
                    if (!splashDrawn) splashDrawn = true
                }.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                }
            )
        }
    }
}
