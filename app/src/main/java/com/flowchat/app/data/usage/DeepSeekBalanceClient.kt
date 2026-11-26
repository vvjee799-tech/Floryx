package com.flowchat.app.data.usage

import com.flowchat.app.domain.model.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.net.URI
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

enum class BalanceStatus { Disabled, Loading, Waiting, Ready, Unsupported, MissingKey, Failed }
data class AccountBalance(val currency: String, val total: String, val granted: String, val toppedUp: String)
data class BalanceState(val status: BalanceStatus = BalanceStatus.Disabled,
    val balances: List<AccountBalance> = emptyList(), val updatedAt: Long? = null)

class DeepSeekBalanceClient @Inject constructor(private val http: HttpClient) {
    suspend fun fetch(provider: ProviderConfig, apiKey: String?): BalanceState {
        if (!supports(provider)) return BalanceState(BalanceStatus.Unsupported)
        if (apiKey.isNullOrBlank()) return BalanceState(BalanceStatus.MissingKey)
        return try {
            val response = http.get("https://api.deepseek.com/user/balance") {
                timeout { requestTimeoutMillis = 10_000; connectTimeoutMillis = 5_000; socketTimeoutMillis = 10_000 }
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            if (!response.status.isSuccess()) BalanceState(BalanceStatus.Failed)
            else BalanceState(BalanceStatus.Ready, parse(response.bodyAsText()), System.currentTimeMillis())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { BalanceState(BalanceStatus.Failed) }
    }

    companion object {
        // Never forward a third-party provider's credential to DeepSeek based on its model name.
        fun supports(provider: ProviderConfig): Boolean = runCatching {
            val uri = URI(provider.baseUrl.trim().trimEnd('/'))
            uri.scheme == "https" && uri.host.equals("api.deepseek.com", ignoreCase = true) &&
                uri.port in listOf(-1, 443) && uri.path in listOf("", "/v1", "/beta") &&
                uri.userInfo == null && uri.query == null && uri.fragment == null
        }.getOrDefault(false)

        fun parse(body: String): List<AccountBalance> {
            val items = Json.parseToJsonElement(body).jsonObject["balance_infos"]!!.jsonArray
            require(items.size in 1..2)
            return items.map { item ->
                val value = item.jsonObject
                fun amount(key: String): String {
                    val raw = value[key]!!.jsonPrimitive.content
                    require(raw.length <= 40 && raw.matches(Regex("-?\\d+(\\.\\d+)?")))
                    // Retain decimal precision from the service; no floating-point arithmetic.
                    return BigDecimal(raw).toPlainString()
                }
                val currency = value["currency"]!!.jsonPrimitive.content
                require(currency == "CNY" || currency == "USD")
                AccountBalance(currency, amount("total_balance"), amount("granted_balance"), amount("topped_up_balance"))
            }.also { require(it.map { entry -> entry.currency }.distinct().size == it.size) }
        }
    }
}
