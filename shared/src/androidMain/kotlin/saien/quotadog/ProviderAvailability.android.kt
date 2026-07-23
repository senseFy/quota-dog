package saien.quotadog

actual fun availableProviders(): List<ProviderId> = listOf(
    ProviderId.CODEX,
    ProviderId.CLAUDE_CODE,
    ProviderId.GROK,
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
