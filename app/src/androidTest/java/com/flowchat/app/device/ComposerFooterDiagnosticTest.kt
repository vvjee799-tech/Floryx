package com.flowchat.app.device

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.db.UsageRecord
import com.flowchat.app.data.usage.*
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.domain.model.*
import com.flowchat.app.presentation.chat.MessageComposer
import com.flowchat.app.ui.theme.*
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ComposerFooterDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun integratedFooterThemesDetailsAndModelPicker() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runUsageDiagnostics") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java).settings()
        val original = settings.state.value.usageTrackingEnabled
        settings.setUsageTrackingEnabled(false)
        val appearance = mutableStateOf(AppAppearance.Light)
        val show = mutableStateOf(true)
        val narrow = mutableStateOf(false)
        val amount = mutableStateOf("12.3456")
        val provider = ProviderConfig(id = "footer-fixture", displayName = "Local fixture", baseUrl = "http://127.0.0.1", defaultModel = "deepseek-v4-flash")
        val conversation = Conversation(providerId = provider.id, modelName = provider.defaultModel)
        var selections = 0
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    FlowChatTheme(appearance.value) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.BottomCenter) {
                            Box(if (narrow.value) Modifier.width(320.dp) else Modifier.fillMaxWidth()) {
                                MessageComposer("", null, false, true, false, listOf(provider), conversation,
                                    {}, {}, {}, {}, {}, {}, {}, { _, _ -> selections++ },
                                    usageRecords = if (show.value) listOf(UsageRecord("fixture", conversation.id, provider.defaultModel, 0,
                                        inputTokens = 2000, outputTokens = 200, cacheHitTokens = 1200)) else null,
                                    balanceState = BalanceState(BalanceStatus.Ready,
                                        listOf(AccountBalance("CNY", amount.value, "0", amount.value)), 1_791_000_000_000L))
                            }
                        }
                    }
                } }
                for (theme in listOf(AppAppearance.Light, AppAppearance.Dark)) {
                    compose.runOnIdle { appearance.value = theme }
                    compose.onNodeWithTag("composer_usage_footer").assertIsDisplayed()
                    val footer = compose.onNodeWithTag("composer_usage_footer").fetchSemanticsNode().boundsInRoot
                    val panel = compose.onNodeWithTag("message_composer_panel").fetchSemanticsNode().boundsInRoot
                    assertTrue(footer.bottom <= panel.bottom && footer.top > panel.top)
                    compose.onNodeWithTag("message_composer_panel").captureToImage().asAndroidBitmap().let { bitmap ->
                        File(context.getExternalFilesDir(null), "composer-footer-${theme.name}.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                }
                compose.onNodeWithTag("usage_summary").performClick()
                compose.onNodeWithText(context.getString(R.string.usage_title)).assertIsDisplayed()
                compose.onNodeWithText(context.getString(R.string.close)).performClick()
                compose.onNodeWithContentDescription(context.getString(R.string.switch_model)).performClick()
                compose.onAllNodesWithText(provider.defaultModel).onLast().performClick()
                assertEquals(1, selections)
                compose.runOnIdle { narrow.value = true; amount.value = "12345.6789012345" }
                compose.onNodeWithTag("usage_summary").performClick()
                compose.onNodeWithText(context.getString(R.string.balance_amount, "CNY 12345.6789012345")).assertIsDisplayed()
                compose.onNodeWithText(context.getString(R.string.close)).performClick()
                compose.runOnIdle { show.value = false }
                compose.onNodeWithTag("composer_usage_footer").assertDoesNotExist()
                compose.onNodeWithContentDescription(context.getString(R.string.switch_model)).assertIsDisplayed()
            }
        } finally { settings.setUsageTrackingEnabled(original) }
    }
}
