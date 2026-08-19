package saien.quotadog

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

actual fun grokCliImportAvailable(): Boolean = true

actual fun loadCursorCredentialsFromLocalApp(): OAuthTokenBundle {
    val fromDb = runCatching { loadCursorCredentialsFromStateDb() }
    fromDb.getOrNull()?.let { return it }

    val fromKeychain = runCatching { loadCursorCredentialsFromKeychain() }
    fromKeychain.getOrNull()?.let { return it }

    throw preferredCursorImportError(fromDb.exceptionOrNull(), fromKeychain.exceptionOrNull())
}

actual fun cursorAuthFileHint(): String {
    val dbFile = cursorStateDbFile()
    return if (isMacOs()) {
        if (dbFile.exists()) {
            "${dbFile.absolutePath} or macOS Keychain (cursor-access-token)"
        } else {
            "macOS Keychain (cursor-access-token) or ${dbFile.absolutePath}"
        }
    } else {
        dbFile.absolutePath
    }
}

actual fun loadAntigravityCredentialsFromCli(): OAuthTokenBundle {
    if (!isMacOs()) {
        throw ProviderException(
            AuthState.NotConfigured,
            "Antigravity CLI import is currently supported on macOS only. Run `agy` on a Mac, then import.",
        )
    }
    val service = System.getenv("ANTIGRAVITY_KEYRING_SERVICE")?.takeIf { it.isNotBlank() } ?: "gemini"
    val account = System.getenv("ANTIGRAVITY_KEYRING_ACCOUNT")?.takeIf { it.isNotBlank() } ?: "antigravity"
    val secret = readMacKeychainSecret(
        service = service,
        account = account,
        notFoundMessage = "Antigravity CLI credentials not found in Keychain ($service / $account). " +
            "Run `agy`, sign in with Google OAuth, then import again.",
    )
    return AntigravityAuthParser.parseKeyringSecret(secret)
}

actual fun antigravityAuthHint(): String = "macOS Keychain (service=gemini, account=antigravity)"

private fun grokAuthFile(): File {
    val home = System.getenv("GROK_HOME")?.takeIf { it.isNotBlank() }
        ?: File(System.getProperty("user.home"), ".grok").absolutePath
    return File(home, "auth.json")
}

private fun cursorStateDbFile(): File {
    System.getenv("CURSOR_STATE_VSCDB")?.takeIf { it.isNotBlank() }?.let { return File(it) }
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val path = when {
        os.contains("mac") || os.contains("darwin") ->
            File(home, "Library/Application Support/Cursor/User/globalStorage/state.vscdb")
        os.contains("win") -> {
            val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                ?: File(home, "AppData/Roaming").absolutePath
            File(appData, "Cursor/User/globalStorage/state.vscdb")
        }
        else -> File(home, ".config/Cursor/User/globalStorage/state.vscdb")
    }
    return path
}

private fun loadCursorCredentialsFromStateDb(): OAuthTokenBundle {
    val dbFile = cursorStateDbFile()
    if (!dbFile.exists()) {
        throw ProviderException(
            AuthState.NotConfigured,
            "Cursor auth database not found at ${dbFile.absolutePath}. Install Cursor, sign in, then import again.",
        )
    }
    val rows = readCursorAuthRows(dbFile)
    return CursorAuthParser.fromAuthRows(rows)
}

private fun loadCursorCredentialsFromKeychain(): OAuthTokenBundle {
    if (!isMacOs()) {
        throw ProviderException(
            AuthState.NotConfigured,
            "Cursor CLI keychain import is currently supported on macOS only.",
        )
    }
    val accessToken = readMacKeychainSecret(
        service = "cursor-access-token",
        account = "cursor-user",
        notFoundMessage = "Cursor CLI access token not found in Keychain. Run `cursor-agent login`, then import again.",
    )
    val refreshToken = runCatching {
        readMacKeychainSecret(service = "cursor-refresh-token", account = "cursor-user")
    }.getOrNull()
    return CursorAuthParser.toTokenBundle(
        accessToken = accessToken,
        refreshToken = refreshToken,
        email = CursorAuthParser.emailFromAccessToken(accessToken),
    )
}

private fun preferredCursorImportError(dbError: Throwable?, keychainError: Throwable?): ProviderException {
    val errors = listOfNotNull(dbError, keychainError).filterIsInstance<ProviderException>()
    val hardError = errors.firstOrNull { it.state != AuthState.NotConfigured }
    return ProviderException(
        state = hardError?.state ?: AuthState.NotConfigured,
        message = hardError?.message
            ?: "Cursor credentials not found. Sign in to the Cursor app, or run `cursor-agent login`, then import again.",
    )
}

private fun isMacOs(): Boolean {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return os.contains("mac") || os.contains("darwin")
}

private fun readCursorAuthRows(dbFile: File): Map<String, String> {
    val uri = "file:${dbFile.absolutePath.replace('\\', '/')}?mode=ro&immutable=1"
    val process = runCatching {
        ProcessBuilder(
            "sqlite3",
            "-json",
            uri,
            "SELECT key, value FROM ItemTable WHERE key LIKE 'cursorAuth/%';",
        ).redirectErrorStream(true).start()
    }.getOrElse {
        throw ProviderException(
            AuthState.Error,
            "Could not run sqlite3 to read Cursor credentials. Install sqlite3 and try again.",
        )
    }
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        val preview = output.trim().take(200).ifBlank { "exit $exitCode" }
        throw ProviderException(
            AuthState.Error,
            "Failed to read Cursor auth database: $preview",
        )
    }
    return parseSqliteJsonRows(output)
}

private fun parseSqliteJsonRows(output: String): Map<String, String> {
    val trimmed = output.trim()
    if (trimmed.isEmpty() || trimmed == "[]") return emptyMap()
    val json = Json { ignoreUnknownKeys = true }
    val array = runCatching { json.parseToJsonElement(trimmed).jsonArray }.getOrElse {
        throw ProviderException(AuthState.Error, "Unexpected sqlite3 output while reading Cursor auth.")
    }
    val rows = linkedMapOf<String, String>()
    for (element in array) {
        val obj = element.jsonObject
        val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: continue
        val value = obj["value"]?.jsonPrimitive?.contentOrNull ?: continue
        rows[key] = value
    }
    return rows
}

private fun readMacKeychainSecret(
    service: String,
    account: String,
    notFoundMessage: String? = null,
): String {
    val process = runCatching {
        ProcessBuilder(
            "security",
            "find-generic-password",
            "-s",
            service,
            "-a",
            account,
            "-w",
        ).redirectErrorStream(true).start()
    }.getOrElse {
        throw ProviderException(
            AuthState.Error,
            "Could not run macOS `security` to read local credentials.",
        )
    }
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    val secret = output.trim()
    if (exitCode != 0 || secret.isEmpty()) {
        val preview = secret.take(200).ifBlank { "exit $exitCode" }
        val notFound = preview.contains("could not be found", ignoreCase = true) ||
            preview.contains("The specified item could not be found", ignoreCase = true)
        throw ProviderException(
            if (notFound) AuthState.NotConfigured else AuthState.Error,
            if (notFound) {
                notFoundMessage ?: "Keychain item not found ($service / $account)."
            } else {
                "Failed to read Keychain item ($service / $account): $preview"
            },
        )
    }
    return secret
}
