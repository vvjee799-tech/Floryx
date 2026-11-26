package com.flowchat.app.data.network

import com.flowchat.app.domain.model.*
import com.flowchat.app.domain.provider.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GeminiWireFormatTest {
    private val json = Json { explicitNulls = false; ignoreUnknownKeys = true }
    private fun provider(url: String = "https://generativelanguage.googleapis.com/v1beta/openai") =
        ProviderConfig(displayName = "Gemini", baseUrl = url, defaultModel = "gemini-3.5-flash")
    private fun request(model: String = "gemini-3.5-flash", enabled: Boolean = true, effort: ThinkingEffort = ThinkingEffort.Auto) =
        ChatRequest(model, listOf(ChatRequestMessage("user", "hello")), 0.7, 1.0, null, enabled, thinkingEffort = effort)
    private fun thinking(request: ChatRequest) = request.toGeminiBody(provider(), json)["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject

    @Test fun onlyTheOfficialGoogleEndpointUsesTheNativeProtocolAndOldConfigurationsStillWork() {
        for (url in listOf("https://generativelanguage.googleapis.com", "https://generativelanguage.googleapis.com/v1beta",
            "https://generativelanguage.googleapis.com/v1beta/openai/")) {
            assertEquals(ModelProtocol.Gemini, modelProtocol(provider(url), "gemini-3.8-flash"))
            assertEquals("https://generativelanguage.googleapis.com/v1beta", provider(url).geminiApiBaseUrl())
        }
        assertEquals("https://generativelanguage.googleapis.com/v1", provider("https://generativelanguage.googleapis.com/v1/openai").geminiApiBaseUrl())
        for (url in listOf("https://example.test/generativelanguage.googleapis.com/v1", "https://generativelanguage.googleapis.com.example.test/v1")) {
            assertEquals(ModelProtocol.ChatCompletions, modelProtocol(provider(url), "gemini-3.5-flash"))
        }
        assertEquals("gemini-3.8-flash", geminiModelName("models/gemini-3.8-flash"))
    }

    @Test fun nativeThinkingSeparatesBudgetsFromLevelsAndHonorsModelCapabilities() {
        val disabled = thinking(request("gemini-2.5-flash", false))
        assertEquals(JsonPrimitive(0), disabled["thinkingBudget"])
        assertEquals(JsonPrimitive(false), disabled["includeThoughts"])
        assertFalse(disabled.containsKey("thinkingLevel"))
        val pro = thinking(request("gemini-2.5-pro", false, ThinkingEffort.Low))
        assertEquals(JsonPrimitive(true), pro["includeThoughts"])
        assertEquals(JsonPrimitive(1024), pro["thinkingBudget"])
        assertEquals(JsonPrimitive(-1), thinking(request("gemini-2.5-flash-lite"))["thinkingBudget"])
        assertEquals(JsonPrimitive("minimal"), thinking(request(effort = ThinkingEffort.Minimal))["thinkingLevel"])
        assertEquals(JsonPrimitive("medium"), thinking(request("gemini-4-argon"))["thinkingLevel"])
        assertFalse(thinking(request("gemini-3.8-flash", effort = ThinkingEffort.Minimal)).containsKey("thinkingLevel"))
        assertFalse(thinking(request(effort = ThinkingEffort.High)).containsKey("thinkingBudget"))
        assertFalse(ThinkingSupport.resolve(provider(), "gemini-3.8-flash").efforts.contains(ThinkingEffort.Minimal))
    }

    @Test fun systemInstructionsImagesAndSignedParallelToolsRoundTripWithoutChangingParts() {
        val image = ChatImage("image/png", "test-base64")
        val signed = json.parseToJsonElement("""[
            {"text":"Checking","thought":true,"thoughtSignature":"opaque-thought"},
            {"functionCall":{"id":"native-1","name":"read_one","args":{}},"thoughtSignature":"opaque-call"},
            {"functionCall":{"name":"read_two","args":{"n":2}}}
        ]""").jsonArray
        val r = request().copy(messages = listOf(
            ChatRequestMessage("system", "system rules"), ChatRequestMessage("developer", "context"),
            ChatRequestMessage("user", "look", images = listOf(image)),
            ChatRequestMessage("assistant", toolCalls = listOf(ChatToolCall("one", "read_one", "{}"), ChatToolCall("two", "read_two", "{\"n\":2}")), providerData = signed),
            ChatRequestMessage("tool", "{\"value\":1}", toolCallId = "one", images = listOf(image)),
            ChatRequestMessage("tool", "plain result", toolCallId = "two")))
        val body = r.toGeminiBody(provider(), json)
        assertEquals("system rules\n\ncontext", body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        val contents = body["contents"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "model", "user"), contents.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(image.base64, contents[0]["parts"]!!.jsonArray.last().jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals(signed, contents[1]["parts"])
        val results = contents[2]["parts"]!!.jsonArray.map { it.jsonObject["functionResponse"]!!.jsonObject }
        assertEquals(listOf("read_one", "read_two"), results.map { it["name"]!!.jsonPrimitive.content })
        assertEquals(JsonPrimitive("native-1"), results[0]["id"])
        assertFalse(results[1].containsKey("id"))
        assertEquals(JsonPrimitive(1), results[0]["response"]!!.jsonObject["value"])
        assertEquals(JsonPrimitive("plain result"), results[1]["response"]!!.jsonObject["result"])
        assertEquals(image.base64, results[0]["parts"]!!.jsonArray.single().jsonObject["inlineData"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    }

    @Test fun nativeFunctionsPreserveJsonSchemaAndDoNotSendOpenAiFields() {
        val schema = json.parseToJsonElement("""{"type":"object","properties":{"n":{"type":"integer"}},"required":["n"],"additionalProperties":false}""").jsonObject
        val body = request().copy(maxTokens = 4096, tools = listOf(ChatToolDefinition("read", "Read", schema)), toolChoice = "none").toWireBody(provider(), true, json)
        assertEquals(schema, body["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!.jsonArray.single().jsonObject["parametersJsonSchema"])
        assertEquals(JsonPrimitive("NONE"), body["toolConfig"]!!.jsonObject["functionCallingConfig"]!!.jsonObject["mode"])
        assertEquals(JsonPrimitive(4096), body["generationConfig"]!!.jsonObject["maxOutputTokens"])
        assertTrue(listOf("model", "messages", "stream", "reasoning_effort", "max_tokens").none(body::containsKey))
    }

    @Test(expected = IllegalArgumentException::class) fun unmatchedToolResultsCannotBeSentAsInventedCalls() {
        request().copy(messages = listOf(ChatRequestMessage("tool", "result", toolCallId = "unknown"))).toGeminiBody(provider(), json)
    }
}
