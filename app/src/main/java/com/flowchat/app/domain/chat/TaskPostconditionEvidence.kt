package com.flowchat.app.domain.chat

import com.flowchat.app.domain.device.DeviceScreenWaitRequest
import com.flowchat.app.domain.device.DeviceToolResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** These limits travel with the record, including when it is read after a restart. */
@Serializable
enum class TaskPostconditionProofLimitation {
    ExactDeclaredScreenConditionOnly,
    HistoricalObservationNotCurrentState,
    DoesNotEstablishActionCausality,
    DoesNotVerifyPixels,
    DoesNotCompleteOverallGoal,
    DoesNotResolveRecoveryEffects
}

/**
 * Bounded, structured evidence from one explicit wait_for_screen condition.
 * Screen IDs identify the historical observation; they never authorize a new action.
 * Only the requested predicate is retained, not a screen dump or the tool/model's prose.
 */
@Serializable
data class TaskPostconditionEvidence(
    val stepId: String,
    val messageId: String,
    val runId: String,
    val conditionSha256: String,
    val sourceArgumentsSha256: String,
    val requestedCondition: JsonObject,
    val observedScreenId: String,
    val observedPackageName: String,
    val observedWindowId: Int?,
    val observedDisplayId: Int?,
    val observedAtMillis: Long,
    val requestedObservationScope: JsonObject? = null,
    val sourceTool: String = "wait_for_screen",
    val conditionMatched: Boolean = true,
    val proofLimitations: List<TaskPostconditionProofLimitation> = TaskPostconditionProofLimitation.entries.toList(),
    val version: Int = 1
)

/**
 * Trusted runtime input only: invoke for the returned result of wait_for_screen.
 * Deliberately has no text-based success parser, goal-completion or recovery-resolution API.
 */
object TaskPostconditionEvidencePolicy {
    private const val MaxArgumentsLength = 65_536
    private const val MaxPredicateJsonLength = 16_384
    private const val MaxScopeJsonLength = 12_288
    private const val MaxReferenceLength = 512

    fun capture(
        stepId: String,
        messageId: String,
        runId: String,
        arguments: String,
        result: DeviceToolResult
    ): TaskPostconditionEvidence? {
        if (listOf(stepId, messageId, runId).any { !validReference(it) } ||
            arguments.length > MaxArgumentsLength || !result.success || result.errorCode != null ||
            result.conditionMatched != true) return null
        val request = runCatching { DeviceScreenWaitRequest.fromToolArguments(arguments) }.getOrNull() ?: return null
        val condition = request.condition ?: return null
        // A pixel change is progress, not an exact UI/business postcondition in this ledger.
        if (condition.regionChangedFrom != null) return null
        val screen = result.screen ?: return null
        if (screen.sensitive || screen.observationErrorCode != null || !validReference(screen.screenId) ||
            !validReference(screen.packageName) || screen.capturedAt < 0 ||
            screen.windowId?.let { it < 0 } == true || screen.geometry?.displayId?.let { it < 0 } == true ||
            screen.observationScope != request.observationScope ||
            (request.observationScope != null && screen.observationRootPath.isNullOrBlank())) return null
        // A single visible match in an incomplete/general tree cannot exclude hidden duplicates.
        if (condition.element != null && (!screen.searchComplete ||
                (screen.truncated && screen.elementQuery != condition.element))) return null
        if (!condition.matches(screen)) return null

        // Preserve the original predicate's explicit/default distinctions, while normalizing key order.
        val fields = runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull() ?: return null
        val requestedCondition = (fields["condition"] as? JsonObject)?.let(::canonicalObject) ?: return null
        val requestedScope = (fields["observation_scope"] as? JsonObject)?.let(::canonicalObject)
        if (requestedCondition.toString().length > MaxPredicateJsonLength ||
            (requestedScope?.toString()?.length ?: 0) > MaxScopeJsonLength) return null

        return TaskPostconditionEvidence(
            stepId = stepId,
            messageId = messageId,
            runId = runId,
            conditionSha256 = TaskRecoveryGate.fingerprint(requestedCondition.toString()),
            sourceArgumentsSha256 = TaskRecoveryGate.fingerprint(arguments),
            requestedCondition = requestedCondition,
            requestedObservationScope = requestedScope,
            observedScreenId = screen.screenId,
            observedPackageName = screen.packageName,
            observedWindowId = screen.windowId,
            observedDisplayId = screen.geometry?.displayId,
            observedAtMillis = screen.capturedAt
        )
    }

    private fun validReference(value: String): Boolean = value.isNotBlank() && value.length <= MaxReferenceLength

    private fun canonicalObject(value: JsonObject): JsonObject = canonical(value) as JsonObject

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
}
