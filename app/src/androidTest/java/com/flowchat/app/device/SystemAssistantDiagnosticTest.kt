package com.flowchat.app.device

import android.app.Activity
import android.app.Application
import android.app.UiAutomation
import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.withTransaction
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.SystemAssistantDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.presentation.assistant.AssistPageContext
import com.flowchat.app.presentation.assistant.AssistantPanelActivity
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in: synthetic model/page only. All fixture settings and data are durably restored. */
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
    private suspend fun await(label: String, check: suspend () -> Boolean) {
        try { withTimeout(25_000) { while (!check()) delay(50) } }
        catch (error: TimeoutCancellationException) { throw AssertionError("Timed out: $label", error) }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nSYSTEM_ASSISTANT $label\n") })
    }
    private suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
    }
    private suspend fun <T : Activity> open(type: Class<T>, launch: (suspend () -> Unit)? = null): T {
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
        }
        withContext(Dispatchers.IO) { check(context.getSharedPreferences("app_controls", Context.MODE_PRIVATE).edit().commit()) }
        marker.delete()
    }

    private data class Fixture(val editor: String, val owner: String, val model: TaskRuntimeDiagnosticTest.GatedModel)
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
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
        }.toString())
        val model = TaskRuntimeDiagnosticTest.GatedModel()
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
            val returns = assistant.bridge().deviceTaskControl.foregroundReturns.value
            val panel = open(AssistantPanelActivity::class.java)
            await("panel-ready") { withContext(Dispatchers.Main) { panel.findViewById<EditText>(R.id.assistant_input)?.isEnabled == true } }
            await("panel-initialized") { withContext(Dispatchers.Main) {
                panel.findViewById<EditText>(R.id.assistant_input).setText("PANEL_SYNTHETIC_REQUEST")
                panel.findViewById<Button>(R.id.assistant_send).isEnabled
            } }
            withContext(Dispatchers.Main) { assertTrue(panel.findViewById<Button>(R.id.assistant_send).performClick()) }
            await("accepted-panel-closed") { panel.isDestroyed }
            await("background-stream") { fixture.model.requests.get() == 1 && data.chats().getMessages(fixture.owner).any { it.content.contains("LOCAL_PREFIX") } }
            assertEquals(returns, assistant.bridge().deviceTaskControl.foregroundReturns.value)
            assertEquals(fixture.editor, runtime.uiState.value.currentConversation?.id)
            assertEquals("EDITOR_DRAFT_KEEP", runtime.uiState.value.input)
            assertEquals("editor-only.txt", runtime.uiState.value.pendingAttachment?.name)
            assertTrue(data.chats().getMessages(fixture.editor).isEmpty())
            assertFalse(fixture.model.bodies.single().contains("EDITOR_ATTACHMENT_KEEP"))
            val reopened = open(AssistantPanelActivity::class.java)
            await("progress-without-takeover") { withContext(Dispatchers.Main) { reopened.findViewById<Button>(R.id.assistant_stop).visibility == android.view.View.VISIBLE } }
            assertEquals(returns, assistant.bridge().deviceTaskControl.foregroundReturns.value)
            withContext(Dispatchers.Main) { reopened.findViewById<Button>(R.id.assistant_stop).performClick() }
            await("panel-stopped-task") { data.chats().getMessages(fixture.owner).lastOrNull()?.status == MessageStatus.Stopped && fixture.owner !in runtime.activeConversations.value }
            assertEquals(1, fixture.model.requests.get())
            assertEquals("EDITOR_DRAFT_KEEP", runtime.uiState.value.input)
            assertEquals(1, assistant.bridge().assistantPanels.value)
        }
        await("panel-leases-released") { assistant.bridge().assistantPanels.value == 0 }
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
                val returns = assistant.bridge().deviceTaskControl.foregroundReturns.value
                val panel = open(AssistantPanelActivity::class.java) {
                    // Tap as SystemUI would. Sending the PendingIntent from a background app is a different,
                    // intentionally restricted action on OriginOS/Android 16.
                    shell("cmd statusbar expand-notifications")
                    val title = notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString()
                    await("notification-clicked") { clickableText(title)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
                }
                await("notification-opens-progress") { withContext(Dispatchers.Main) { panel.findViewById<Button>(R.id.assistant_stop).visibility == android.view.View.VISIBLE } }
                assertEquals(returns, assistant.bridge().deviceTaskControl.foregroundReturns.value)
                withContext(Dispatchers.Main) { panel.findViewById<Button>(R.id.assistant_stop).performClick(); panel.finish() }
                await("notification-task-stopped") { fixture.owner !in runtime.activeConversations.value }
                shell("cmd statusbar add-tile $tile")
                val tilePanel = open(AssistantPanelActivity::class.java) {
                    shell("cmd statusbar expand-settings")
                    await("tile-clicked") { clickableText("Floryx")?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
                }
                assertNotNull(tilePanel.findViewById<EditText>(R.id.assistant_input))
                assertEquals(returns, assistant.bridge().deviceTaskControl.foregroundReturns.value)
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
        assumeTrue("Temporary assistant-role testing is opt-in", InstrumentationRegistry.getArguments().getString("testAssistantRole") == "true")
        val oldHolders = shell("cmd role get-role-holders --user 0 android.app.role.ASSISTANT").trim().lines().filter { it.isNotBlank() }
        val keys = listOf("assistant", "voice_interaction_service", "voice_recognition_service")
        val oldSettings = keys.associateWith { shell("settings get secure $it").trim() }
        val info = automation.serviceInfo
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        try {
            val result = shell("cmd role add-role-holder --user 0 android.app.role.ASSISTANT ${context.packageName} 0")
            assertFalse(result, result.contains("Error", true))
            await("system-role-selected") { shell("settings get secure voice_interaction_service").contains("FloryxVoiceInteractionService") }
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
