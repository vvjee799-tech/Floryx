package com.flowchat.app.device

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.applauncher.AndroidAppLauncher
import com.flowchat.app.domain.chat.*
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real calendar writes and source receipts; a local model injects lost observations, never business results. */
class CalendarSaveRecoveryAcceptanceTest : ScrollFixtureDiagnostics() {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun arguments(sample: Int) = buildJsonObject {
        put("title", "Floryx 自动验收 B2 ${UUID.randomUUID().toString().take(8)}")
        put("begin_time", "2026-11-16T18:${(sample * 15).toString().padStart(2, '0')}:00+08:00")
        put("end_time", "2026-11-16T18:${(sample * 15 + 10).toString().padStart(2, '0')}:00+08:00")
    }.toString()
    private fun open(arguments: String) = ChatToolCall(UUID.randomUUID().toString(), PublicAppActions.Calendar,
        JsonObject(Json.parseToJsonElement(arguments).jsonObject + ("completion" to JsonPrimitive("save"))).toString())
    private fun save(arguments: String) = ChatToolCall(UUID.randomUUID().toString(), CalendarEventSave.ToolName, arguments)
    private fun result(call: ChatToolCall?) = if (call == null) listOf(ChatDelta.Content("测试日程已保存，已独立核对准确记录和开始、结束时间。"), ChatDelta.Done)
        else listOf(ChatDelta.ToolCalls(listOf(call)), ChatDelta.Done)
    private suspend fun metadata(fixture: AutonomyRuntimeFixture) = requireNotNull(TaskRunMetadata.decode(fixture.journal.latest(fixture.owner)?.metadataJson))
    private fun recordSynthetic(args: String) = CalendarSyntheticLedger.record(context, args)

    @Test fun lostClickResultsAndDelayedSourceBecomeVerifiedWithoutAnotherSave() = runBlocking {
        repeat(3) { sample ->
            val fixture = AutonomyRuntimeFixture(context)
            var passed = false
            try {
                fixture.connect(); fixture.create()
                if (sample == 1) AndroidAppLauncher(context, fixture.gateway).openApp("设置")
                val args = arguments(sample); recordSynthetic(args)
                var stage = 0; var saveCalls = 0; var actualSaves = 0; var sourceCalls = 0; var hiddenReads = 0
                fixture.calendarSourceTransform = { source ->
                    sourceCalls++
                    if (sample == 2 && actualSaves > 0 && hiddenReads++ < 2) null else source
                }
                fixture.calendarTapTransform = { original ->
                    if (original.actionOutcome != DeviceActionOutcome.NotDispatched) {
                        actualSaves++
                        if (sample != 0) original.copy(success = false, summary = "Synthetic lost click result after real dispatch",
                            errorCode = "qa_lost_result", actionOutcome = DeviceActionOutcome.Unverified) else original
                    } else original
                }
                fixture.responder = { request, _ ->
                    val call = when (stage++) {
                        0 -> open(args)
                        1 -> { saveCalls++; save(args) }
                        else -> {
                            val current = metadata(fixture)
                            val effect = current.effects.last()
                            if (effect.dispatchState == TaskEffectDispatchState.NotDispatched && saveCalls < 3) { saveCalls++; save(args) }
                            else { check(effect.proof?.resolution == TaskEffectResolution.Applied); null }
                        }
                    }
                    if (call != null) check(request.tools.any { it.name == call.name })
                    result(call)
                }
                val started = SystemClock.elapsedRealtime()
                val goal = CalendarEventSave.request(args)
                fixture.send("创建并保存测试日程：${goal.title}，2026年11月16日18:${(sample*15).toString().padStart(2,'0')}至18:${(sample*15+10).toString().padStart(2,'0')}，办完后核对。")
                val saved = metadata(fixture)
                assertTrue(saved.pendingEffects.isEmpty()); assertEquals(1, saved.effects.count { it.proof?.resolution == TaskEffectResolution.Applied })
                assertEquals(1, actualSaves); assertEquals(0, fixture.confirmations.size)
                fixture.calendarSourceTransform = null
                val rows = requireNotNull(fixture.real.inspectCalendarEvents(args)).rows
                assertEquals(1, rows.size)
                report("batch2 task=calendar scenario=$sample completed=true actual_saves=$actualSaves save_attempts=$saveCalls source_reads=$sourceCalls " +
                    "receipt=${saved.effects.last().proof?.receiptId} app_confirmations=0 manual_steps=0 elapsed_ms=${SystemClock.elapsedRealtime()-started} synthetic_title=${goal.title}")
                passed = true
            } finally { fixture.close(passed) }
        }
    }

    @Test fun persistedUncertainSaveIsReadBackAfterRuntimeReconstructionWithoutReplay() = runBlocking {
        val fixture = AutonomyRuntimeFixture(context)
        var passed = false
        try {
            fixture.connect(); fixture.create()
            val args = arguments(0); recordSynthetic(args)
            var actualSaves = 0; var phase = 0; var saveAttempts = 0
            fixture.calendarSourceTransform = { source -> if (actualSaves == 0) source else null }
            fixture.calendarTapTransform = { original ->
                if (original.actionOutcome != DeviceActionOutcome.NotDispatched) actualSaves++
                original
            }
            fixture.responder = { _, _ -> when (phase++) {
                0 -> result(open(args)); 1 -> { saveAttempts++; result(save(args)) }
                else -> if (metadata(fixture).effects.last().dispatchState == TaskEffectDispatchState.NotDispatched && saveAttempts++ < 3)
                    result(save(args)) else listOf(ChatDelta.Error("Synthetic model interruption with an uncertain saved result"))
            } }
            val started = SystemClock.elapsedRealtime()
            fixture.send("创建并保存测试日程 ${CalendarEventSave.request(args).title}，2026年11月16日18:00至18:10，并核对实际结果。", "Failed")
            val before = metadata(fixture)
            assertEquals(1, actualSaves); assertEquals(1, before.pendingEffects.size)
            val effect = before.effects.last()
            assertNull(effect.proof); assertNotNull(effect.preflightEvidence); assertNotNull(effect.preflightArguments)
            fixture.calendarSourceTransform = null
            var recoveryPhase = 0
            fixture.responder = { _, _ -> result(when (recoveryPhase++) {
                0 -> save(args) // Deliberate replay attempt must be blocked before any new UI action.
                1 -> ChatToolCall("verify-durable-calendar", TaskEffectRegistry.VerifyToolName,
                    buildJsonObject { put("effect_id", effect.effectId) }.toString())
                else -> null
            }) }
            fixture.rebuildRuntime()
            withContext(Dispatchers.Main) { fixture.runtime.resumeInterruptedTask() }
            fixture.await { fixture.journal.latest(fixture.owner)?.state == "Completed" && fixture.owner !in fixture.runtime.activeConversations.value }
            val restored = metadata(fixture)
            assertEquals(before.logicalTaskId, restored.logicalTaskId)
            assertTrue(restored.pendingEffects.isEmpty()); assertEquals(TaskEffectResolution.Applied, restored.effects.last().proof?.resolution)
            assertEquals(1, actualSaves); assertEquals(0, fixture.confirmations.size)
            assertEquals(1, requireNotNull(fixture.real.inspectCalendarEvents(args)).rows.size)
            report("batch2 task=calendar scenario=runtime_reconstruction completed=true runtime_reconstructed=true process_killed=false " +
                "actual_saves=$actualSaves prevented_replay=1 app_confirmations=0 manual_completion_steps=0 explicit_test_resume=1 elapsed_ms=${SystemClock.elapsedRealtime()-started} synthetic_title=${CalendarEventSave.request(args).title}")
            passed = true
        } finally { fixture.close(passed) }
    }
}
