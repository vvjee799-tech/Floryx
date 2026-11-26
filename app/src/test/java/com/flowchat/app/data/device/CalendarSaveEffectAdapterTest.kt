package com.flowchat.app.data.device

import com.flowchat.app.domain.chat.*
import com.flowchat.app.domain.device.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CalendarSaveEffectAdapterTest {
    private val arguments = """{"title":"receipt test","begin_time":"2026-11-15T18:00:00+08:00","end_time":"2026-11-15T18:10:00+08:00"}"""
    private val request = CalendarEventSave.request(arguments)
    private val baseline = CalendarEventReceipt(CalendarEventSave.SourceId, CalendarEventSave.fingerprint(request), 1000, emptyList())
    private val saved = baseline.copy(observedAtMillis = 2000, rows = listOf(CalendarEventRow(17, 2, request.beginTimeMillis!!, request.endTimeMillis!!)))
    private fun record(adapter: CalendarSaveEffectAdapter) = TaskEffectRecord("step", "task", "run", "step", "message", adapter.tool.name,
        TaskRecoveryGate.fingerprint(arguments), adapter.describe(arguments), TaskEffectDispatchState.Unconfirmed,
        preflightEvidence = Json.encodeToString(baseline), preflightArguments = arguments)

    @Test fun durableExactNewRecordVerifiesWithoutAnotherUiAction() = runBlocking {
        var writes = 0
        val gateway = object : DeviceAssistantGateway by UnavailableDeviceAssistantGateway {
            override suspend fun inspectCalendarEvents(arguments: String) = saved
            override suspend fun tapUiElement(index: Int, longPress: Boolean, screenId: String?): DeviceToolResult { writes++; error("No replay") }
            override suspend fun observeScreen(): DeviceToolResult = error("UI navigation is unnecessary")
        }
        val adapter = CalendarSaveEffectAdapter(gateway)
        // Recreate both the adapter and record, as after runtime reconstruction.
        val restored = Json.decodeFromString<TaskEffectRecord>(Json.encodeToString(record(adapter)))
        val proof = requireNotNull(CalendarSaveEffectAdapter(gateway).inspect(restored))
        assertEquals(TaskEffectResolution.Applied, proof.resolution)
        assertEquals("calendar:2:event:17", proof.receiptId)
        assertNotNull(TaskEffectRegistry(listOf(adapter)).accept(restored, proof, listOf(restored)))
        assertTrue(TaskEffectRegistry(listOf(adapter)).blocksReplay(adapter.describe(arguments), listOf(restored.copy(proof = proof))))
        assertEquals(0, writes)
    }

    @Test fun emptyAmbiguousChangedSourceAndOldReadsNeverProveAbsenceOrSuccess() = runBlocking {
        for (source in listOf(null, baseline.copy(observedAtMillis = 3000), saved.copy(observedAtMillis = 999),
            saved.copy(sourceId = "another-user"), saved.copy(requestSha256 = "0".repeat(64)),
            saved.copy(rows = saved.rows + saved.rows.single().copy(id = 18)))) {
            val adapter = CalendarSaveEffectAdapter(object : DeviceAssistantGateway by UnavailableDeviceAssistantGateway {
                override suspend fun inspectCalendarEvents(arguments: String) = source
            })
            assertNull(adapter.inspect(record(adapter)))
            assertFalse(adapter.supportsAuthoritativeAbsence)
        }
    }

    @Test fun missingBaselineChangedArgumentsAndUnstartedWritesCannotGetAReceipt() = runBlocking {
        val adapter = CalendarSaveEffectAdapter(object : DeviceAssistantGateway by UnavailableDeviceAssistantGateway {
            override suspend fun inspectCalendarEvents(arguments: String) = saved
        })
        val record = record(adapter)
        for (invalid in listOf(record.copy(preflightEvidence = null), record.copy(preflightArguments = arguments.replace("receipt test", "other")),
            record.copy(dispatchState = TaskEffectDispatchState.IntentSaved), record.copy(dispatchState = TaskEffectDispatchState.NotDispatched),
            record.copy(preflightEvidence = Json.encodeToString(saved)))) assertNull(adapter.inspect(invalid))
    }

    @Test fun preparationRefusesExistingMatchesOrUnavailableSourceBeforeAnySave() = runBlocking {
        for (source in listOf(null, saved)) {
            val adapter = CalendarSaveEffectAdapter(object : DeviceAssistantGateway by UnavailableDeviceAssistantGateway {
                override suspend fun inspectCalendarEvents(arguments: String) = source
            })
            var refused = false
            try { adapter.prepare(record(adapter), arguments) } catch (_: TaskEffectNotDispatched) { refused = true }
            assertTrue(refused)
        }
    }
}
