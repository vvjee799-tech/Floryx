package com.flowchat.app.device

import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.domain.chat.*
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.tools.AgentToolDefinitions
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*

class AutonomyAuthorizationTest : ScrollFixtureDiagnostics() {
    @Test fun explicitRequestSurvivesRuntimeReconstructionWithoutAnotherDialog() = runBlocking {
        val fixture = AutonomyRuntimeFixture(InstrumentationRegistry.getInstrumentation().targetContext)
        var passed = false
        try {
            fixture.connect()
            fixture.forceStopMock = {
                if (fixture.forceStopCalls == 1) DeviceToolResult(false, "Synthetic transient before dispatch", errorCode = "qa_transient",
                    actionOutcome = DeviceActionOutcome.NotDispatched)
                else DeviceToolResult(true, "Synthetic app stopped", actionOutcome = DeviceActionOutcome.TargetVerified)
            }
            fixture.responder = { request, round ->
                check(request.tools.any { it.name == AgentToolDefinitions.ForceStopAppToolName })
                when (round) {
                    1, 3 -> listOf(ChatDelta.ToolCalls(listOf(ChatToolCall("qa-stop-$round", AgentToolDefinitions.ForceStopAppToolName,
                        """{"app_name":"合成应用"}"""))), ChatDelta.Done)
                    2 -> listOf(ChatDelta.Error("Synthetic transient model interruption"))
                    4 -> listOf(ChatDelta.Content("合成任务已完成。"), ChatDelta.Done)
                    else -> error("Unexpected model retry: $round")
                }
            }
            fixture.create()
            fixture.send("请强行停止合成应用，然后完成本地验收。", state = "Failed")
            val previous = requireNotNull(fixture.journal.latest(fixture.owner))
            val metadata = requireNotNull(TaskRunMetadata.decode(previous.metadataJson))
            assertEquals(TaskAuthorizationDecision.Granted, metadata.authorization.decision(AgentToolDefinitions.ForceStopAppToolName, "合成应用"))
            assertTrue(metadata.pendingEffects.isEmpty()); assertEquals(0, fixture.confirmations.size)
            fixture.rebuildRuntime()
            withContext(Dispatchers.Main) { fixture.runtime.resumeInterruptedTask() }
            fixture.await { fixture.journal.latest(fixture.owner)?.state == "Completed" && fixture.owner !in fixture.runtime.activeConversations.value }
            val resumed = requireNotNull(TaskRunMetadata.decode(fixture.journal.latest(fixture.owner)?.metadataJson))
            assertEquals(metadata.logicalTaskId, resumed.logicalTaskId)
            assertEquals(previous.id, resumed.continuedFromRunId)
            assertEquals(2, fixture.forceStopCalls); assertEquals(4, fixture.modelCalls.get()); assertEquals(0, fixture.confirmations.size)
            report("authorization reconstructed_runtime=true app_confirmations=0 manual_steps=0 synthetic_before_dispatch_recovery=true real_apps_force_stopped=0")
            passed = true
        } finally { fixture.close(passed) }
    }
}
