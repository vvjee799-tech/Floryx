package com.flowchat.app.device

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.domain.model.*
import com.flowchat.app.presentation.chat.*
import com.flowchat.app.ui.theme.*
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic fixtures; never calls a paid provider or changes the user's provider settings. */
class ChatOptimizationDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("runChatOptimizationDiagnostics") == "true")

    @Test fun streamingRespectsReadingPositionAndJumpResumesFollowing() {
        enabled()
        val messages = mutableStateOf((0 until 60).map { index -> Message(id = "row-$index", conversationId = "scroll-fixture",
            role = if (index % 2 == 0) MessageRole.User else MessageRole.Assistant,
            content = "Row $index\n" + "Long reading content. ".repeat(10),
            status = if (index == 59) MessageStatus.Streaming else MessageStatus.Complete) })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { FlowChatTheme(AppAppearance.Light) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp)) {
                    MessageList(messages.value, null, null, false, Modifier.fillMaxSize())
                }
            } } }
            compose.waitForIdle()
            repeat(3) { compose.onNodeWithTag("chat-message-list").performTouchInput { swipeDown(durationMillis = 450) } }
            compose.onNodeWithTag("jump-to-latest").assertIsDisplayed()
            val before = scrollPosition()
            repeat(12) { part ->
                compose.runOnIdle { messages.value = messages.value.dropLast(1) + messages.value.last().copy(content = "New live fragment $part\n".repeat(20)) }
            }
            compose.onNodeWithTag("jump-to-latest").assertIsDisplayed()
            assertEquals("Streaming moved the reading position", before, scrollPosition(), 0.05f)
            compose.onNodeWithTag("jump-to-latest").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("jump-to-latest").assertDoesNotExist()
            compose.runOnIdle { messages.value = messages.value.dropLast(1) + messages.value.last().copy(content = "Latest response is visible") }
            compose.onNodeWithText("Latest response is visible").assertIsDisplayed()
            capture("scroll-follow")
        }
    }

    @Test fun loadingEarlierMessagesKeepsTheVisibleAnchor() {
        enabled()
        val all = (0 until 180).map { index -> Message(id = "page-$index", conversationId = "page-fixture", role = MessageRole.User,
            content = "Historical row $index", status = MessageStatus.Sent) }
        val limit = mutableStateOf(80)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { FlowChatTheme(AppAppearance.Light) {
                MessageList(all.takeLast(limit.value), null, null, false, Modifier.fillMaxSize(),
                    hasOlderMessages = limit.value < all.size, onLoadOlder = { limit.value += 80 })
            } } }
            compose.onNodeWithTag("chat-message-list").performScrollToIndex(0)
            val before = compose.onNodeWithText("Historical row 100").fetchSemanticsNode().boundsInRoot.top
            compose.onNodeWithTag("load-older-messages").performClick()
            compose.onNodeWithText("Historical row 100").assertIsDisplayed()
            assertEquals(before, compose.onNodeWithText("Historical row 100").fetchSemanticsNode().boundsInRoot.top, 2f)
            compose.onNodeWithTag("chat-message-list").performScrollToIndex(0)
            compose.onNodeWithTag("load-older-messages").performClick()
            compose.onNodeWithTag("chat-message-list").performScrollToIndex(0)
            compose.onNodeWithText("Historical row 0").assertIsDisplayed()
            compose.onNodeWithTag("load-older-messages").assertDoesNotExist()
        }
    }

    @Test fun markdownCopiesCodeAndOpensLinksAcrossThemesAndLargeText() {
        enabled()
        val theme = mutableStateOf(AppAppearance.Light)
        val large = mutableStateOf(false)
        var opened = ""
        val code = "val greeting = \"你好\"\n"
        val source = "# Readable title\n\n**Bold** and *emphasis*\n\n[Example](https://example.com)\n\n```kotlin\n${code}```\n\n| Name | Value |\n| --- | --- |\n| Alpha | 123 |"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { FlowChatTheme(theme.value) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { opened = uri }
                }, LocalDensity provides Density(density.density, if (large.value) 1.5f else 1f)) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
                        item { Box(Modifier.width(320.dp).padding(16.dp)) { MarkdownContent(source) } }
                    }
                }
            } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("copy-code").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("copy-code").performClick()
            compose.onNodeWithText(context.getString(R.string.code_copied)).assertIsDisplayed()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            instrumentation.runOnMainSync { assertEquals(code, clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
            compose.onNodeWithText("Example").performTouchInput { click(center) }
            assertEquals("https://example.com", opened)
            capture("markdown-light")
            compose.runOnIdle { theme.value = AppAppearance.Dark; large.value = true }
            compose.onNodeWithTag("markdown-code").assertExists()
            compose.onNodeWithText("Readable title").assertIsDisplayed()
            capture("markdown-dark-large")
        }
    }

    @Test fun historyActionsArchiveAndExportUsePersistentRecords() = runBlocking<Unit> {
        enabled()
        val data = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
        val repository = data.chats()
        val a = repository.createConversation("history-fixture", "local")
        val b = repository.createConversation("history-fixture", "local")
        val owners = setOf(a.id, b.id)
        var exported = ""
        try {
            repository.updateConversationTitle(a.id, "QA Alpha")
            repository.updateConversationTitle(b.id, "QA Beta")
            repository.appendMessage(b.id, MessageRole.User, "exported-test-body", MessageStatus.Sent, "local")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { FlowChatTheme(AppAppearance.Light) {
                    val rows by repository.observeConversations().collectAsState(emptyList())
                    val scope = rememberCoroutineScope()
                    Box(Modifier.width(320.dp).fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
                        ConversationHistory(rows.filter { it.id in owners }, a.id,
                            onSelect = {}, onRename = { id, title -> scope.launch { repository.updateConversationTitle(id, title) } },
                            onPin = { id, pin -> scope.launch { repository.setConversationPinned(id, pin) } },
                            onArchive = { id, archived -> scope.launch { repository.setConversationArchived(id, archived) } },
                            onExport = { chat -> scope.launch { exported = ConversationExporter.markdown(chat, repository.getMessages(chat.id)) } },
                            onDelete = {}, modifier = Modifier.fillMaxSize())
                    }
                } } }
                compose.waitUntil(10_000) { compose.onAllNodesWithText("QA Alpha").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("history-search").assertDoesNotExist()
                compose.onNodeWithTag("history-row-${a.id}").performTouchInput { longClick() }
                compose.onNodeWithText(context.getString(R.string.rename_conversation)).performClick()
                compose.onNodeWithTag("history-rename-field").performTextReplacement("Renamed QA")
                compose.onNodeWithText(context.getString(R.string.save)).performClick()
                compose.waitUntil(10_000) { runBlocking { repository.getConversation(a.id)?.title == "Renamed QA" } }
                compose.onNodeWithTag("history-row-${a.id}").performTouchInput { longClick() }
                compose.onNodeWithText(context.getString(R.string.pin_conversation)).performClick()
                compose.waitUntil(10_000) { runBlocking { repository.getConversation(a.id)?.isPinned == true } }
                compose.onNodeWithText("Renamed QA").assertIsDisplayed()
                compose.onNodeWithText("QA Beta").assertIsDisplayed()
                compose.onNodeWithTag("history-row-${b.id}").performTouchInput { longClick() }
                compose.onNodeWithText(context.getString(R.string.export_conversation)).performClick()
                compose.waitUntil(10_000) { exported.contains("exported-test-body") }
                compose.onNodeWithTag("history-row-${b.id}").performTouchInput { longClick() }
                compose.onNodeWithText(context.getString(R.string.archive_conversation)).performClick()
                compose.waitUntil(10_000) { runBlocking { repository.getConversation(b.id)?.isArchived == true } }
                compose.onNodeWithTag("history-toggle-archive").performClick()
                compose.onNodeWithText("QA Beta").assertIsDisplayed()
                capture("history-archive")
                compose.onNodeWithTag("history-row-${b.id}").performTouchInput { longClick() }
                compose.onNodeWithText(context.getString(R.string.restore_conversation)).performClick()
                compose.waitUntil(10_000) { runBlocking { repository.getConversation(b.id)?.isArchived == false } }
            }
        } finally { owners.forEach { repository.deleteConversation(it) } }
    }

    @Test fun messageListMovesSynchronouslyEveryFrameDuringComposerExpansion() {
        enabled()
        val provider = ProviderConfig(
            id = "sync-fixture",
            displayName = "DeepSeek",
            baseUrl = "http://127.0.0.1",
            defaultModel = "deepseek-v4-flash"
        )
        val conversation = Conversation(id = "sync-conv", providerId = provider.id, modelName = provider.defaultModel)
        val messages = (0 until 24).map { index ->
            Message(
                id = "sync-row-$index",
                conversationId = conversation.id,
                role = if (index % 2 == 0) MessageRole.User else MessageRole.Assistant,
                content = if (index == 23) "Latest anchor row" else "Message row $index\nSynchronized layout verification line.",
                status = MessageStatus.Complete
            )
        }
        val expanded = mutableStateOf<Boolean?>(false)
        val simulatedImePadding = mutableStateOf(0.dp)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var hostActivity: MainActivity
            scenario.onActivity { activity ->
                hostActivity = activity
                activity.setContent {
                    FlowChatTheme(AppAppearance.Dark) {
                        androidx.compose.material3.Scaffold(
                            modifier = Modifier.padding(bottom = simulatedImePadding.value),
                            containerColor = MaterialTheme.colorScheme.background,
                            bottomBar = {
                                MessageComposer(
                                    value = "",
                                    pendingAttachment = null,
                                    isAttachmentLoading = false,
                                    enabled = true,
                                    isStreaming = false,
                                    providers = listOf(provider),
                                    currentConversation = conversation,
                                    onValueChange = {},
                                    onSend = {},
                                    onStop = {},
                                    onThinkingEnabledChange = {},
                                    onThinkingEffortChange = {},
                                    onPickAttachment = {},
                                    onClearAttachment = {},
                                    onSelectModel = { _, _ -> },
                                    imeVisibleOverride = expanded.value
                                )
                            }
                        ) { padding ->
                            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                                MessageList(messages, null, null, false, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            val initialPanelTop = compose.onNodeWithTag("message_composer_panel").fetchSemanticsNode().boundsInRoot.top
            val initialMessageBottom = compose.onNodeWithText("Latest anchor row").fetchSemanticsNode().boundsInRoot.bottom
            val initialGap = initialPanelTop - initialMessageBottom
            assertTrue("Latest message should start above composer ($initialMessageBottom vs $initialPanelTop)", initialGap >= 0f)

            compose.mainClock.autoAdvance = false
            try {
                compose.runOnIdle { expanded.value = true }
                compose.mainClock.advanceTimeByFrame()
                var intermediateMovements = 0
                repeat(24) { frame ->
                    compose.mainClock.advanceTimeByFrame()
                    val panelTop = compose.onNodeWithTag("message_composer_panel").fetchSemanticsNode().boundsInRoot.top
                    val msgBottom = compose.onNodeWithText("Latest anchor row").fetchSemanticsNode().boundsInRoot.bottom
                    val panelShift = panelTop - initialPanelTop
                    val msgShift = msgBottom - initialMessageBottom
                    assertTrue(
                        "Composer overlapped message at frame $frame: msgBottom=$msgBottom > panelTop=$panelTop",
                        msgBottom <= panelTop + 1f
                    )
                    assertEquals(
                        "Message bottom did not track composer height at frame $frame",
                        panelShift,
                        msgShift,
                        2.5f
                    )
                    if (panelShift < -4f && panelTop > initialPanelTop - 1000f) {
                        intermediateMovements++
                    }
                }
                assertTrue("Expected smooth multi-frame composer expansion", intermediateMovements >= 4)

                // Verify smooth collapse back as well
                val expandedPanelTop = compose.onNodeWithTag("message_composer_panel").fetchSemanticsNode().boundsInRoot.top
                assertTrue("Composer must have grown taller ($expandedPanelTop vs $initialPanelTop)", expandedPanelTop < initialPanelTop - 20f)
                compose.runOnIdle { expanded.value = false }
                compose.mainClock.advanceTimeByFrame()
                repeat(24) { frame ->
                    compose.mainClock.advanceTimeByFrame()
                    val panelTop = compose.onNodeWithTag("message_composer_panel").fetchSemanticsNode().boundsInRoot.top
                    val msgBottom = compose.onNodeWithText("Latest anchor row").fetchSemanticsNode().boundsInRoot.bottom
                    val panelShift = panelTop - initialPanelTop
                    val msgShift = msgBottom - initialMessageBottom
                    assertEquals(
                        "Message bottom did not track composer collapse at frame $frame",
                        panelShift,
                        msgShift,
                        2.5f
                    )
                }
                // Also verify simultaneous capsule spring expansion + 280dp IME height rise across 20 frames
                compose.runOnIdle { expanded.value = true }
                repeat(20) { step ->
                    val fraction = (step + 1) / 20f
                    compose.runOnIdle { simulatedImePadding.value = (280f * fraction).dp }
                    compose.mainClock.advanceTimeByFrame()
                    val panelTop = compose.onNodeWithTag("message_composer_panel").fetchSemanticsNode().boundsInRoot.top
                    val msgBottom = compose.onNodeWithText("Latest anchor row").fetchSemanticsNode().boundsInRoot.bottom
                    assertEquals(
                        "Message bottom lagged behind combined IME + capsule rise at step $step",
                        panelTop - initialPanelTop,
                        msgBottom - initialMessageBottom,
                        2.5f
                    )
                }
                compose.runOnIdle {
                    expanded.value = false
                    simulatedImePadding.value = 0.dp
                }
                repeat(24) { compose.mainClock.advanceTimeByFrame() }
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.waitForIdle()

            // Live real-IME tap verification (both during rise and once settled)
            compose.runOnIdle { expanded.value = null }
            compose.waitForIdle()
            val liveInitialPanelTop = compose.onNodeWithTag("message_composer_panel").fetchSemanticsNode().boundsInRoot.top
            val liveInitialMsgBottom = compose.onNodeWithText("Latest anchor row").fetchSemanticsNode().boundsInRoot.bottom
            compose.onNode(hasSetTextAction()).performTouchInput { click(center) }
            var lastImeBottom = -1
            var stableSince = 0L
            compose.waitUntil(8_000) {
                var currentBottom = 0
                var visible = false
                instrumentation.runOnMainSync {
                    val insets = androidx.core.view.ViewCompat.getRootWindowInsets(hostActivity.window.decorView)
                    visible = insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                    currentBottom = insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    if (currentBottom != lastImeBottom) {
                        lastImeBottom = currentBottom
                        stableSince = android.os.SystemClock.elapsedRealtime()
                    }
                }
                visible && currentBottom > 0 && (android.os.SystemClock.elapsedRealtime() - stableSince >= 250L)
            }
            compose.waitForIdle()
            val settledPanelTop = compose.onNodeWithTag("message_composer_panel").fetchSemanticsNode().boundsInRoot.top
            val settledMsgBottom = compose.onNodeWithText("Latest anchor row").fetchSemanticsNode().boundsInRoot.bottom
            assertTrue(
                "Composer must rise with live IME (settled=$settledPanelTop initial=$liveInitialPanelTop ime=$lastImeBottom)",
                settledPanelTop < liveInitialPanelTop - 40f
            )
            assertEquals(
                "Settled live IME message bottom must align with composer shift",
                settledPanelTop - liveInitialPanelTop,
                settledMsgBottom - liveInitialMsgBottom,
                2.5f
            )
            capture("composer-sync-settled")
        }
    }

    private fun scrollPosition(): Float = compose.onNodeWithTag("chat-message-list").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        File(context.getExternalFilesDir(null), "chat-optimization-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
