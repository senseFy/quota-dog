package saien.quotadog.hostsync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import saien.quotadog.AccountKey
import saien.quotadog.AccountUiState
import saien.quotadog.AuthState
import saien.quotadog.EmailPrivacyMode
import saien.quotadog.ProviderId
import saien.quotadog.ProviderUsageSnapshot
import saien.quotadog.app.accountTitle
import saien.quotadog.app.attributableSnapshot
import saien.quotadog.app.components.QdButton
import saien.quotadog.app.components.QdButtonSize
import saien.quotadog.app.components.QdButtonVariant
import saien.quotadog.app.components.QdSegmentedControl
import saien.quotadog.app.theme.QdTheme

// Only the owner sees this device-local label. It also distinguishes two imports with the same
// email on the same provider. Received cards use different, per-pair random grant labels.
internal fun AccountKey.localSourceLabel() = "Account ${accountId.hashCode().toUInt().toString(16).padStart(8, '0').uppercase()}"

internal data class GrantOption(val label: String, val snapshot: ProviderUsageSnapshot)
internal data class GrantSources(
    val accounts: Map<AccountKey, GrantOption>,
    val workspaces: Map<AccountKey, GrantOption>,
)

internal fun shareableSources(local: Collection<AccountUiState>, privacy: EmailPrivacyMode): GrantSources {
    val accounts = mutableMapOf<AccountKey, GrantOption>()
    val workspaces = mutableMapOf<AccountKey, GrantOption>()
    for (account in local.sortedWith(compareBy({ it.providerId.displayName }, { it.accountKey.accountId }))) {
        if (account.authState != AuthState.LoggedIn) continue
        val snapshot = account.attributableSnapshot() ?: continue
        val key = account.accountKey
        val label = "${account.accountTitle(privacy)} · ${key.localSourceLabel()}"
        if (snapshot.toQuotaReading() != null) accounts[key] = GrantOption(label, snapshot)
        // The grant's opaque scope (or one-observation nonce) is not a name a user can recognize.
        val workspace = snapshot.ampWorkspaceName?.takeIf { it.isNotBlank() }
        if (workspace != null && key.providerId == ProviderId.AMP && snapshot.toQuotaReading(workspace = true) != null) {
            workspaces[key] = GrantOption("$label · $workspace workspace credits", snapshot)
        }
    }
    return GrantSources(accounts, workspaces)
}

private val accessOptions = listOf("Not shared" to Access.Off, "View only" to Access.View, "Allow refresh" to Access.Refresh)

/** Callers supply only local candidates and this recipient's outgoing grants, never received refs. */
@Composable
internal fun SourceGrants(
    sources: GrantSources,
    grants: Map<AccountKey, GrantPermission>,
    workspaceGrants: Map<AccountKey, GrantPermission>,
    onSetAccess: (GrantEdit) -> Unit,
) {
    if (sources.accounts.isEmpty() && sources.workspaces.isEmpty()) {
        Hint("Refresh a signed-in account on this device to make it available for sharing.")
    }
    for (workspace in listOf(false, true)) {
        val available = if (workspace) sources.workspaces else sources.accounts
        val allowed = if (workspace) workspaceGrants else grants
        val keys = available.keys + allowed.keys
        if (workspace && keys.isNotEmpty()) {
            Text("Amp workspace credits", style = QdTheme.typography.titleMedium, color = QdTheme.colors.textPrimary)
            Hint("Separate permission. Sharing an Amp account does not share its workspace credits.")
        }
        for (source in keys) key(source, workspace) {
            val option = available[source]
            val permission = allowed[source]
            val access = permission?.access ?: Access.Off
            val select = { to: Access -> onSetAccess(GrantEdit(source, workspace, permission, to, option?.snapshot)) }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(QdTheme.spacing.xs)) {
                Text(option?.label ?: "${source.providerId.displayName} · ${source.localSourceLabel()}",
                    style = QdTheme.typography.labelLarge, color = QdTheme.colors.textPrimary)
                if (option != null) QdSegmentedControl(options = accessOptions, selected = access,
                    onSelect = { if (it != access) select(it) })
                else {
                    Hint("Currently unavailable · ${if (access == Access.View) "View only" else "Refresh allowed"}")
                    QdButton(if (workspace) "Stop sharing unavailable workspace" else "Stop sharing unavailable account",
                        { select(Access.Off) }, variant = QdButtonVariant.Secondary, size = QdButtonSize.Small)
                }
            }
        }
    }
}
