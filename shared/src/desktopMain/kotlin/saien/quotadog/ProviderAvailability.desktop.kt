package saien.quotadog

import java.io.File

actual fun availableProviders(): List<ProviderId> = ProviderId.entries

actual fun loadGrokCredentialsFromCli(): OAuthTokenBundle {
    val file = grokAuthFile()
    if (!file.exists()) {
        throw ProviderException(
            AuthState.NotConfigured,
            "Grok auth.json not found at ${file.absolutePath}. Run `grok login` first."
        )
    }
    return runCatching {
        GrokAuthParser.parseAuthJson(file.readText())
    }.getOrElse { error ->
        when (error) {
            is ProviderException -> throw error
            else -> throw ProviderException(
                AuthState.Error,
                "Failed to read Grok credentials from ${file.absolutePath}."
            )
        }
    }
}

actual fun grokAuthFileHint(): String = grokAuthFile().absolutePath

private fun grokAuthFile(): File {
    val home = System.getenv("GROK_HOME")?.takeIf { it.isNotBlank() }
        ?: File(System.getProperty("user.home"), ".grok").absolutePath
    return File(home, "auth.json")
}