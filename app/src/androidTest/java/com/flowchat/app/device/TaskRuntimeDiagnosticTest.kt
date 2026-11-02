package com.flowchat.app.device

import android.app.ActivityManager
import android.app.Activity
import android.app.Application
import android.app.UiAutomation
import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.data.task.TaskExecutionService
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.domain.device.ShizukuConnectionStatus
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.presentation.chat.ChatViewModel
import dagger.hilt.android.EntryPointAccessors
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Local HTTP, actual singleton runtime/Room/service, no paid model or personal app operations. */
class TaskRuntimeDiagnosticTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("runTaskRuntimeDiagnostics") == "true")
    private fun entry() = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java)
    private fun data() = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
    private val fixtureState get() = context.getFileStreamPath("task-runtime-ui-fixture.json")
    private fun saveFixture(settings: com.flowchat.app.data.preferences.AppSettings, providerId: String, owner: String?) {
        fixtureState.writeText(org.json.JSONObject().apply {
            put("providerId", providerId); put("owner", owner.orEmpty())
            put("powerModeEnabled", settings.powerModeEnabled)
            put("memoryEnabled", settings.memoryEnabled)
            put("commandExecutionEnabled", settings.commandExecutionEnabled)
        }.toString())
    }
    private suspend fun flushFixtureSettings() = withContext(Dispatchers.IO) {
        // Instrumentation can be terminated immediately after returning; wait for earlier apply() writes too.
        check(context.getSharedPreferences("app_controls", Context.MODE_PRIVATE)
            .edit().commit()) { "Could not persist restored fixture settings" }
    }
    private suspend fun restoreFixtureIfPresent() {
        if (!fixtureState.exists()) return
        val saved = org.json.JSONObject(fixtureState.readText())
        val providerId = saved.getString("providerId")
        check(providerId.startsWith("task-runtime-")) { "Unexpected fixture provider" }
        val owner = saved.optString("owner")
        if (owner.isNotBlank() && data().chats().getConversation(owner)?.providerId == providerId) {
            data().chats().deleteConversation(owner)
        }
        data().providers().deleteProvider(providerId)
        entry().settings().setMemoryEnabled(saved.getBoolean("memoryEnabled"))
        entry().settings().setCommandExecutionEnabled(saved.getBoolean("commandExecutionEnabled"))
        entry().settings().setPowerModeEnabled(saved.getBoolean("powerModeEnabled"))
        flushFixtureSettings()
        fixtureState.delete()
    }
    private suspend fun openChat(): MainActivity {
        // OriginOS 6 can leave Instrumentation.startActivitySync waiting after the Activity is already created.
        // Observe application lifecycle callbacks and use the system launcher path to avoid background-start policy.
        val application = context.applicationContext as Application
        var activity: MainActivity? = null
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(value: Activity) { if (value is MainActivity) activity = value }
            override fun onActivityCreated(value: Activity, state: android.os.Bundle?) = Unit
            override fun onActivityStarted(value: Activity) = Unit
            override fun onActivityPaused(value: Activity) = Unit
            override fun onActivityStopped(value: Activity) = Unit
            override fun onActivitySaveInstanceState(value: Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(value: Activity) = Unit
        }
        withContext(Dispatchers.Main) { application.registerActivityLifecycleCallbacks(callbacks) }
        try {
            val automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val descriptor = automation.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/${MainActivity::class.java.name}")
            val result = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8) }
            check(result.contains("Status: ok")) { result }
            await("activity-resumed") {
                withContext(Dispatchers.Main) { activity?.let { !it.isFinishing && !it.isDestroyed } == true }
            }
            return requireNotNull(activity)
        } finally {
            withContext(Dispatchers.Main) { application.unregisterActivityLifecycleCallbacks(callbacks) }
        }
    }
    private suspend fun closeChat(activity: MainActivity) {
        withContext(Dispatchers.Main) { activity.finish() }
        await("activity-destroyed") { withContext(Dispatchers.Main) { activity.isDestroyed } }
    }
    private suspend fun await(label: String, condition: suspend () -> Boolean) {
        withTimeout(20_000) { while (!condition()) delay(50) }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", "\nTASK_RUNTIME $label\n") })
    }
    @Suppress("DEPRECATION")
    private fun serviceRunning(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(100).any { it.service.className == TaskExecutionService::class.java.name }

    private fun taskWakeLockHeld(): Boolean {
        val descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand("dumpsys power")
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readLines() }
            .any { it.contains("PARTIAL_WAKE_LOCK") && it.contains("Floryx:UserTask") }
    }

    @Test fun replyFinishesAfterTheActivityIsClosedAndAFreshViewModelCanStopAnotherRun() = runBlocking<Unit> {
        enabled()
        restoreFixtureIfPresent()
        val entry = entry()
        val data = data()
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        val originalSettings = entry.settings().state.value
        val previousConversation = runtime.uiState.value.currentConversation?.id
        val server = GatedModel()
        val provider = ProviderConfig(id = "task-runtime-${UUID.randomUUID()}", displayName = "Task runtime local QA",
            baseUrl = "http://127.0.0.1:${server.port}/v1", defaultModel = "task-runtime-local")
        var owner: String? = null
        var activity: MainActivity? = null
        val screenOff = InstrumentationRegistry.getArguments().getString("taskRuntimeScreenOff") == "true"
        fun powerKey(code: Int) {
            val descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .executeShellCommand("input keyevent $code")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        saveFixture(originalSettings, provider.id, null)
        try {
            entry.settings().setMemoryEnabled(false)
            entry.settings().setPowerModeEnabled(false)
            entry.settings().setCommandExecutionEnabled(false)
            data.providers().upsertProvider(provider, null)
            owner = data.chats().createConversation(provider.id, provider.defaultModel).id
            saveFixture(originalSettings, provider.id, owner)
            val conversationId = owner
            activity = openChat()
            var originalVm: ChatViewModel? = null
            withContext(Dispatchers.Main) {
                originalVm = ViewModelProvider(requireNotNull(activity))[ChatViewModel::class.java]
                originalVm!!.selectConversation(conversationId)
            }
            await("conversation-selected") { runtime.uiState.value.currentConversation?.id == conversationId }
            withContext(Dispatchers.Main) { originalVm!!.updateInput("Finish this local synthetic task"); originalVm!!.send() }
            await("stream-started") { server.requests.get() == 1 && data.chats().getMessages(conversationId).any { it.content.contains("LOCAL_PREFIX") } }
            assertTrue(serviceRunning())
            await("task-wake-lock-held") { runtime.backgroundWorkActive.value && taskWakeLockHeld() }
            val firstRun = requireNotNull(entry.journal().latest(conversationId))
            closeChat(activity); activity = null
            if (screenOff) {
                powerKey(223)
                await("physical-screen-off") { !context.getSystemService(android.os.PowerManager::class.java).isInteractive }
            }
            val backgroundMillis = InstrumentationRegistry.getArguments().getString("taskRuntimeBackgroundMillis", "500")
                .toLong().coerceIn(500, 60_000)
            delay(backgroundMillis)
            assertTrue(runtime.activeConversations.value.contains(conversationId))
            assertEquals("Running", entry.journal().get(firstRun.id)!!.state)
            await("background-retained-${backgroundMillis}ms") { serviceRunning() }
            server.release()
            await("finished-without-activity") { entry.journal().get(firstRun.id)?.state == "Completed" }
            assertTrue(data.chats().getMessages(conversationId).any { it.content == "LOCAL_PREFIX_LOCAL_FINISHED" && it.status == MessageStatus.Complete })
            await("service-released-while-idle") { !serviceRunning() }
            await("task-wake-lock-released") { !taskWakeLockHeld() }
            if (screenOff) {
                assertFalse(context.getSystemService(android.os.PowerManager::class.java).isInteractive)
                return@runBlocking
            }

            server.prepareNext()
            activity = openChat()
            withContext(Dispatchers.Main) {
                val fresh = ViewModelProvider(requireNotNull(activity))[ChatViewModel::class.java]
                assertNotSame(originalVm, fresh)
                fresh.updateInput("Stop this second local synthetic task"); fresh.send()
            }
            await("second-stream-started") { server.requests.get() == 2 && runtime.activeConversations.value.contains(conversationId) }
            val secondRun = requireNotNull(entry.journal().latest(conversationId))
            closeChat(activity)
            activity = openChat()
            withContext(Dispatchers.Main) { ViewModelProvider(requireNotNull(activity))[ChatViewModel::class.java].stop() }
            await("fresh-ui-stopped-existing-task") { entry.journal().get(secondRun.id)?.state == "Stopped" }
            assertTrue(data.chats().getMessages(conversationId).last { it.role == MessageRole.Assistant }.status == MessageStatus.Stopped)
            assertEquals(2, server.requests.get())
        } finally {
            withContext(Dispatchers.Main) {
                owner?.let { runtime.selectConversation(it); runtime.stop() }
                previousConversation?.let(runtime::selectConversation)
            }
            activity?.let { closeChat(it) }
            server.close()
            owner?.let { data.chats().deleteConversation(it) }
            data.providers().deleteProvider(provider.id)
            entry.settings().setMemoryEnabled(originalSettings.memoryEnabled)
            entry.settings().setCommandExecutionEnabled(originalSettings.commandExecutionEnabled)
            entry.settings().setPowerModeEnabled(originalSettings.powerModeEnabled)
            flushFixtureSettings()
            fixtureState.delete()
            if (screenOff) powerKey(224)
        }
    }

    @Test fun restoreAnAbortedUiFixture() = runBlocking<Unit> {
        enabled()
        assumeTrue(InstrumentationRegistry.getArguments().getString("restoreTaskRuntimeFixture") == "true")
        restoreFixtureIfPresent()
        assertFalse(fixtureState.exists())
    }

    @Test fun notificationActionStopsTheRunningTaskAndReleasesItsService() = runBlocking<Unit> {
        enabled()
        assumeTrue(android.os.Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
        restoreFixtureIfPresent()
        val entry = entry()
        val data = data()
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        val originalSettings = entry.settings().state.value
        val previousConversation = runtime.uiState.value.currentConversation?.id
        val server = GatedModel()
        val provider = ProviderConfig(id = "task-runtime-notification-${UUID.randomUUID()}", displayName = "Notification local QA",
            baseUrl = "http://127.0.0.1:${server.port}/v1", defaultModel = "notification-local")
        var owner: String? = null
        var activity: MainActivity? = null
        saveFixture(originalSettings, provider.id, null)
        try {
            entry.settings().setMemoryEnabled(false)
            entry.settings().setPowerModeEnabled(false)
            entry.settings().setCommandExecutionEnabled(false)
            data.providers().upsertProvider(provider, null)
            owner = data.chats().createConversation(provider.id, provider.defaultModel).id
            saveFixture(originalSettings, provider.id, owner)
            val conversationId = owner
            activity = openChat()
            withContext(Dispatchers.Main) { runtime.selectConversation(conversationId) }
            await("notification-conversation-selected") { runtime.uiState.value.currentConversation?.id == conversationId }
            withContext(Dispatchers.Main) { runtime.updateInput("Stop this synthetic task from its notification"); runtime.send() }
            await("notification-stream-started") { server.requests.get() == 1 }
            val run = requireNotNull(entry.journal().latest(conversationId))
            val manager = context.getSystemService(NotificationManager::class.java)
            await("task-notification-posted") { manager.activeNotifications.any { it.notification.channelId == "assistant_tasks" } }
            closeChat(activity); activity = null
            val notification = manager.activeNotifications.first { it.notification.channelId == "assistant_tasks" }.notification
            assertTrue(notification.actions.isNotEmpty())
            notification.actions.last().actionIntent.send()
            await("notification-stopped-task") { entry.journal().get(run.id)?.state == "Stopped" }
            await("notification-released-service") { !serviceRunning() }
            await("notification-released-wake-lock") { !taskWakeLockHeld() }
            assertEquals(1, server.requests.get())
        } finally {
            withContext(Dispatchers.Main) {
                owner?.let { runtime.selectConversation(it); runtime.stop() }
                previousConversation?.let(runtime::selectConversation)
            }
            activity?.let { closeChat(it) }
            server.close()
            owner?.let { data.chats().deleteConversation(it) }
            data.providers().deleteProvider(provider.id)
            entry.settings().setMemoryEnabled(originalSettings.memoryEnabled)
            entry.settings().setCommandExecutionEnabled(originalSettings.commandExecutionEnabled)
            entry.settings().setPowerModeEnabled(originalSettings.powerModeEnabled)
            flushFixtureSettings()
            fixtureState.delete()
        }
    }

    @Test fun rootAuthorizationUsesActualExecutionAndCachedServiceCanReadDeviceStatus() = runBlocking<Unit> {
        enabled()
        assumeTrue(InstrumentationRegistry.getArguments().getString("runRootConnectionDiagnostics") == "true")
        val root = entry().root()
        withContext(Dispatchers.IO) {
            root.refresh(requestPermission = true)
            assertEquals(root.state.value.toString(), ShizukuConnectionStatus.ConnectedDirectRoot, root.state.value.status)
            val first = root.serviceInfo()!!
            repeat(5) {
                assertTrue(root.withService { it.getDeviceStatus() }.contains("battery="))
                assertEquals(first.pid, root.serviceInfo()!!.pid)
            }
        }
    }

    @Test fun seedAnInterruptedRunForARealProcessRestart() = runBlocking<Unit> {
        enabled()
        assumeTrue(InstrumentationRegistry.getArguments().getString("taskRuntimeRecoveryPhase") == "seed")
        val data = data()
        val journal = entry().journal()
        journal.recoverInterrupted()
        data.chats().recoverInterruptedTasks()
        val provider = ProviderConfig(id = "runtime-recovery-${UUID.randomUUID()}", displayName = "Recovery local QA",
            baseUrl = "http://127.0.0.1:9/v1", defaultModel = "recovery-local")
        data.providers().upsertProvider(provider, null)
        val conversation = data.chats().createConversation(provider.id, provider.defaultModel)
        data.chats().appendMessage(conversation.id, MessageRole.User, "Synthetic interrupted task", MessageStatus.Sent, provider.defaultModel)
        val assistant = data.chats().appendMessage(conversation.id, MessageRole.Assistant, "SAVED_PARTIAL", MessageStatus.Streaming, provider.defaultModel)
        data.chats().updateExecutionContext(assistant.id, com.flowchat.app.domain.chat.TaskExecutionHistory.encode(listOf(
            com.flowchat.app.domain.chat.ExecutionStep("get_device_status", "{}", "unconfirmed", "No result recorded")
        )))
        val runId = UUID.randomUUID().toString()
        journal.begin(runId, conversation.id, "Synthetic interrupted task", provider.defaultModel)
        journal.attachMessage(runId, assistant.id)
        context.getFileStreamPath("task-runtime-recovery.txt").writeText(listOf(conversation.id, runId, provider.id).joinToString("\n"))
        assertEquals("Running", journal.get(runId)!!.state)
    }

    @Test fun aColdProcessRecoversEvidenceWithoutReplayingTheTask() = runBlocking<Unit> {
        enabled()
        assumeTrue(InstrumentationRegistry.getArguments().getString("taskRuntimeRecoveryPhase") == "verify")
        val metadata = context.getFileStreamPath("task-runtime-recovery.txt")
        val (owner, runId, providerId) = metadata.readLines()
        val data = data()
        val entry = entry()
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        try {
            await("cold-process-recovered-run") { entry.journal().get(runId)?.state == "Interrupted" }
            await("cold-process-recovered-evidence") {
                data.chats().getMessages(owner).last().status == MessageStatus.Stopped
            }
            val recovered = data.chats().getMessages(owner).last()
            assertTrue(recovered.content.startsWith("SAVED_PARTIAL"))
            assertTrue(recovered.executionContext.orEmpty().contains("interrupted_unconfirmed"))
            assertTrue(runtime.activeConversations.value.isEmpty())
            assertFalse(serviceRunning())
        } finally {
            data.chats().deleteConversation(owner)
            data.providers().deleteProvider(providerId)
            metadata.delete()
        }
    }

    internal class GatedModel(private val prefix: String = "LOCAL_PREFIX", private val suffix: String = "_LOCAL_FINISHED") : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val requests = AtomicInteger()
        val bodies = java.util.Collections.synchronizedList(mutableListOf<String>())
        @Volatile private var gate = CountDownLatch(1)
        @Volatile private var closed = false
        private val sockets = java.util.Collections.synchronizedList(mutableListOf<Socket>())
        init {
            thread(isDaemon = true, name = "task-runtime-local-model") {
                while (!closed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    sockets.add(socket)
                    val requestGate = gate
                    thread(isDaemon = true) {
                        runCatching { socket.use {
                            val input = it.getInputStream().buffered()
                            fun line(): String {
                                val buffer = StringBuilder()
                                while (true) {
                                    val next = input.read()
                                    if (next < 0 || next == 10) return buffer.toString().trimEnd('\r')
                                    buffer.append(next.toChar())
                                }
                            }
                            line()
                            var length = 0
                            while (true) {
                                val header = line()
                                if (header.isEmpty()) break
                                if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                            }
                            require(length in 0..2_000_000)
                            val body = ByteArray(length)
                            var offset = 0
                            while (offset < length) {
                                val count = input.read(body, offset, length - offset)
                                check(count > 0); offset += count
                            }
                            bodies.add(body.toString(Charsets.UTF_8))
                            requests.incrementAndGet()
                            val out = it.getOutputStream().bufferedWriter(Charsets.UTF_8)
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
                            fun delta(text: String) {
                                out.write("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":${org.json.JSONObject.quote(text)}}}]}\n\n")
                                out.flush()
                            }
                            delta(prefix)
                            check(requestGate.await(120, TimeUnit.SECONDS))
                            delta(suffix)
                            out.write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                            out.flush()
                        } }
                        sockets.remove(socket)
                    }
                }
            }
        }
        fun release() { gate.countDown() }
        fun prepareNext() { gate = CountDownLatch(1) }
        override fun close() {
            closed = true
            server.close()
            synchronized(sockets) { sockets.toList().forEach { runCatching { it.close() } } }
            // Close transports before unblocking writers, so a simulated disconnect cannot
            // accidentally send a successful final response while teardown races the writer.
            release()
        }
    }
}
