package com.flowchat.app.data.network

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class KtorOpenAiCompatibleClientContractTest {
    @Test
    fun chatCompletionRequestsUseLongTimeoutsForStreamingThinkingResponses() {
        val source = File("src/main/java/com/flowchat/app/data/network/KtorOpenAiCompatibleClient.kt").readText()

        assertTrue(source.contains("import io.ktor.client.plugins.timeout"))
        assertTrue(source.contains("requestTimeoutMillis = ChatRequestTimeoutMillis"))
        assertTrue(source.contains("socketTimeoutMillis = ChatSocketTimeoutMillis"))
        assertTrue(source.contains("connectTimeoutMillis = ChatConnectTimeoutMillis"))
        assertTrue(source.contains("const val ChatConnectTimeoutMillis = 30_000L"))
        assertTrue(source.contains("const val ChatSocketTimeoutMillis = 5 * 60 * 1000L"))
        assertTrue(source.contains("const val ChatRequestTimeoutMillis = 10 * 60 * 1000L"))
    }
}
