package com.flowchat.app.domain.chat

import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.model.ChatToolDefinition
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** An adapter fixes the meaning of a result; model prose and arbitrary screen predicates cannot. */
@Serializable
data class TaskEffectIntent(
    val adapterId: String,
    val adapterVersion: Int,
    val kind: String,
    val sourceId: String,
    val targetId: String,
    val contentSha256: String
) {
    fun fingerprint(): String = TaskRecoveryGate.fingerprint(Json.encodeToString(serializer(), this))
    /** An adapter upgrade cannot create a second copy of the same operation in the same source. */
    fun sameOperation(other: TaskEffectIntent): Boolean = copy(adapterVersion = other.adapterVersion) == other
    fun valid(): Boolean = adapterVersion > 0 && listOf(adapterId, kind, sourceId, targetId).all {
        it.isNotBlank() && it.length <= 512
    } && contentSha256.matches(Regex("[0-9a-f]{64}"))
}

@Serializable
enum class TaskEffectDispatchState { IntentSaved, DispatchStarted, Returned, NotDispatched, Unconfirmed }

@Serializable
enum class TaskEffectResolution { Unverified, Applied, NotApplied }

/** A receipt is evidence for one exact operation token in one authoritative source. */
@Serializable
data class TaskEffectProof(
    val effectId: String,
    val intent: TaskEffectIntent,
    val resolution: TaskEffectResolution,
    val receiptId: String,
    val sourceRevision: String,
    val observedAtMillis: Long
)

@Serializable
data class TaskEffectRecord(
    val effectId: String,
    val logicalTaskId: String,
    val runId: String,
    val stepId: String,
    val messageId: String,
    val tool: String,
    val argumentsSha256: String,
    val intent: TaskEffectIntent,
    val dispatchState: TaskEffectDispatchState = TaskEffectDispatchState.IntentSaved,
    val proof: TaskEffectProof? = null,
    val verificationCounted: Boolean = false,
    val preflightEvidence: String? = null,
    val preflightArguments: String? = null
)

/**
 * Registered application code only. describe() is pure; inspect() MUST be read-only.
 * Authoritative absence requires a source that can exclude an in-flight/late commit for this token.
 * A missing UI row, timeout, lost callback or error is never authoritative absence.
 */
interface TaskEffectAdapter {
    val id: String
    val version: Int
    val tool: ChatToolDefinition
    val exposedToModel: Boolean get() = true
    val supportsAuthoritativeAbsence: Boolean get() = false
    fun describe(arguments: String): TaskEffectIntent
    /** Read-only preparation is persisted before dispatch; never supplied by the model. */
    suspend fun prepare(record: TaskEffectRecord, arguments: String): String? = null
    suspend fun dispatch(effectId: String, arguments: String)
    suspend fun inspect(record: TaskEffectRecord): TaskEffectProof?
}

/** Only throw before dispatch, or after an executor explicitly proves NotDispatched. */
class TaskEffectNotDispatched(message: String) : Exception(message)

/** Production adapters are provided by the application module; fixtures stay isolated. */
class TaskEffectRegistry(val adapters: List<TaskEffectAdapter>) {
    constructor() : this(emptyList())

    init {
        require(adapters.map { it.id }.distinct().size == adapters.size)
        require(adapters.map { it.tool.name }.distinct().size == adapters.size)
        require(adapters.all { it.id.isNotBlank() && it.version > 0 && it.tool.name.startsWith("task_effect_") &&
            it.tool.name != VerifyToolName })
    }

    fun adapter(tool: String): TaskEffectAdapter? = adapters.singleOrNull { it.tool.name == tool }
    fun adapter(record: TaskEffectRecord): TaskEffectAdapter? = adapter(record.tool)?.takeIf {
        it.id == record.intent.adapterId && it.version == record.intent.adapterVersion
    }

    fun describe(call: ChatToolCall): TaskEffectIntent? = adapter(call.name)?.let { adapter ->
        adapter.describe(call.arguments).also {
            require(it.valid() && it.adapterId == adapter.id && it.adapterVersion == adapter.version)
        }
    }

    fun tools(keepVerification: Boolean = false): List<ChatToolDefinition> = if (adapters.isEmpty() && !keepVerification) emptyList() else
        adapters.filter { it.exposedToModel }.map { it.tool } + ChatToolDefinition(VerifyToolName,
            "Read the authoritative receipt of a saved task effect by effect_id, or return its already accepted historical receipt. This never submits or retries an action. " +
                "A matching receipt verifies only that declared operation, not the entire user goal. Missing or ambiguous evidence remains unverified.",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("effect_id") { put("type", "string") } }
                putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("effect_id")) }
                put("additionalProperties", false)
            })

    fun accept(record: TaskEffectRecord, proof: TaskEffectProof?, allRecords: List<TaskEffectRecord>): TaskEffectProof? {
        val adapter = adapter(record) ?: return null
        proof ?: return null
        if (!proofMatches(record, proof) ||
            proof.resolution == TaskEffectResolution.NotApplied && !adapter.supportsAuthoritativeAbsence ||
            record.dispatchState == TaskEffectDispatchState.NotDispatched && proof.resolution == TaskEffectResolution.Applied ||
            allRecords.any { it.effectId != record.effectId && it.intent.sourceId == record.intent.sourceId &&
                it.proof?.receiptId == proof.receiptId }) return null
        // Historical applied evidence is never downgraded by a later missing record.
        if (record.proof?.resolution == TaskEffectResolution.Applied && proofMatches(record, record.proof) && proof != record.proof) return null
        return proof
    }

    fun blocksReplay(intent: TaskEffectIntent, records: List<TaskEffectRecord>): Boolean =
        Companion.blocksReplay(intent, records) || records.any { record -> record.intent.sameOperation(intent) &&
            record.dispatchState != TaskEffectDispatchState.NotDispatched &&
            record.proof?.resolution == TaskEffectResolution.NotApplied && accept(record, record.proof, records) == null }

    companion object {
        const val VerifyToolName = "verify_task_effect"
        const val MaxRecords = 96

        /** Content identity is byte-exact; JSON-looking text must not be canonicalized. */
        fun contentSha256(content: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun proofMatches(record: TaskEffectRecord, proof: TaskEffectProof?): Boolean = proof != null &&
            listOf(record.effectId, record.logicalTaskId, record.runId, record.stepId, record.messageId, record.tool)
                .all { it.isNotBlank() && it.length <= 512 } && record.effectId == record.stepId &&
            record.argumentsSha256.matches(Regex("[0-9a-f]{64}")) &&
            proof.effectId == record.effectId && proof.intent == record.intent && proof.intent.valid() &&
            proof.observedAtMillis >= 0 && listOf(proof.receiptId, proof.sourceRevision).all { it.isNotBlank() && it.length <= 512 } &&
            proof.resolution != TaskEffectResolution.Unverified &&
            !(record.dispatchState == TaskEffectDispatchState.NotDispatched && proof.resolution == TaskEffectResolution.Applied)

        /** Applied/unresolved operations cannot be recreated with a different model call ID or tool spelling. */
        fun blocksReplay(intent: TaskEffectIntent, records: List<TaskEffectRecord>): Boolean = records.any {
            it.intent.sameOperation(intent) && it.dispatchState != TaskEffectDispatchState.NotDispatched &&
                !(it.proof?.resolution == TaskEffectResolution.NotApplied && proofMatches(it, it.proof))
        }
    }
}
