package com.flowchat.app.data.network

import com.flowchat.app.domain.model.ChatDelta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiStreamParserTest {
    @Test
    fun parsesContentDelta() {
        val delta = OpenAiStreamParser.parseLine(
            """data: {"choices":[{"delta":{"content":"Hel"}}]}"""
        )

        assertEquals(ChatDelta.Content("Hel"), delta)
    }

    @Test
    fun parsesReasoningContentDeltaSeparatelyFromFinalContent() {
        val delta = OpenAiStreamParser.parseLine(
            """data: {"choices":[{"delta":{"reasoning_content":"Thinking"}}]}"""
        )

        assertEquals(ChatDelta.Reasoning("Thinking"), delta)
    }

    @Test
    fun parsesDoneEvent() {
        val delta = OpenAiStreamParser.parseLine("data: [DONE]")

        assertEquals(ChatDelta.Done, delta)
    }

    @Test
    fun ignoresBlankAndNonDataLines() {
        assertEquals(null, OpenAiStreamParser.parseLine(""))
        assertEquals(null, OpenAiStreamParser.parseLine(": keep-alive"))
    }

    @Test
    fun convertsMalformedJsonToErrorDelta() {
        val delta = OpenAiStreamParser.parseLine("data: {not-json")

        assertTrue(delta is ChatDelta.Error)
    }

    @Test
    fun parsesNonStreamingCompletionContent() {
        val delta = OpenAiStreamParser.parseCompletionBody(
            """{"choices":[{"message":{"content":"pong"}}]}"""
        )

        assertEquals(ChatDelta.Content("pong"), delta)
    }

    @Test
    fun parsesNonStreamingReasoningContentSeparatelyFromFinalContent() {
        val delta = OpenAiStreamParser.parseCompletionBody(
            """{"choices":[{"message":{"reasoning_content":"thinking","content":"final"}}]}"""
        )

        assertEquals(ChatDelta.FullResponse(reasoningText = "thinking", contentText = "final"), delta)
    }

    @Test
    fun parsesStreamingToolCallDelta() {
        val delta = OpenAiStreamParser.parseLine(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\"query\""}}]}}]}"""
        )

        assertTrue(delta is ChatDelta.ToolCallDelta)
        val toolDelta = delta as ChatDelta.ToolCallDelta
        assertEquals(0, toolDelta.calls.first().index)
        assertEquals("call_1", toolDelta.calls.first().id)
        assertEquals("web_search", toolDelta.calls.first().name)
        assertEquals("""{"query"""", toolDelta.calls.first().argumentsDelta)
    }

    @Test
    fun parsesNonStreamingToolCalls() {
        val delta = OpenAiStreamParser.parseCompletionBody(
            """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\"query\":\"FlowChat\"}"}}]}}]}"""
        )

        assertTrue(delta is ChatDelta.ToolCalls)
        val calls = (delta as ChatDelta.ToolCalls).calls
        assertEquals("call_1", calls.first().id)
        assertEquals("web_search", calls.first().name)
        assertEquals("""{"query":"FlowChat"}""", calls.first().arguments)
    }
}
