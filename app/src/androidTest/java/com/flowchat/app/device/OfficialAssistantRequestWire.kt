package com.flowchat.app.device

import com.flowchat.app.data.db.UsageRecord
import com.flowchat.app.data.network.KtorOpenAiCompatibleClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.URLProtocol
import io.ktor.http.content.TextContent
import io.ktor.http.encodedPath
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Inspects one live official DeepSeek Flash HTTP request without altering the Dev APK. */
internal class OfficialAssistantRequestWire(
    client: KtorOpenAiCompatibleClient,
    directory: File,
    private val sample: String,
    private val maxInputBytes: Int,
    private val validatePayload: (JSONObject) -> Unit
) {
    private val reservation = File(directory, "flash-reservation.json")
    private val sent = AtomicReference<JSONObject>()
    @Volatile var active = true
    val request: JSONObject? get() = sent.get()
    val requestCount: Int get() = if (request == null) 0 else 1

    init {
        val field = KtorOpenAiCompatibleClient::class.java.getDeclaredField("httpClient").apply { isAccessible = true }
        val http = field.get(client) as HttpClient
        http.plugin(HttpSend).intercept { call ->
            if (active) synchronized(this@OfficialAssistantRequestWire) {
                check(requestCount == 0 && !reservation.exists()) { "$sample one-request allowance already reserved" }
                check(call.url.protocol == URLProtocol.HTTPS && call.url.host == "api.deepseek.com" &&
                    call.url.build().encodedPath.endsWith("/chat/completions")) { "$sample request did not target official chat completions" }
                val body = (call.body as TextContent).text
                val payload = JSONObject(body)
                check(payload.getString("model") == "deepseek-flash") { "$sample expected deepseek-flash" }
                validatePayload(payload)
                val input = body.toByteArray(Charsets.UTF_8).size + 4096
                val output = payload.getInt("max_tokens")
                check(input <= maxInputBytes && output in 1..65_536) { "$sample request needs input=$input (max=$maxInputBytes) and output=$output" }
                reservation.writeText(
                    JSONObject()
                        .put("sample", sample)
                        .put("host", "api.deepseek.com")
                        .put("model", "deepseek-flash")
                        .put("requests", 1)
                        .put("input_reserved", input)
                        .put("output_reserved", output)
                        .put("usage", "reserved")
                        .toString(2)
                )
                sent.set(payload)
            }
            execute(call)
        }
    }

    fun reportUsage(usage: List<UsageRecord>) {
        if (!reservation.exists()) return
        val record = usage.singleOrNull()
        val ledger = JSONObject(reservation.readText())
        if (record?.inputTokens != null && record.outputTokens != null) {
            ledger.put("input_reported", record.inputTokens).put("output_reported", record.outputTokens).put("usage", "reported")
        }
        reservation.writeText(ledger.toString(2))
    }
}
