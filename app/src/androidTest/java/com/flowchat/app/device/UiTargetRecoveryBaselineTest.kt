package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.domain.device.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Keeps the existing indexed-action contract as the before-change evidence. */
class UiTargetRecoveryBaselineTest : ScrollFixtureDiagnostics() {
    @Test fun oneIndexedRequestCannotFinishAfterLayoutOrReadinessChanges() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val fixturePackage = instrumentation.context.packageName
        val scope = DeviceObservationScope(DeviceUiSelector(description = "r2b_scope"))
        fun command(value: String) { app.sendBroadcast(Intent("$fixturePackage.R2B_CONTROL").setPackage(fixturePackage).putExtra("command", value)) }
        fun shell(value: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(value)).bufferedReader().use { it.readText() }
        try {
            shell("am start -W -n $fixturePackage/com.flowchat.app.device.ScopedObservationFixtureActivity --ez recovery_fixture true")
            val initial = requireNotNull(gateway.observeScreen(scope).screen)
            val target = initial.elements.single { it.contentDescription == "r2b_action" }
            command("target_relayout"); delay(200)
            val start = SystemClock.elapsedRealtime()
            val result = gateway.tapUiElement(target.index, false, initial.screenId)
            assertEquals(DeviceActionOutcome.NotDispatched, result.actionOutcome)
            val final = requireNotNull(gateway.observeScreen(scope).screen)
            assertTrue(final.elements.single { it.contentDescription == "r2b_ledger" }.text!!.startsWith("writes=0;relayouts=1"))
            report("batch2b baseline=old_index completed=false known_not_dispatched=true actual_clicks=0 extra_model_relocation_required=true elapsed_ms=${SystemClock.elapsedRealtime()-start}")
        } finally { command("finish"); delay(150) }
    }
}
