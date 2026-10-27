package com.flowchat.app.device

import android.animation.ValueAnimator
import android.app.UiModeManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.presentation.BloomSplashContent
import com.flowchat.app.presentation.BloomSplashDurationMillis
import com.flowchat.app.presentation.BloomSplashBrandReadyMillis
import com.flowchat.app.presentation.SplashTransition
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in rendering/lifecycle checks on MuMu, without editing stored app settings. */
class BloomSplashDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val arguments get() = InstrumentationRegistry.getArguments()
    private fun enabled() = assumeTrue(arguments.getString("runBloomSplashDiagnostics") == "true")

    @Test fun lightDarkSystemAndAdaptiveLayouts() {
        enabled()
        val current = mutableStateOf(Poster("light", AppAppearance.Light))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val poster = current.value
                    val configuration = Configuration(LocalConfiguration.current).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (poster.systemDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val fixtureDensity = min(constraints.maxWidth / poster.width, constraints.maxHeight / poster.height)
                        CompositionLocalProvider(
                            LocalDensity provides Density(fixtureDensity, poster.fontScale),
                            LocalConfiguration provides configuration
                        ) {
                            FlowChatTheme(poster.appearance) {
                                Box(Modifier.requiredSize(poster.width.dp, poster.height.dp).align(Alignment.Center)) {
                                    BloomSplashContent(poster.millis, poster.appearance)
                                }
                            }
                        }
                    }
                }
            }
            val posters = listOf(
                Poster("light", AppAppearance.Light),
                Poster("dark", AppAppearance.Dark),
                Poster("system-light", AppAppearance.System),
                Poster("system-dark", AppAppearance.System, systemDark = true),
                Poster("small-large-text", AppAppearance.Light, width = 320f, fontScale = 2f),
                Poster("landscape", AppAppearance.Light, width = 640f, height = 320f),
                Poster("landscape-large-text", AppAppearance.Dark, width = 640f, height = 320f, fontScale = 2f)
            )
            for (poster in posters) {
                compose.runOnIdle { current.value = poster }
                compose.waitForIdle()
                val surface = compose.onNodeWithTag("bloom_splash", useUnmergedTree = true)
                val bounds = surface.fetchSemanticsNode().boundsInRoot
                val bitmap = surface.captureToImage().asAndroidBitmap()
                val expected = if (poster.appearance == AppAppearance.Dark ||
                    poster.appearance == AppAppearance.System && poster.systemDark) 0x24231F else 0xF9F7F2
                val actual = bitmap.getPixel(2, bitmap.height / 2)
                for (shift in listOf(0, 8, 16)) assertTrue("${poster.name}: paper background matches theme",
                    abs((actual shr shift and 255) - (expected shr shift and 255)) <= 1)
                save(bitmap, poster.name)
                for (tag in listOf("bloom_wordmark", "bloom_tagline")) {
                    val text = compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
                    val textBounds = text.fetchSemanticsNode().boundsInRoot
                    assertTrue("${poster.name}: $tag must fit horizontally", textBounds.left >= bounds.left - 1 && textBounds.right <= bounds.right + 1)
                    assertTrue("${poster.name}: $tag must fit vertically", textBounds.top >= bounds.top - 1 && textBounds.bottom <= bounds.bottom + 1)
                    val layouts = mutableListOf<TextLayoutResult>()
                    text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertTrue("Text layout result is available", layouts.isNotEmpty())
                    assertFalse("${poster.name}: $tag must not clip its text: " + layouts.joinToString {
                        "size=${it.size}, widthOverflow=${it.didOverflowWidth}, heightOverflow=${it.didOverflowHeight}, lineWidth=${it.getLineRight(0)-it.getLineLeft(0)}, lineBottom=${it.getLineBottom(0)}"
                    }, layouts.any { it.hasVisualOverflow })
                }
            }
            compose.runOnIdle { current.value = Poster("opening", AppAppearance.Light, millis = 260f) }
            compose.waitForIdle()
            save(compose.onNodeWithTag("bloom_splash").captureToImage().asAndroidBitmap(), "opening")
        }
    }

    @Test fun overlayBlocksTouchesThenReleasesHomeAndDoesNotReplayOnThemeChange() {
        enabled()
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        compose.mainClock.autoAdvance = false
        var clicks = 0
        val appearance = mutableStateOf(AppAppearance.Light)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    FlowChatTheme(appearance.value) {
                        SplashTransition(appearance.value) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Button(onClick = { clicks++ }, modifier = Modifier.testTag("splash_home")) { Text("Home") }
                            }
                        }
                    }
                }
            }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("bloom_splash").assertExists()
            compose.onNodeWithTag("splash_home").assertDoesNotExist()
            compose.onRoot(useUnmergedTree = true).performTouchInput { click(center) }
            compose.runOnIdle { assertEquals("The hidden home must not receive startup touches", 0, clicks) }
            compose.mainClock.advanceTimeBy(BloomSplashBrandReadyMillis + 100L)
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(BloomSplashDurationMillis - BloomSplashBrandReadyMillis + 100L)
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("bloom_splash").assertDoesNotExist()
            compose.onNodeWithTag("splash_home").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(1, clicks); appearance.value = AppAppearance.Dark }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("bloom_splash").assertDoesNotExist()
        }
    }

    @Test fun nativeHandoffAndSlowHomeDoNotConsumeOpeningAnimation() {
        enabled()
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        compose.mainClock.autoAdvance = false
        val launchReady = mutableStateOf(false)
        var homeCreated = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    FlowChatTheme(AppAppearance.Light) {
                        SplashTransition(AppAppearance.Light, launchReady = launchReady.value) {
                            if (!homeCreated) {
                                homeCreated = true
                                // Reproduce the cold home/DI work that used to consume the bloom.
                                Thread.sleep(400)
                            }
                            Box(Modifier.fillMaxSize().testTag("slow_splash_home"))
                        }
                    }
                }
            }
            compose.mainClock.advanceTimeBy(2_000L)
            compose.waitForIdle()
            compose.onNodeWithTag("bloom_splash").assertExists()
            compose.runOnIdle { assertFalse("Home must not delay the first splash frame", homeCreated) }
            compose.runOnIdle { launchReady.value = true }
            compose.mainClock.advanceTimeBy(400L)
            compose.waitForIdle()
            compose.runOnIdle { assertFalse("The opening must finish before heavy home work starts", homeCreated) }
            compose.mainClock.advanceTimeBy(500L)
            compose.waitForIdle()
            compose.runOnIdle { assertTrue("Home is prepared behind the completed brand", homeCreated) }
            compose.onNodeWithTag("bloom_splash").assertExists()
            val flower = compose.onNodeWithTag("bloom_flower", useUnmergedTree = true).captureToImage().asAndroidBitmap()
            try {
                assertTrue("The flower remains painted after slow home initialization", (0 until flower.width).any { x ->
                    (0 until flower.height).any { y ->
                        val pixel = flower.getPixel(x, y)
                        (pixel shr 16 and 255) - (pixel shr 8 and 255) > 20
                    }
                })
            } finally { flower.recycle() }
            compose.mainClock.advanceTimeBy(500L)
            compose.waitForIdle()
            compose.onNodeWithTag("bloom_splash").assertDoesNotExist()
            compose.onNodeWithTag("slow_splash_home").assertIsDisplayed()
        }
    }

    @Test fun activityRecreationDoesNotReplayAnInFlightSplash() {
        enabled()
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("bloom_splash").assertExists()
            compose.runOnIdle { }
            scenario.recreate()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("bloom_splash").assertDoesNotExist()
        }
    }

    @Test fun openingClockPausesWhileActivityIsInTheBackground() {
        enabled()
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        compose.mainClock.autoAdvance = false
        var homeCreated = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    FlowChatTheme(AppAppearance.Light) {
                        SplashTransition(AppAppearance.Light) {
                            homeCreated = true
                            Box(Modifier.fillMaxSize().testTag("resumed_splash_home"))
                        }
                    }
                }
            }
            compose.mainClock.advanceTimeBy(200L)
            compose.waitForIdle()
            scenario.moveToState(Lifecycle.State.STARTED)
            compose.mainClock.advanceTimeBy(2_000L)
            compose.waitForIdle()
            compose.runOnIdle { assertFalse("Background time must not consume the opening", homeCreated) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.mainClock.advanceTimeBy(250L)
            compose.waitForIdle()
            compose.runOnIdle { assertFalse("Resume must continue the remaining opening", homeCreated) }
            compose.mainClock.advanceTimeBy(650L)
            compose.waitForIdle()
            compose.runOnIdle { assertTrue(homeCreated) }
            compose.mainClock.advanceTimeBy(500L)
            compose.waitForIdle()
            compose.onNodeWithTag("bloom_splash").assertDoesNotExist()
            compose.onNodeWithTag("resumed_splash_home").assertIsDisplayed()
        }
    }

    @Test fun disabledSystemAnimationsSkipSplash() {
        enabled()
        assumeTrue(arguments.getString("runBloomNoMotionDiagnostics") == "true")
        assertEquals("Run this check with animator_duration_scale=0", 0f,
            Settings.Global.getFloat(InstrumentationRegistry.getInstrumentation().targetContext.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f), 0f)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("bloom_splash").assertDoesNotExist()
        }
    }

    @Test fun nativeStartingThemeTracksLightDarkAndFollowSystem() {
        enabled()
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = AppAppearance.load(context)
        fun assertMode(appearance: AppAppearance, night: Int) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                AppAppearance.syncStartingTheme(context, appearance)
            }
            compose.waitUntil(5_000) {
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == night
            }
            val expected = if (night == Configuration.UI_MODE_NIGHT_YES) 0xFF24231F.toInt() else 0xFFF9F7F2.toInt()
            assertEquals("Native launch background must match $appearance", expected, context.getColor(R.color.splash_background))
        }
        try {
            assertMode(AppAppearance.Light, Configuration.UI_MODE_NIGHT_NO)
            assertMode(AppAppearance.Dark, Configuration.UI_MODE_NIGHT_YES)
            val systemNight = when (context.getSystemService(UiModeManager::class.java).nightMode) {
                UiModeManager.MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
                UiModeManager.MODE_NIGHT_NO -> Configuration.UI_MODE_NIGHT_NO
                else -> Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            }
            assertMode(AppAppearance.System, systemNight)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                AppAppearance.syncStartingTheme(context, original)
            }
        }
    }

    private fun save(bitmap: Bitmap, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "bloom-splash").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private data class Poster(
        val name: String, val appearance: AppAppearance,
        val systemDark: Boolean = false,
        val width: Float = 360f, val height: Float = 640f,
        val fontScale: Float = 1f, val millis: Float = 740f
    )
}
