package com.flowchat.app.data.network

import com.flowchat.app.data.usage.*
import com.flowchat.app.domain.model.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DeepSeekBalanceClientTest {
    private val provider = ProviderConfig(displayName = "DeepSeek", baseUrl = "https://api.deepseek.com/v1", defaultModel = "deepseek-flash")
    private val body = """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"12.34567890","granted_balance":"2.00000000","topped_up_balance":"10.34567890"}]}"""

    @Test fun readsOfficialBalanceWithoutRoundingAndFailureDiscardsIt() = runBlocking {
        var fail = false
        val http = HttpClient(MockEngine { request ->
            assertEquals("https://api.deepseek.com/user/balance", request.url.toString())
            assertEquals("Bearer test-placeholder", request.headers[HttpHeaders.Authorization])
            if (fail) respond("unauthorized", HttpStatusCode.Unauthorized) else respond(body, HttpStatusCode.OK)
        }) { install(HttpTimeout) }
        try {
            val client = DeepSeekBalanceClient(http)
            val result = client.fetch(provider, "test-placeholder")
            assertEquals(BalanceStatus.Ready, result.status)
            assertEquals("12.34567890", result.balances.single().total)
            assertNotNull(result.updatedAt)
            fail = true
            val failed = client.fetch(provider, "test-placeholder")
            assertEquals(BalanceStatus.Failed, failed.status)
            assertTrue(failed.balances.isEmpty())
            assertNull(failed.updatedAt)
        } finally { http.close() }
    }

    @Test fun neverSendsThirdPartyOrMissingCredentials() = runBlocking {
        val http = HttpClient(MockEngine { error("Must not send a request") })
        try {
            val client = DeepSeekBalanceClient(http)
            for (url in listOf("http://api.deepseek.com", "https://api.deepseek.com.evil.test", "https://third-party.test/v1", "https://api.deepseek.com@evil.test", "https://api.deepseek.com/v1?proxy=x")) {
                assertEquals(BalanceStatus.Unsupported, client.fetch(provider.copy(baseUrl = url), "test-placeholder").status)
            }
            assertEquals(BalanceStatus.MissingKey, client.fetch(provider, null).status)
        } finally { http.close() }
    }

    @Test fun rejectsMalformedAmountsAndKeepsCurrenciesSeparate() {
        assertTrue(runCatching { DeepSeekBalanceClient.parse(body.replace("12.34567890", "NaN")) }.isFailure)
        assertTrue(runCatching { DeepSeekBalanceClient.parse("""{"balance_infos":[]}""") }.isFailure)
        val usd = body.replace("CNY", "USD")
        assertEquals("USD", DeepSeekBalanceClient.parse(usd).single().currency)
    }

    @Test fun cancellationIsNotAQueryFailure() = runBlocking {
        val http = HttpClient(MockEngine { throw CancellationException("cancelled") }) { install(HttpTimeout) }
        try {
            try { DeepSeekBalanceClient(http).fetch(provider, "test-placeholder"); fail("Expected cancellation") }
            catch (_: CancellationException) { }
        } finally { http.close() }
    }
}
