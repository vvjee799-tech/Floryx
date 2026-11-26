package com.flowchat.app.device

import com.flowchat.app.domain.chat.ContextNote
import com.flowchat.app.domain.chat.TaskSummary
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.serialization.json.*

/** Scripted OpenAI-compatible responses through the same loopback transport on every task. */
internal class ContinuousReliabilityModel : AutoCloseable {
    class Plan(val reply: (JsonObject, Int) -> JsonObject?) {
        val requests = AtomicInteger()
        val agentRequests = AtomicInteger()
        val checkpointRequests = AtomicInteger()
        var allowClientDisconnect = false
    }

    private val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val connection = AtomicReference<Socket?>()
    val baseUrl = "http://127.0.0.1:${listener.localPort}/v1"
    val inFlight = AtomicInteger()
    val requests = AtomicInteger()
    val failure = AtomicReference<Throwable?>()
    @Volatile var plan: Plan? = null

    private val worker = thread(name = "s1-loopback-model", isDaemon = true) {
        while (!listener.isClosed) {
            val socket = try { listener.accept() } catch (error: IOException) {
                if (!listener.isClosed) failure.compareAndSet(null, error)
                break
            }
            connection.set(socket)
            try {
                socket.use(::serve)
            } catch (error: Exception) {
                if (!listener.isClosed) failure.compareAndSet(null, error)
            } finally {
                connection.set(null)
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 10_000
        val input = socket.getInputStream().buffered()
        val requestLine = input.asciiLine()
        val headers = buildMap {
            while (true) {
                val line = input.asciiLine()
                if (line.isEmpty()) break
                put(line.substringBefore(':').lowercase(), line.substringAfter(':').trim())
                check(size <= 64)
            }
        }
        check(headers["authorization"] == "Bearer $Key") { "Unexpected loopback caller" }
        if (requestLine.startsWith("GET /v1/models ")) {
            write(socket, "200 OK", "application/json", """{"data":[{"id":"$ModelName"}]}""")
            return
        }
        check(requestLine.startsWith("POST /v1/chat/completions ")) { "Unexpected loopback request path" }
        val length = requireNotNull(headers["content-length"]).toInt()
        check(length in 1..2_000_000)
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            check(read > 0) { "Incomplete fixture request" }
            offset += read
        }
        val request = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        check(request.getValue("model").jsonPrimitive.content == ModelName)
        val task = requireNotNull(plan) { "Model called outside a fixture task" }
        inFlight.incrementAndGet()
        try {
            requests.incrementAndGet()
            task.requests.incrementAndGet()
            val messages = request.getValue("messages").jsonArray
            val checkpoint = messages.firstOrNull()?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?.startsWith("# Floryx context checkpoint writer") == true
            val delta = if (checkpoint) {
                task.checkpointRequests.incrementAndGet()
                checkpointReply(messages.last().jsonObject.getValue("content").jsonPrimitive.content)
            } else task.reply(request, task.agentRequests.incrementAndGet())
            try {
                if (delta == null) write(socket, "503 Service Unavailable", "application/json", """{"error":{"message":"S1 fixture loading failed"}}""")
                else {
                    val finish = if ("tool_calls" in delta) "tool_calls" else "stop"
                    val chunk = buildJsonObject {
                        putJsonArray("choices") { add(buildJsonObject { put("delta", delta) }) }
                    }
                    write(socket, "200 OK", "text/event-stream", "data: $chunk\n\n" +
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$finish\"}]}\n\ndata: [DONE]\n\n")
                }
            } catch (error: IOException) {
                if (!task.allowClientDisconnect) throw error
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun checkpointReply(rawInput: String): JsonObject {
        val input = Json.parseToJsonElement(rawInput).jsonObject
        val records = input.getValue("new_records").jsonArray.map { it.jsonObject }
        val source = records.firstOrNull { it["role"]?.jsonPrimitive?.content == "User" }
        val objective = if (source != null) {
            ContextNote(source.getValue("content").jsonPrimitive.content.take(400),
                listOf(source.getValue("id").jsonPrimitive.content))
        } else {
            val previous = input.getValue("previous_checkpoint").jsonObject.getValue("summary").jsonObject
            Json.decodeFromJsonElement<ContextNote>(previous.getValue("objective"))
        }
        return answer(Json.encodeToString(TaskSummary.serializer(), TaskSummary(objective)))
    }

    private fun write(socket: Socket, status: String, type: String, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    private fun BufferedInputStream.asciiLine(): String = buildString {
        while (true) {
            val value = read()
            if (value == -1) throw EOFException("Incomplete fixture headers")
            if (value == '\n'.code) break
            check(length < 8_192)
            if (value != '\r'.code) append(value.toChar())
        }
    }

    override fun close() {
        listener.close()
        connection.get()?.close()
        worker.join(2_000)
        check(!worker.isAlive) { "Loopback worker still running" }
    }

    companion object {
        const val ModelName = "s1-loopback-model"
        const val Key = "s1-loopback-key"

        fun answer(text: String) = buildJsonObject { put("content", text) }

        fun tool(id: String, name: String, args: JsonObject) = buildJsonObject {
            putJsonArray("tool_calls") {
                add(buildJsonObject {
                    put("index", 0); put("id", id); put("type", "function")
                    putJsonObject("function") { put("name", name); put("arguments", args.toString()) }
                })
            }
        }
    }
}
