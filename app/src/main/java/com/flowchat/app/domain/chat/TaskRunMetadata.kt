package com.flowchat.app.domain.chat

import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Goal evidence is separate from the existing response execution state. */
@Serializable
enum class TaskGoalStatus { Unverified, PartiallyVerified, Verified, Failed }

@Serializable
data class TaskRecoveryConcern(
    val stepId: String,
    val messageId: String,
    val tool: String,
    val argumentsSha256: String,
    val reason: String = "result_unconfirmed",
    val replayFingerprintSha256: String? = null
)

@Serializable
data class TaskRunMetadata(
    val version: Int = 1,
    val logicalTaskId: String,
    val continuedFromRunId: String? = null,
    val goalStatus: TaskGoalStatus = TaskGoalStatus.Unverified,
    val stopReason: String? = null,
    val pendingEffects: List<TaskRecoveryConcern> = emptyList(),
    // A person's decision permits remaining work; it is not independent verification of an effect.
    val acknowledgedEffects: List<TaskRecoveryConcern> = emptyList(),
    val recoveryObservationMessageId: String? = null,
    val endedByUser: Boolean = false,
    val verifiedStepCount: Int = 0,
    val toolCallCount: Int = 0,
    val budget: TaskExecutionBudgetSnapshot? = null,
    val recoveryBudget: TaskExecutionBudgetSnapshot? = null,
    val lastBudgetEvidenceRef: String? = null,
    val progressEvidenceSha256: List<String> = emptyList(),
    val progressScopeSha256: String? = null,
    val informationProgress: TaskInformationProgress = TaskInformationProgress(),
    val postconditionEvidence: List<TaskPostconditionEvidence> = emptyList(),
    val effects: List<TaskEffectRecord> = emptyList(),
    val authorization: TaskAuthorization = TaskAuthorization()
) {
    fun encode(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun decode(value: String?): TaskRunMetadata? = value?.let {
            runCatching { json.decodeFromString<TaskRunMetadata>(it) }.getOrNull()?.takeIf { data -> data.version == 1 }
        }
    }
}

/** Conservative, program-enforced recovery. Unknown tools never acquire read-only status. */
object TaskRecoveryGate {
    private val json = Json { ignoreUnknownKeys = true }
    private val readOnlyTools = setOf(
        "observe_screen", "wait_for_screen", "get_device_status", "get_device_time",
        "get_foreground_app", "get_app_usage_summary", "get_recent_app_activity", "web_search",
        "read_attachment", "read_task_history", "workspace_status", "workspace_list", "workspace_read", "workspace_stat", "workspace_search",
        "workspace_inspect_execution", "terminal_status", "read_command_output", TaskEffectRegistry.VerifyToolName,
        com.flowchat.app.domain.workspace.WorkspaceToolDefinitions.AnswerCompatibility
    )

    const val Guidance = "Recovery verification is required. Only read-only observation is permitted. " +
        "Inspect the saved evidence and the current state; do not repeat or replace prior actions. " +
        "Use workspace_list/stat/read/search for phone originals, workspace_inspect_execution for this conversation's Linux copies, " +
        "and terminal_status/read_command_output for command identity, existing output and cleanup. Observations never rerun commands or clear unknown effects. " +
        "Finish independent read-only goals and report supported findings even while prior changes remain uncertain. " +
        "Ask only when an unresolved effect prevents a necessary next change, describing the concrete object and decision. " +
        "Remaining changes require an exact authoritative receipt from a registered effect adapter " +
        "that resolves the saved uncertainty, or the user's explicit recovery review in the app. Ordinary screen conditions do not resolve uncertain effects."

    fun isReadOnly(tool: String): Boolean = tool in readOnlyTools

    fun concern(stepId: String, messageId: String, call: ChatToolCall, reason: String = "result_unconfirmed") =
        TaskRecoveryConcern(stepId, messageId, call.name, fingerprint(call.arguments), reason, replayFingerprint(call.arguments))

    fun isAcknowledgedReplay(call: ChatToolCall, effects: List<TaskRecoveryConcern>): Boolean = effects.any {
        it.tool == call.name && (it.argumentsSha256 == fingerprint(call.arguments) ||
            it.replayFingerprintSha256 == replayFingerprint(call.arguments))
    }

    /** Read the old turn before appending a new User, including legacy evidence. */
    fun legacyConcerns(messages: List<Message>): List<TaskRecoveryConcern> {
        val start = messages.indexOfLast { it.role == MessageRole.User }
        if (start < 0) return emptyList()
        return messages.drop(start + 1).flatMap { message ->
            val context = message.executionContext ?: return@flatMap emptyList()
            val steps = runCatching { json.decodeFromString<List<ExecutionStep>>(context) }.getOrNull()
                ?: return@flatMap listOf(TaskRecoveryConcern("${message.id}:unreadable", message.id,
                    "unknown", fingerprint(context), "evidence_unreadable"))
            steps.mapIndexedNotNull { index, step ->
                val legacyUnverified = step.effectState == null && (
                    step.result.lineSequence().any { it.trim() == "action_outcome=Unverified" } ||
                        step.outcome == "returned" && (step.result.startsWith("Tool call failed:") || step.result.startsWith("failed:")) &&
                        step.result.lineSequence().none { it.trim() == "action_outcome=NotDispatched" })
                if (isReadOnly(step.tool) || step.outcome !in setOf("unconfirmed", "interrupted_unconfirmed") &&
                    step.effectState != "Unverified" && !legacyUnverified) null
                else TaskRecoveryConcern(step.stepId ?: "${message.id}:$index", message.id, step.tool,
                    step.argumentsSha256 ?: fingerprint(step.arguments),
                    replayFingerprintSha256 = step.replayFingerprintSha256 ?: replayFingerprint(step.arguments))
            }
        }
    }

    fun merge(vararg concerns: List<TaskRecoveryConcern>): List<TaskRecoveryConcern> =
        concerns.flatMap { it }.distinctBy { it.stepId }

    /** Reconcile a crash between a saved settled-scroll result and its metadata write. Never infer a submit receipt. */
    fun reconcileObservedScroll(concerns: List<TaskRecoveryConcern>, messages: List<Message>): List<TaskRecoveryConcern> {
        val completed = messages.flatMap { message ->
            runCatching { json.decodeFromString<List<ExecutionStep>>(message.executionContext ?: "[]") }.getOrDefault(emptyList())
        }.filter { it.tool in setOf("swipe_screen", "scroll_container") && it.outcome == "returned" &&
            it.effectState in setOf("StateObserved", "ProgressObserved", "TargetVerified", "NotDispatched") }
        return concerns.filterNot { concern -> completed.any { step ->
            step.stepId == concern.stepId && step.tool == concern.tool && step.argumentsSha256 == concern.argumentsSha256
        } }
    }

    fun fingerprint(arguments: String): String {
        val canonical = runCatching { canonical(json.parseToJsonElement(arguments)).toString() }.getOrDefault(arguments)
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** A new observation ID cannot turn the same requested effect into remaining work. */
    fun replayFingerprint(arguments: String): String = fingerprint(runCatching {
        val fields = json.parseToJsonElement(arguments) as? JsonObject ?: return@runCatching arguments
        // A different wait allowance cannot authorize the same semantic click again.
        JsonObject(if (fields["target"] is JsonObject) fields - "screen_id" - "timeout_ms" else fields - "screen_id").toString()
    }.getOrDefault(arguments))

    private fun canonical(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(element.map(::canonical))
        else -> element
    }
}
