package saien.quotadog

/**
 * Sign-in methods a provider integration supports.
 *
 * [OAuth] runs the provider's own browser / device-code flow. [CliImport] reads the
 * credentials the provider's CLI or desktop app already stored on this device instead.
 */
enum class ProviderSignInMethod {
    OAuth,
    CliImport,
}

/**
 * Sign-in methods this provider supports at all, independent of the current platform.
 *
 * Codex and Claude Code only speak OAuth. Cursor, Antigravity, and Devin only expose local
 * credential import. Grok and Droid support both, and the picker offers a method choice for
 * them when the local CLI store is reachable.
 */
fun ProviderId.supportedSignInMethods(): Set<ProviderSignInMethod> = when (this) {
    ProviderId.CODEX, ProviderId.CLAUDE_CODE -> setOf(ProviderSignInMethod.OAuth)
    ProviderId.CURSOR, ProviderId.ANTIGRAVITY, ProviderId.DEVIN -> setOf(ProviderSignInMethod.CliImport)
    ProviderId.GROK, ProviderId.DROID -> setOf(ProviderSignInMethod.OAuth, ProviderSignInMethod.CliImport)
}

/** Whether browser / device-code sign-in works for this provider on the current platform. */
fun ProviderId.oauthAvailable(): Boolean = ProviderSignInMethod.OAuth in supportedSignInMethods()

/**
 * Whether local CLI/app credential import works for this provider on the current platform.
 *
 * Grok and Droid keep their credential stores outside the sandbox on desktop only, so the
 * support columns and method picker defer to the platform checks for those two.
 */
fun ProviderId.cliImportAvailable(): Boolean = when (this) {
    ProviderId.GROK -> grokCliImportAvailable()
    ProviderId.DROID -> droidCliImportAvailable()
    // Cursor, Antigravity, and Devin keep their credentials where the sandbox allows, so they
    // are offered only where availableProviders() lists them (desktop).
    else -> this in availableProviders() && ProviderSignInMethod.CliImport in supportedSignInMethods()
}
