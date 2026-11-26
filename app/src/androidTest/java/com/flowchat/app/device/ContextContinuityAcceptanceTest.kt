package com.flowchat.app.device

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.data.chat.ContextLimits
import com.flowchat.app.data.workspace.WorkspaceManager
import com.flowchat.app.domain.chat.CheckpointCodec
import com.flowchat.app.domain.chat.ExecutionStep
import com.flowchat.app.domain.chat.TaskContextTools
import com.flowchat.app.domain.chat.TaskInformationRange
import com.flowchat.app.domain.chat.TaskRunMetadata
import com.flowchat.app.domain.model.ChatDelta
import com.flowchat.app.domain.model.ChatRequest
import com.flowchat.app.domain.model.ChatToolCall
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.workspace.WorkspaceToolDefinitions
import com.flowchat.app.presentation.chat.ChatScreen
import com.flowchat.app.presentation.chat.ChatViewModel
import com.flowchat.app.ui.theme.AppAppearance
import com.flowchat.app.ui.theme.FlowChatTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

internal fun archivedAnnouncement(permit: String) =
    ("请用一句话总结下面的合成公告，不要列出编号或逐项细节。\n" +
        "Notice section on the venue and opening hours.\n".repeat(105) +
        "许可编号为$permit。\n" +
        "Notice section on access and operating rules.\n".repeat(115)).trimEnd()

/** M03 with isolated Room, local scripted model output and a Dev-only document provider. */
class ContextContinuityAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun largeFileCoverageSurvivesInTurnCompaction() = runBlocking<Unit> {
        val workspace = WorkspaceManager(context)
        val tree = DocumentsContract.buildTreeDocumentUri("${context.packageName}.p0c.documents", "root")
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        fun hasGrant() = context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        val originallyGranted = hasGrant()
        val fixture = AutonomyRuntimeFixture(context, workspaceOverride = workspace, contextLimits =
            ContextLimits(softTokens = 6_500, targetTokens = 5_000, minGrowthTokens = 500))
        fun readRange(id: String, start: Long, size: Int) = call(id, WorkspaceToolDefinitions.Read,
            """{"path":"stream.txt","byte_offset":$start,"max_bytes":$size}""")
        try {
            fixture.settings.setPowerModeEnabled(false)
            if (!originallyGranted) context.grantUriPermission(context.packageName, tree,
                read or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            val location = workspace.select(tree.toString(), read)
            fixture.create()
            fixture.chats.updateConversationWorkspace(fixture.owner, location)
            fixture.await { fixture.runtime.uiState.value.currentConversation?.workspace == location }

            var coverageBeforeOverlap: TaskRunMetadata? = null
            var archivedReadId: String? = null
            fixture.responder = { request, round ->
                when (round) {
                    1 -> readRange("first-range", 0, 9_000)
                    2 -> {
                        assertEquals(listOf(TaskInformationRange(0, 9_000)), metadata(fixture).informationProgress.sources.single().ranges)
                        readRange("second-range", 9_000, 9_000)
                    }
                    3 -> {
                        val archived = fixture.chats.getMessages(fixture.owner).first { !it.executionContext.isNullOrBlank() }
                        archivedReadId = archived.id
                        val checkpoint = requireNotNull(fixture.chats.getContextCheckpoint(fixture.owner))
                        assertTrue(checkpoint.matches(fixture.chats.getMessages(fixture.owner)))
                        assertTrue(request.messages.any { it.content.orEmpty().contains(checkpoint.sourceHash) })
                        assertFalse(request.messages.any { it.toolCalls.any { call -> call.id == "first-range" } })
                        assertFalse(request.messages.any { it.toolCallId == "first-range" })
                        val rows = fixture.chats.getMessages(fixture.owner)
                        assertTrue(rows.indexOfFirst { it.id == checkpoint.throughMessageId } >= rows.indexOf(archived))
                        val before = metadata(fixture)
                        assertEquals(listOf(TaskInformationRange(0, 18_000)), before.informationProgress.sources.single().ranges)
                        coverageBeforeOverlap = before
                        readRange("overlap", 8_710, 128)
                    }
                    4 -> {
                        val before = requireNotNull(coverageBeforeOverlap)
                        val after = metadata(fixture)
                        assertEquals(before.informationProgress, after.informationProgress)
                        assertEquals(before.lastBudgetEvidenceRef, after.lastBudgetEvidenceRef)
                        assertEquals(1, after.budget!!.noProgressCount)
                        readRange("tail", 199_997, 32)
                    }
                    5 -> {
                        val result = toolResult(request, "tail")
                        assertTrue(result.getValue("eof").jsonPrimitive.boolean)
                        val ending = result.getValue("text").jsonPrimitive.content.removePrefix("xxx")
                        reply("文件末尾是$ending。")
                    }
                    else -> error("Unexpected workspace round $round")
                }
            }
            val started = SystemClock.elapsedRealtime()
            fixture.send("只读检查合成文件的开头与末尾，然后告诉我文件末尾写了什么。")
            val saved = metadata(fixture)
            val source = saved.informationProgress.sources.single()
            assertEquals(requireNotNull(coverageBeforeOverlap).informationProgress.sources.single().versionSha256, source.versionSha256)
            assertEquals(listOf(TaskInformationRange(0, 18_000), TaskInformationRange(199_997, 200_012)), source.ranges)
            assertEquals(0, saved.budget!!.noProgressCount)
            assertEquals(4, saved.budget.totalToolCalls)
            assertTrue(saved.pendingEffects.isEmpty() && saved.effects.isEmpty() && fixture.confirmations.isEmpty())
            assertEquals("文件末尾是中文末尾。", fixture.chats.getMessages(fixture.owner).last().content)
            assertEquals(listOf(WorkspaceToolDefinitions.Read), steps(fixture).map { it.tool }.distinct())
            report("large_file elapsed_ms=${SystemClock.elapsedRealtime() - started} " +
                "archived_read=$archivedReadId coverage=[0,18000),[199997,200012) overlap_credit=0 " +
                "model_rounds=${fixture.modelCalls.get()} summary_rounds=${fixture.summaryCalls.get()} confirmations=0 writes=0")
        } finally {
            fixture.close(true)
            if (!originallyGranted) {
                if (hasGrant()) context.contentResolver.releasePersistableUriPermission(tree, read)
                context.revokeUriPermission(context.packageName, tree, read)
            }
        }
    }

    @Test fun followUpReadsTheMiddleOfAnArchivedDocument() = runBlocking<Unit> {
        val fixture = AutonomyRuntimeFixture(context, contextLimits = ContextLimits(triggerTurns = 1, keepTurns = 1))
        val permit = "北岸-" + UUID.randomUUID().toString().take(8)
        val original = archivedAnnouncement(permit)
        val firstSummary = "公告介绍了场馆的开放时间和入场规则。"
        val finalAnswer = "公告的许可编号是$permit。"
        var sourceId: String? = null
        var answerOffset = -1
        try {
            fixture.settings.setPowerModeEnabled(false)
            fixture.create()
            compose.activityRule.scenario.onActivity { activity ->
                val viewModel = ChatViewModel(fixture.runtime)
                activity.setContent {
                    FlowChatTheme(AppAppearance.Dark) {
                        ChatScreen({}, {}, {}, AppAppearance.Dark, {}, viewModel)
                    }
                }
            }
            fixture.responder = { request, round ->
                when (round) {
                    1 -> {
                        assertTrue(request.messages.any { it.role == "user" && it.content.orEmpty().contains(permit) })
                        reply(firstSummary)
                    }
                    2 -> {
                        assertFalse(request.messages.any { it.content.orEmpty().contains(permit) })
                        val context = request.messages.first { it.content.orEmpty().startsWith("Conversation task checkpoint") }
                        sourceId = requireNotNull(CheckpointCodec.decode(context.content!!.substringAfterLast('\n')))
                            .originalRequest.sources.single()
                        history("excerpt", requireNotNull(sourceId))
                    }
                    3 -> {
                        val excerpt = historyRecord(request, "excerpt").getValue("content").jsonPrimitive.content
                        assertTrue(excerpt.contains("middle omitted"))
                        assertFalse(excerpt.contains(permit))
                        history("document-first", requireNotNull(sourceId), 0)
                    }
                    4 -> {
                        val first = historyRecord(request, "document-first")
                        assertEquals("The first page must identify its offset", 0, first["content_offset"]?.jsonPrimitive?.int)
                        assertFalse(first.getValue("content").jsonPrimitive.content.contains(permit))
                        answerOffset = first.getValue("next_content_offset").jsonPrimitive.int
                        history("document-next", requireNotNull(sourceId), answerOffset)
                    }
                    5 -> {
                        val page = historyRecord(request, "document-next")
                        assertEquals(sourceId, page.getValue("id").jsonPrimitive.content)
                        val fact = Regex("许可编号为([^。]+)。").find(page.getValue("content").jsonPrimitive.content)
                        reply("公告的许可编号是${requireNotNull(fact).groupValues[1]}。")
                    }
                    else -> error("Unexpected document round $round")
                }
            }
            fixture.send(original)
            assertEquals(firstSummary, fixture.chats.getMessages(fixture.owner).last().content)
            val started = SystemClock.elapsedRealtime()
            fixture.send("刚才那份公告的许可编号是什么？")
            val elapsed = SystemClock.elapsedRealtime() - started
            val rows = fixture.chats.getMessages(fixture.owner)
            val savedSource = rows.first { it.role == MessageRole.User }
            assertEquals(savedSource.id, sourceId)
            assertEquals(original, savedSource.content)
            val checkpoint = requireNotNull(fixture.chats.getContextCheckpoint(fixture.owner))
            assertTrue(checkpoint.matches(rows))
            assertEquals(2, rows.count { it.role == MessageRole.User })
            assertEquals(MessageStatus.Complete, rows.last().status)
            assertEquals(finalAnswer, rows.last().content)
            assertEquals(List(3) { TaskContextTools.Read }, steps(fixture).map { it.tool })
            assertTrue(metadata(fixture).effects.isEmpty() && metadata(fixture).pendingEffects.isEmpty())
            assertTrue(fixture.confirmations.isEmpty())
            assertEquals(fixture.provider.defaultModel, fixture.journal.latest(fixture.owner)?.modelName)
            compose.waitForIdle()
            compose.onNodeWithText(finalAnswer, useUnmergedTree = true).assertIsDisplayed()
            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val screenshot = File(context.getExternalFilesDir(null), "d3-m03/follow-up.png")
            screenshot.parentFile?.mkdirs()
            screenshot.outputStream().use { assertTrue(requireNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)) }
            report("document_followup elapsed_ms=$elapsed source=$sourceId page_offset=$answerOffset " +
                "model_rounds=${fixture.modelCalls.get()} summary_rounds=${fixture.summaryCalls.get()} " +
                "history_reads=3 confirmations=0 writes=0 answer=$finalAnswer")
        } finally {
            fixture.close(true)
        }
    }

    private fun call(id: String, name: String, arguments: String) =
        listOf(ChatDelta.ToolCalls(listOf(ChatToolCall(id, name, arguments))), ChatDelta.Done)

    private fun history(id: String, source: String, offset: Int? = null) =
        call(id, TaskContextTools.Read, buildJsonObject {
            putJsonArray("message_ids") { add(source) }
            offset?.let { put("content_offset", it) }
        }.toString())

    private fun reply(answer: String) = listOf(ChatDelta.Content(answer), ChatDelta.Done)

    private fun toolResult(request: ChatRequest, id: String) =
        Json.parseToJsonElement(requireNotNull(request.messages.single { it.toolCallId == id }.content)).jsonObject

    private fun historyRecord(request: ChatRequest, id: String) = toolResult(request, id).getValue("records").jsonArray.single().jsonObject

    private suspend fun metadata(fixture: AutonomyRuntimeFixture) =
        requireNotNull(TaskRunMetadata.decode(fixture.journal.latest(fixture.owner)?.metadataJson))

    private suspend fun steps(fixture: AutonomyRuntimeFixture) = fixture.chats.getMessages(fixture.owner)
        .flatMap { row -> row.executionContext?.let { Json.decodeFromString<List<ExecutionStep>>(it) }.orEmpty() }

    private fun report(result: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nM03 $result\n") })
}
