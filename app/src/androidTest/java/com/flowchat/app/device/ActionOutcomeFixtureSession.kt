package com.flowchat.app.device

import android.app.UiAutomation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.data.device.RootDeviceConnection
import com.flowchat.app.data.device.ShizukuDeviceAssistantGateway
import com.flowchat.app.data.preferences.AppSettings
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.domain.device.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*

/** Cross-UID fixture evidence is queried from the owner; event delivery is not an action ledger. */
internal class ActionOutcomeFixtureSession : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixturePackage = instrumentation.context.packageName
    private val runId = UUID.randomUUID().toString()
    private val queries = ConcurrentHashMap<String, CompletableDeferred<State>>()
    val gateway = ShizukuDeviceAssistantGateway(context, AppSettingsStore(AppSettings()), RootDeviceConnection(context))
    var windowId: Int? = null
        private set

    data class State(val acknowledged: Int, val delayed: Int, val opaque: Int, val completed: Int)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(owner: Context, intent: Intent) {
            if (intent.getStringExtra("run_id") != runId) return
            val queryId = intent.getStringExtra("query_id") ?: return
            queries[queryId]?.complete(State(intent.getIntExtra("acknowledgements", -1),
                intent.getIntExtra("delayed", -1), intent.getIntExtra("opaque", -1), intent.getIntExtra("completed", -1)))
        }
    }

    init {
        val filter = IntentFilter("${context.packageName}.ACTION_OUTCOME_FIXTURE_RESULT")
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }

    fun report(stage: String) = instrumentation.sendStatus(1, Bundle().apply {
        putString("ACTION_OUTCOME_STAGE", stage)
    })

    suspend fun launch(delayMillis: Int = 350, showProgress: Boolean = false, acknowledgeOnly: Boolean = false) {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        check(FlowChatAccessibilityService.current() != null) { "Restore the already-authorized service before this diagnostic." }
        val launch = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "am start -W --display 0 -f 0x10008000 -n $fixturePackage/${ActionOutcomeFixtureActivity::class.java.name} " +
                "--es run_id $runId --ei delay_ms $delayMillis --ez show_progress $showProgress --ez acknowledge_only $acknowledgeOnly"
        )).bufferedReader().use { it.readText() }
        assertFalse(launch, launch.contains("Error:") || launch.contains("Exception"))
        val ready = actionable("outcome_fixture_delayed")
        assertTrue(ready.asToolContent(), ready.success && ready.conditionMatched == true)
        windowId = requireNotNull(ready.screen).windowId
        assertEquals(State(0, 0, 0, 0), state())
        report("fixture-ready delay_ms=$delayMillis progress=$showProgress acknowledge_only=$acknowledgeOnly window=$windowId")
    }

    suspend fun state(): State {
        val id = UUID.randomUUID().toString()
        val reply = CompletableDeferred<State>()
        queries[id] = reply
        try {
            context.sendBroadcast(Intent("$fixturePackage.ACTION_OUTCOME_FIXTURE_QUERY").setPackage(fixturePackage)
                .putExtra("run_id", runId).putExtra("query_id", id))
            return withTimeout(3_000) { reply.await() }
        } finally { queries.remove(id) }
    }

    private suspend fun actionable(description: String) = gateway.waitForScreen(DeviceScreenWaitRequest(
        DeviceScreenCondition(packageName = fixturePackage, windowId = windowId,
            element = DeviceUiSelector(description = description), state = DeviceElementState.Actionable), 5_000))

    suspend fun action(description: String): DeviceToolResult =
        action(description) { index, id -> gateway.tapUiElement(index, false, id) }

    suspend fun action(description: String,
        dispatch: suspend (Int, String) -> DeviceToolResult): DeviceToolResult {
        repeat(3) { attempt ->
            val before = state()
            // Read-only preparation tolerates a transient system window, with a fixed deadline.
            val ready = actionable(description)
            assertTrue(ready.asToolContent(), ready.success && ready.conditionMatched == true)
            // The condition query is narrow. Dispatch from a fresh full observation, matching
            // the action's full after-observation rather than changing its result scope.
            val observed = gateway.observeScreen()
            assertTrue(observed.asToolContent(), observed.success)
            val screen = requireNotNull(observed.screen)
            assertEquals(fixturePackage, screen.packageName)
            assertEquals(windowId, screen.windowId)
            val target = screen.elements.single { it.contentDescription == description }
            val result = dispatch(target.index, screen.screenId)
            report("dispatch attempt=${attempt + 1} target=$description outcome=${result.actionOutcome} error=${result.errorCode}")
            // Only an explicit no-dispatch result permits re-localization. Unknown effects return once.
            if (result.actionOutcome != DeviceActionOutcome.NotDispatched ||
                result.errorCode !in setOf("stale_screen", "ui_target_obscured")) return result
            assertEquals("A rejected dispatch must not change the fixture ledger", before, state())
        }
        error("Fixture did not settle; all three attempts were explicitly not dispatched")
    }

    suspend fun delayedResult(timeoutMillis: Long = 5_000): DeviceToolResult {
        val result = gateway.waitForScreen(DeviceScreenWaitRequest(DeviceScreenCondition(
            packageName = fixturePackage, windowId = requireNotNull(windowId),
            element = DeviceUiSelector(description = "outcome_fixture_status", text = "Delayed action completed")), timeoutMillis))
        report("exact-result matched=${result.conditionMatched} outcome=${result.actionOutcome} error=${result.errorCode} state=${state()}")
        return result
    }

    suspend fun failureEvidence() {
        val observed = runCatching { FlowChatAccessibilityService.current()?.observeScreen() }.getOrNull()
        val evidence = if (observed?.packageName == fixturePackage) observed.asToolContent()
            else "package=${observed?.packageName} sensitive=${observed?.sensitive}"
        report("failure-evidence state=${runCatching { state() }.getOrNull()}\n$evidence")
    }

    override fun close() {
        context.unregisterReceiver(receiver)
        queries.values.forEach { it.cancel() }
    }
}
