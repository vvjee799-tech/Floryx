package com.flowchat.app.domain.memory

import com.flowchat.app.domain.model.MemoryDraft
import java.security.MessageDigest

/** Conservative gate: ordinary questions, tool output and assistant claims are not memories. */
object MemoryPolicy {
    private val durable = Regex("记住|记下|以后|今后|默认|长期|一直|我的(?:名字|昵称|职业|工作|专业|项目|偏好)|我(?:叫|住在|居住|从事|喜欢|不喜欢|习惯|偏好|正在开发|正在学习)|remember|from now on|always|prefer|my (?:name|job|project)|i (?:live|work|am working)", RegexOption.IGNORE_CASE)
    private val temporary = Regex("(?:今天|这次|现在|等会|临时|刚才|明天|today|this time|right now|temporarily)", RegexOption.IGNORE_CASE)
    private val secrets = Regex("sk-[a-zA-Z0-9_-]{8,}|(?:api[_ -]?key|密钥|密码|口令|验证码|password|token|secret|身份证|银行卡|健康|病史|宗教|政治倾向|sexual|medical)", RegexOption.IGNORE_CASE)
    private val optOut = Regex("(?:不要|别|不用|不许|无需).{0,6}(?:记住|记忆|保存)|(?:do not|don't|never) (?:remember|save|store)", RegexOption.IGNORE_CASE)
    private val question = Regex("^(?:请问|怎么|如何|为什么|什么是|what|how|why|can you tell)", RegexOption.IGNORE_CASE)
    private val keyPattern = Regex("(?:preference|profile|project)\\.[a-z0-9_.-]{1,56}")

    fun shouldExtract(text: String): Boolean {
        val clean = text.trim()
        if (clean.length !in 6..6000 || secrets.containsMatchIn(clean) || optOut.containsMatchIn(clean)) return false
        if (question.containsMatchIn(clean) && !clean.contains("记住") && !clean.contains("remember", true)) return false
        return durable.containsMatchIn(clean) && (!temporary.containsMatchIn(clean) || Regex("以后|今后|默认|长期|from now on|always", RegexOption.IGNORE_CASE).containsMatchIn(clean))
    }

    fun valid(draft: MemoryDraft): Boolean =
        draft.category in setOf("preference", "profile", "project") &&
            keyPattern.matches(draft.key) && draft.key.startsWith(draft.category + ".") &&
            validSummary(draft.summary)

    fun validSummary(summary: String): Boolean = summary.length in 6..180 &&
        !secrets.containsMatchIn(summary) && !optOut.containsMatchIn(summary) &&
        !summary.contains("助手：") && !summary.contains("用户：") &&
        !summary.contains("<") && !summary.contains("\n")

    /** Old turn copies are derived data. Keep only explicit durable user statements, never replies. */
    fun migrateLegacy(goal: String): MemoryDraft? {
        val clean = goal.trim().replace(Regex("\\s+"), " ")
        if (!shouldExtract(clean) || clean.length > 180 || clean.endsWith("？") || clean.endsWith("?")) return null
        val category = when {
            Regex("项目|开发|project|develop", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> "project"
            Regex("我叫|名字|昵称|my name|住在|职业|专业", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> "profile"
            else -> "preference"
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(clean.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        return MemoryDraft("$category.legacy_$hash", category, clean).takeIf(::valid)
    }
}
