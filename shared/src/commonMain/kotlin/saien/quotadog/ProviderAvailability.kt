package saien.quotadog

expect fun availableProviders(): List<ProviderId>

expect fun loadGrokCredentialsFromCli(): OAuthTokenBundle

expect fun grokAuthFileHint(): String