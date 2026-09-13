package saien.quotadog

import kotlinx.datetime.Clock

/**
 * Parses the Devin CLI credentials file.
 *
 * Observed shape (`~/.local/share/devin/credentials.toml`, written by `devin auth login`):
 * ```
 * api_server_url = "https://server.codeium.com"
 * devin_api_url = "https://api.devin.ai"
 * devin_webapp_host = "app.devin.ai"
 * windsurf_api_key = "..."
 * ```
 */
internal object DevinAuthParser {
    private const val NO_EXPIRY_MILLIS = 100L * 365L * 24L * 60L * 60L * 1000L
    const val DEFAULT_API_SERVER_URL = "https://server.codeium.com"

    fun parseCredentialsToml(text: String): OAuthTokenBundle {
        val apiKey = readTomlString(text, "windsurf_api_key")
            ?: throw ProviderException(
                AuthState.NotConfigured,
                "Devin credentials file has no windsurf_api_key. Run `devin auth login` first.",
            )
        return OAuthTokenBundle(
            accessToken = apiKey,
            refreshToken = "",
            apiServerUrl = cleanApiServerUrl(readTomlString(text, "api_server_url")),
            expiresAtEpochMillis = Clock.System.now().toEpochMilliseconds() + NO_EXPIRY_MILLIS,
        )
    }

    internal fun cleanApiServerUrl(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!trimmed.startsWith("https://")) return null
        return trimmed.trimEnd('/').takeIf { it.isNotEmpty() }
    }

    internal fun readTomlString(text: String, key: String): String? {
        for (line in text.lines()) {
            val separator = line.indexOf('=')
            if (separator < 0) continue
            if (line.substring(0, separator).trim() != key) continue
            var value = line.substring(separator + 1).trim()
            if (value.isEmpty()) return null
            val quote = value.first()
            if (quote == '"' || quote == '\'') {
                return readQuotedValue(value, quote)
            }
            value = value.substringBefore('#').trim()
            return value.takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun readQuotedValue(value: String, quote: Char): String? {
        val output = StringBuilder()
        var index = 1
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && quote == '"' && index + 1 < value.length) {
                output.append(value[index + 1])
                index += 2
                continue
            }
            if (char == quote) {
                return output.toString().trim().takeIf { it.isNotEmpty() }
            }
            output.append(char)
            index += 1
        }
        return null
    }
}
