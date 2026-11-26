package com.flowchat.app.data.network

import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.provider.ThinkingSupport
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ThinkingCompatibilityTest {
    private val json = Json { explicitNulls = false }
    private fun provider(model: String, url: String = "https://example.test/v1") = ProviderConfig(displayName = "Test", baseUrl = url, defaultModel = model)
    private fun request(model: String, on: Boolean = true, effort: ThinkingEffort = ThinkingEffort.Auto) = ChatRequest(
        model, listOf(ChatRequestMessage("user", "hello")), 0.7, 0.8, 16_384, on, thinkingEffort = effort)
    private fun body(model: String, on: Boolean = true, effort: ThinkingEffort = ThinkingEffort.Auto, url: String = "https://example.test/v1") =
        request(model, on, effort).toWireBody(provider(model, url), true, json)

    @Test fun deepSeekThinkingToolsAttributeStoredHistoryAndKeepNativeReplayIntact() {
        val p = provider("deepseek-flash")
        val native = ChatRequestMessage("assistant", "Checking", reasoningContent = "original replay",
            toolCalls = listOf(ChatToolCall("c1", "read_only", "{}")))
        val history = ChatRequestMessage("assistant", "Previously confirmed result", historical = true)
        val tool = ChatToolDefinition("read_only", "Read only", buildJsonObject { put("type", "object") })
        val payload = request(p.defaultModel).copy(messages = listOf(history, native,
            ChatRequestMessage("tool", "ok", toolCallId = "c1")), tools = listOf(tool)).toWireBody(p, true, json)
        val messages = payload["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("user", messages[0]["role"]!!.jsonPrimitive.content)
        assertTrue(messages[0]["content"]!!.jsonPrimitive.content.contains("not a new request or authorization"))
        assertTrue(messages[0]["content"]!!.jsonPrimitive.content.contains(history.content!!))
        assertFalse(messages[0].containsKey("reasoning_content"))
        assertEquals("assistant", messages[1]["role"]!!.jsonPrimitive.content)
        assertEquals("original replay", messages[1]["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("c1", messages[1]["tool_calls"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("c1", messages[2]["tool_call_id"]!!.jsonPrimitive.content)
        assertTrue(messages.none { it.containsKey("historical") || it.containsKey("localMessageId") })
    }

    @Test fun historyProjectionDoesNotChangeOtherProtocolsOrNonThinkingRequests() {
        val history = ChatRequestMessage("assistant", "old answer", historical = true)
        val tool = ChatToolDefinition("read_only", "Read", buildJsonObject { put("type", "object") })
        for ((name, on, tools) in listOf(Triple("gpt-5.4", true, listOf(tool)),
            Triple("deepseek-flash", false, listOf(tool)), Triple("deepseek-flash", true, emptyList()))) {
            val payload = request(name, on).copy(messages = listOf(history), tools = tools).toWireBody(provider(name), true, json)
            assertEquals("assistant", payload["messages"]!!.jsonArray.single().jsonObject["role"]!!.jsonPrimitive.content)
        }
    }

    @Test fun compatibleStreamKeepsAllChannelsAndEncryptedReasoningForTheNextToolRound() {
        val line = """data: {"choices":[{"delta":{"content":"Checking","reasoning":"summary","reasoning_details":[{"index":0,"type":"reasoning.encrypted","data":"first-"}],"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"observe_screen","arguments":"{}"}}]}}]}"""
        val deltas = OpenAiStreamParser.parseDeltas(line)
        assertTrue(deltas.contains(ChatDelta.Content("Checking")))
        assertTrue(deltas.contains(ChatDelta.Reasoning("summary")))
        assertTrue(deltas.any { it is ChatDelta.ToolCallDelta })
        val metadata = ChatReasoningMetadata(json)
        metadata.readLine(line)
        metadata.readLine("""data: {"choices":[{"delta":{"reasoning_details":[{"index":0,"type":"reasoning.encrypted","data":"second"}]}}]}""")
        val blocks = metadata.event()!!.blocks
        val p = provider("anthropic/claude-sonnet-4-6", "https://openrouter.ai/api/v1")
        val payload = request(p.defaultModel).copy(messages = listOf(ChatRequestMessage("assistant", "Checking", providerData = blocks))).toWireBody(p, true, json)
        val details = payload["messages"]!!.jsonArray.single().jsonObject["reasoning_details"]!!.jsonArray
        assertEquals("first-second", details.single().jsonObject["data"]!!.jsonPrimitive.content)
    }

    @Test fun deepSeekHonorsBothSwitchPositionsAndOnlySendsEffortWhenOn() {
        val on = body("deepseek-v4-pro", effort = ThinkingEffort.Max)
        assertEquals("enabled", on["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("max", on["reasoning_effort"]!!.jsonPrimitive.content)
        val off = body("deepseek-v4-pro", false, ThinkingEffort.Max)
        assertEquals("disabled", off["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(off.containsKey("reasoning_effort"))
        assertFalse(on.containsKey("temperature"))
    }

    @Test fun gptUsesCompletionLimitAndNeverSendsUnsupportedSamplingFields() {
        val on = body("gpt-5.4", effort = ThinkingEffort.XHigh)
        assertEquals("xhigh", on["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals(16_384, on["max_completion_tokens"]!!.jsonPrimitive.int)
        assertFalse(on.containsKey("max_tokens"))
        assertFalse(on.containsKey("temperature"))
        assertFalse(on.containsKey("top_p"))
        assertEquals("none", body("gpt-5.4", false)["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("medium", body("gpt-5.4")["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test fun forcedReasonersCannotAccidentallyReceiveAnOffValue() {
        listOf("o3-mini", "gpt-5", "gpt-6-astra", "glm-5.3", "kimi-k3", "grok-4.7", "MiniMax-M3.1-Flash").forEach { model ->
            val support = ThinkingSupport.resolve(provider(model), model)
            assertFalse(model, support.canDisable)
            assertTrue(model, support.isEnabled(false))
            val payload = body(model, false, ThinkingEffort.Low)
            assertFalse(model, payload["reasoning_effort"]?.jsonPrimitive?.content == "none")
            assertFalse(model, payload["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "disabled")
        }
    }

    @Test fun geminiAndQwenUseTheirOwnControls() {
        assertEquals("none", body("gemini-2.5-flash", false)["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(ThinkingSupport.resolve(null, "gemini-3-pro-preview").canDisable)
        val budget = body("qwen3.5-plus", effort = ThinkingEffort.Medium)
        assertEquals(true, budget["enable_thinking"]!!.jsonPrimitive.boolean)
        assertEquals(8192, budget["thinking_budget"]!!.jsonPrimitive.int)
        assertFalse(budget.containsKey("reasoning_effort"))
        val modern = body("qwen3.8-max", effort = ThinkingEffort.XHigh)
        assertEquals("xhigh", modern["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(modern.containsKey("thinking_budget"))
        val off = body("qwen3.8-max", false, ThinkingEffort.XHigh)
        assertEquals(false, off["enable_thinking"]!!.jsonPrimitive.boolean)
        assertFalse(off.containsKey("reasoning_effort"))
    }

    @Test fun aliCloudHostedModelsUseTheGatewayBooleanSwitch() {
        val payload = body("deepseek-v4-pro", false, url = "https://demo.cn-beijing.maas.aliyuncs.com/compatible-mode/v1")
        assertEquals(false, payload["enable_thinking"]!!.jsonPrimitive.boolean)
        assertFalse(payload.containsKey("thinking"))
    }

    @Test fun kimiAndGlmVersionSpecificEffortsAreNotInvented() {
        assertTrue(ThinkingSupport.resolve(null, "kimi-k2.5").efforts.isEmpty())
        assertTrue(ThinkingSupport.resolve(null, "glm-4.7").efforts.isEmpty())
        assertEquals(listOf(ThinkingEffort.Low, ThinkingEffort.High, ThinkingEffort.Max), ThinkingSupport.resolve(null, "kimi-k3").efforts)
        val kimi = body("kimi-k3", effort = ThinkingEffort.Max)
        assertFalse(kimi.containsKey("temperature"))
        assertFalse(kimi.containsKey("top_p"))
        assertTrue(kimi.containsKey("max_completion_tokens"))
        assertEquals("max", kimi["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test fun modelSwitchesAndNonReasoningModelsDropUnsupportedSettings() {
        val plain = body("gpt-4.1", true, ThinkingEffort.Max)
        assertFalse(plain.containsKey("reasoning_effort"))
        assertFalse(plain.containsKey("thinking"))
        assertFalse(plain.containsKey("enable_thinking"))
        assertFalse(body("deepseek-v4-pro", effort = ThinkingEffort.XHigh).containsKey("reasoning_effort"))
        assertFalse(ThinkingSupport.resolve(null, "unknown-custom-alias").supported)
        assertEquals(ThinkingEffort.Auto, ThinkingEffort.fromStored("future-level"))
    }

    @Test fun openRouterUsesUnifiedReasoningAndExactHostMatching() {
        val payload = body("anthropic/claude-sonnet-4-6", effort = ThinkingEffort.High, url = "https://openrouter.ai/api/v1")
        assertEquals(true, payload["reasoning"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("high", payload["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse(payload.containsKey("thinking"))
        assertEquals(ModelProtocol.ChatCompletions, modelProtocol(provider("claude-sonnet-4-6", "https://api.anthropic.com.example.test/v1"), "claude-sonnet-4-6"))
    }

    @Test fun claudeNativeUsesAdaptiveThinkingAndValidLegacyBudgets() {
        val url = "https://api.anthropic.com/v1"
        val adaptive = body("claude-sonnet-4-6", effort = ThinkingEffort.Max, url = url)
        assertEquals("adaptive", adaptive["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("max", adaptive["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse(adaptive.containsKey("temperature"))
        val off = body("claude-opus-5", false, ThinkingEffort.Max, url)
        assertEquals("disabled", off["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(off.containsKey("output_config"))
        val legacy = body("claude-sonnet-4-5", effort = ThinkingEffort.High, url = url)
        assertEquals(8192, legacy["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int)
        assertTrue(legacy["max_tokens"]!!.jsonPrimitive.int > 8192)
        assertFalse(ThinkingSupport.resolve(null, "claude-opus-5-5").canDisable)
    }

    @Test fun gpt6RoutesToolsThroughResponsesAndPreservesOpaqueReasoning() {
        val p = provider("gpt-6-astra", "https://api.openai.com/v1")
        val opaque = json.parseToJsonElement("""[{"type":"reasoning","id":"rs_1","encrypted_content":"opaque"},{"type":"function_call","call_id":"call_1","name":"observe_screen","arguments":"{}"}]""").jsonArray
        val payload = request(p.defaultModel, effort = ThinkingEffort.Max).copy(messages = listOf(
            ChatRequestMessage("user", "Inspect the page"), ChatRequestMessage("assistant", providerData = opaque),
            ChatRequestMessage("tool", "page result", toolCallId = "call_1")
        )).toWireBody(p, true, json)
        assertEquals(ModelProtocol.Responses, modelProtocol(p, p.defaultModel))
        assertEquals("max", payload["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals(opaque[0], payload["input"]!!.jsonArray[1])
        assertEquals("function_call_output", payload["input"]!!.jsonArray.last().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun claudeNativeReturnsThinkingSignaturesAndToolResultsUnchanged() {
        val p = provider("claude-sonnet-4-6", "https://api.anthropic.com/v1")
        val opaque = json.parseToJsonElement("""[{"type":"thinking","thinking":"summary","signature":"opaque-signature"},{"type":"tool_use","id":"tool_1","name":"observe_screen","input":{}}]""").jsonArray
        val payload = request(p.defaultModel).copy(messages = listOf(
            ChatRequestMessage("user", "Inspect"), ChatRequestMessage("assistant", providerData = opaque),
            ChatRequestMessage("tool", "screen", toolCallId = "tool_1")
        )).toWireBody(p, true, json)
        assertEquals(opaque, payload["messages"]!!.jsonArray[1].jsonObject["content"])
        assertEquals("tool_result", payload["messages"]!!.jsonArray[2].jsonObject["content"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun claudeStreamSeparatesSummariesSignaturesAndToolCalls() {
        val parser = NativeModelStreamParser(ModelProtocol.Anthropic, json)
        fun event(value: String) = parser.parseLine("data: $value")
        event("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""")
        assertEquals(listOf(ChatDelta.Reasoning("summary")), event("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"summary"}}"""))
        event("""{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signature"}}""")
        event("""{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call_1","name":"observe_screen","input":{}}}""")
        event("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{}"}}""")
        val result = event("""{"type":"message_stop"}""")
        assertEquals("signature", (result.first() as ChatDelta.ProviderData).blocks[0].jsonObject["signature"]!!.jsonPrimitive.content)
        assertEquals(ChatDelta.ToolCalls(listOf(ChatToolCall("call_1", "observe_screen", "{}"))), result.last())
    }

    @Test fun responsesStreamKeepsCompletedOutputAndReportsIncompleteResponses() {
        val parser = NativeModelStreamParser(ModelProtocol.Responses, json)
        assertEquals(listOf(ChatDelta.Content("answer")), parser.parseLine("""data: {"type":"response.output_text.delta","delta":"answer"}"""))
        val completed = parser.parseLine("""data: {"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"answer"}]}]}}""")
        assertTrue(completed.first() is ChatDelta.ProviderData)
        assertFalse(completed.any { it is ChatDelta.Content })
        assertEquals(ChatDelta.Done, completed.last())
        assertTrue(parser.parseLine("""data: {"type":"response.incomplete","response":{}}""").single() is ChatDelta.Error)
        val fallback = NativeModelStreamParser(ModelProtocol.Responses, json).parseCompletion("""{"status":"completed","error":null,"output":[{"type":"message","content":[{"type":"output_text","text":"answer"}]}]}""")
        assertTrue(fallback.contains(ChatDelta.Content("answer")))
        assertEquals(ChatDelta.Done, fallback.last())
    }
}
