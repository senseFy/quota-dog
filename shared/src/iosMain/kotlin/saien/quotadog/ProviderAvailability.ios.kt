package saien.quotadog

actual fun availableProviders(): List<ProviderId> = listOf(
    ProviderId.CODEX,
    ProviderId.CLAUDE_CODE,
    ProviderId.GROK,
    ProviderId.DROID,
)

actual fun loadGrokCredentialsFromCli(): OAuthTokenBundle {
    throw ProviderException(
        AuthState.NotConfigured,
        "Grok CLI import is only available on the desktop app. Sign in with xAI instead."
    )
}

actual fun grokAuthFileHint(): String = "~/.grok/auth.json"

actual fun grokCliImportAvailable(): Boolean = false

actual fun loadCursorCredentialsFromLocalApp(): OAuthTokenBundle {
    throw ProviderException(
        AuthState.NotConfigured,
        "Cursor is only available on the desktop app."
    )
}

actual fun cursorAuthFileHint(): String =
    "~/Library/Application Support/Cursor/User/globalStorage/state.vscdb"

actual fun loadAntigravityCredentialsFromCli(): OAuthTokenBundle {
    throw ProviderException(
        AuthState.NotConfigured,
        "Antigravity CLI is only available on the desktop app."
    )
}

actual fun antigravityAuthHint(): String = "macOS Keychain (service=gemini, account=antigravity)"

actual fun loadDevinCredentialsFromCli(): OAuthTokenBundle {
    throw ProviderException(
        AuthState.NotConfigured,
        "Devin CLI import is only available on the desktop app."
    )
}

actual fun devinAuthFileHint(): String = "~/.local/share/devin/credentials.toml"

actual fun loadDroidCredentialsFromCli(): OAuthTokenBundle {
    throw ProviderException(
        AuthState.NotConfigured,
        "droid CLI import is only available on the desktop app. Sign in with Factory instead."
    )
}

actual fun droidAuthFileHint(): String = "~/.factory"

actual fun droidCliImportAvailable(): Boolean = false
