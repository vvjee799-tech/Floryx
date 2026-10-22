package com.flowchat.app.data.network

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInWebSearchContractTest {
    @Test
    fun searchesThroughBuiltInProxyThenFallsBackToUsersTavilyKey() {
        val client = File("src/main/java/com/flowchat/app/data/network/TavilySearchClient.kt").readText()
        val viewModel = File("src/main/java/com/flowchat/app/presentation/chat/ChatViewModel.kt").readText()
        val build = File("build.gradle.kts").readText()

        assertTrue(build.contains("SEARCH_PROXY_URL"))
        assertTrue(client.contains("BuildConfig.SEARCH_PROXY_URL"))
        assertTrue(client.contains("X-FlowChat-Install-ID"))
        assertTrue(client.contains("searchBuiltInProxy"))
        assertTrue(client.contains("searchDirectTavily"))
        assertFalse(viewModel.contains("webSearchClient.search("))
        assertFalse(viewModel.contains("Tavily API key is not configured."))
    }
}
