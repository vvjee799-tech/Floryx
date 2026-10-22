package com.flowchat.app.domain

import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.terminal.*
import org.junit.Assert.*
import org.junit.Test

class TerminalCommandTest {
    @Test fun commandToolsRequireExplicitSettingAndKeepExistingTools() {
        val request = ChatRequest("model", listOf(ChatRequestMessage("user", "hello")), 0.7, 1.0, null)
        assertSame(request, TerminalToolDefinitions.attach(request, false))
        val enabled = TerminalToolDefinitions.attach(request, true)
        assertEquals(TerminalToolDefinitions.Names, enabled.tools.map { it.name }.toSet())
        assertFalse(enabled.parallelToolCalls!!)
        assertTrue(enabled.messages.last().content!!.contains("untrusted data"))
    }
    @Test fun readsTruncatedOutputWithoutDuplicatingAlreadyReadCharacters() {
        val full = CommandSnapshot("c", "cmd", CommandEnvironment.Linux, "/workspace", output = "abcdef", outputOffset = 100, nextOffset = 106)
        assertEquals("abc", full.outputSince(0, 3).output)
        assertEquals(103, full.outputSince(0, 3).nextOffset)
        assertEquals("def", full.outputSince(103).output)
        assertEquals("", full.outputSince(106).output)
        assertEquals(106, full.outputSince(1000).nextOffset)
    }
    @Test fun validatesBoundariesWithoutRejectingLegitimateShellSyntax() {
        CommandValidation.validate("python3 - <<'PY'\nprint('hello')\nPY", "/workspace/with spaces", 600)
        listOf("", "a\u0000b", "x".repeat(16001)).forEach { value ->
            assertTrue(runCatching { CommandValidation.validate(value, "/workspace", 1) }.isFailure)
        }
        assertTrue(runCatching { CommandValidation.validate("pwd", "relative", 1) }.isFailure)
        assertTrue(runCatching { CommandValidation.validate("pwd", "/", 0) }.isFailure)
        assertTrue(runCatching { CommandValidation.validate("pwd", "/", 601) }.isFailure)
    }
    @Test fun reportsActualElapsedTimeAndFreezesDurationAfterCompletion() {
        val running = CommandSnapshot("c", "cmd", CommandEnvironment.Linux, "/workspace", createdAt = 1000)
        assertEquals(20_000, running.elapsedMillis(21_000))
        assertEquals(0, running.elapsedMillis(500))
        val finished = running.copy(status = CommandStatus.Exited, finishedAt = 21_000)
        assertEquals(20_000, finished.elapsedMillis(999_000))
    }
}
