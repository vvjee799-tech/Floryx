package com.flowchat.app.device

import androidx.test.platform.app.InstrumentationRegistry
import com.flowchat.app.data.device.*
import com.flowchat.app.data.preferences.*
import com.flowchat.app.domain.device.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class AdaptiveBackgroundScrollTest : ScrollFixtureDiagnostics() {
    @Test fun microScrollStaysOnTheOwnedDisplayAndSecureWindowsRemainBlocked() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val tasks = DeviceTaskControl()
        val root = RootDeviceConnection(context)
        val settings = AppSettingsStore(AppSettings(powerModeEnabled = true, powerConnectionMode = PowerConnectionMode.Root, backgroundExecutionEnabled = true))
        val background = ShizukuDeviceAssistantGateway(context, settings, root, tasks)
        var task: DeviceTaskControl.Task? = null
        try {
            root.refresh(requestPermission = true); background.refreshConnection()
            withTimeout(15_000) { while (!root.state.value.isConnected) delay(100) }
            task = tasks.begin(); tasks.arm(task)
            val physicalBefore = FlowChatAccessibilityService.current()!!.observeScreenOnDisplay(0)
            val launched = background.launchApp(instrumentation.context.packageName, AdaptiveScrollFixtureActivity::class.java.name)
            assertTrue(launched.asToolContent(), launched.success)
            val id = requireNotNull(launched.screen?.geometry).displayId
            assertTrue(id > 0)
            val result = background.swipeScreen(DeviceScrollRequest(DeviceSwipeDirection.Up, 2, 600))
            assertTrue(result.asToolContent(), result.success)
            assertEquals(id, result.screen?.geometry?.displayId)
            val data = result.screen!!.elements.single { it.contentDescription?.startsWith("scroll_metrics:") == true }
            val metrics = JSONObject(data.contentDescription!!.substringAfter("scroll_metrics:"))
            assertEquals(metrics.getInt("listY").toDouble(), result.scrollFeedback!!.motion!!.contentPixels.toDouble(), 5.0)
            val physicalAfter = FlowChatAccessibilityService.current()!!.observeScreenOnDisplay(0)
            assertEquals(physicalBefore?.packageName, physicalAfter?.packageName)
            assertEquals(physicalBefore?.windowId, physicalAfter?.windowId)
            background.launchApp(instrumentation.context.packageName, ExecutionDisplaySecureFixtureActivity::class.java.name)
            val blocked = background.swipeScreen(DeviceScrollRequest(DeviceSwipeDirection.Up, 2, 600))
            assertFalse(blocked.asToolContent(), blocked.success)
            assertNull(blocked.screen)
            assertEquals(DeviceActionOutcome.NotDispatched, blocked.actionOutcome)
            report("background_micro=true display=$id physical_window_unchanged=true secure_swipe=not_dispatched delta=${result.scrollFeedback?.motion?.contentPixels}")
        } finally {
            tasks.finish(task)
            withTimeout(15_000) { while (root.serviceInfo()?.activeDisplays != 0) delay(100) }
            root.release()
        }
    }

    @Test fun backgroundNestedContainerLeavesOuterAndSiblingStill() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixturePackage = instrumentation.context.packageName
        val tasks = DeviceTaskControl()
        val root = RootDeviceConnection(context)
        val settings = AppSettingsStore(AppSettings(powerModeEnabled = true, powerConnectionMode = PowerConnectionMode.Root, backgroundExecutionEnabled = true))
        val background = ShizukuDeviceAssistantGateway(context, settings, root, tasks)
        val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun runShell(cmd: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd))
            .bufferedReader().use { it.readText() }.trim()
        fun measureFixture(): JSONObject {
            val reply = runShell("am broadcast --receiver-foreground -a $fixturePackage.SCROLL_CONTROL -p $fixturePackage --es command measure")
            val data = reply.lineSequence().singleOrNull { it.startsWith("Broadcast completed:") }
                ?.substringAfter(", data=\"", "")?.removeSuffix("\"").orEmpty()
            return JSONObject(data)
        }
        var task: DeviceTaskControl.Task? = null
        try {
            root.refresh(requestPermission = true); background.refreshConnection()
            withTimeout(15_000) { while (!root.state.value.isConnected) delay(100) }
            task = tasks.begin(); tasks.arm(task)
            val physicalBefore = FlowChatAccessibilityService.current()!!.observeScreenOnDisplay(0)
            val launched = background.launchApp(fixturePackage, AdaptiveScrollFixtureActivity::class.java.name)
            assertTrue(launched.asToolContent(), launched.success)
            val displayId = requireNotNull(launched.screen?.geometry).displayId
            assertTrue(displayId > 0)

            val nestedStart = runShell("am start -W --display $displayId -f 0x10008000 -n $fixturePackage/${AdaptiveScrollFixtureActivity::class.java.name} " +
                "--es mode nested --ei partial_row 17 --ei initial_y 220 --ei initial_outer_y 84 --ei initial_sibling_y 224")
            check(nestedStart.contains("Status: ok")) { nestedStart }
            delay(500)
            var current = requireNotNull(background.observeScreen(
                DeviceObservationScope(DeviceUiSelector(description = "scroll_list"), DeviceObservationScope.Kind.ScrollContainer)
            ).screen)
            var steps = 0
            while (steps++ < 10) {
                val list = current.elements.single { it.contentDescription == "scroll_list" }
                val res = background.scrollContainer(DeviceContainerScrollRequest(list.index, DeviceScrollDirection.Down, current.screenId,
                    target = DeviceUiSelector(description = "scroll_item_17")))
                assertTrue(res.asToolContent(), res.success)
                current = requireNotNull(res.screen)
                if (res.scrollFeedback?.targetState == "ready") break
            }
            val item17 = current.elements.single { it.contentDescription == "scroll_item_17" }
            val tap17 = background.tapUiElement(item17.index, false, current.screenId)
            assertTrue(tap17.asToolContent(), tap17.success)
            val metrics = measureFixture()
            assertEquals(17, metrics.getInt("selected"))
            assertEquals("[84]", metrics.getJSONArray("outer_positions").toString())
            assertEquals("[224]", metrics.getJSONArray("sibling_positions").toString())
            val physicalAfter = FlowChatAccessibilityService.current()!!.observeScreenOnDisplay(0)
            assertEquals(physicalBefore?.packageName, physicalAfter?.packageName)
            assertEquals(physicalBefore?.windowId, physicalAfter?.windowId)
            report("background_nested=true display=$displayId listY=${metrics.getInt("listY")} outer=84 sibling=224 selected=17")
        } finally {
            tasks.finish(task)
            withTimeout(15_000) { while (root.serviceInfo()?.activeDisplays != 0) delay(100) }
            root.release()
        }
    }

    @Test fun backgroundAmplifiedScrollRecoversWithoutPhysicalFallback() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixturePackage = instrumentation.context.packageName
        val tasks = DeviceTaskControl()
        val root = RootDeviceConnection(context)
        val settings = AppSettingsStore(AppSettings(powerModeEnabled = true, powerConnectionMode = PowerConnectionMode.Root, backgroundExecutionEnabled = true))
        val background = ShizukuDeviceAssistantGateway(context, settings, root, tasks)
        val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun runShell(cmd: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(cmd))
            .bufferedReader().use { it.readText() }.trim()
        fun measureFixture(): JSONObject {
            val reply = runShell("am broadcast --receiver-foreground -a $fixturePackage.SCROLL_CONTROL -p $fixturePackage --es command measure")
            val data = reply.lineSequence().singleOrNull { it.startsWith("Broadcast completed:") }
                ?.substringAfter(", data=\"", "")?.removeSuffix("\"").orEmpty()
            return JSONObject(data)
        }
        var task: DeviceTaskControl.Task? = null
        try {
            root.refresh(requestPermission = true); background.refreshConnection()
            withTimeout(15_000) { while (!root.state.value.isConnected) delay(100) }
            task = tasks.begin(); tasks.arm(task)
            val physicalBefore = FlowChatAccessibilityService.current()!!.observeScreenOnDisplay(0)
            val launched = background.launchApp(fixturePackage, AdaptiveScrollFixtureActivity::class.java.name)
            assertTrue(launched.asToolContent(), launched.success)
            val displayId = requireNotNull(launched.screen?.geometry).displayId
            assertTrue(displayId > 0)

            val gainStart = runShell("am start -W --display $displayId -f 0x10008000 -n $fixturePackage/${AdaptiveScrollFixtureActivity::class.java.name} " +
                "--es mode gain --ei partial_row 44 --ei initial_y 1420 --ei gain_tenths 75 --ez independent_metrics true")
            check(gainStart.contains("Status: ok")) { gainStart }
            delay(500)
            val before = requireNotNull(background.observeScreen().screen)
            val list = before.elements.single { it.contentDescription == "scroll_list" }
            val result = background.scrollContainer(DeviceContainerScrollRequest(list.index, DeviceScrollDirection.Up, before.screenId,
                target = DeviceUiSelector(description = "scroll_item_44")))
            val metrics = measureFixture()
            assertTrue("${result.asToolContent()} metrics=$metrics", result.success)
            val feedback = requireNotNull(result.scrollFeedback)
            assertEquals("ready", feedback.targetState)
            assertTrue("Expected reverse overshoot correction: $feedback metrics=$metrics", feedback.corrections >= 1)
            val fresh = requireNotNull(result.screen)
            val item44 = fresh.elements.single { it.contentDescription == "scroll_item_44" }
            val opened = background.tapUiElement(item44.index, false, fresh.screenId)
            assertTrue(opened.asToolContent(), opened.success)
            val openedMetrics = measureFixture()
            assertEquals(44, openedMetrics.getInt("selected"))
            val physicalAfter = FlowChatAccessibilityService.current()!!.observeScreenOnDisplay(0)
            assertEquals(physicalBefore?.packageName, physicalAfter?.packageName)
            assertEquals(physicalBefore?.windowId, physicalAfter?.windowId)
            report("background_overshoot=true display=$displayId feedback=$feedback metrics=$openedMetrics")
        } finally {
            tasks.finish(task)
            withTimeout(15_000) { while (root.serviceInfo()?.activeDisplays != 0) delay(100) }
            root.release()
        }
    }
}