package saien.quotadog

actual fun availableProviders(): List<ProviderId> = listOf(
    ProviderId.CODEX,
    ProviderId.CLAUDE_CODE,
)

actual fun loadGrokCredentialsFromCli(): OAuthTokenBundle {
    throw ProviderException(
        AuthState.NotConfigured,
        "Grok is only available on the desktop app."
    )
}

actual fun grokAuthFileHint(): String = "~/.grok/auth.json"

actual fun loadCursorCredentialsFromLocalApp(): OAuthTokenBundle {
    throw ProviderException(
        AuthState.NotConfigured,
        "Cursor is only available on the desktop app."
    )
}

actual fun cursorAuthFileHint(): String =
    "~/Library/Application Support/Cursor/User/globalStorage/state.vscdb"
