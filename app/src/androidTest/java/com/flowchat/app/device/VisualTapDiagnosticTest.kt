package com.flowchat.app.device

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.MainActivity
import com.flowchat.app.R
import com.flowchat.app.data.device.FlowChatAccessibilityService
import com.flowchat.app.diagnostics.AttachmentDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.DeviceDiagnosticsEntryPoint
import com.flowchat.app.diagnostics.TerminalDiagnosticsEntryPoint
import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.model.*
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

/** Opt-in synthetic pixel targets and a strict local model. No paid APIs or personal app content. */
class VisualTapDiagnosticTest {
    private val compose = createEmptyComposeRule()
    // Direct device tests must use the real frame clock, especially while rotating Compose activities.
    @get:Rule val modelComposeRule = TestRule { base, description ->
        if (description.methodName in setOf("screenshotsDriveTheModelToolLoopAndReturnedPixelsVerifyTheActualTap",
                "liveFlashRefinesAndClicksASmallSyntheticTarget")) compose.apply(base, description) else base
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device by lazy { EntryPointAccessors.fromApplication(context, DeviceDiagnosticsEntryPoint::class.java) }
    private val data by lazy { EntryPointAccessors.fromApplication(context, AttachmentDiagnosticsEntryPoint::class.java) }

    private suspend fun setup() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runVisualTapDiagnostics") == "true")
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        returnToApp()
        delay(350)
        val original = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = ComponentName(context, FlowChatAccessibilityService::class.java).flattenToString()
        check(component in original.split(':')) { "Previously authorized Floryx Dev accessibility is required." }
        if (FlowChatAccessibilityService.current() == null) {
            automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
            try {
                try {
                    check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        original.split(':').filterNot { it == component }.joinToString(":")))
                    delay(200)
                } finally { check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, original)) }
            } finally { automation.dropShellPermissionIdentity() }
        }
        withTimeout(10_000) { while (FlowChatAccessibilityService.current()?.observeScreen() == null) delay(100) }
        device.bridge().deviceTaskControl.begin()
    }

    private fun returnToApp() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private suspend fun fixture(vararg flags: String) {
        context.startActivity(Intent().apply {
            component = ComponentName(instrumentation.context.packageName, VisualTapFixtureActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("portrait", true)
            flags.forEach { putExtra(it, true) }
        })
        withTimeout(8_000) {
            while (FlowChatAccessibilityService.current()?.observeScreen()?.packageName != instrumentation.context.packageName) delay(100)
        }
        delay(450)
    }

    private suspend fun command(command: String) {
        context.sendBroadcast(Intent(VisualTapFixtureActivity.CONTROL)
            .setPackage(instrumentation.context.packageName).putExtra("command", command))
        delay(350)
    }

    private fun report(label: String, result: DeviceToolResult) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\n$label\n${result.asToolContent()}\ngeometry=${result.screen?.geometry}\n")
        })
    }

    private fun save(label: String, image: DeviceScreenImage) {
        val directory = File(context.filesDir, "diagnostics/visual-tap").apply { mkdirs() }
        File(directory, "$label.png").writeBytes(Base64.getDecoder().decode(image.image.base64))
    }

    private suspend fun prepareBlueDetail(image: DeviceScreenImage, longPress: Boolean = false): DeviceScreenImage {
        if (!image.needsVisualRefinement) return image
        val proposal = device.gateway().tapVisualTarget(locateBlue(image).copy(longPress = longPress))
        assertTrue(proposal.asToolContent(), proposal.visualRefinementRequired)
        val detail = requireNotNull(proposal.screenImage)
        assertEquals("Refinement must not click", 0, countGreen(detail.image))
        assertFalse(detail.needsVisualRefinement)
        return detail
    }

    private suspend fun tapBlue(image: DeviceScreenImage, longPress: Boolean = false): DeviceToolResult {
        val detail = prepareBlueDetail(image, longPress)
        return device.gateway().tapVisualTarget(locateBlue(detail).copy(longPress = longPress))
    }

    private suspend fun calibratedTap(target: VisualTapTarget): DeviceToolResult =
        device.bridge().withExternalUiAction { device.bridge().withDisplayCapture { device.gateway().tapVisualTarget(target) } }

    @Test fun tapsPixelOnlyTargetsFromFullAndCroppedImagesAndRejectsReplay() = runBlocking<Unit> {
        setup()
        try {
            for (region in listOf(null, ScreenImageRegion(45, 35, 95, 90))) {
                fixture()
                val observed = device.gateway().captureScreen(region)
                report("CAPTURE", observed)
                assertTrue(observed.screen!!.elements.isEmpty())
                val image = requireNotNull(observed.screenImage) { observed.asToolContent() }
                assertNotNull("Actual image-to-display geometry is required", image.geometry)
                val target = locateBlue(image)
                val result = tapBlue(image)
                report("TAP", result)
                assertTrue(result.asToolContent(), result.success)
                val resultImage = requireNotNull(result.screenImage)
                assertNotEquals(image.screenId, resultImage.screenId)
                assertTrue("Result is still a zero-controls Canvas", result.screen!!.elements.isEmpty())
                assertActivated(resultImage)
                save(if (region == null) "full-result" else "crop-result", resultImage)
                val replay = device.gateway().tapVisualTarget(target)
                assertFalse(replay.success)
                assertEquals("stale_screen", replay.errorCode)
                assertActivated(requireNotNull(replay.screenImage))
            }
        } finally { returnToApp() }
    }

    @Test fun mapsAnOffsetWindowAndDispatchesARealLongPress() = runBlocking<Unit> {
        setup()
        try {
            fixture("offset", "require_long_press")
            val observed = device.gateway().captureScreen()
            report("OFFSET_CAPTURE", observed)
            val image = requireNotNull(observed.screenImage)
            assertNotNull(image.geometry)
            val bounds = requireNotNull(image.captureBounds)
            assertTrue(bounds.left > 0 || bounds.top > 0)
            val result = tapBlue(image, longPress = true)
            report("OFFSET_LONG_PRESS", result)
            assertTrue(result.asToolContent(), result.success)
            val resultImage = requireNotNull(result.screenImage)
            assertActivated(resultImage)
            save("offset-result", resultImage)
        } finally { returnToApp() }
    }

    @Test fun refusesAMovedCanvasTargetThenRecoversFromTheReturnedNewImage() = runBlocking<Unit> {
        setup()
        try {
            fixture()
            val original = device.gateway().captureScreen()
            val originalImage = requireNotNull(original.screenImage)
            val oldFullTarget = locateBlue(originalImage)
            val oldTarget = locateBlue(prepareBlueDetail(originalImage))
            command("move")
            val rejected = device.gateway().tapVisualTarget(oldTarget)
            report("MOVED_TARGET_REJECTED", rejected)
            assertFalse(rejected.success)
            assertEquals("stale_visual_target", rejected.errorCode)
            assertTrue(rejected.screen!!.elements.isEmpty())
            val freshImage = requireNotNull(rejected.screenImage)
            assertEquals(0, countGreen(freshImage.image))
            val newFull = requireNotNull(device.gateway().captureScreen().screenImage)
            assertTrue(locateBlue(newFull).x < oldFullTarget.x - 200)
            val recovered = tapBlue(newFull)
            assertTrue(recovered.asToolContent(), recovered.success)
            assertActivated(requireNotNull(recovered.screenImage))
        } finally { returnToApp() }
    }

    @Test fun controlsOnlyReadsAndWrongImageIdsNeverAuthorizeAVisualTap() = runBlocking<Unit> {
        setup()
        try {
            fixture()
            val target = locateBlue(requireNotNull(device.gateway().captureScreen().screenImage))
            device.gateway().observeScreen()
            val rejected = device.gateway().tapVisualTarget(target)
            assertFalse(rejected.success)
            assertEquals("stale_screen", rejected.errorCode)
            val freshImage = requireNotNull(rejected.screenImage)
            assertEquals(0, countGreen(freshImage.image))
            val wrongId = device.gateway().tapVisualTarget(locateBlue(freshImage).copy(screenId = "unobserved-image"))
            assertFalse(wrongId.success)
            assertEquals("stale_screen", wrongId.errorCode)
            val wrongIdImage = requireNotNull(wrongId.screenImage)
            assertEquals(0, countGreen(wrongIdImage.image))
            val previousTasksTarget = locateBlue(wrongIdImage)
            device.bridge().deviceTaskControl.begin()
            val differentTask = device.gateway().tapVisualTarget(previousTasksTarget)
            assertFalse(differentTask.success)
            assertEquals("stale_screen", differentTask.errorCode)
            assertEquals(0, countGreen(requireNotNull(differentTask.screenImage).image))
        } finally { returnToApp() }
    }

    @Test fun secureAndSensitiveTransitionsCannotBeBypassedByVisualCoordinates() = runBlocking<Unit> {
        setup()
        try {
            for (protection in listOf("secure", "sensitive")) {
                fixture()
                val target = locateBlue(requireNotNull(device.gateway().captureScreen().screenImage))
                command(protection)
                val result = device.gateway().tapVisualTarget(target)
                report("PROTECTED_$protection", result)
                assertFalse(result.success)
                assertNull(result.screenImage)
                assertEquals(if (protection == "secure") "secure_window_blocked" else "sensitive_screen_blocked", result.errorCode)
            }
        } finally { returnToApp() }
    }

    @Test fun toleratesUnrelatedAnimationAndReportsAnIgnoredTapAsNoChange() = runBlocking<Unit> {
        setup()
        try {
            fixture("animated")
            val image = requireNotNull(device.gateway().captureScreen().screenImage)
            val result = tapBlue(image)
            report("ANIMATED_TARGET", result)
            assertTrue(result.asToolContent(), result.success)
            assertActivated(requireNotNull(result.screenImage))
            fixture("ignore_touches")
            val ignored = tapBlue(requireNotNull(device.gateway().captureScreen().screenImage))
            report("IGNORED_TAP", ignored)
            assertFalse(ignored.success)
            assertEquals("ui_no_change", ignored.errorCode)
            val ignoredImage = requireNotNull(ignored.screenImage)
            assertEquals(0, countGreen(ignoredImage.image))
            locateBlue(ignoredImage)
        } finally { returnToApp() }
    }

    @Test fun rotationAndUserTakeoverInvalidateVisualActions() = runBlocking<Unit> {
        setup()
        val control = device.bridge().deviceTaskControl
        var task: DeviceTaskControl.Task? = null
        try {
            fixture()
            val original = requireNotNull(device.gateway().captureScreen().screenImage)
            command("landscape")
            withTimeout(8_000) {
                while (FlowChatAccessibilityService.current()?.observeScreen()?.geometry?.rotation == original.geometry!!.rotation) delay(100)
            }
            val rotated = device.gateway().tapVisualTarget(locateBlue(original))
            report("ROTATION_REJECTED", rotated)
            assertFalse(rotated.success)
            assertEquals("stale_screen", rotated.errorCode)
            command("portrait")
            withTimeout(8_000) {
                while (FlowChatAccessibilityService.current()?.observeScreen()?.geometry?.rotation != original.geometry!!.rotation) delay(100)
            }
            val takeoverTask = control.begin()
            task = takeoverTask
            control.arm(takeoverTask)
            val target = locateBlue(requireNotNull(device.gateway().captureScreen().screenImage))
            returnToApp()
            withTimeout(8_000) { while (!takeoverTask.interrupted) delay(100) }
            assertTrue("Returning to Floryx must cancel the pending visual action",
                runCatching { device.gateway().tapVisualTarget(target) }.exceptionOrNull() is CancellationException)
        } finally { control.finish(task); returnToApp() }
    }

    @Test fun screenshotsDriveTheModelToolLoopAndReturnedPixelsVerifyTheActualTap() = runBlocking<Unit> {
        setup()
        val settings = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java).settings()
        val original = settings.state.value
        val server = LocalVisualTapModel()
        val provider = ProviderConfig(id = "visual-tap-${UUID.randomUUID()}", displayName = "Visual tap local QA",
            baseUrl = "http://127.0.0.1:${server.port}/v1", defaultModel = "vision-local-qa")
        var owner: String? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            settings.setPowerModeEnabled(true); settings.setCommandExecutionEnabled(false)
            settings.setMemoryEnabled(false); settings.setUsageTrackingEnabled(false)
            data.providers().upsertProvider(provider, null)
            val ownerId = data.chats().createConversation(provider.id, provider.defaultModel).id
            owner = ownerId
            data.chats().updateConversationTitle(ownerId, "Visual tap local QA")
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(context.getString(R.string.open_navigation_drawer)).performClick()
            compose.onNodeWithText("Visual tap local QA").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("VISUAL_TAP_QA 点击蓝色画面按钮，并检查它变为绿色。")
            compose.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
            fixture()
            server.fixtureReady.countDown()
            withTimeout(35_000) {
                while (data.chats().getMessages(ownerId).none { it.role == MessageRole.Assistant &&
                        it.status == MessageStatus.Complete && it.content.contains("VISUAL_TAP_LOCAL_OK") }) {
                    server.failure?.let { error(it) }; delay(100)
                }
            }
            assertEquals(4, server.requests)
            assertTrue(server.verifiedGreenPixels)
            val stored = data.chats().getMessages(ownerId)
            assertTrue(stored.any { it.executionContext.orEmpty().contains("tap_visual_target") })
            assertTrue(stored.none { it.content.contains("data:image/") || it.executionContext.orEmpty().contains("data:image/") || it.attachmentImagePath != null })
        } finally {
            server.fixtureReady.countDown(); scenario?.close(); returnToApp()
            owner?.let { data.chats().deleteConversation(it) }; data.providers().deleteProvider(provider.id)
            settings.setPowerModeEnabled(original.powerModeEnabled); settings.setCommandExecutionEnabled(original.commandExecutionEnabled)
            settings.setMemoryEnabled(original.memoryEnabled); settings.setUsageTrackingEnabled(original.usageTrackingEnabled)
            server.close()
        }
    }

    @Test fun upwardCoarseGuessesAreRefinedBeforeSmallTargetsReceiveRealTouchEvents() = runBlocking<Unit> {
        setup()
        val errors = mutableListOf<String>()
        try {
            for (position in listOf("top_target", "center_target", "bottom_target", "offset")) {
                fixture("small_target", "trace_touches", position)
                val captured = device.gateway().captureScreen()
                report("PRECISION_CAPTURE_$position", captured)
                val full = requireNotNull(captured.screenImage) { captured.asToolContent() }
                val exact = locateBlue(full)
                val coarse = exact.copy(y = (exact.y - 25).coerceAtLeast(0))
                val proposal = calibratedTap(coarse)
                assertTrue(proposal.asToolContent(), proposal.visualRefinementRequired)
                val detail = requireNotNull(proposal.screenImage)
                assertEquals("A coarse guess must not cause a touch", 0, countGreen(detail.image))
                val expected = colorCenter(detail.image, red = false)
                val result = calibratedTap(locateBlue(detail))
                assertTrue(result.asToolContent(), result.success)
                val after = requireNotNull(result.screenImage)
                assertEquals(detail.width, after.width); assertEquals(detail.height, after.height)
                val actual = colorCenter(after.image, red = true)
                val dx = kotlin.math.abs(expected.first - actual.first)
                val dy = kotlin.math.abs(expected.second - actual.second)
                errors += "$position: dx=$dx dy=$dy image=${detail.width}x${detail.height}"
                assertTrue("Actual MotionEvent error: ${errors.last()}", dx <= 2.5 && dy <= 2.5)
            }
            val directory = File(context.filesDir, "diagnostics/visual-tap").apply { mkdirs() }
            File(directory, "precision-result.txt").writeText(errors.joinToString("\n"))
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n${errors.joinToString("\n")}\n") })
        } finally { returnToApp() }
    }

    @Test fun capturesTheNewWindowAfterTheClickOpensADelayedDialog() = runBlocking<Unit> {
        setup()
        try {
            fixture("transition_on_tap")
            val before = requireNotNull(device.gateway().captureScreen().screenImage)
            val result = tapBlue(before)
            report("TRANSITION_RESULT", result)
            assertTrue(result.asToolContent(), result.success)
            assertNotNull(result.screenImage)
            assertTrue(result.screen!!.elements.any { it.displayText == "READY" })
        } finally { returnToApp() }
    }

    @Test fun agentRawCoordinateCommandsAreRejectedBeforeAndroidExecution() = runBlocking<Unit> {
        setup()
        try {
            val terminal = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java).terminal()
            for (command in listOf("input tap 742 238", "am start -n test/.Main; input tap 490 442")) {
                val failure = runCatching { terminal.start("precision-qa", com.flowchat.app.domain.terminal.CommandEnvironment.Android,
                    command, "/data/local/tmp", 5, "precision-qa-run") }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException)
                assertTrue(failure!!.message.orEmpty().contains("No command was executed"))
            }
        } finally { returnToApp() }
    }

    @Test fun liveFlashRefinesAndClicksASmallSyntheticTarget() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLiveVisualPrecision") == "true")
        setup()
        val entry = EntryPointAccessors.fromApplication(context, TerminalDiagnosticsEntryPoint::class.java)
        val original = entry.settings().state.value
        val provider = data.providers().getProvidersOnce().firstOrNull {
            it.isEnabled && runCatching { java.net.URI(it.baseUrl).host == "api.deepseek.com" }.getOrDefault(false)
        }
        assumeTrue("An existing DeepSeek configuration is required", provider != null)
        val existingProvider = requireNotNull(provider)
        assumeTrue("Existing key required", !data.providers().getApiKey(existingProvider).isNullOrBlank())
        val conversation = data.chats().createConversation(existingProvider.id, "deepseek-flash")
        var scenario: ActivityScenario<MainActivity>? = null
        suspend fun saveEvidence() {
            val messages = data.chats().getMessages(conversation.id)
            val evidence = buildJsonArray {
                messages.forEach { message -> add(buildJsonObject {
                    put("role", message.role.name); put("status", message.status.name); put("phase", message.replyPhase.name)
                    if (message.role == MessageRole.Assistant && message.replyPhase == ReplyPhase.Answer) put("answer", message.content.take(1800))
                    message.executionContext?.let { stored -> putJsonArray("steps") {
                        Json.parseToJsonElement(stored).jsonArray.forEach { step -> add(buildJsonObject {
                            put("tool", step.jsonObject["tool"]!!)
                            put("arguments", step.jsonObject["arguments"]!!)
                            val result = step.jsonObject["result"]!!.jsonPrimitive.content
                            put("summary", result.lineSequence().firstOrNull().orEmpty())
                            put("metadata", result.lineSequence().filter { it.startsWith("image_") || it.startsWith("visual_") || it.startsWith("error_code:") || it.startsWith("package=") }.joinToString("\n"))
                        }) }
                    } }
                }) }
            }
            File(context.filesDir, "diagnostics/visual-tap").mkdirs()
            File(context.filesDir, "diagnostics/visual-tap/live-precision-evidence.json").writeText(evidence.toString())
        }
        try {
            entry.settings().setPowerModeEnabled(true); entry.settings().setCommandExecutionEnabled(false)
            entry.settings().setMemoryEnabled(false); entry.settings().setUsageTrackingEnabled(false)
            data.chats().updateConversationTitle(conversation.id, "视觉精度真实模型测试")
            data.chats().updateThinkingSettings(conversation.id, true, ThinkingEffort.Low)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(context.getString(R.string.open_navigation_drawer)).performClick()
            compose.onNodeWithText("视觉精度真实模型测试").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("执行已授权的合成页面测试。包名 com.flowchat.app.debug.test，只操作这个测试页面。请主动查看屏幕，找到蓝色 GO 小按钮并点击，确认它变绿再结束。没有可读控件时使用截图视觉工具；若返回局部放大图且未点击，必须根据新图重新定位再点击，不复用整屏坐标。尚未显示页面时等待并重新查看，不要只回复计划或等待用户。不切换其他应用，不用命令工具，不操作其他位置。")
            compose.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
            fixture("small_target", "trace_touches")
            withTimeout(180_000) {
                while (true) {
                    val messages = data.chats().getMessages(conversation.id)
                    check(messages.none { it.role == MessageRole.Assistant && it.status in listOf(MessageStatus.Failed, MessageStatus.Stopped) }) {
                        "Real model task failed; inspect its saved operation status."
                    }
                    if (messages.any { it.role == MessageRole.Assistant && it.status == MessageStatus.Complete && it.replyPhase == ReplyPhase.Answer }) break
                    delay(300)
                }
            }
            val messages = data.chats().getMessages(conversation.id)
            val evidence = messages.mapNotNull { it.executionContext }.joinToString("\n")
            val finalImage = requireNotNull(device.gateway().captureScreen().screenImage)
            saveEvidence()
            assertActivated(finalImage)
            assertTrue("Actual model must use detail pixels", evidence.contains("visual_refinement_required=true") || evidence.contains("visual_precision=detail"))
            File(context.filesDir, "diagnostics/visual-tap/live-precision-result.txt").writeText(
                "PASS deepseek-flash: real image -> region proposal -> detail relocation -> real tap -> green result. Conversation removed after test.\n")
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nPASS real DeepSeek Flash visual precision loop\n") })
        } finally {
            saveEvidence()
            scenario?.close(); returnToApp(); data.chats().deleteConversation(conversation.id)
            entry.settings().setPowerModeEnabled(original.powerModeEnabled); entry.settings().setCommandExecutionEnabled(original.commandExecutionEnabled)
            entry.settings().setMemoryEnabled(original.memoryEnabled); entry.settings().setUsageTrackingEnabled(original.usageTrackingEnabled)
        }
    }

    private class LocalVisualTapModel : AutoCloseable {
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        val fixtureReady = CountDownLatch(1)
        @Volatile var requests = 0
        @Volatile var failure: String? = null
        @Volatile var verifiedGreenPixels = false
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use {
                    try {
                        client.soTimeout = 15_000
                        val input = client.getInputStream().buffered()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) { val byte = input.read(); check(byte >= 0 && header.length < 32768); header.append(byte.toChar()) }
                        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(header)!!.groupValues[1].toInt()
                        check(length in 1..12_000_000)
                        val bytes = ByteArray(length); var offset = 0
                        while (offset < length) { val count = input.read(bytes, offset, length - offset); check(count > 0); offset += count }
                        val request = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                        requests++
                        val delta = when (requests) {
                            1 -> {
                                check(request["tools"]!!.jsonArray.any { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content == "tap_visual_target" })
                                check(fixtureReady.await(10_000, TimeUnit.MILLISECONDS))
                                tool("observe", "observe_screen", "{}")
                            }
                            2, 3 -> {
                                val messages = request["messages"]!!.jsonArray
                                val evidence = messages.last { it.jsonObject["role"]!!.jsonPrimitive.content == "tool" }.jsonObject["content"]!!.jsonPrimitive.content
                                check(evidence.contains("visual_tap_available=true") && evidence.contains("No readable controls"))
                                if (requests == 3) {
                                    check(evidence.contains("visual_refinement_required=true"))
                                    check(evidence.contains("gesture_dispatched=false"))
                                    check(countGreen(lastImage(messages)) == 0)
                                }
                                val id = Regex("image_screen_id=([^\\s]+)").find(evidence)!!.groupValues[1]
                                val located = locateBlue(DeviceScreenImage(lastImage(messages), id, 1, 1))
                                val target = if (requests == 2) located.copy(y = (located.y - 30).coerceAtLeast(0)) else located
                                val arguments = buildJsonObject {
                                    put("screen_id", id); put("x", target.x); put("y", target.y)
                                    put("target_description", "blue GO button"); put("long_press", false)
                                }.toString()
                                tool(if (requests == 2) "propose" else "tap", "tap_visual_target", arguments)
                            }
                            4 -> {
                                val messages = request["messages"]!!.jsonArray
                                val evidence = messages.last { it.jsonObject["role"]!!.jsonPrimitive.content == "tool" }.jsonObject
                                check(evidence["tool_call_id"]!!.jsonPrimitive.content == "tap")
                                check(evidence["content"]!!.jsonPrimitive.content.startsWith("success:"))
                                check(countGreen(lastImage(messages)) > 500)
                                verifiedGreenPixels = true
                                buildJsonObject { put("content", "已点击蓝色按钮，并从新画面确认变为绿色。VISUAL_TAP_LOCAL_OK") }
                            }
                            else -> error("Unexpected extra model request $requests")
                        }
                        val response = buildJsonObject { putJsonArray("choices") { add(buildJsonObject { put("delta", delta) }) } }
                        val body = "data: $response\n\ndata: [DONE]\n\n".toByteArray()
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                    } catch (error: Throwable) { failure = error.toString() }
                }
            }
        }
        private fun tool(id: String, name: String, arguments: String) = buildJsonObject { putJsonArray("tool_calls") {
            add(buildJsonObject { put("index", 0); put("id", id); put("type", "function")
                putJsonObject("function") { put("name", name); put("arguments", arguments) } })
        } }
        override fun close() { socket.close(); worker.join(1000) }
    }

    companion object {
        private fun colorCenter(image: ChatImage, red: Boolean): Pair<Double, Double> {
            val bytes = Base64.getDecoder().decode(image.base64)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            return try {
                var minX = bitmap.width; var maxX = -1; var minY = bitmap.height; var maxY = -1
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    val matches = if (red) Color.red(pixel) > 180 && Color.green(pixel) < 90 && Color.blue(pixel) < 90
                        else Color.blue(pixel) > 180 && Color.red(pixel) < 90 && Color.green(pixel) < 120
                    if (matches) { minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y) }
                }
                check(maxX >= minX && maxY >= minY) { "Expected ${if (red) "touch marker" else "target"} pixels." }
                (minX + maxX) / 2.0 to (minY + maxY) / 2.0
            } finally { bitmap.recycle() }
        }
        private fun lastImage(messages: JsonArray): ChatImage {
            val urls = messages.flatMap { message ->
                (message.jsonObject["content"] as? JsonArray).orEmpty().mapNotNull { part ->
                    part.jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                }
            }
            val url = urls.last()
            return ChatImage(url.substringAfter("data:").substringBefore(';'), url.substringAfter(','))
        }

        /** Local test model locates the target using ONLY pixels, never fixture coordinates or UI indices. */
        private fun locateBlue(image: DeviceScreenImage): VisualTapTarget {
            val bytes = Base64.getDecoder().decode(image.image.base64)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                var minX = bitmap.width; var minY = bitmap.height; var maxX = -1; var maxY = -1
                for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.blue(pixel) > 180 && Color.red(pixel) < 90 && Color.green(pixel) < 120) {
                        minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
                    }
                }
                check(maxX > minX && maxY > minY) { "No blue target in actual screenshot pixels." }
                return VisualTapTarget(image.screenId, (minX + maxX) * 500 / bitmap.width,
                    (minY + maxY) * 500 / bitmap.height, "blue GO button")
            } finally { bitmap.recycle() }
        }

        private fun countGreen(image: ChatImage): Int {
            val bytes = Base64.getDecoder().decode(image.base64)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            return try {
                var green = 0
                for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.green(pixel) > 140 && Color.red(pixel) < 100 && Color.blue(pixel) < 130) green++
                }
                green
            } finally { bitmap.recycle() }
        }

        private fun assertActivated(image: DeviceScreenImage) { assertTrue("Actual green result pixels are required", countGreen(image.image) > 500) }
    }
}
