package saien.quotadog.app.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import saien.quotadog.CloudSyncStatus
import saien.quotadog.CloudSyncUiState
import saien.quotadog.EmailPrivacyMode
import saien.quotadog.ThemeMode
import saien.quotadog.UsageDisplayMode
import saien.quotadog.app.theme.QdTheme

/**
 * Full-height settings panel that slides up. Lives over the main content with its own scroll
 * area; on tall screens it caps at 640dp wide (desktop) and stays bottom-anchored on phones.
 */
@Composable
fun QdSettingsSheet(
    visible: Boolean,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    usageDisplayMode: UsageDisplayMode,
    onUsageDisplayModeChange: (UsageDisplayMode) -> Unit,
    showProjectedUsage: Boolean,
    onShowProjectedUsageChange: (Boolean) -> Unit,
    emailPrivacyMode: EmailPrivacyMode,
    onEmailPrivacyModeChange: (EmailPrivacyMode) -> Unit,
    autoRefreshMinutes: Int,
    onAutoRefreshChange: (Int) -> Unit,
    refreshAllBusy: Boolean,
    refreshAllEnabled: Boolean,
    onRefreshAll: () -> Unit,
    cloudSyncState: CloudSyncUiState,
    syncPassphrase: String,
    onSyncPassphraseChange: (String) -> Unit,
    onConnectDropbox: () -> Unit,
    onUnlockCloudSync: () -> Unit,
    onSyncNow: () -> Unit,
    onCancelCloudSync: () -> Unit,
    onResetCloudSync: () -> Unit,
    onDisconnectCloudSync: () -> Unit,
    onDismiss: () -> Unit,
    versionLabel: String,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(180)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(animationSpec = tween(260)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(animationSpec = tween(220)) { it } + fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .heightIn(max = 720.dp)
                    .padding(start = spacing.md, end = spacing.md, top = spacing.md, bottom = spacing.md + safeBottom)
                    .clip(QdTheme.shapes.xl)
                    .background(colors.backgroundElevated),
            ) {
                // Header row.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = spacing.xxl, end = spacing.lg, top = spacing.xl, bottom = spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Settings",
                        style = typo.titleLarge,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    QdGlassIconButton(onClick = onDismiss, diameter = 44.dp) {
                        // Re-use the chevron rotated 90 degrees as a "close down" affordance.
                        QdChevronRightIcon(
                            modifier = Modifier.rotate(90f),
                            tint = colors.textSecondary,
                            size = 20.dp,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .padding(horizontal = spacing.xxl)
                        .fillMaxWidth()
                        .size(width = 0.dp, height = 1.dp)
                        .background(colors.border),
                )

                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(spacing.xxl),
                    verticalArrangement = Arrangement.spacedBy(spacing.xxl),
                ) {
                    // Appearance.
                    QdSettingsRow(
                        title = "Appearance",
                        description = "Switch between light, dark, or follow your system.",
                        control = {
                            QdSegmentedControl(
                                options = listOf(
                                    "System" to ThemeMode.System,
                                    "Light" to ThemeMode.Light,
                                    "Dark" to ThemeMode.Dark,
                                ),
                                selected = themeMode,
                                onSelect = onThemeChange,
                            )
                        },
                    )

                    // Quota display.
                    QdSettingsRow(
                        title = "Quota display",
                        description = "Choose whether progress bars emphasize used or remaining quota.",
                        control = {
                            QdSegmentedControl(
                                options = listOf(
                                    "Used" to UsageDisplayMode.Used,
                                    "Remaining" to UsageDisplayMode.Remaining,
                                ),
                                selected = usageDisplayMode,
                                onSelect = onUsageDisplayModeChange,
                            )
                        },
                    )

                    // Usage estimate.
                    QdSettingsRow(
                        title = "Usage estimate",
                        description = "Project session and weekly totals from the current pace.",
                        control = {
                            QdSegmentedControl(
                                options = listOf(
                                    "Off" to false,
                                    "On" to true,
                                ),
                                selected = showProjectedUsage,
                                onSelect = onShowProjectedUsageChange,
                            )
                        },
                    )

                    // Email privacy.
                    QdSettingsRow(
                        title = "Email privacy",
                        description = "Mask the middle of account emails while keeping both ends visible.",
                        control = {
                            QdSegmentedControl(
                                options = listOf(
                                    "Visible" to EmailPrivacyMode.Visible,
                                    "Masked" to EmailPrivacyMode.Masked,
                                ),
                                selected = emailPrivacyMode,
                                onSelect = onEmailPrivacyModeChange,
                            )
                        },
                    )

                    // Auto refresh.
                    QdSettingsRow(
                        title = "Auto refresh",
                        description = if (autoRefreshMinutes == 0) {
                            "Quota windows are pulled only when you tap refresh."
                        } else {
                            "Quota windows refresh every $autoRefreshMinutes minutes while the app is open."
                        },
                        control = {
                            QdSegmentedControl(
                                options = listOf(
                                    "Off" to 0,
                                    "5 m" to 5,
                                    "15 m" to 15,
                                    "30 m" to 30,
                                    "1 h" to 60,
                                ),
                                selected = autoRefreshMinutes,
                                onSelect = onAutoRefreshChange,
                            )
                        },
                    )

                    // Manual refresh.
                    QdSettingsRow(
                        title = "Refresh now",
                        description = if (refreshAllBusy) {
                            "Refreshing signed-in providers concurrently."
                        } else {
                            "Pull every signed-in provider immediately."
                        },
                        trailing = {
                            QdButton(
                                text = if (refreshAllBusy) "Refreshing" else "Refresh all",
                                onClick = onRefreshAll,
                                variant = QdButtonVariant.Secondary,
                                size = QdButtonSize.Small,
                                enabled = refreshAllEnabled,
                                leading = {
                                    QdRefreshIcon(tint = colors.textPrimary, size = 14.dp)
                                },
                            )
                        },
                    )

                    // Cloud sync.
                    QdSettingsRow(
                        title = "Cloud sync",
                        description = cloudSyncDescription(cloudSyncState),
                        control = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                            ) {
                                OutlinedTextField(
                                    value = syncPassphrase,
                                    onValueChange = onSyncPassphraseChange,
                                    enabled = !cloudSyncState.busy,
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    label = { Text("Sync passphrase (8+ chars)") },
                                    placeholder = { Text("Use the same phrase on every device") },
                                    colors = TextFieldDefaults.outlinedTextFieldColors(
                                        textColor = colors.textPrimary,
                                        focusedBorderColor = colors.primary,
                                        unfocusedBorderColor = colors.border,
                                        focusedLabelColor = colors.primary,
                                        unfocusedLabelColor = colors.textSecondary,
                                        cursorColor = colors.primary,
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = "This encrypts your Dropbox sync file. It is not your Dropbox password, is never uploaded, and cannot be recovered if forgotten.",
                                    style = typo.caption,
                                    color = colors.textSecondary,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (cloudSyncState.busy) {
                                        QdButton(
                                            text = "Cancel",
                                            onClick = onCancelCloudSync,
                                            variant = QdButtonVariant.Secondary,
                                            size = QdButtonSize.Small,
                                        )
                                    } else {
                                        when {
                                            !cloudSyncState.connected -> {
                                                QdButton(
                                                    text = "Connect Dropbox",
                                                    onClick = onConnectDropbox,
                                                    variant = QdButtonVariant.Primary,
                                                    size = QdButtonSize.Small,
                                                )
                                            }
                                            cloudSyncState.status == CloudSyncStatus.Locked -> {
                                                QdButton(
                                                    text = "Unlock",
                                                    onClick = onUnlockCloudSync,
                                                    variant = QdButtonVariant.Primary,
                                                    size = QdButtonSize.Small,
                                                )
                                            }
                                            else -> {
                                                QdButton(
                                                    text = "Sync now",
                                                    onClick = onSyncNow,
                                                    variant = QdButtonVariant.Secondary,
                                                    size = QdButtonSize.Small,
                                                )
                                            }
                                        }
                                    }
                                    if (cloudSyncState.connected) {
                                        QdButton(
                                            text = "Disconnect",
                                            onClick = onDisconnectCloudSync,
                                            variant = QdButtonVariant.Ghost,
                                            size = QdButtonSize.Small,
                                            enabled = !cloudSyncState.busy,
                                        )
                                    }
                                }
                                if (cloudSyncState.connected && !cloudSyncState.busy) {
                                    QdButton(
                                        text = "Reset sync file",
                                        onClick = onResetCloudSync,
                                        variant = QdButtonVariant.Danger,
                                        size = QdButtonSize.Small,
                                    )
                                }
                            }
                        },
                    )

                    // About.
                    QdSettingsRow(
                        title = "About",
                        description = "QuotaDog $versionLabel - track Codex and Claude Code quotas.",
                    )
                }
            }
        }
    }
}

private fun cloudSyncDescription(state: CloudSyncUiState): String {
    state.message?.let { return it }
    return when (state.status) {
        CloudSyncStatus.Disconnected -> "Use your own Dropbox App Folder to sync encrypted tokens, usage cache, and settings across devices."
        CloudSyncStatus.Locked -> "Dropbox is connected. Enter your sync passphrase to unlock this device."
        CloudSyncStatus.Connecting -> "Connecting to Dropbox..."
        CloudSyncStatus.Syncing -> "Syncing encrypted QuotaDog data with Dropbox..."
        CloudSyncStatus.Connected -> "Dropbox sync is enabled."
        CloudSyncStatus.Error -> "Dropbox sync needs attention."
    }
}
