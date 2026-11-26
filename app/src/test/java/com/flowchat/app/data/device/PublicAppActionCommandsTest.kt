package com.flowchat.app.data.device

import com.flowchat.app.domain.device.DeviceActionOutcome
import com.flowchat.app.domain.device.PublicAppActions
import org.junit.Assert.*
import org.junit.Test

class PublicAppActionCommandsTest {
    private class Executor : DeviceCommandExecutor {
        val calls = mutableListOf<List<String>>()
        var version = "1620700"
        var handlers = PublicAppActions.Phone.component
        var packageName = PublicAppActions.Phone.packageName
        var launcher = "com.bbk.calendar/.MainActivity"
        var launch = DeviceCommandResult(true, "Status: ok\nComplete")
        var user = "0"
        override fun execute(command: List<String>): DeviceCommandResult {
            calls += command
            return when {
                command == listOf("getprop", "ro.product.model") -> DeviceCommandResult(true, "V2302A")
                command == listOf("getprop", "ro.product.manufacturer") -> DeviceCommandResult(true, "vivo")
                command == listOf("getprop", "ro.build.version.sdk") -> DeviceCommandResult(true, "36")
                command == listOf("am", "get-current-user") -> DeviceCommandResult(true, user)
                command.take(4) == listOf("cmd", "package", "list", "packages") -> DeviceCommandResult(true, "package:$packageName versionCode:$version")
                command.take(3) == listOf("cmd", "package", "query-activities") -> DeviceCommandResult(true,
                    if ("android.intent.category.LAUNCHER" in command) launcher else handlers)
                command.take(2) == listOf("am", "start") -> launch
                else -> error("Unexpected command: $command")
            }
        }
        val starts get() = calls.count { it.take(2) == listOf("am", "start") }
    }
    private val args = """{"phone_number":"01000000000"}"""

    @Test fun knownEntryLaunchesOnceWithExplicitPrimaryUserAndDisplay() {
        val executor = Executor()
        val result = PublicAppActionCommands(executor).launch(PublicAppActions.Dialer, args, 0)
        assertTrue(result.success)
        assertEquals(DeviceActionOutcome.Dispatched, result.outcome)
        assertEquals(1, executor.starts)
        assertEquals(PublicAppActions.launchArguments(PublicAppActions.parse(PublicAppActions.Dialer, args), 0), executor.calls.last())
    }
    @Test fun absentUpdatedOrAmbiguousAppCannotLaunchOrFallback() {
        for ((version, handlers) in listOf("1620701" to PublicAppActions.Phone.component,
            "1620700" to "No activities found", "1620700" to "${PublicAppActions.Phone.component}\nother/Activity")) {
            val executor = Executor().also { it.version = version; it.handlers = handlers }
            val result = PublicAppActionCommands(executor).launch(PublicAppActions.Dialer, args, 0)
            assertEquals(DeviceActionOutcome.NotDispatched, result.outcome)
            assertEquals(0, executor.starts)
        }
    }
    @Test fun malformedRequestsAndWrongUserNeverStart() {
        val executor = Executor()
        val runner = PublicAppActionCommands(executor)
        assertFalse(runner.launch(PublicAppActions.Dialer, "{}", 0).success)
        assertFalse(runner.launch("arbitrary_intent", args, 0).success)
        assertFalse(runner.launch(PublicAppActions.Dialer, args, 1).success)
        executor.user = "10"
        assertFalse(runner.launch(PublicAppActions.Dialer, args, 0).success)
        assertEquals(0, executor.starts)
    }
    @Test fun timeoutAndPermissionFailureAfterStartRemainUnknownAndNeverRetry() {
        for (launch in listOf(DeviceCommandResult(false, "timeout"), DeviceCommandResult(true, "Error: Permission Denial"))) {
            val executor = Executor().also { it.launch = launch }
            assertEquals(DeviceActionOutcome.Unverified, PublicAppActionCommands(executor).launch(PublicAppActions.Dialer, args, 0).outcome)
            assertEquals(1, executor.starts)
        }
    }
    @Test fun completeCalendarTaskKeepsItsAppBehindTheFreshEditorWithoutSavingOrClearingDrafts() {
        val args = """{"title":"Synthetic event","begin_time":"2026-11-15T15:00:00+08:00","end_time":"2026-11-15T15:10:00+08:00","completion":"save"}"""
        val executor = Executor().also { it.packageName = PublicAppActions.Event.packageName; it.version = "7540"; it.handlers = PublicAppActions.Event.component }
        assertTrue(PublicAppActionCommands(executor).launch(PublicAppActions.Calendar, args, 0).success)
        val starts = executor.calls.filter { it.take(2) == listOf("am", "start") }
        assertEquals(2, starts.size)
        assertTrue("android.intent.action.MAIN" in starts.first())
        assertEquals(PublicAppActions.launchArguments(PublicAppActions.parse(PublicAppActions.Calendar, args), 0), starts.last())
        assertFalse(starts.flatten().any { it == "--activity-clear-task" || it == "android.intent.action.EDIT" })
        executor.launcher = "No activities found"
        assertEquals(DeviceActionOutcome.NotDispatched, PublicAppActionCommands(executor).launch(PublicAppActions.Calendar, args, 0).outcome)
    }
}
