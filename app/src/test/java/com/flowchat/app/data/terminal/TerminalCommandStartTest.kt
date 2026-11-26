package com.flowchat.app.data.terminal

import com.flowchat.app.domain.device.*
import com.flowchat.app.domain.terminal.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TerminalCommandStartTest {
    @Test fun cancellingRunBeforeStartResponseCancelsItsPendingStart() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        val device = object : DeviceAssistantGateway by UnavailableDeviceAssistantGateway {
            override suspend fun startShellCommand(command: String, workingDirectory: String, timeoutSeconds: Int): CommandSnapshot {
                entered.complete(Unit)
                reply.await()
                return CommandSnapshot("root-late", command, CommandEnvironment.Android, workingDirectory)
            }
        }
        val context = RuntimeEnvironment.getApplication()
        context.applicationInfo.nativeLibraryDir = context.filesDir.absolutePath
        val terminal = TerminalManager(context, LinuxRuntime(context), device)
        val task = launch(Dispatchers.IO) { terminal.start("owner-a", CommandEnvironment.Android, "sleep 30", "/", 60, "run-a") }
        try {
            withTimeout(5_000) { entered.await() }
            terminal.cancelRun("run-a")
            delay(250)
            assertTrue("The running start call must receive cancellation before any command ID arrives", task.isCancelled)
        } finally { task.cancelAndJoin(); reply.complete(Unit) }
    }
}
