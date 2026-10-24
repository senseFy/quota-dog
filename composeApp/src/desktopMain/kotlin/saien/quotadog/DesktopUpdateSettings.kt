package saien.quotadog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import saien.quotadog.app.components.QdButton
import saien.quotadog.app.components.QdButtonSize
import saien.quotadog.app.components.QdButtonVariant
import saien.quotadog.app.components.QdSettingsRow
import saien.quotadog.app.theme.QdTheme

@Composable
internal fun DesktopUpdateSettings(updater: DesktopUpdater) {
    val state by updater.state.collectAsState()
    DesktopUpdateControls(state) { action ->
        when (action) {
            DesktopUpdateAction.Check -> updater.check()
            DesktopUpdateAction.Download -> updater.download()
            DesktopUpdateAction.Install -> updater.install()
            DesktopUpdateAction.Cancel -> updater.cancel()
            DesktopUpdateAction.Discard -> updater.discard()
            DesktopUpdateAction.Recover -> updater.retryRecovery()
        }
    }
}

internal enum class DesktopUpdateAction { Check, Download, Install, Cancel, Discard, Recover }

@Composable
internal fun DesktopUpdateControls(state: DesktopUpdateUiState, onAction: (DesktopUpdateAction) -> Unit) {
    QdSettingsRow(title = "Software update", description = state.message, control = {
        Column(verticalArrangement = Arrangement.spacedBy(QdTheme.spacing.sm)) {
            state.progress?.let { LinearProgressIndicator(progress = it, modifier = Modifier.fillMaxWidth(), color = QdTheme.colors.primary) }
            state.notes?.takeIf(String::isNotBlank)?.let {
                Text(it, style = QdTheme.typography.caption, color = QdTheme.colors.textSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(QdTheme.spacing.sm)) {
                if (state.canCheck) QdButton("Check for updates", { onAction(DesktopUpdateAction.Check) }, size = QdButtonSize.Small)
                if (state.canDownload) QdButton("Download update", { onAction(DesktopUpdateAction.Download) }, size = QdButtonSize.Small)
                if (state.canInstall) QdButton("Install & restart", { onAction(DesktopUpdateAction.Install) }, size = QdButtonSize.Small)
                if (state.canCancel) QdButton("Cancel", { onAction(DesktopUpdateAction.Cancel) }, size = QdButtonSize.Small, variant = QdButtonVariant.Secondary)
                if (state.canDiscard) QdButton("Discard", { onAction(DesktopUpdateAction.Discard) }, size = QdButtonSize.Small, variant = QdButtonVariant.Secondary)
                if (state.canRecover) QdButton("Retry recovery", { onAction(DesktopUpdateAction.Recover) }, size = QdButtonSize.Small)
            }
        }
    })
}
