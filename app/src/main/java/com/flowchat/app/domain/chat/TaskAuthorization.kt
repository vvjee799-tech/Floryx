package com.flowchat.app.domain.chat

import com.flowchat.app.domain.device.DeviceExecutionTarget
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
enum class TaskAuthorizationDecision { Unspecified, Granted, Denied }

@Serializable
data class TaskActionAuthorization(val tool: String, val target: String, val decision: TaskAuthorizationDecision)

/** Same goal + same phone, persisted with the run. A receipt or page text never creates authorization. */
@Serializable
data class TaskAuthorization(
    val scopeSha256: String = "",
    val androidCommands: TaskAuthorizationDecision = TaskAuthorizationDecision.Unspecified,
    val actions: List<TaskActionAuthorization> = emptyList()
) {
    fun decision(tool: String, target: String): TaskAuthorizationDecision = actions.singleOrNull {
        it.tool == tool && it.target == normalize(target)
    }?.decision ?: TaskAuthorizationDecision.Unspecified

    fun withDecision(tool: String, target: String, decision: TaskAuthorizationDecision): TaskAuthorization {
        val normalized = normalize(target)
        return copy(actions = (actions.filterNot { it.tool == tool && it.target == normalized } +
            TaskActionAuthorization(tool, normalized, decision)).takeLast(32))
    }

    companion object {
        fun scope(target: DeviceExecutionTarget): String = TaskRecoveryGate.fingerprint(
            "${target.deviceId}|${target.ownerConversationId}|${target.backgroundExecution}")

        fun start(target: DeviceExecutionTarget, previous: TaskAuthorization?, continuingSameTask: Boolean): TaskAuthorization {
            val scope = scope(target)
            return previous?.takeIf { continuingSameTask && it.scopeSha256 == scope } ?: TaskAuthorization(scope)
        }

        fun normalize(target: String): String = target.trim().lowercase(Locale.ROOT)

        /** Only unambiguous direct wording skips the first force-stop dialog. Unclear requests keep the existing review. */
        fun explicitlyRequestsForceStop(instruction: String, target: String): Boolean {
            if (instruction.contains(Regex("不要|不得|不准|禁止|别|don't|do not|never", RegexOption.IGNORE_CASE))) return false
            if (target.isBlank()) return false
            val name = Regex.escape(target.trim())
            return Regex("^\\s*(?:(?:请帮我|帮我|请|麻烦|现在|立即|please)\\s*)?(?:强行停止|强制停止|强制关闭|force\\s*stop)\\s*[\"'“]?$name[\"'”]?(?=$|[\\s，。,.、;；！!]|然后|并且|并)",
                RegexOption.IGNORE_CASE).containsMatchIn(instruction)
        }
    }
}
