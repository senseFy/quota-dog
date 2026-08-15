package saien.quotadog

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object GrokCreditsProxyFetcher {
    const val ENDPOINT = "https://cli-chat-proxy.grok.com/v1/billing?format=credits"

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(
        httpClient: HttpClient,
        accessToken: String,
        userId: String? = null,
    ): GrokBillingSnapshot {
        val response = httpClient.get(ENDPOINT) {
            header("Authorization", "Bearer $accessToken")
            header("X-XAI-Token-Auth", "xai-grok-cli")
            header("Accept", "application/json")
            header("User-Agent", "QuotaDog/1.0 (Kotlin)")
            userId
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.contains('@') }
                ?.let { header("x-userid", it) }
        }
        return parseResponse(
            statusCode = response.status.value,
            body = response.bodyAsText(),
        )
    }

    internal fun parseResponse(
        statusCode: Int,
        body: String,
        now: Instant = Clock.System.now(),
    ): GrokBillingSnapshot {
        if (statusCode == HttpStatusCode.Unauthorized.value || statusCode == HttpStatusCode.Forbidden.value) {
            throw ProviderException(
                AuthState.RequiresRelogin,
                "Grok billing rejected credentials. Sign in with xAI again.",
                statusCode,
            )
        }
        if (statusCode !in 200..299) {
            throw ProviderException(
                AuthState.Error,
                "Grok billing request failed: HTTP $statusCode: ${body.trim().take(200)}",
                statusCode,
            )
        }
        return parseCreditsJson(body, now)
    }

    internal fun parseCreditsJson(body: String, now: Instant = Clock.System.now()): GrokBillingSnapshot {
        val response = runCatching {
            json.decodeFromString(GrokCreditsResponse.serializer(), body)
        }.getOrElse {
            throw ProviderException(AuthState.Error, "Grok billing returned invalid JSON.")
        }
        val config = response.config
            ?: throw ProviderException(AuthState.Error, "Grok billing response is missing config.")

        val resetsAt = parseIsoInstant(config.currentPeriod?.end)
            ?: parseIsoInstant(config.billingPeriodEnd)

        val percent = config.creditUsagePercent
            ?.takeIf { it.isFinite() }
            ?.let { it.coerceIn(0.0, 100.0) }
            ?: onDemandPercent(config.onDemandUsed?.value, config.onDemandCap?.value)

        if (percent == null && resetsAt == null) {
            throw ProviderException(AuthState.Error, "Could not parse Grok billing usage.")
        }

        val usedPercent = percent ?: 0.0
        val subscriptionTier = response.subscriptionTier
            ?: response.subscriptionTierDisplay
            ?: response.subscriptionTierSnake

        return GrokBillingSnapshot(
            usedPercent = usedPercent,
            resetsAt = resetsAt?.takeIf { it > now } ?: resetsAt,
            periodType = config.currentPeriod?.type,
            subscriptionTier = subscriptionTier?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun onDemandPercent(used: Double?, cap: Double?): Double? {
        if (used == null || cap == null || !used.isFinite() || !cap.isFinite() || cap <= 0.0) return null
        return ((used / cap) * 100.0).coerceIn(0.0, 100.0)
    }

    private fun parseIsoInstant(raw: String?): Instant? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Instant.parse(value) }.getOrNull()
    }

    @Serializable
    private data class GrokCreditsResponse(
        val config: GrokCreditsConfig? = null,
        val subscriptionTier: String? = null,
        @SerialName("subscription_tier") val subscriptionTierSnake: String? = null,
        @SerialName("subscription_tier_display") val subscriptionTierDisplay: String? = null,
    )

    @Serializable
    private data class GrokCreditsConfig(
        val creditUsagePercent: Double? = null,
        val currentPeriod: GrokUsagePeriod? = null,
        val billingPeriodEnd: String? = null,
        val onDemandCap: GrokCentAmount? = null,
        val onDemandUsed: GrokCentAmount? = null,
    )

    @Serializable
    private data class GrokUsagePeriod(
        val type: String? = null,
        val start: String? = null,
        val end: String? = null,
    )

    @Serializable
    private data class GrokCentAmount(
        @SerialName("val") val value: Double? = null,
    )
}
