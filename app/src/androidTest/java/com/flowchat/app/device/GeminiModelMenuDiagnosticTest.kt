package com.flowchat.app.device

import android.app.UiAutomation
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.domain.model.Conversation
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.presentation.chat.MessageComposer
import com.flowchat.app.presentation.chat.ProviderModelOptions
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic menu fixtures: no account calls, settings changes, or saved provider changes. */
class GeminiModelMenuDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun loadingFailureRetryAndSelectionWorkWithLargeTextAcrossThemes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runGeminiConnectionDiagnostics") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val provider = ProviderConfig(id = "gemini-menu-fixture", displayName = "Gemini", baseUrl = "https://generativelanguage.googleapis.com/v1beta", defaultModel = "gemini-3.5-flash")
        val conversation = Conversation(providerId = provider.id, modelName = provider.defaultModel)
        val theme = mutableStateOf(AppAppearance.Light)
        val options = mutableStateOf<Map<String, ProviderModelOptions>>(emptyMap())
        val density = context.resources.displayMetrics.widthPixels / 375f
        var opens = 0
        var selected = ""
        val directory = File(context.getExternalFilesDir(null), "gemini-connection").apply { mkdirs() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                FlowChatTheme(theme.value) {
                    CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.BottomCenter) {
                            MessageComposer("", null, false, true, false, listOf(provider), conversation,
                                {}, {}, {}, {}, {}, {}, {}, { _, model -> selected = model },
                                providerModelOptions = options.value, onOpenModelOptions = { opens++ })
                        }
                    }
                }
            } }
            for (appearance in listOf(AppAppearance.Light, AppAppearance.Dark)) {
                compose.runOnIdle { theme.value = appearance; options.value = mapOf(provider.id to ProviderModelOptions.Loading) }
                val before = opens
                compose.onNodeWithTag("model-switch").assertIsDisplayed().performClick()
                compose.onNodeWithText(context.getString(R.string.provider_models_loading, "Gemini")).assertIsDisplayed()
                compose.runOnIdle { options.value = mapOf(provider.id to ProviderModelOptions.Failed("Synthetic network failure")) }
                compose.onNodeWithTag("model-list-retry").assertIsDisplayed().performClick()
                assertEquals(before + 2, opens)
                compose.runOnIdle { options.value = mapOf(provider.id to ProviderModelOptions.Ready(listOf("gemini-3.5-flash", "gemini-3.8-flash"))) }
                compose.onNodeWithText("gemini-3.8-flash").performScrollTo().assertIsDisplayed()
                instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()?.let { bitmap ->
                    File(directory, "model-menu-${appearance.name}-large-text.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                compose.onNodeWithText("gemini-3.8-flash").performClick()
                assertEquals("gemini-3.8-flash", selected)
            }
        }
        File(directory, "model-menu-layout.txt").writeText("PASS: loading, failure, retry, and selection; Light/Dark; 375dp viewport; 2x font scale\n")
    }
}
