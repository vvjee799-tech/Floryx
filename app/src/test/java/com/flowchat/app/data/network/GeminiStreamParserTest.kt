package com.flowchat.app.data.network

import com.flowchat.app.domain.model.ChatDelta
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GeminiStreamParserTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun event(parser: GeminiStreamParser, body: String): List<ChatDelta> = parser.parseLine("data: $body") + parser.parseLine("")

    @Test fun streamsThoughtSummariesAndTextSeparatelyAndKeepsEmptySignedParts() {
        val parser = GeminiStreamParser(json)
        assertEquals(listOf(ChatDelta.Reasoning("summary")), event(parser, """{"candidates":[{"content":{"parts":[{"thought":true,"text":"summary"}]}}]}"""))
        assertEquals(listOf(ChatDelta.Content("answer")), event(parser, """{"candidates":[{"content":{"parts":[{"text":"answer"}]}}]}"""))
        val finished = event(parser, """{"candidates":[{"content":{"parts":[{"text":"","thoughtSignature":"opaque"}]},"finishReason":"STOP"}]}""")
        assertEquals(ChatDelta.Done, finished.last())
        val blocks = finished.filterIsInstance<ChatDelta.ProviderData>().single().blocks
        assertEquals(3, blocks.size)
        assertEquals(JsonPrimitive("opaque"), blocks.last().jsonObject["thoughtSignature"])
        assertTrue(parser.terminal)
        assertTrue(parser.endOfInput().isEmpty())
    }

    @Test fun toolCallsWaitForSuccessfulCompletionAndKeepNativeIdsAndSignatures() {
        val parser = GeminiStreamParser(json)
        val incoming = """{"candidates":[{"content":{"parts":[{"functionCall":{"id":"native-call","name":"read","args":{"n":1}},"thoughtSignature":"opaque"},{"functionCall":{"name":"other","args":{}}}]}}]}"""
        assertTrue(event(parser, incoming).isEmpty())
        val finished = event(parser, """{"candidates":[{"finishReason":"STOP"}]}""")
        val calls = finished.filterIsInstance<ChatDelta.ToolCalls>().single().calls
        assertEquals("native-call", calls[0].id)
        assertEquals("read", calls[0].name)
        assertEquals("{\"n\":1}", calls[0].arguments)
        assertNotEquals(calls[0].id, calls[1].id)
        assertEquals(JsonPrimitive("opaque"), finished.filterIsInstance<ChatDelta.ProviderData>().single().blocks[0].jsonObject["thoughtSignature"])
        assertTrue(finished.none { it is ChatDelta.Done })
    }

    @Test fun truncatedBlockedAndTokenLimitedResponsesNeverCompleteOrExecuteTools() {
        for (reason in listOf(null, "MAX_TOKENS", "SAFETY", "MALFORMED_FUNCTION_CALL", "CONTINUATION")) {
            val parser = GeminiStreamParser(json)
            val finish = reason?.let { ",\"finishReason\":\"$it\"" }.orEmpty()
            val output = event(parser, """{"candidates":[{"content":{"parts":[{"text":"partial"},{"functionCall":{"name":"run_command","args":{}}}]}$finish}]}""") + parser.endOfInput()
            assertTrue(output.any { it is ChatDelta.Error })
            assertTrue(output.none { it is ChatDelta.Done || it is ChatDelta.ToolCalls || it is ChatDelta.ProviderData })
        }
    }

    @Test fun multiLineSseCommentsAndEofAreHandledWithoutRetryingOrLosingTheLastEvent() {
        val parser = GeminiStreamParser(json)
        assertTrue(parser.parseLine(": keepalive").isEmpty())
        assertTrue(parser.parseLine("data: {\"candidates\": [{").isEmpty())
        assertTrue(parser.parseLine("data: \"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}").isEmpty())
        val output = parser.endOfInput()
        assertTrue(output.contains(ChatDelta.Content("ok")))
        assertEquals(ChatDelta.Done, output.last())
    }

    @Test fun invalidAndEmptyResponsesSurfaceErrorsInsteadOfFalseSuccess() {
        for (body in listOf("not-json", "{}", """{"promptFeedback":{"blockReason":"SAFETY"}}""",
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"read","args":"partial"}}]},"finishReason":"STOP"}]}""")) {
            val deltas = GeminiStreamParser(json).parseCompletion(body)
            assertTrue(deltas.last() is ChatDelta.Error)
            assertTrue(deltas.none { it is ChatDelta.Done || it is ChatDelta.ToolCalls })
        }
    }
}
