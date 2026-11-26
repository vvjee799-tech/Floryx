package com.flowchat.app.domain.chat

import com.flowchat.app.domain.model.Message
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ReplyPhase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Converts a persisted, unfinished turn into durable history without executing it again. */
object TaskRecovery {
    // These visible suffixes are durable recovery markers. Keep recognizing old values if copy changes.
    private const val ChineseAssistantNotice = "[应用中断：此回复未完成。上方仅是已保存的片段，执行结果未经确认。继续前请核实先前操作的实际状态。]"
    private const val EnglishAssistantNotice = "[App interrupted: This reply did not finish. The text above is only the saved partial response, and action results are unverified. Check the actual state before continuing.]"
    private const val ChineseToolNotice = "[应用中断：此操作未记录到确认的最终结果；操作可能已生效，请核实后再重试。]"
    private const val EnglishToolNotice = "[App interrupted: This action has no confirmed final result. It may have taken effect; verify before retrying.]"
    private const val UnconfirmedResult = "Interrupted before a result was recorded. The action may have taken effect; check its state before retrying."
    private val json = Json { ignoreUnknownKeys = true }

    fun recover(message: Message, latestUserText: String?, now: Long): Message {
        if (message.role !in setOf(MessageRole.Assistant, MessageRole.Tool)) return message
        val context = interruptUnconfirmedSteps(message.executionContext)
        val hasPendingStep = context != message.executionContext
        if (message.status !in setOf(MessageStatus.Pending, MessageStatus.Streaming) && !hasPendingStep) return message
        val chinese = isChinese(latestUserText.orEmpty())
        val notice = when (message.role) {
            MessageRole.Tool -> if (chinese) ChineseToolNotice else EnglishToolNotice
            else -> if (chinese) ChineseAssistantNotice else EnglishAssistantNotice
        }
        return message.copy(
            content = appendNotice(message.content, notice),
            status = if (message.role == MessageRole.Tool) MessageStatus.Cancelled else MessageStatus.Stopped,
            executionContext = context,
            updatedAt = now
        )
    }

    /** True only for an interruption recovered from persistent storage in the latest user turn. */
    fun isRecoveredInterruption(messages: List<Message>): Boolean = latestTurn(messages).any { message ->
        message.role in setOf(MessageRole.Assistant, MessageRole.Tool) &&
            message.status in setOf(MessageStatus.Stopped, MessageStatus.Cancelled) && hasRecoveryNotice(message.content)
    } && canResume(messages)

    /** A stopped or failed latest turn can be continued until an answer completes it. */
    fun canResume(messages: List<Message>): Boolean {
        val turn = latestTurn(messages)
        if (turn.isEmpty()) return false
        if (turn.any { it.role == MessageRole.Assistant && it.replyPhase == ReplyPhase.Answer &&
                it.status == MessageStatus.Complete }) return false
        return turn.any { it.role == MessageRole.Assistant &&
            it.status in setOf(MessageStatus.Stopped, MessageStatus.Cancelled, MessageStatus.Failed) } ||
            turn.any { it.role == MessageRole.Tool && it.status == MessageStatus.Cancelled }
    }

    fun latestUserInstruction(messages: List<Message>): String? =
        messages.lastOrNull { it.role == MessageRole.User }?.content?.trim()?.takeIf { it.isNotEmpty() }

    fun continueInstruction(messages: List<Message>): String? {
        if (!canResume(messages)) return null
        return if (isChinese(latestUserInstruction(messages).orEmpty())) {
            "继续处理上一条用户请求。先查看已保存的进度和工具记录，核实中断时未确认的操作是否实际生效；不要重复已完成的步骤，也不要把未确认结果当作完成。只执行剩余的必要步骤，最后说明已核实结果与仍不确定之处。"
        } else {
            "Continue the previous user request. Review the saved progress and tool history first. Verify whether any action left unconfirmed by the interruption actually took effect; do not repeat completed steps or treat unconfirmed results as completed. Perform only the remaining necessary work, then report verified results and any remaining uncertainty."
        }
    }

    private fun latestTurn(messages: List<Message>): List<Message> {
        val start = messages.indexOfLast { it.role == MessageRole.User }
        return if (start < 0) emptyList() else messages.drop(start + 1)
    }

    private fun hasRecoveryNotice(content: String): Boolean = listOf(
        ChineseAssistantNotice, EnglishAssistantNotice, ChineseToolNotice, EnglishToolNotice
    ).any(content::endsWith)

    private fun appendNotice(content: String, notice: String): String =
        if (content.isBlank()) notice else content.trimEnd() + "\n\n" + notice

    private fun isChinese(text: String): Boolean = text.any { it in '\u3400'..'\u9fff' }

    private fun interruptUnconfirmedSteps(context: String?): String? {
        if (context.isNullOrBlank()) return context
        return runCatching {
            val steps = json.parseToJsonElement(context) as? JsonArray ?: return@runCatching context
            var changed = false
            val recovered = steps.map { step ->
                val fields = step as? JsonObject ?: return@map step
                if (fields["outcome"]?.jsonPrimitive?.contentOrNull != "unconfirmed") return@map step
                changed = true
                JsonObject(fields + mapOf("outcome" to JsonPrimitive("interrupted_unconfirmed"),
                    "result" to JsonPrimitive(UnconfirmedResult)))
            }
            if (changed) JsonArray(recovered).toString() else context
        }.getOrDefault(context)
    }
}
