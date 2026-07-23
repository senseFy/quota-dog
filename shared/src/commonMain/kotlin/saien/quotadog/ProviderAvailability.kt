package saien.quotadog

expect fun availableProviders(): List<ProviderId>

expect fun loadGrokCredentialsFromCli(): OAuthTokenBundle

expect fun grokAuthFileHint(): String

expect fun grokCliImportAvailable(): Boolean

expect fun loadCursorCredentialsFromLocalApp(): OAuthTokenBundle

expect fun cursorAuthFileHint(): String

expect fun loadAntigravityCredentialsFromCli(): OAuthTokenBundle

expect fun antigravityAuthHint(): String
