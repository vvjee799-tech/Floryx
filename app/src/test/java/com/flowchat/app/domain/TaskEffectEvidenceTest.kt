package com.flowchat.app.domain

import com.flowchat.app.domain.chat.TaskEffectAdapter
import com.flowchat.app.domain.chat.TaskEffectDispatchState
import com.flowchat.app.domain.chat.TaskEffectIntent
import com.flowchat.app.domain.chat.TaskEffectProof
import com.flowchat.app.domain.chat.TaskEffectRecord
import com.flowchat.app.domain.chat.TaskEffectRegistry
import com.flowchat.app.domain.chat.TaskEffectResolution
import com.flowchat.app.domain.chat.TaskRecoveryGate
import com.flowchat.app.domain.device.CalendarEventSave
import com.flowchat.app.domain.device.CalendarTimeEdit
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.model.ChatToolDefinition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TaskEffectEvidenceTest {
    private val intent = TaskEffectIntent(
        adapterId = "fixture.message", adapterVersion = 1, kind = "submit_message",
        sourceId = "fixture:messages", targetId = "conversation:alice",
        contentSha256 = TaskEffectRegistry.contentSha256("synthetic message")
    )

    @Test fun contentHashKeepsTheExactSubmittedBytesEvenWhenContentLooksLikeJson() {
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            TaskEffectRegistry.contentSha256("hello"))
        val first = """{"a":1,"b":2}"""
        val reordered = """{"b":2,"a":1}"""
        assertEquals(TaskRecoveryGate.fingerprint(first), TaskRecoveryGate.fingerprint(reordered))
        assertNotEquals(TaskEffectRegistry.contentSha256(first), TaskEffectRegistry.contentSha256(reordered))
        assertNotEquals(TaskEffectRegistry.contentSha256("hello"), TaskEffectRegistry.contentSha256("hello\n"))
        assertNotEquals(TaskEffectRegistry.contentSha256("\u00e9"), TaskEffectRegistry.contentSha256("e\u0301"))
    }

    @Test fun exactIntentIdentityIncludesAdapterVersionKindSourceTargetAndContent() {
        val restored = Json.decodeFromString<TaskEffectIntent>(Json.encodeToString(intent))
        assertEquals(intent, restored)
        assertEquals(intent.fingerprint(), restored.fingerprint())
        assertTrue(intent.fingerprint().matches(Regex("[0-9a-f]{64}")))
        changedIntents().forEach { changed ->
            assertNotEquals(changed.toString(), intent, changed)
            assertNotEquals(changed.toString(), intent.fingerprint(), changed.fingerprint())
        }
    }

    @Test fun intentRejectsBlankUnboundedOrMalformedIdentityBeforeItCanBeDescribed() {
        val invalid = listOf(
            intent.copy(adapterId = " "), intent.copy(adapterId = "a".repeat(513)),
            intent.copy(adapterVersion = 0), intent.copy(adapterVersion = -1),
            intent.copy(kind = ""), intent.copy(kind = "k".repeat(513)),
            intent.copy(sourceId = "\t"), intent.copy(sourceId = "s".repeat(513)),
            intent.copy(targetId = ""), intent.copy(targetId = "t".repeat(513)),
            intent.copy(contentSha256 = ""), intent.copy(contentSha256 = "a".repeat(63)),
            intent.copy(contentSha256 = "a".repeat(65)),
            intent.copy(contentSha256 = "A".repeat(64)), intent.copy(contentSha256 = "g".repeat(64))
        )
        invalid.forEach { assertFalse(it.toString(), it.valid()) }
        assertTrue(intent.copy(adapterId = "a".repeat(512), kind = "k".repeat(512),
            sourceId = "s".repeat(512), targetId = "t".repeat(512), contentSha256 = "0".repeat(64)).valid())
    }

    @Test fun productionRegistryStartsEmptyAndUnknownToolsCannotInventEffects() {
        val registry = TaskEffectRegistry()
        assertTrue(registry.adapters.isEmpty())
        assertTrue(registry.tools().isEmpty())
        assertNull(registry.adapter("task_effect_fixture_message"))
        assertNull(registry.describe(ChatToolCall("call", "task_effect_unregistered", "{}")))
        assertNull(TaskEffectRegistry(listOf(adapter())).describe(ChatToolCall("call", "unknown", "{}")))
    }

    @Test fun duplicateAdapterIdsAndDuplicateToolNamesAreRejectedAtRegistration() {
        rejectsArgument { TaskEffectRegistry(listOf(adapter(), adapter(toolName = "task_effect_alias"))) }
        rejectsArgument { TaskEffectRegistry(listOf(adapter(), adapter(id = "fixture.other"))) }
        val other = adapter(id = "fixture.other", toolName = "task_effect_other")
        assertEquals(2, TaskEffectRegistry(listOf(adapter(), other)).adapters.size)
    }

    @Test fun registrationRequiresARealAdapterVersionAndDedicatedToolNamespace() {
        rejectsArgument { TaskEffectRegistry(listOf(adapter(id = " "))) }
        rejectsArgument { TaskEffectRegistry(listOf(adapter(version = 0))) }
        rejectsArgument { TaskEffectRegistry(listOf(adapter(version = -1))) }
        rejectsArgument { TaskEffectRegistry(listOf(adapter(toolName = "send_message"))) }
        rejectsArgument { TaskEffectRegistry(listOf(adapter(toolName = TaskEffectRegistry.VerifyToolName))) }
    }

    @Test fun describeCannotReturnAnotherAdapterIdentityOrMalformedIntent() {
        listOf(intent.copy(adapterId = "another.adapter"), intent.copy(adapterVersion = 2),
            intent.copy(targetId = ""), intent.copy(contentSha256 = "not-a-hash")).forEach { invalid ->
            val registry = TaskEffectRegistry(listOf(adapter(describedIntent = invalid)))
            rejectsArgument { registry.describe(ChatToolCall("call", "task_effect_fixture_message", "{}")) }
        }
        assertEquals(intent, TaskEffectRegistry(listOf(adapter())).describe(
            ChatToolCall("call", "task_effect_fixture_message", "{}")))
    }

    @Test fun verificationToolIsReadOnlyAndOnlyAppearsWithRegisteredEffectTools() {
        val registry = TaskEffectRegistry(listOf(adapter(), adapter(id = "fixture.other",
            toolName = "task_effect_other")))
        assertEquals(listOf("task_effect_fixture_message", "task_effect_other", TaskEffectRegistry.VerifyToolName),
            registry.tools().map { it.name })
        assertTrue(TaskRecoveryGate.isReadOnly(TaskEffectRegistry.VerifyToolName))
        assertFalse(TaskRecoveryGate.isReadOnly("task_effect_fixture_message"))
    }

    @Test fun acceptsOnlyAnExactReceiptForTheSavedOperation() {
        val registry = TaskEffectRegistry(listOf(adapter()))
        val record = record()
        val proof = proof(record)
        assertEquals(proof, registry.accept(record, proof, listOf(record)))
        assertNull(registry.accept(record, null, listOf(record)))
        listOf("", "effect:unknown", "effect:another-operation").forEach { token ->
            assertNull(token, registry.accept(record, proof.copy(effectId = token), listOf(record)))
        }
    }

    @Test fun receiptForAnotherTargetContentSourceKindAdapterOrVersionNeverVerifiesTheRecord() {
        val registry = TaskEffectRegistry(listOf(adapter()))
        val record = record()
        changedIntents().forEach { changed ->
            assertNull(changed.toString(), registry.accept(record, proof(record).copy(intent = changed), listOf(record)))
        }
    }

    @Test fun unknownOrStaleRegisteredAdapterCannotVerifyEvenAnOtherwiseMatchingReceipt() {
        val record = record()
        val proof = proof(record)
        assertNull(TaskEffectRegistry().accept(record, proof, listOf(record)))
        assertNull(TaskEffectRegistry(listOf(adapter(version = 2))).accept(record, proof, listOf(record)))
        assertNull(TaskEffectRegistry(listOf(adapter(id = "replacement.adapter"))).accept(record, proof, listOf(record)))
        val unknownTool = record.copy(tool = "task_effect_unknown")
        assertNull(TaskEffectRegistry(listOf(adapter())).accept(unknownTool, proof(unknownTool), listOf(unknownTool)))
    }

    @Test fun rejectsMissingUnboundedOrUnresolvedReceiptEvidence() {
        val registry = TaskEffectRegistry(listOf(adapter()))
        val record = record()
        val proof = proof(record)
        listOf(
            proof.copy(receiptId = ""), proof.copy(receiptId = " "), proof.copy(receiptId = "r".repeat(513)),
            proof.copy(sourceRevision = ""), proof.copy(sourceRevision = "\t"),
            proof.copy(sourceRevision = "v".repeat(513)), proof.copy(observedAtMillis = -1),
            proof.copy(resolution = TaskEffectResolution.Unverified),
            proof.copy(intent = intent.copy(contentSha256 = "bad"))
        ).forEach { invalid -> assertNull(invalid.toString(), registry.accept(record, invalid, listOf(record))) }
        val boundary = proof.copy(receiptId = "r".repeat(512), sourceRevision = "v".repeat(512), observedAtMillis = 0)
        assertEquals(boundary, registry.accept(record, boundary, listOf(record)))
    }

    @Test fun malformedSavedOperationReferencesCannotAcquireAReceipt() {
        val registry = TaskEffectRegistry(listOf(adapter()))
        val saved = record()
        val invalid = listOf(
            saved.copy(effectId = ""), saved.copy(effectId = "e".repeat(513)),
            saved.copy(logicalTaskId = " "), saved.copy(logicalTaskId = "t".repeat(513)),
            saved.copy(runId = ""), saved.copy(runId = "r".repeat(513)),
            saved.copy(stepId = ""), saved.copy(stepId = "s".repeat(513)),
            saved.copy(stepId = "another-operation"),
            saved.copy(messageId = "\t"), saved.copy(messageId = "m".repeat(513)),
            saved.copy(tool = ""), saved.copy(tool = "task_effect_" + "x".repeat(513)),
            saved.copy(argumentsSha256 = ""), saved.copy(argumentsSha256 = "a".repeat(63)),
            saved.copy(argumentsSha256 = "A".repeat(64)), saved.copy(argumentsSha256 = "g".repeat(64)),
            saved.copy(intent = intent.copy(targetId = ""))
        )
        invalid.forEach { record ->
            assertNull(record.toString(), registry.accept(record, proof(record), listOf(record)))
        }
        val toolAtBoundary = "task_effect_" + "t".repeat(500)
        val bounded = record(effectId = "e".repeat(512)).copy(logicalTaskId = "t".repeat(512),
            runId = "r".repeat(512), messageId = "m".repeat(512), tool = toolAtBoundary)
        val proof = proof(bounded)
        assertEquals(proof, TaskEffectRegistry(listOf(adapter(toolName = toolAtBoundary)))
            .accept(bounded, proof, listOf(bounded)))
    }

    @Test fun receiptCannotBeReusedForAnotherOperationInTheSameAuthoritativeSource() {
        val registry = TaskEffectRegistry(listOf(adapter()))
        val earlier = record(effectId = "effect:earlier")
        val earlierWithProof = earlier.copy(proof = proof(earlier))
        val later = record(effectId = "effect:later", targetIntent = intent.copy(targetId = "conversation:bob"))
        val reused = proof(later)
        assertNull(registry.accept(later, reused, listOf(earlierWithProof, later)))
        val identicalIntentAttempt = record(effectId = "effect:identical-intent")
        assertNull(registry.accept(identicalIntentAttempt, proof(identicalIntentAttempt),
            listOf(earlierWithProof, identicalIntentAttempt)))
        val distinct = reused.copy(receiptId = "receipt:later")
        assertEquals(distinct, registry.accept(later, distinct, listOf(earlierWithProof, later)))
        // Receipt names belong to their source; unrelated sources may use the same local receipt ID.
        val differentSource = later.copy(intent = later.intent.copy(sourceId = "fixture:other-messages"))
        val independentProof = proof(differentSource)
        assertEquals(independentProof, registry.accept(differentSource, independentProof,
            listOf(earlierWithProof, differentSource)))
    }

    @Test fun notAppliedRequiresAnAdapterThatCanAuthoritativelyExcludeALateCommit() {
        val record = record().copy(dispatchState = TaskEffectDispatchState.Unconfirmed)
        val absent = proof(record, TaskEffectResolution.NotApplied)
        assertNull(TaskEffectRegistry(listOf(adapter())).accept(record, absent, listOf(record)))
        assertEquals(absent, TaskEffectRegistry(listOf(adapter(authoritativeAbsence = true)))
            .accept(record, absent, listOf(record)))
    }

    @Test fun aNotDispatchedRecordCannotAcquireAnAppliedReceipt() {
        val record = record().copy(dispatchState = TaskEffectDispatchState.NotDispatched)
        val registry = TaskEffectRegistry(listOf(adapter(authoritativeAbsence = true)))
        assertNull(registry.accept(record, proof(record), listOf(record)))
        val absent = proof(record, TaskEffectResolution.NotApplied)
        assertEquals(absent, registry.accept(record, absent, listOf(record)))
    }

    @Test fun historicalAppliedEvidenceCannotBeDowngradedOrReplacedByLaterObservations() {
        val original = record()
        val applied = proof(original)
        val saved = original.copy(proof = applied)
        val registry = TaskEffectRegistry(listOf(adapter(authoritativeAbsence = true)))
        assertEquals(applied, registry.accept(saved, applied, listOf(saved)))
        assertNull(registry.accept(saved, null, listOf(saved)))
        listOf(applied.copy(resolution = TaskEffectResolution.NotApplied),
            applied.copy(resolution = TaskEffectResolution.Unverified),
            applied.copy(receiptId = "receipt:replacement"),
            applied.copy(sourceRevision = "revision:later", observedAtMillis = 2_000L)).forEach { later ->
            assertNull(registry.accept(saved, later, listOf(saved)))
        }
        assertEquals(applied, saved.proof)
        assertTrue(TaskEffectRegistry.blocksReplay(intent, listOf(saved)))
    }

    @Test fun changedModelCallIdOrToolSpellingCannotReplayTheSameSemanticEffect() {
        val registry = TaskEffectRegistry(listOf(adapter()))
        val first = ChatToolCall("model-call-original", "task_effect_fixture_message", "{}")
        val retry = first.copy(id = "model-call-new", arguments = "{ }")
        val saved = record(effectId = first.id).copy(argumentsSha256 = TaskRecoveryGate.fingerprint(first.arguments))
        val described = registry.describe(retry)!!
        assertEquals(registry.describe(first), described)
        assertTrue(TaskEffectRegistry.blocksReplay(described, listOf(saved)))
        assertTrue(TaskEffectRegistry.blocksReplay(described, listOf(saved.copy(tool = "task_effect_legacy_alias"))))
        changedIntents().forEach { different ->
            assertEquals(different.toString(), different.adapterId == intent.adapterId && different.adapterVersion != intent.adapterVersion,
                TaskEffectRegistry.blocksReplay(different, listOf(saved)))
        }
    }

    @Test fun adapterUpgradeCannotTurnAnAppliedOrUnresolvedOperationIntoNewWork() {
        val upgraded = intent.copy(adapterVersion = 2)
        val saved = record()
        assertTrue(TaskEffectRegistry.blocksReplay(upgraded, listOf(saved)))
        assertTrue(TaskEffectRegistry.blocksReplay(upgraded, listOf(saved.copy(proof = proof(saved)))))
        val absent = saved.copy(proof = proof(saved, TaskEffectResolution.NotApplied))
        // Historical absence cannot be reinterpreted by an adapter that no longer matches its version.
        assertTrue(TaskEffectRegistry(listOf(adapter(describedIntent = upgraded, version = 2, authoritativeAbsence = true)))
            .blocksReplay(upgraded, listOf(absent)))
        assertFalse(TaskEffectRegistry.blocksReplay(upgraded,
            listOf(saved.copy(dispatchState = TaskEffectDispatchState.NotDispatched))))
    }

    @Test fun unresolvedAndAppliedOperationsStayBlockedAcrossAllDispatchedStates() {
        val saved = record()
        listOf(TaskEffectDispatchState.IntentSaved, TaskEffectDispatchState.DispatchStarted,
            TaskEffectDispatchState.Returned, TaskEffectDispatchState.Unconfirmed).forEach { state ->
            val unresolved = saved.copy(dispatchState = state)
            assertTrue(state.toString(), TaskEffectRegistry.blocksReplay(intent, listOf(unresolved)))
            assertTrue(state.toString(), TaskEffectRegistry.blocksReplay(intent,
                listOf(unresolved.copy(proof = proof(unresolved)))))
        }
        assertFalse(TaskEffectRegistry.blocksReplay(intent, emptyList()))
    }

    @Test fun authoritativeNotAppliedAndNotDispatchedAllowRetryButDoNotClearOtherPendingAttempts() {
        val saved = record()
        val notDispatched = saved.copy(dispatchState = TaskEffectDispatchState.NotDispatched)
        assertFalse(TaskEffectRegistry.blocksReplay(intent, listOf(notDispatched)))
        val absent = proof(saved, TaskEffectResolution.NotApplied)
        val accepted = TaskEffectRegistry(listOf(adapter(authoritativeAbsence = true)))
            .accept(saved, absent, listOf(saved))!!
        val notApplied = saved.copy(proof = accepted)
        assertFalse(TaskEffectRegistry.blocksReplay(intent, listOf(notApplied)))
        val otherPending = record(effectId = "effect:other-attempt")
        assertTrue(TaskEffectRegistry.blocksReplay(intent, listOf(notDispatched, notApplied, otherPending)))
    }

    @Test fun malformedPersistedNotAppliedProofCannotPermitReplay() {
        val saved = record()
        val absent = proof(saved, TaskEffectResolution.NotApplied)
        val registry = TaskEffectRegistry(listOf(adapter(authoritativeAbsence = true)))
        listOf(
            absent.copy(effectId = "effect:unknown"),
            absent.copy(intent = intent.copy(targetId = "conversation:bob")),
            absent.copy(receiptId = ""), absent.copy(sourceRevision = ""),
            absent.copy(observedAtMillis = -1)
        ).forEach { invalid ->
            val restored = saved.copy(proof = invalid)
            assertTrue(invalid.toString(), TaskEffectRegistry.blocksReplay(intent, listOf(restored)))
            assertTrue(invalid.toString(), registry.blocksReplay(intent, listOf(restored)))
        }
        val malformedRecord = saved.copy(argumentsSha256 = "invalid", proof = absent)
        assertTrue(TaskEffectRegistry.blocksReplay(intent, listOf(malformedRecord)))
        assertFalse(TaskEffectRegistry.blocksReplay(intent, listOf(saved.copy(proof = absent))))
    }

    @Test fun persistedAbsenceOnlyPermitsReplayWithTheMatchingAuthoritativeAdapter() {
        val saved = record()
        val restored = saved.copy(proof = proof(saved, TaskEffectResolution.NotApplied))
        val records = listOf(restored)
        assertTrue(TaskEffectRegistry(listOf(adapter())).blocksReplay(intent, records))
        assertTrue(TaskEffectRegistry().blocksReplay(intent, records))
        assertTrue(TaskEffectRegistry(listOf(adapter(version = 2, authoritativeAbsence = true)))
            .blocksReplay(intent, records))
        assertFalse(TaskEffectRegistry(listOf(adapter(authoritativeAbsence = true))).blocksReplay(intent, records))
    }

    @Test fun persistedAbsenceWithAnotherOperationsReceiptCannotPermitReplay() {
        val earlier = record(effectId = "effect:earlier", targetIntent = intent.copy(targetId = "conversation:bob"))
        val earlierWithProof = earlier.copy(proof = proof(earlier))
        val saved = record()
        val absent = proof(saved, TaskEffectResolution.NotApplied)
        val restored = saved.copy(proof = absent)
        val registry = TaskEffectRegistry(listOf(adapter(authoritativeAbsence = true)))
        val records = listOf(earlierWithProof, restored)
        assertFalse(TaskEffectRegistry.blocksReplay(intent, records))
        assertTrue(registry.blocksReplay(intent, records))
        assertFalse(registry.blocksReplay(intent, listOf(earlierWithProof,
            restored.copy(proof = absent.copy(receiptId = "receipt:independent-absence")))))
    }

    @Test fun verifiedCalendarSummaryFormatsSavedAndEditedEventsOnlyWhenProofMatches() {
        val args = """{"title":"验收活动","begin_time":"2026-11-20T18:00:00+08:00","end_time":"2026-11-20T18:10:00+08:00"}"""
        val digest = CalendarEventSave.fingerprint(CalendarEventSave.request(args))
        val saveIntent = TaskEffectIntent(CalendarEventSave.AdapterId, 1, "saved_calendar_event",
            CalendarEventSave.SourceId, "event:$digest", digest)
        val saveRecord = TaskEffectRecord("e1", "task", "run", "e1", "m1", CalendarEventSave.ToolName,
            TaskRecoveryGate.fingerprint(args), saveIntent, TaskEffectDispatchState.Returned,
            preflightArguments = args)
        val applied = TaskEffectProof("e1", saveIntent, TaskEffectResolution.Applied, "calendar:1:event:1", "$digest:1", 10L)
        assertEquals("验收活动 · 2026-11-20 18:00–18:10", CalendarEventSave.verifiedSummary(saveRecord.copy(proof = applied)))
        assertNull(CalendarEventSave.verifiedSummary(saveRecord.copy(proof = applied.copy(resolution = TaskEffectResolution.Unverified))))
        assertNull(CalendarEventSave.verifiedSummary(saveRecord.copy(proof = applied, argumentsSha256 = "tampered")))

        val revised = """{"title":"验收活动","begin_time":"2026-11-20T18:05:00+08:00","end_time":"2026-11-20T18:16:00+08:00"}"""
        val editArgs = CalendarTimeEdit(1L, 1L, args, revised).arguments()
        val editDigest = CalendarEventSave.fingerprint(CalendarEventSave.request(revised))
        val editIntent = TaskEffectIntent(CalendarTimeEdit.AdapterId, 1, "edited_calendar_time",
            CalendarEventSave.SourceId, "calendar:1:event:1", editDigest)
        val editRecord = TaskEffectRecord("e2", "task", "run", "e2", "m2", CalendarTimeEdit.ToolName,
            TaskRecoveryGate.fingerprint(editArgs), editIntent, TaskEffectDispatchState.Returned,
            preflightArguments = editArgs)
        val editApplied = TaskEffectProof("e2", editIntent, TaskEffectResolution.Applied, "calendar-edit:e2", "$editDigest:1", 20L)
        assertEquals("验收活动 · 2026-11-20 18:05–18:16", CalendarEventSave.verifiedSummary(editRecord.copy(proof = editApplied)))
    }

    private fun changedIntents() = listOf(
        intent.copy(adapterId = "fixture.other"), intent.copy(adapterVersion = 2),
        intent.copy(kind = "create_record"), intent.copy(sourceId = "fixture:other-messages"),
        intent.copy(targetId = "conversation:bob"),
        intent.copy(contentSha256 = TaskEffectRegistry.contentSha256("different synthetic message"))
    )

    private fun record(effectId: String = "effect:one", targetIntent: TaskEffectIntent = intent) = TaskEffectRecord(
        effectId = effectId, logicalTaskId = "task", runId = "run", stepId = effectId, messageId = "message",
        tool = "task_effect_fixture_message", argumentsSha256 = TaskRecoveryGate.fingerprint("{}"),
        intent = targetIntent, dispatchState = TaskEffectDispatchState.Returned
    )

    private fun proof(record: TaskEffectRecord, resolution: TaskEffectResolution = TaskEffectResolution.Applied) =
        TaskEffectProof(record.effectId, record.intent, resolution, "receipt:one", "revision:one", 1_000L)

    private fun adapter(
        describedIntent: TaskEffectIntent = intent,
        id: String = intent.adapterId,
        version: Int = intent.adapterVersion,
        toolName: String = "task_effect_fixture_message",
        authoritativeAbsence: Boolean = false
    ): TaskEffectAdapter = object : TaskEffectAdapter {
        override val id = id
        override val version = version
        override val tool = ChatToolDefinition(toolName, "Synthetic test effect", buildJsonObject {})
        override val supportsAuthoritativeAbsence = authoritativeAbsence
        override fun describe(arguments: String) = describedIntent
        override suspend fun dispatch(effectId: String, arguments: String) {
            error("Unit evidence checks must never dispatch an effect")
        }
        override suspend fun inspect(record: TaskEffectRecord): TaskEffectProof? =
            error("Registry validation must not call an authoritative source")
    }

    private fun rejectsArgument(action: () -> Unit) {
        try {
            action()
            fail("Expected invalid adapter or intent to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected fail-closed validation.
        }
    }
}
