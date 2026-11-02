package com.flowchat.app.device

import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.attachment.ImageAttachmentStore
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.data.preferences.AppSettingsStore
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.DeviceDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TaskRuntimeDiagnosticsEntryPoint
import com.flowchat.app.domain.model.MessageRole
import com.flowchat.app.domain.model.MessageStatus
import com.flowchat.app.domain.model.ProviderConfig
import com.flowchat.app.presentation.overlay.FloatingAssistantService
import com.flowchat.app.presentation.overlay.ScreenQuestionPhase
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Device checks use synthetic pixels and a local model, preserving personal data and settings. */
class ScreenQuestionDiagnosticTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val data get() = EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java)
    private val entry get() = EntryPointAccessors.fromApplication(context, TaskRuntimeDiagnosticsEntryPoint::class.java)
    private val bridge get() = EntryPointAccessors.fromApplication(context, DeviceDiagnosticsEntryPoint::class.java).bridge()
    private val marker get() = context.getFileStreamPath("screen-question-fixture.json")
    private suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
    }
    private fun saveScreenshot(name: String) {
        automation.takeScreenshot()?.let { bitmap ->
            try { context.getFileStreamPath(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }
    private suspend fun await(label: String, check: suspend () -> Boolean) {
        try { withTimeout(25_000) { while (!check()) delay(50) } }
        catch (e: TimeoutCancellationException) { saveScreenshot("answer-mode-failure.png"); throw AssertionError("Timed out: $label", e) }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nANSWER_MODE $label\n") })
    }
    private fun window(title: String): View? {
        var found: View? = null
        instrumentation.runOnMainSync { found = WindowInspector.getGlobalWindowViews().firstOrNull {
            (it.layoutParams as? WindowManager.LayoutParams)?.title == title } }
        return found
    }
    private fun bounds(view: View): Rect {
        val rect = Rect()
        instrumentation.runOnMainSync {
            val p = IntArray(2); view.getLocationOnScreen(p)
            rect.set(p[0], p[1], p[0] + view.width, p[1] + view.height)
        }
        return rect
    }
    private fun appNodes(): List<AccessibilityNodeInfo> {
        fun children(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
            add(node); for (i in 0 until node.childCount) node.getChild(i)?.let { addAll(children(it)) }
        }
        return automation.windows.mapNotNull { it.root }.flatMap(::children)
            .filter { it.packageName?.toString() == context.packageName && it.isVisibleToUser }
    }
    private fun clickAppText(text: String): Boolean {
        for (candidate in appNodes().filter { it.contentDescription?.toString() == text || it.text?.toString() == text }) {
            var node: AccessibilityNodeInfo? = candidate
            repeat(8) {
                val current = node
                if (current?.isClickable == true && current.isEnabled) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node = current?.parent
            }
        }
        return false
    }
    // The answer-mode subpage has one toggle; its label and switch are separate accessibility nodes.
    private fun modeSwitch(): AccessibilityNodeInfo? {
        val nodes = appNodes()
        if (nodes.none { it.text?.toString() == context.getString(R.string.photo_answer_gesture) }) return null
        return nodes.firstOrNull { it.isCheckable && it.isClickable }
    }
    private suspend fun gesture(rect: Rect, hold: Long = 60, drag: Int = 0) {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.exactCenterX(), y, 0)
            e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try {
                val delivered = automation.injectInputEvent(e, true)
                // A long press hides/recreates the bubble for capture, removing its original input channel.
                // The request/banner assertions below verify that the gesture completed its action.
                assertTrue(delivered || (action == MotionEvent.ACTION_UP && hold >= 500 && drag == 0))
            } finally { e.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, rect.exactCenterY())
        if (drag != 0) repeat(12) { delay(20); event(MotionEvent.ACTION_MOVE, rect.exactCenterY() + drag * (it + 1) / 12f) }
        else delay(hold)
        event(MotionEvent.ACTION_UP, rect.exactCenterY() + drag)
        delay(250)
    }
    private suspend fun openPage(secure: Boolean = false) {
        check(shell("am start -W -f 0x10008000 -n ${instrumentation.context.packageName}/${ScreenQuestionFixtureActivity::class.java.name} --ez secure $secure").contains("Status: ok"))
        await("fixture-foreground") { FlowChatAccessibilityService.current()?.observeScreenOnDisplay(0)?.packageName == instrumentation.context.packageName }
        delay(450)
    }
    private suspend fun restore() {
        if (!marker.exists()) return
        val saved = JSONObject(marker.readText())
        val provider = saved.getString("provider"); check(provider.startsWith("screen-question-qa-"))
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        val owned = data.chats().observeConversations().first().filter { it.providerId == provider }
        withContext(Dispatchers.Main) { owned.forEach { runtime.stopFromAssistant(it.id) }; bridge.clearAnswerBanner() }
        await("fixture-stopped") { owned.none { it.id in runtime.activeConversations.value } }
        withContext(Dispatchers.Main) { owned.forEach { runtime.deleteConversation(it.id) } }
        await("fixture-removed") { data.chats().observeConversations().first().none { it.providerId == provider } }
        data.providers().deleteProvider(provider)
        saved.optString("selection").takeIf { it.isNotBlank() && data.chats().getConversation(it) != null }?.let {
            withContext(Dispatchers.Main) { runtime.selectConversation(it) }
        }
        entry.settings().apply {
            setMemoryEnabled(saved.getBoolean("memory")); setPowerModeEnabled(saved.getBoolean("power"))
            setCommandExecutionEnabled(saved.getBoolean("commands")); setBackgroundExecutionEnabled(saved.getBoolean("background"))
            setPhotoAnswerEnabled(saved.optBoolean("answerMode", true))
        }
        val positions = context.getSharedPreferences("floating_assistant_position", Context.MODE_PRIVATE)
        if (saved.has("position")) positions.edit().putInt("position_y", saved.getInt("position")).commit()
        else positions.edit().remove("position_y").commit()
        withContext(Dispatchers.Main) { bridge.clearScreenQuestion() }
        marker.delete()
    }

    @Test fun answerModeStreamsAtTheTopKeepsTheBubbleFittedAndDoesNotNeedSystemNotifications() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runScreenQuestionDiagnostics") == "true")
        check(Settings.canDrawOverlays(context))
        if (FlowChatAccessibilityService.current() == null) {
            val originalServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val component = "${context.packageName}/${FlowChatAccessibilityService::class.java.name}"
            check(component in originalServices.split(':')) { "Previously authorized accessibility service required" }
            automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
            try {
                try {
                    check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        originalServices.split(':').filterNot { it == component }.joinToString(":")))
                    delay(200)
                } finally { check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, originalServices)) }
            } finally { automation.dropShellPermissionIdentity() }
        }
        await("accessibility-connected") { FlowChatAccessibilityService.current() != null }
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        restore()
        val runtime = withContext(Dispatchers.Main) { entry.runtime() }
        await("runtime-ready") { runtime.uiState.value.providers.isNotEmpty() }
        val original = entry.settings().state.value
        val manager = context.getSystemService(NotificationManager::class.java)
        val dnd = manager.currentInterruptionFilter
        val providerId = "screen-question-qa-${UUID.randomUUID()}"
        val positions = context.getSharedPreferences("floating_assistant_position", Context.MODE_PRIVATE)
        marker.writeText(JSONObject().apply {
            put("provider", providerId); put("selection", runtime.uiState.value.currentConversation?.id.orEmpty())
            put("memory", original.memoryEnabled); put("power", original.powerModeEnabled)
            put("commands", original.commandExecutionEnabled); put("background", original.backgroundExecutionEnabled)
            put("answerMode", original.photoAnswerEnabled)
            if (positions.contains("position_y")) put("position", positions.getInt("position_y", 0))
        }.toString())
        val model = TaskRuntimeDiagnosticTest.GatedModel(prefix = "1. 56", suffix = "\n2. 12")
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            entry.settings().apply {
                setMemoryEnabled(false); setCommandExecutionEnabled(true); setPowerModeEnabled(false)
                setBackgroundExecutionEnabled(true); setPhotoAnswerEnabled(true)
            }
            val provider = ProviderConfig(id = providerId, displayName = "Answer banner local QA", baseUrl = "http://127.0.0.1:${model.port}/v1", defaultModel = "screen-local")
            data.providers().upsertProvider(provider, null)
            val owner = data.chats().createConversation(provider.id, provider.defaultModel).id
            withContext(Dispatchers.Main) {
                runtime.selectConversation(owner); runtime.updateInput("KEEP_SCREEN_DRAFT")
                runtime.attachTextFile("screen-draft.txt", "KEEP_SCREEN_ATTACHMENT")
                context.startForegroundService(Intent(context, FloatingAssistantService::class.java))
            }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            await("drawer-opened") { clickAppText(context.getString(R.string.open_navigation_drawer)) }
            await("settings-opened") { clickAppText(context.getString(R.string.settings)) }
            await("answer-mode-settings-opened") { clickAppText(context.getString(R.string.photo_answer_title)) }
            await("answer-mode-switch-visible") { modeSwitch() != null }
            await("answer-mode-switch-enabled") { modeSwitch()?.isChecked == true }
            assertFalse(appNodes().any { it.contentDescription?.toString() == "顶部显示答案" })
            assertTrue(requireNotNull(modeSwitch()).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("unified-mode-disabled") { !entry.settings().state.value.photoAnswerEnabled }
            withContext(Dispatchers.Main) { assertFalse(runtime.recognizeScreen(owner)) }
            assertFalse(AppSettingsStore(context).state.value.photoAnswerEnabled)
            assertTrue(requireNotNull(modeSwitch()).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("unified-mode-enabled") { entry.settings().state.value.photoAnswerEnabled }
            openPage()
            await("bubble-visible") { window("FloryxOutput")?.visibility == View.VISIBLE }
            val bubble = requireNotNull(window("FloryxOutput"))
            val diameter = (48 * context.resources.displayMetrics.density).toInt()
            assertEquals(diameter, bounds(bubble).width())
            val full = requireNotNull(automation.takeScreenshot())
            try {
                val b = bounds(bubble); val r = b.width() * .40f
                for ((dx, dy) in listOf(r to 0f, -r to 0f, 0f to r, 0f to -r)) {
                    val pixel = full.getPixel((b.exactCenterX() + dx).toInt(), (b.exactCenterY() + dy).toInt())
                    assertTrue("The logo must fill the circle without a wide dark rim", Color.red(pixel) > 210 && Color.green(pixel) > 210 && Color.blue(pixel) > 210)
                }
            } finally { full.recycle() }
            saveScreenshot("answer-mode-fitted-bubble.png")
            val before = bounds(bubble)
            gesture(before, drag = 120)
            assertTrue(bounds(bubble).top > before.top + 40)
            assertEquals(0, model.requests.get())
            gesture(bounds(bubble), hold = 1_300)
            await("screenshot-request-received") { model.requests.get() == 1 }
            await("live-top-banner-visible") { window("FloryxAnswerBanner")?.visibility == View.VISIBLE && appNodes().any { it.text?.toString() == "1. 56" } }
            assertTrue(bridge.answerBanner.value!!.isStreaming)
            assertTrue(bounds(requireNotNull(window("FloryxAnswerBanner"))).top < context.resources.displayMetrics.heightPixels / 4)
            assertEquals(diameter, bounds(bubble).width())
            assertNull(window("FloryxControls"))
            assertEquals(instrumentation.context.packageName, FlowChatAccessibilityService.current()?.observeScreenOnDisplay(0)?.packageName)
            val payload = JSONObject(model.bodies.single())
            assertFalse(payload.has("tools"))
            assertFalse(model.bodies.single().contains("KEEP_SCREEN_ATTACHMENT"))
            val user = data.chats().getMessages(owner).first { it.role == MessageRole.User }
            val file = ImageAttachmentStore.resolve(context, requireNotNull(user.attachmentImagePath))
            val bitmap = requireNotNull(BitmapFactory.decodeFile(file.absolutePath))
            try {
                assertTrue(bitmap.width > 900 && bitmap.height > bitmap.width)
                assertEquals(Color.rgb(30, 70, 235), bitmap.getPixel(bitmap.width / 4, bitmap.height * 66 / 100))
            } finally { bitmap.recycle() }
            assertTrue(payload.toString().contains("image_url"))
            assertEquals("KEEP_SCREEN_DRAFT", runtime.uiState.value.input)
            model.release()
            await("completed-answer-stays-in-banner") {
                bridge.answerBanner.value?.let { !it.isStreaming && it.text == "1. 56\n2. 12" } == true &&
                    appNodes().any { it.text?.toString() == "1. 56\n2. 12" }
            }
            assertEquals(dnd, manager.currentInterruptionFilter)
            assertTrue(manager.activeNotifications.none { it.tag?.startsWith("photo-answer:") == true })
            assertEquals("The banner should contain its status label and model text", listOf(context.getString(R.string.answer_complete_status), "1. 56\n2. 12"),
                appNodes().mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) }.distinct())
            assertTrue(appNodes().any { it.contentDescription?.toString() == context.getString(R.string.answer_banner_close) })
            val bannerFrame = requireNotNull(automation.takeScreenshot())
            try {
                val banner = bounds(requireNotNull(window("FloryxAnswerBanner")))
                assertEquals("The answer card should use the selected warm dark background", Color.rgb(36, 35, 31),
                    bannerFrame.getPixel(banner.left + 2, banner.centerY()))
            } finally { bannerFrame.recycle() }
            saveScreenshot("answer-mode-live-banner.png")
            val capturedAgain = bridge.withDisplayCapture {
                assertNull("The previous answer banner must be hidden before capturing", window("FloryxAnswerBanner"))
                entry.deviceGateway().captureForegroundScreen()
            }
            assertTrue(capturedAgain.success)
            val pixels = java.util.Base64.getDecoder().decode(requireNotNull(capturedAgain.screenImage).image.base64)
            val repeated = requireNotNull(BitmapFactory.decodeByteArray(pixels, 0, pixels.size))
            try { assertEquals("Old banner pixels must not enter a fresh screenshot", Color.WHITE,
                repeated.getPixel(repeated.width / 4, repeated.height / 10)) } finally { repeated.recycle() }
            await("banner-restored-after-capture") { window("FloryxAnswerBanner") != null }
            val other = data.chats().createConversation(provider.id, provider.defaultModel).id
            withContext(Dispatchers.Main) { runtime.selectConversation(other) }
            await("banner-opens-conversation") { clickAppText("1. 56\n2. 12") }
            await("banner-opens-correct-chat") { bridge.appInForeground.value && runtime.uiState.value.currentConversation?.id == owner && appNodes().any { it.className?.toString() == "android.widget.EditText" } }
            openPage(secure = true)
            gesture(bounds(requireNotNull(window("FloryxOutput"))), hold = 900)
            await("secure-capture-rejected") { bridge.screenQuestion.value.phase == ScreenQuestionPhase.Failed }
            assertNull(window("FloryxAnswerBanner"))
            assertEquals(1, model.requests.get())
            openPage()
            model.prepareNext()
            withContext(Dispatchers.Main) { assertTrue(runtime.recognizeScreen(owner)); runtime.selectConversation(other); runtime.updateInput("KEEP_OTHER_EDITOR_DRAFT") }
            await("new-banner-correct-owner") { model.requests.get() == 2 && window("FloryxAnswerBanner") != null }
            assertEquals(owner, bridge.answerBanner.value?.conversationId)
            assertTrue(data.chats().getMessages(other).isEmpty())
            await("close-banner") { clickAppText(context.getString(R.string.answer_banner_close)) }
            await("banner-dismissed") { window("FloryxAnswerBanner") == null }
            model.release()
            await("dismissed-banner-does-not-reopen") { owner !in runtime.activeConversations.value && bridge.answerBanner.value?.isStreaming == false }
            assertNull(window("FloryxAnswerBanner"))
            model.prepareNext()
            withContext(Dispatchers.Main) { assertTrue(runtime.recognizeScreen(owner)) }
            await("third-live-banner") { model.requests.get() == 3 && window("FloryxAnswerBanner") != null }
            withContext(Dispatchers.Main) { runtime.setPhotoAnswerEnabled(false) }
            await("mode-off-removes-banner") { window("FloryxAnswerBanner") == null && bridge.answerBanner.value == null }
            model.close()
            await("interrupted-answer-retained") { owner !in runtime.activeConversations.value && data.chats().getMessages(owner).last().status == MessageStatus.Failed }
            assertNull(window("FloryxAnswerBanner"))
            assertEquals("KEEP_OTHER_EDITOR_DRAFT", runtime.uiState.value.input)
            assertEquals(0, bridge.displayCaptureCount.value)
        } finally {
            model.close()
            try {
                withContext(Dispatchers.Main) {
                    val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    androidx.test.runner.lifecycle.Stage.values().flatMap { monitor.getActivitiesInStage(it) }
                        .filterIsInstance<MainActivity>().distinct().forEach { if (!it.isDestroyed) it.finish() }
                }
                runCatching { scenario?.close() }
            } finally {
                shell("am broadcast -a ${instrumentation.context.packageName}.FINISH_SCREEN_QUESTION -p ${instrumentation.context.packageName}")
                restore()
            }
        }
    }
}
