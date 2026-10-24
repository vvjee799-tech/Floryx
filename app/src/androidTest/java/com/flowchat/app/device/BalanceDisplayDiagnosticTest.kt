package com.flowchat.app.device

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.db.UsageRecord
import com.flowchat.app.data.usage.*
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.presentation.chat.UsageDisplay
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class BalanceDisplayDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun exactBalanceRefreshAndFailureNeverShowEstimates() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runUsageDiagnostics") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java).settings()
        val enabled = settings.state.value.usageTrackingEnabled
        settings.setUsageTrackingEnabled(false) // This fixture must not query a real account.
        val balance = mutableStateOf(BalanceState(BalanceStatus.Ready,
            listOf(AccountBalance("CNY", "12.34567890", "2.00000000", "10.34567890")), 1_791_000_000_000L))
        var refreshes = 0
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme {
                        UsageDisplay(listOf(UsageRecord("test", "test", "deepseek-flash", 0,
                            inputTokens = 2000, outputTokens = 200, cacheHitTokens = 1200,
                            costLowNanos = 1_624_000, costHighNanos = 3_248_000)), true,
                            balance = balance.value, onRefresh = { refreshes++; balance.value = BalanceState(BalanceStatus.Loading) })
                    }
                } }
                compose.onNodeWithText("¥12.34567890").assertIsDisplayed()
                compose.onAllNodesWithText("0.001624", substring = true).assertCountEquals(0)
                compose.onNodeWithTag("usage_summary").performClick()
                compose.onNodeWithText(context.getString(R.string.balance_breakdown, "CNY", "2.00000000", "10.34567890")).assertIsDisplayed()
                compose.onNodeWithText(context.getString(R.string.balance_refresh)).performClick()
                assertEquals(1, refreshes)
                compose.onNodeWithText(context.getString(R.string.balance_refresh)).assertIsNotEnabled()
                compose.runOnIdle { balance.value = BalanceState(BalanceStatus.Failed) }
                compose.onAllNodesWithText("12.34567890", substring = true).assertCountEquals(0)
                compose.onNodeWithText(context.getString(R.string.balance_refresh)).assertIsEnabled()
                compose.onNodeWithText(context.getString(R.string.close)).performClick()
                compose.onNodeWithText(context.getString(R.string.balance_failed)).assertIsDisplayed()
            }
        } finally { settings.setUsageTrackingEnabled(enabled) }
    }
}
