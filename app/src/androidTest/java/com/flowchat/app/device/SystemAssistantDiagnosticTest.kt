package com.flowchat.app.device

import android.app.Activity
import android.app.Application
import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.withTransaction
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.network.KtorOpenAiCompatibleClient
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.SystemAssistantDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.domain.assistant.AssistantModelSelection
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.locale.AppLocale
import com.flowchat.app.presentation.assistant.AssistPageContext
import com.flowchat.app.presentation.assistant.AndroidAssistantSpeechInput
import com.flowchat.app.presentation.assistant.AssistantPanelActivity
import com.flowchat.app.presentation.assistant.AssistantPanelView
import com.flowchat.app.presentation.assistant.AssistantSpeechInput
import com.flowchat.app.presentation.assistant.AssistantVoicePlayback
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in assistant diagnostics using synthetic pages. */
class SystemAssistantDiagnosticTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val runtimeEntry get() = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java)
    private val data get() = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
    private val assistant get() = EntryPointAccessors.fromApplication(context, SystemAssistantDiagnosticsEntryPoint::class.java)
    private val prefs get() = context.getSharedPreferences("assistant_entry", Context.MODE_PRIVATE)
    private val marker get() = context.getFileStreamPath("system-assistant-fixture.json")
    private val activities = mutableListOf<Activity>()
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("runSystemAssistantDiagnostics") == "true")
    internal suspend fun await(label: String, check: suspend () -> Boolean) {
        try { withTimeout(25_000) { while (!check()) delay(50) } }
        catch (error: TimeoutCancellationException) { throw AssertionError("Timed out: $label", error) }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nSYSTEM_ASSISTANT $label\n") })
    }
    private suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
    }
    internal suspend fun <T : Activity> open(type: Class<T>, launch: (suspend () -> Unit)? = null): T {
        val result = AtomicReference<T>()
        val application = context.applicationContext as Application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { if (type.isInstance(activity)) result.set(type.cast(activity)) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        withContext(Dispatchers.Main) { application.registerActivityLifecycleCallbacks(callbacks) }
        try {
            if (launch == null) {
                val result = shell("am start -W -f 0x10008000 -n ${context.packageName}/${type.name}")
                check(result.contains("Status: ok")) { result }
            } else launch()
            await("${type.simpleName}-resumed") { result.get() != null }
            return result.get().also { activities += it }
        } finally { withContext(Dispatchers.Main) { application.unregisterActivityLifecycleCallbacks(callbacks) } }
    }

    private suspend fun restoreFixture() {
        if (!marker.exists()) return
        val saved = JSONObject(marker.readText())
        val provider = saved.getString("provider")
        check(provider.startsWith("system-assistant-qa-"))
        val runtime = withContext(Dispatchers.Main) { runtimeEntry.runtime() }
        val owned = data.chats().observeConversations().first().filter { it.providerId == provider }
        withContext(Dispatchers.Main) { owned.forEach { runtime.stopFromAssistant(it.id) } }
        await("fixture-tasks-stopped") { owned.none { it.id in runtime.activeConversations.value } }
        withContext(Dispatchers.Main) { owned.forEach { runtime.deleteConversation(it.id) } }
        await("fixture-conversations-removed") { data.chats().observeConversations().first().none { it.providerId == provider } }
        data.providers().deleteProvider(provider)
        val originalOwner = saved.optString("selection").takeIf { it.isNotEmpty() }
        if (originalOwner != null && data.chats().getConversation(originalOwner) != null)
            withContext(Dispatchers.Main) { runtime.selectConversation(originalOwner) }
        prefs.edit().apply {
            if (saved.has("assistantOwner")) putString("conversation_id", saved.getString("assistantOwner")) else remove("conversation_id")
        }.commit()
        runtimeEntry.settings().apply {
            setMemoryEnabled(saved.getBoolean("memory")); setCommandExecutionEnabled(saved.getBoolean("commands")); setPowerModeEnabled(saved.getBoolean("power"))
            if (saved.has("configuration")) {
                setAssistantModelSelection(if (saved.isNull("configuration")) null else Json.decodeFromString(saved.getString("configuration")))
            }
        }
        withContext(Dispatchers.IO) { check(context.getSharedPreferences("app_controls", Context.MODE_PRIVATE).edit().commit()) }
        marker.delete()
    }

    internal data class Fixture(val editor: String, val owner: String, val model: TaskRuntimeDiagnosticTest.GatedModel)
    internal suspend fun fixture(
        modelFactory: () -> TaskRuntimeDiagnosticTest.GatedModel = { TaskRuntimeDiagnosticTest.GatedModel() },
        block: suspend (Fixture) -> Unit
    ) {
        restoreFixture()
        val runtime = withContext(Dispatchers.Main) { runtimeEntry.runtime() }
        // Let the existing list load before saving the selection, without changing a personal draft.
        await("runtime-ready") { runtime.uiState.value.providers.isNotEmpty() }
        val settings = runtimeEntry.settings().state.value
        val providerId = "system-assistant-qa-${UUID.randomUUID()}"
        marker.writeText(JSONObject().apply {
            put("provider", providerId); put("selection", runtime.uiState.value.currentConversation?.id.orEmpty())
            if (prefs.contains("conversation_id")) put("assistantOwner", prefs.getString("conversation_id", ""))
            put("memory", settings.memoryEnabled); put("power", settings.powerModeEnabled); put("commands", settings.commandExecutionEnabled)
            put("configuration", settings.assistantModelSelection?.let { Json.encodeToString(it) } ?: JSONObject.NULL)
        }.toString())
        val model = modelFactory()
        try {
            runtimeEntry.settings().apply { setMemoryEnabled(false); setPowerModeEnabled(false); setCommandExecutionEnabled(false) }
            val provider = ProviderConfig(id = providerId, displayName = "System assistant local QA", baseUrl = "http://127.0.0.1:${model.port}/v1", defaultModel = "assistant-local")
            data.providers().upsertProvider(provider, null)
            val editor = data.chats().createConversation(provider.id, provider.defaultModel).id
            val owner = data.chats().createConversation(provider.id, provider.defaultModel).id
            prefs.edit().putString("conversation_id", owner).commit()
            block(Fixture(editor, owner, model))
        } finally {
            withContext(Dispatchers.Main) { activities.forEach { if (!it.isDestroyed) it.finish() } }
            activities.clear()
            model.close()
            restoreFixture()
        }
    }

    @Test fun realPanelStartsInBackgroundReopensProgressAndStopsWithoutChangingTheEditor() = runBlocking<Unit> {
        enabled()
        fixture { fixture ->
            val runtime = withContext(Dispatchers.Main) { runtimeEntry.runtime() }
            val main = open(MainActivity::class.java)
            withContext(Dispatchers.Main) { runtime.selectConversation(fixture.editor); runtime.updateInput("EDITOR_DRAFT_KEEP"); runtime.attachTextFile("editor-only.txt", "EDITOR_ATTACHMENT_KEEP") }
            await("editor-selected") { runtime.uiState.value.currentConversation?.id == fixture.editor }
            withContext(Dispatchers.Main) { main.finish() }
            await("main-closed") { main.isDestroyed }
            val returns = assistant.bridge().deviceTaskControl.controlChanges.value
            val panel = open(AssistantPanelActivity::class.java)
            await("panel-ready") { withContext(Dispatchers.Main) { panel.findViewById<EditText>(R.id.assistant_input)?.isEnabled == true } }
            await("panel-initialized") { withContext(Dispatchers.Main) {
                panel.findViewById<EditText>(R.id.assistant_input).setText("PANEL_SYNTHETIC_REQUEST")
                panel.findViewById<Button>(R.id.assistant_send).isEnabled
            } }
            withContext(Dispatchers.Main) { assertTrue(panel.findViewById<Button>(R.id.assistant_send).performClick()) }
            await("accepted-panel-closed") { panel.isDestroyed }
            await("background-stream") { fixture.model.requests.get() == 1 && data.chats().getMessages(fixture.owner).any { it.content.contains("LOCAL_PREFIX") } }
            assertEquals(returns, assistant.bridge().deviceTaskControl.controlChanges.value)
            assertEquals(fixture.editor, runtime.uiState.value.currentConversation?.id)
            assertEquals("EDITOR_DRAFT_KEEP", runtime.uiState.value.input)
            assertEquals("editor-only.txt", runtime.uiState.value.pendingAttachment?.name)
            assertTrue(data.chats().getMessages(fixture.editor).isEmpty())
            assertFalse(fixture.model.bodies.single().contains("EDITOR_ATTACHMENT_KEEP"))
            val reopened = open(AssistantPanelActivity::class.java)
            await("progress-without-takeover") { withContext(Dispatchers.Main) { reopened.findViewById<Button>(R.id.assistant_stop).visibility == android.view.View.VISIBLE } }
            assertEquals(returns, assistant.bridge().deviceTaskControl.controlChanges.value)
            withContext(Dispatchers.Main) { reopened.findViewById<Button>(R.id.assistant_stop).performClick() }
            await("panel-stopped-task") { data.chats().getMessages(fixture.owner).lastOrNull()?.status == MessageStatus.Stopped && fixture.owner !in runtime.activeConversations.value }
            assertEquals(1, fixture.model.requests.get())
            assertEquals("EDITOR_DRAFT_KEEP", runtime.uiState.value.input)
            assertEquals(1, assistant.bridge().assistantPanels.value)
        }
        await("panel-leases-released") { assistant.bridge().assistantPanels.value == 0 }
    }

    @Test fun voiceInputSubmitsOneTaskAndReadsOnlyItsFinalAnswer() = runBlocking<Unit> {
        enabled()
        val transcript = "请算一下三加四等于多少，用一句话回答。"
        val answer = "三加四等于七。"
        fixture({ TaskRuntimeDiagnosticTest.GatedModel(prefix = "三加四", suffix = "等于七。") }) { fixture ->
            val directory = File(context.getExternalFilesDir(null), "assistant-voice-acceptance").apply { mkdirs() }
            val played = CompletableDeferred<String>()
            val stopped = CompletableDeferred<Unit>()
            val playback = AssistantVoicePlayback { text ->
                played.complete(text)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
            val panel = open(AssistantPanelActivity::class.java)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val input = ManualSpeechInput()
            val presenter = withContext(Dispatchers.Main) { assistant.panels().create(playback) }
            try {
                withContext(Dispatchers.Main) {
                    val view = AssistantPanelView(panel, presenter, {}, {}, { error("Voice invoked manual send") },
                        requestVoicePermission = { granted, _ -> granted() }, voiceAvailable = { true }, speechInput = { input })
                    panel.setContentView(view)
                    scope.launch { presenter.state.collect(view::render) }
                }
                val voice = panel.findViewById<Button>(R.id.assistant_voice)
                val status = panel.findViewById<TextView>(R.id.assistant_status)
                await("voice-panel-ready") { withContext(Dispatchers.Main) { voice.isEnabled } }
                withContext(Dispatchers.Main) {
                    assertTrue(voice.performClick())
                    input.listener.ready()
                    input.listener.partial("这句应当被取消")
                    val cancelled = input.listener
                    voice.performClick()
                    cancelled.result("这句不应发送")
                    assertEquals(1, input.closeCount)
                    voice.performClick()
                    input.listener.result("  ")
                    assertEquals(2, input.closeCount)
                    voice.performClick()
                    input.listener.ready()
                    input.listener.partial("请算一下三加四")
                    assertTrue(status.text.contains("请算一下三加四"))
                    assertFalse(panel.findViewById<Button>(R.id.assistant_send).isEnabled)
                }
                assertEquals(0, fixture.model.requests.get())
                assertTrue(data.chats().getMessages(fixture.owner).isEmpty())
                captureVoicePanel(panel, directory, "01-partial.png")
                val spokenEnd = withContext(Dispatchers.Main) {
                    SystemClock.elapsedRealtime().also {
                        input.listener.endOfSpeech()
                        assertTrue(status.text.contains(panel.getString(R.string.assistant_voice_transcribing)))
                    }
                }
                assertEquals(0, fixture.model.requests.get())
                withContext(Dispatchers.Main) {
                    val current = input.listener
                    current.result(transcript)
                    current.result("重复的最终结果不应发送")
                    assertEquals(3, input.closeCount)
                }
                await("voice-started-one-task") { fixture.model.requests.get() == 1 }
                val firstRequestMs = SystemClock.elapsedRealtime() - spokenEnd
                assertFalse(played.isCompleted)
                fixture.model.release()
                assertEquals(answer, withTimeout(15_000) { played.await() })
                val playbackMs = SystemClock.elapsedRealtime() - spokenEnd
                await("voice-final-answer") { withContext(Dispatchers.Main) {
                    panel.findViewById<TextView>(R.id.assistant_output).text.toString() == answer &&
                        status.text.toString() == panel.getString(R.string.assistant_voice_speaking)
                } }
                captureVoicePanel(panel, directory, "02-final-answer.png")
                withContext(Dispatchers.Main) { panel.findViewById<Button>(R.id.assistant_stop).performClick() }
                withTimeout(5_000) { stopped.await() }
                val messages = data.chats().getMessages(fixture.owner)
                assertEquals(listOf(transcript), messages.filter { it.role == MessageRole.User }.map { it.content })
                assertEquals(answer, messages.last().content)
                assertEquals(MessageStatus.Complete, messages.last().status)
                assertEquals(1, fixture.model.requests.get())
                assertTrue(fixture.model.bodies.single().contains(transcript))
                assertTrue(data.chats().getMessages(fixture.editor).isEmpty())
                File(directory, "result.json").writeText(JSONObject().put("passed", true)
                    .put("transcript", transcript).put("answer", answer)
                    .put("model", "local scripted model").put("recognition", "synthetic callbacks")
                    .put("playback", "synthetic output sink").put("requests", fixture.model.requests.get())
                    .put("manualSendActions", 0).put("userMessages", 1).put("cancelledCaptureSends", 0)
                    .put("duplicateFinalSends", 0).put("emptyCaptureSends", 0).put("playbackStoppedLocally", true)
                    .put("endOfSpeechStatusImmediate", true).put("endOfSpeechToFirstRequestMs", firstRequestMs)
                    .put("endOfSpeechToPlaybackMs", playbackMs).toString(2))
            } finally {
                withContext(Dispatchers.Main) { playback.stop(); presenter.close(); scope.cancel() }
            }
        }
    }

    @Test fun voiceEntryReportsMissingOnDeviceRecognizerWithoutCreatingATask() = runBlocking<Unit> {
        enabled()
        check(!AndroidAssistantSpeechInput.isAvailable(context)) { "Run this missing-engine case on the MuMu fixture" }
        fixture { fixture ->
            val panel = open(AssistantPanelActivity::class.java)
            val voice = panel.findViewById<Button>(R.id.assistant_voice)
            val error = panel.findViewById<TextView>(R.id.assistant_error)
            await("voice-panel-ready") { withContext(Dispatchers.Main) { voice.isEnabled } }
            withContext(Dispatchers.Main) {
                assertTrue(voice.performClick())
                assertEquals(panel.getString(R.string.assistant_voice_unavailable), error.text.toString())
                assertEquals(android.view.View.VISIBLE, error.visibility)
            }
            val directory = File(context.getExternalFilesDir(null), "assistant-voice-acceptance").apply { mkdirs() }
            captureVoicePanel(panel, directory, "03-unavailable.png")
            assertEquals(0, fixture.model.requests.get())
            assertTrue(data.chats().getMessages(fixture.owner).isEmpty())
        }
    }

    internal suspend fun captureVoicePanel(panel: Activity, directory: File, name: String) {
        withContext(Dispatchers.Main) {
            withTimeout(2_000) {
                suspendCancellableCoroutine { continuation ->
                    val decor = panel.window.decorView
                    decor.postOnAnimation { decor.postOnAnimation { continuation.resume(Unit) } }
                }
            }
        }
        val image = requireNotNull(automation.takeScreenshot())
        try {
            File(directory, name).outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            image.recycle()
        }
    }

    private class ManualSpeechInput : AssistantSpeechInput {
        lateinit var listener: AssistantSpeechInput.Listener
        var closeCount = 0
            private set

        override fun start(listener: AssistantSpeechInput.Listener) { this.listener = listener }
        override fun close() { closeCount++ }
    }

    @Test fun pageTextIsOptInAndStaleContextDoesNotSendOrConsumeInput() = runBlocking<Unit> {
        enabled()
        fixture { fixture ->
            var panel = withContext(Dispatchers.Main) { assistant.panels().create() }
            try {
                await("presenter-ready") { panel.state.value.ready }
                withContext(Dispatchers.Main) {
                    panel.updatePage(AssistPageContext("synthetic.source", "PAGE_NOT_SELECTED"))
                    panel.updateInput("First request without page"); panel.send {}
                }
                await("page-omitted") { fixture.model.requests.get() == 1 }
                assertFalse(fixture.model.bodies.single().contains("PAGE_NOT_SELECTED"))
                fixture.model.release()
                await("first-completed") { data.chats().getMessages(fixture.owner).lastOrNull()?.status == MessageStatus.Complete }
                withContext(Dispatchers.Main) { panel.close(); panel = assistant.panels().create() }
                await("fresh-panel-ready") { panel.state.value.ready }
                assertNull(panel.state.value.page)
                withContext(Dispatchers.Main) {
                    panel.updatePage(AssistPageContext("synthetic.source", "STALE_PAGE", SystemClock.elapsedRealtime() - 60_001))
                    panel.includePage(true); panel.updateInput("KEEP_UNSENT"); panel.send {}
                }
                assertEquals(1, fixture.model.requests.get())
                assertEquals("KEEP_UNSENT", panel.state.value.input)
                assertNull(panel.state.value.page)
                fixture.model.prepareNext()
                withContext(Dispatchers.Main) {
                    panel.updatePage(AssistPageContext("synthetic.source", "PAGE_SELECTED_BY_USER"))
                    assertFalse(panel.state.value.includePage)
                    panel.includePage(true); panel.send {}
                }
                await("selected-page-sent") { fixture.model.requests.get() == 2 }
                val user = data.chats().getMessages(fixture.owner).last { it.role == MessageRole.User }
                assertTrue(user.attachmentText.orEmpty().contains("PAGE_SELECTED_BY_USER"))
                assertTrue(fixture.model.bodies.last().contains("untrusted page content"))
                withContext(Dispatchers.Main) { panel.close() }
                fixture.model.release()
                await("closed-panel-task-completed") { data.chats().getMessages(fixture.owner).lastOrNull()?.status == MessageStatus.Complete }
            } finally { withContext(Dispatchers.Main) { panel.close() } }
        }
    }

    private fun findNode(resourceId: String): AccessibilityNodeInfo? = automation.windows.asSequence()
        .mapNotNull { it.root }.flatMap { it.findAccessibilityNodeInfosByViewId("${context.packageName}:id/$resourceId").asSequence() }
        .firstOrNull { it.isVisibleToUser }

    private fun clickableText(text: String): AccessibilityNodeInfo? {
        val candidates = automation.windows.asSequence().mapNotNull { it.root }
            .flatMap { it.findAccessibilityNodeInfosByText(text).asSequence() }
            .filter { it.isVisibleToUser && it.packageName?.toString()?.contains("systemui", ignoreCase = true) == true }
        for (candidate in candidates) {
            var current: AccessibilityNodeInfo? = candidate
            repeat(8) {
                val node = current
                if (node != null && node.isClickable && node.isEnabled) return node
                current = node?.parent
            }
        }
        return null
    }

    @Test fun taskNotificationAndQuickSettingsTileOpenTheLightweightPanel() = runBlocking<Unit> {
        enabled()
        assumeTrue(InstrumentationRegistry.getArguments().getString("testAssistantEntrySurfaces") == "true")
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS))
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        val tileKey = "sysui_qs_tiles"
        // Modern target SDKs cannot read this private SystemUI setting through the app API.
        val oldTiles = shell("settings get secure $tileKey").trim().takeUnless { it == "null" }
        check(!oldTiles.isNullOrBlank() && oldTiles.none { it.isWhitespace() }) { "A stable tile list is required for this opt-in fixture" }
        val tile = "${context.packageName}/com.flowchat.app.presentation.assistant.AssistantTileService"
        val tilesMarker = context.getFileStreamPath("system-assistant-tiles-fixture.txt")
        tilesMarker.writeText(oldTiles)
        try {
            fixture { fixture ->
                val runtime = withContext(Dispatchers.Main) { runtimeEntry.runtime() }
                val main = open(MainActivity::class.java)
                withContext(Dispatchers.Main) { assertTrue(runtime.sendFromAssistant(fixture.owner, "LOCAL_NOTIFICATION_REQUEST")) }
                await("notification-task-running") { fixture.model.requests.get() == 1 }
                withContext(Dispatchers.Main) { main.finish() }
                await("notification-main-closed") { main.isDestroyed }
                val manager = context.getSystemService(android.app.NotificationManager::class.java)
                await("task-notification-visible") { manager.activeNotifications.any { it.id == 109 } }
                val notification = manager.activeNotifications.first { it.id == 109 }.notification
                val returns = assistant.bridge().deviceTaskControl.controlChanges.value
                val panel = open(AssistantPanelActivity::class.java) {
                    // Tap as SystemUI would. Sending the PendingIntent from a background app is a different,
                    // intentionally restricted action on OriginOS/Android 16.
                    shell("cmd statusbar expand-notifications")
                    val title = notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString()
                    await("notification-clicked") { clickableText(title)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
                }
                await("notification-opens-progress") { withContext(Dispatchers.Main) { panel.findViewById<Button>(R.id.assistant_stop).visibility == android.view.View.VISIBLE } }
                assertEquals(returns, assistant.bridge().deviceTaskControl.controlChanges.value)
                withContext(Dispatchers.Main) { panel.findViewById<Button>(R.id.assistant_stop).performClick(); panel.finish() }
                await("notification-task-stopped") { fixture.owner !in runtime.activeConversations.value }
                shell("cmd statusbar add-tile $tile")
                val tilePanel = open(AssistantPanelActivity::class.java) {
                    shell("cmd statusbar expand-settings")
                    await("tile-clicked") { clickableText("Floryx")?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
                }
                assertNotNull(tilePanel.findViewById<EditText>(R.id.assistant_input))
                assertEquals(returns, assistant.bridge().deviceTaskControl.controlChanges.value)
                assertEquals(1, fixture.model.requests.get())
            }
        } finally {
            // Use SystemUI's own API so its live tile host and the setting are restored together.
            if (!oldTiles.contains("custom($tile)")) shell("cmd statusbar remove-tile $tile")
            shell("cmd statusbar set-tiles $oldTiles")
            shell("cmd statusbar collapse")
            await("quick-settings-restored") { shell("settings get secure $tileKey").trim() == oldTiles }
            tilesMarker.delete()
        }
    }

    @Test fun quickSettingsTileOpensPanelWithoutNotificationPermission() = runBlocking<Unit> {
        enabled()
        assumeTrue(InstrumentationRegistry.getArguments().getString("testAssistantTile") == "true")
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        val oldTiles = shell("settings get secure sysui_qs_tiles").trim()
        check(oldTiles != "null" && oldTiles.isNotBlank() && oldTiles.none { it.isWhitespace() })
        val tile = "${context.packageName}/com.flowchat.app.presentation.assistant.AssistantTileService"
        try {
            val main = open(MainActivity::class.java)
            withContext(Dispatchers.Main) { main.finish() }
            await("tile-main-closed") { main.isDestroyed }
            shell("cmd statusbar add-tile $tile")
            // Keep every original tile and make the tested tile visible on the first page.
            val first = (listOf("custom($tile)") + oldTiles.split(',').filterNot { it == "custom($tile)" }).joinToString(",")
            shell("cmd statusbar set-tiles $first")
            val panel = open(AssistantPanelActivity::class.java) {
                shell("cmd statusbar expand-settings")
                await("standalone-tile-clicked") { clickableText("Floryx")?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
            }
            assertNotNull(panel.findViewById<EditText>(R.id.assistant_input))
        } finally {
            withContext(Dispatchers.Main) { activities.forEach { if (!it.isDestroyed) it.finish() } }
            activities.clear()
            if (!oldTiles.contains("custom($tile)")) shell("cmd statusbar remove-tile $tile")
            shell("cmd statusbar set-tiles $oldTiles"); shell("cmd statusbar collapse")
            await("standalone-tiles-restored") { shell("settings get secure sysui_qs_tiles").trim() == oldTiles }
        }
    }

    @Test fun actualSystemSessionTransfersOnlyVisibleNonPasswordTextAndRespectsSecureWindows() = runBlocking<Unit> {
        enabled()
        withSystemAssistantRole {
            val component = "${instrumentation.context.packageName}/${AssistantContextFixtureActivity::class.java.name}"
            assertTrue(shell("am start -W -f 0x10008000 -n $component").contains("Status: ok"))
            shell("input keyevent 219")
            await("voice-session-open") { findNode("assistant_input") != null }
            await("system-page-available") { findNode("assistant_page_toggle") != null }
            val toggle = requireNotNull(findNode("assistant_page_toggle"))
            assertFalse(toggle.isChecked)
            // Preview the actual system-delivered text via the panel's native button.
            val preview = automation.windows.asSequence().mapNotNull { it.root }.flatMap {
                (it.findAccessibilityNodeInfosByText("Preview") + it.findAccessibilityNodeInfosByText("预览")).asSequence()
            }.first { it.isVisibleToUser && it.isClickable }
            assertTrue(preview.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("page-preview-open") { automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("SYNTHETIC_ASSIST_PAGE_VISIBLE")?.isNotEmpty() == true }
            val root = automation.rootInActiveWindow
            assertTrue(root.findAccessibilityNodeInfosByText("SYNTHETIC_PASSWORD_MUST_NOT_TRANSFER").isEmpty())
            assertTrue(root.findAccessibilityNodeInfosByText("SYNTHETIC_HIDDEN_MUST_NOT_TRANSFER").isEmpty())
            assertTrue(root.findAccessibilityNodeInfosByText("SYNTHETIC_OFFSCREEN_MUST_NOT_TRANSFER").isEmpty())
            assertTrue(root.findAccessibilityNodeInfosByText("MUST_NOT_TRANSFER").isEmpty())
            shell("input keyevent 4"); shell("cmd voiceinteraction hide")
            assertTrue(shell("am start -W -f 0x10008000 -n $component --ez secure true").contains("Status: ok"))
            shell("input keyevent 219")
            await("secure-session-open") { findNode("assistant_input") != null }
            delay(1_500)
            assertNull(findNode("assistant_page_toggle"))
        }
    }

    @Test fun currentPageSummaryReturnsToTheSystemPanel() = runBlocking<Unit> {
        enabled()
        val args = InstrumentationRegistry.getArguments()
        val run = args.getString("i01RunId")
        val official = args.getString("i01OfficialFlash") == "true"
        if (official) check(android.os.Build.MODEL == "V2302A" && android.os.Build.MANUFACTURER == "vivo")
        assumeTrue("An I01 evidence run ID is required", run != null)
        check(requireNotNull(run).matches(Regex("[a-z0-9-]+")))
        val directory = File(context.getExternalFilesDir(null), "d4-i01/$run")
        check(directory.mkdirs())
        val evidence = JSONObject().put("model_endpoint", if (official) "api.deepseek.com" else "loopback")
        fun capture(name: String) {
            val screenshot = requireNotNull(automation.takeScreenshot())
            try {
                File(directory, "$name.png").outputStream().use {
                    check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { screenshot.recycle() }
        }
        fun click(id: String) = assertTrue(id, requireNotNull(findNode(id)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        try {
            withSystemAssistantRole {
                val summary = "社区图书馆周六上午10点起提供自助借还，每次最多借3本。"
                fixture(modelFactory = { TaskRuntimeDiagnosticTest.GatedModel(summary.substringBefore("，"), "，" + summary.substringAfter("，")) }) { fixture ->
                    val runtime = withContext(Dispatchers.Main) { runtimeEntry.runtime() }
                    assertTrue(runtime.activeConversations.value.isEmpty())
                    val sourcePackage = instrumentation.context.packageName
                    val component = "$sourcePackage/${AssistantContextFixtureActivity::class.java.name}"
                    val instruction = AppLocale.wrapContext(context).getString(R.string.assistant_summarize_page)
                    val settings = runtimeEntry.settings()
                    val chosen = if (official) {
                        requireNotNull(settings.state.value.assistantModelSelection).also {
                            check(it.providerId == "provider-deepseek" && it.modelName == "deepseek-flash")
                        }
                    } else {
                        val providerId = requireNotNull(data.chats().getConversation(fixture.owner)).providerId
                        AssistantModelSelection(providerId, "deepseek-flash").also(settings::setAssistantModelSelection)
                    }
                    val provider = requireNotNull(data.providers().getProvider(chosen.providerId))
                    if (official) {
                        check(provider.isEnabled && java.net.URI(provider.baseUrl).let { it.scheme == "https" && it.host == "api.deepseek.com" })
                        check(!data.providers().getApiKey(provider).isNullOrBlank())
                    } else assertEquals("http://127.0.0.1:${fixture.model.port}/v1", provider.baseUrl)
                    val originalIds = if (official) data.chats().observeConversations().first().map { it.id }.toSet() else emptySet()
                    val controls = context.getSharedPreferences("app_controls", Context.MODE_PRIVATE)
                    val hadUsageSetting = controls.contains("usage_tracking_enabled")
                    val priorUsage = settings.state.value.usageTrackingEnabled
                    val wire = if (official) OfficialAssistantRequestWire(
                        client = data.client() as KtorOpenAiCompatibleClient,
                        directory = directory,
                        sample = "I01",
                        maxInputBytes = 24_576
                    ) { payload ->
                        val messages = payload.getJSONArray("messages")
                        val system = (0 until messages.length()).map(messages::getJSONObject).filter { it.getString("role") == "system" }
                            .joinToString("\n") { it.optString("content") }
                        val users = (0 until messages.length()).map(messages::getJSONObject).filter { it.getString("role") == "user" }
                        val user = users.single().getString("content")
                        check(system.contains("Summarize the attached current page") && user.contains("Source package: $sourcePackage") &&
                            user.contains(AssistantContextFixtureActivity.SUMMARY_PAGE_TEXT) &&
                            !payload.toString().contains("MUST_NOT_TRANSFER")) { "I01 request failed the synthetic page check" }
                    } else null
                    fun requestCount() = wire?.requestCount ?: fixture.model.requests.get()
                    if (official) settings.setUsageTrackingEnabled(true)
                    try {
                        check(prefs.edit().remove("conversation_id").commit())
                        assertTrue(shell("am start -W -f 0x10008000 -n $component --ez summary true").contains("Status: ok"))
                        shell("input keyevent 219")
                        await("i01-page-and-selected-model") {
                            findNode("assistant_page_toggle") != null &&
                                findNode("assistant_send")?.let { it.isEnabled && it.text?.toString() == instruction } == true &&
                                findNode("assistant_configuration")?.text?.toString() == "${provider.displayName} · ${chosen.modelName}"
                        }
                        assertFalse(requireNotNull(findNode("assistant_page_toggle")).isChecked)
                        capture("01-page-entry")
                        val input = requireNotNull(findNode("assistant_input"))
                        assertTrue(input.isShowingHintText || input.text.isNullOrEmpty())
                        assertEquals(0, requestCount())

                        val started = SystemClock.elapsedRealtime()
                        click("assistant_send")
                        await("i01-request-observed") {
                            if (requestCount() == 1) true else {
                                if (official) {
                                    val owner = prefs.getString("conversation_id", null)
                                    if (owner != null && runtimeEntry.journal().latest(owner)?.state in setOf("Failed", "Stopped", "Paused")) {
                                        val answer = data.chats().getMessages(owner).lastOrNull { it.role == MessageRole.Assistant }
                                        error("I01 ended before dispatch: ${answer?.content.orEmpty().take(300)}")
                                    }
                                }
                                false
                            }
                        }
                        evidence.put("send_to_request_ms", SystemClock.elapsedRealtime() - started)
                        val request = wire?.request ?: JSONObject(fixture.model.bodies.single())
                        assertEquals(chosen.modelName, request.getString("model"))
                        val requestMessages = request.getJSONArray("messages")
                        val prompt = (0 until requestMessages.length()).joinToString("\n") {
                            requestMessages.getJSONObject(it).optString("content")
                        }
                        assertTrue("Visible page text", prompt.contains(AssistantContextFixtureActivity.SUMMARY_PAGE_TEXT))
                        assertTrue("Page source package", prompt.contains("Source package: $sourcePackage"))
                        assertTrue("Clean page URL", prompt.contains("https://example.test/current-page"))
                        assertFalse("Hidden text and URL credentials", prompt.contains("MUST_NOT_TRANSFER"))
                        val owner = requireNotNull(prefs.getString("conversation_id", null))
                        assertNotEquals(fixture.owner, owner)
                        assertNotEquals(fixture.editor, owner)
                        assertEquals(chosen, AssistantModelSelection.from(requireNotNull(data.chats().getConversation(owner))))
                        if (!official) await("i01-progress-in-origin") { findNode("assistant_stop") != null }
                        if (findNode("assistant_stop") != null) capture("02-running-in-panel")
                        fixture.model.release()
                        val journal = runtimeEntry.journal()
                        await("i01-task-completed") {
                            val state = journal.latest(owner)?.state
                            if (official && state in setOf("Failed", "Stopped", "Paused")) {
                                val last = data.chats().getMessages(owner).lastOrNull { it.role == MessageRole.Assistant }
                                error("I01 ended as $state: ${last?.content.orEmpty().take(300)}")
                            }
                            state == "Completed" && owner !in runtime.activeConversations.value
                        }
                        evidence.put("send_to_completed_ms", SystemClock.elapsedRealtime() - started)
                        val completed = requireNotNull(journal.latest(owner))
                        val messages = data.chats().getMessages(owner)
                        val sent = messages.single { it.role == MessageRole.User }
                        val answer = messages.single { it.role == MessageRole.Assistant }
                        assertEquals("", sent.content)
                        assertEquals(instruction, data.chats().getConversation(owner)?.title)
                        assertTrue(sent.attachmentText.orEmpty().contains(AssistantContextFixtureActivity.SUMMARY_PAGE_TEXT))
                        assertEquals(answer.id, completed.assistantMessageId)
                        assertEquals(MessageStatus.Complete, answer.status)
                        if (official) {
                            val facts = listOf("周六|星期六|礼拜六", "10\\s*[点时:：]|十[点时]", "自助", "借还|借阅|借书", "([3三])\\s*本")
                            assertTrue(answer.content, facts.all { Regex(it).containsMatchIn(answer.content) })
                        } else assertEquals(summary, answer.content)
                        await("i01-answer-in-origin") { findNode("assistant_output")?.text?.toString() == answer.content }
                        evidence.put("send_to_answer_visible_ms", SystemClock.elapsedRealtime() - started)
                        capture("03-summary-result")
                        click("assistant_close")
                        await("i01-origin-dismissed") { findNode("assistant_panel_root") == null }
                        shell("input keyevent 219")
                        await("i01-same-answer-on-reopen") { findNode("assistant_output")?.text?.toString() == answer.content }
                        assertEquals(1, requestCount())
                        assertEquals(completed.id, journal.latest(owner)?.id)
                        assertEquals(messages.map { it.id }, data.chats().getMessages(owner).map { it.id })
                        open(MainActivity::class.java) { click("assistant_full_chat") }
                        await("i01-same-task-in-full-chat") {
                            runtime.uiState.value.currentConversation?.id == owner &&
                                runtime.uiState.value.messages.any { it.id == answer.id && it.content == answer.content }
                        }
                        assertEquals(1, requestCount())
                        assertTrue(data.chats().getMessages(fixture.editor).isEmpty())
                        assertTrue(data.chats().getMessages(fixture.owner).isEmpty())
                        evidence.put("source_package", sourcePackage).put("provider", provider.displayName)
                            .put("configured_model", chosen.modelName).put("requested_model", request.getString("model"))
                            .put("task", completed.id).put("conversation", owner).put("assistant_message", answer.id)
                            .put("answer", answer.content).put("state", completed.state)
                            .put("local_requests", fixture.model.requests.get()).put("official_requests", wire?.requestCount ?: 0)
                            .put("page_attachment_selected_separately", false).put("reopen_needed_for_result", false)
                            .put("same_task_in_full_chat", true)
                            .put("same_task_after_reopen", true).put("non_visible_or_private_text_omitted", true)
                    } finally {
                        if (wire != null) withContext(NonCancellable) {
                            try {
                                val owner = prefs.getString("conversation_id", null)?.takeUnless(originalIds::contains)
                                if (owner != null) {
                                    val chat = requireNotNull(data.chats().getConversation(owner))
                                    check(chat.providerId == provider.id && chat.modelName == chosen.modelName)
                                    val users = data.chats().getMessages(owner).filter { it.role == MessageRole.User }
                                    check(users.all { it.content == instruction &&
                                        it.attachmentText.orEmpty().contains(AssistantContextFixtureActivity.SUMMARY_PAGE_TEXT) })
                                    withContext(Dispatchers.Main) { runtime.stopFromAssistant(owner) }
                                    await("official-test-task-idle") { owner !in runtime.activeConversations.value }
                                    wire.reportUsage(data.usage().observe(owner).first())
                                    withContext(Dispatchers.Main) { runtime.deleteConversation(owner) }
                                    await("official-test-conversation-removed") { data.chats().getConversation(owner) == null }
                                }
                            } finally {
                                wire.active = false
                                settings.setUsageTrackingEnabled(priorUsage)
                                if (!hadUsageSetting) check(controls.edit().remove("usage_tracking_enabled").commit())
                            }
                        }
                    }
                }
            }
            assertFalse(marker.exists())
            evidence.put("fixture_restored", true).put("passed", true)
        } catch (failure: Throwable) {
            evidence.put("failure", failure.toString()).put("passed", false)
            throw failure
        } finally {
            File(directory, "result.json").writeText(evidence.toString(2))
        }
    }

    private suspend fun withSystemAssistantRole(block: suspend () -> Unit) {
        assumeTrue("Temporary assistant-role testing is opt-in", InstrumentationRegistry.getArguments().getString("testAssistantRole") == "true")
        val oldHolders = shell("cmd role get-role-holders --user 0 android.app.role.ASSISTANT").trim().lines().filter { it.isNotBlank() }
        val keys = listOf("assistant", "voice_interaction_service", "voice_recognition_service")
        val oldSettings = keys.associateWith { shell("settings get secure $it").trim() }
        val info = automation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        if (oldHolders == listOf(context.packageName) &&
            oldSettings.getValue("voice_interaction_service").startsWith("${context.packageName}/")) {
            try { block() } finally { shell("cmd voiceinteraction hide") }
            return
        }
        try {
            val result = shell("cmd role add-role-holder --user 0 android.app.role.ASSISTANT ${context.packageName} 0")
            assertFalse(result, result.contains("Error", true))
            await("system-role-selected") { shell("settings get secure voice_interaction_service").contains("FloryxVoiceInteractionService") }
            block()
        } finally {
            shell("cmd voiceinteraction hide")
            shell("input keyevent 4")
            shell("cmd role remove-role-holder --user 0 android.app.role.ASSISTANT ${context.packageName} 0")
            oldHolders.forEach { shell("cmd role add-role-holder --user 0 android.app.role.ASSISTANT $it 0") }
            delay(800)
            // UiAutomation executes argv directly, so quoted empty values would be stored literally.
            // Restore null/empty/component values exactly through the public settings API.
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                oldSettings.forEach { (key, value) ->
                    check(android.provider.Settings.Secure.putString(context.contentResolver, key, value.takeUnless { it == "null" }))
                }
            } finally { automation.dropShellPermissionIdentity() }
            await("system-role-restored") { keys.all { shell("settings get secure $it").trim() == oldSettings[it] } }
        }
    }

    @Test fun restoreAnAbortedFixture() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("restoreSystemAssistantFixture") == "true")
        restoreFixture()
    }

    @Test fun inspectRecentRegressionFixtureRouting() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("inspectSystemAssistantRegression") == "true")
        val since = requireNotNull(args.getString("regressionSince")).toLong()
        for (chat in data.chats().observeConversations().first()) {
            val messages = data.chats().getMessages(chat.id)
            for (user in messages.filter { it.createdAt >= since && it.role == MessageRole.User && it.content.startsWith("ROOT_") }) {
                val provider = data.providers().getProvider(chat.providerId)
                val uri = provider?.baseUrl?.let(android.net.Uri::parse)
                val report = JSONObject().apply {
                    put("conversationId", chat.id); put("userMessageId", user.id); put("createdAt", user.createdAt)
                    put("providerId", chat.providerId); put("endpointHost", uri?.host); put("endpointPort", uri?.port)
                    put("instruction", user.content)
                    put("followingMessageIds", org.json.JSONArray(messages.dropWhile { it.id != user.id }.drop(1)
                        .takeWhile { it.role != MessageRole.User }.map { it.id }))
                    put("priorMessages", messages.indexOfFirst { it.id == user.id })
                    put("titleIsSynthetic", chat.title.startsWith("ROOT_ALLOW_QA"))
                    put("checkpointThrough", data.chats().getContextCheckpoint(chat.id)?.throughMessageId)
                    put("followingDetails", org.json.JSONArray(messages.dropWhile { it.id != user.id }.drop(1)
                        .takeWhile { it.role != MessageRole.User }.map { message -> JSONObject().apply {
                            put("role", message.role.name); put("status", message.status.name)
                            put("contentPrefix", message.content.take(500))
                        } }))
                }
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nREGRESSION_ROUTING $report\n") })
            }
        }
    }

    @Test fun removeExactlyTheMisroutedSyntheticRound() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cleanupSystemAssistantRegression") == "true")
        val owner = requireNotNull(args.getString("regressionConversation"))
        val ids = requireNotNull(args.getString("regressionMessageIds")).split(',')
        check(ids.size == 4 && ids.distinct().size == 4)
        val db = assistant.database()
        val before = db.messageDao().getMessages(owner)
        val start = before.indexOfFirst { it.id == ids.first() }
        check(start >= 0)
        val round = before.drop(start).takeWhile { it.id == ids.first() || it.role != MessageRole.User.name }
        check(round.map { it.id } == ids)
        check(round.first().role == MessageRole.User.name && round.first().content == "ROOT_ALLOW_QA run two harmless system commands")
        check(data.chats().getContextCheckpoint(owner) == null) { "Review a context checkpoint before cleanup" }
        val run = db.taskRunDao().latest(owner)
        check(run?.assistantMessageId in ids && run?.instruction == round.first().content)
        val backup = JSONObject().apply {
            put("conversationId", owner)
            put("taskRunId", run!!.id)
            put("messages", org.json.JSONArray(round.map { row -> JSONObject().apply {
                put("id", row.id); put("role", row.role); put("content", row.content); put("status", row.status)
                put("createdAt", row.createdAt); put("updatedAt", row.updatedAt)
                put("reasoningContent", row.reasoningContent); put("executionContext", row.executionContext)
            } }))
        }
        context.getFileStreamPath("system-assistant-misroute-backup.json").writeText(backup.toString())
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("DELETE FROM task_runs WHERE id = ? AND conversationId = ?", arrayOf(run!!.id, owner))
            ids.forEach { id -> sql.execSQL("DELETE FROM messages WHERE id = ? AND conversationId = ?", arrayOf(id, owner)) }
        }
        assertEquals(before.filterNot { it.id in ids }.map { it.id }, db.messageDao().getMessages(owner).map { it.id })
        assertNull(db.taskRunDao().get(run!!.id))
        val usage = db.usageDao().observe(owner).first().filter { it.createdAt >= round.first().createdAt && it.createdAt <= round.last().updatedAt }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nMISROUTED_ROUND_REMOVED messages=4 existingMessagesPreserved=$start usageRecordsRetained=${usage.size}\n") })
    }
}
