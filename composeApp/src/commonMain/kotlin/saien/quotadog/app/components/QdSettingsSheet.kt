package saien.quotadog.app.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import quotadog.composeapp.generated.resources.*
import saien.quotadog.DashboardLayout
import saien.quotadog.EmailPrivacyMode
import saien.quotadog.ThemeMode
import saien.quotadog.UsageDisplayMode
import saien.quotadog.app.theme.QdTheme

/**
 * Full-height settings panel with its own scroll area. It stays bottom-anchored on phones and
 * switches to a centered modal on wider desktop windows.
 */
@Composable
fun QdSettingsSheet(
    visible: Boolean,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    dashboardLayout: DashboardLayout?,
    onDashboardLayoutChange: (DashboardLayout) -> Unit,
    usageDisplayMode: UsageDisplayMode,
    onUsageDisplayModeChange: (UsageDisplayMode) -> Unit,
    showProjectedUsage: Boolean,
    onShowProjectedUsageChange: (Boolean) -> Unit,
    sinkExhaustedAccounts: Boolean,
    onSinkExhaustedAccountsChange: (Boolean) -> Unit,
    emailPrivacyMode: EmailPrivacyMode,
    onEmailPrivacyModeChange: (EmailPrivacyMode) -> Unit,
    autoRefreshMinutes: Int,
    onAutoRefreshChange: (Int) -> Unit,
    refreshAllBusy: Boolean,
    refreshAllEnabled: Boolean,
    onRefreshAll: () -> Unit,
    onDismiss: () -> Unit,
    versionLabel: String,
    platformSettings: (@Composable () -> Unit)? = null,
    onOpenComputer: (() -> Unit)? = null,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isDesktop = maxWidth >= 720.dp
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
            enter = if (isDesktop) {
                fadeIn(tween(200))
            } else {
                slideInVertically(animationSpec = tween(260)) { it } + fadeIn(tween(220))
            },
            exit = if (isDesktop) {
                fadeOut(tween(180))
            } else {
                slideOutVertically(animationSpec = tween(220)) { it } + fadeOut(tween(180))
            },
            modifier = Modifier.align(if (isDesktop) Alignment.Center else Alignment.BottomCenter),
        ) {
            val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .heightIn(max = 720.dp)
                    .padding(
                        start = spacing.md,
                        end = spacing.md,
                        top = spacing.md,
                        bottom = spacing.md + if (isDesktop) 0.dp else safeBottom,
                    )
                    .clip(QdTheme.shapes.lg)
                    .background(colors.backgroundElevated)
                    .border(1.dp, colors.border, QdTheme.shapes.lg),
            ) {
                // Header row.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = spacing.xl, end = spacing.md, top = spacing.md, bottom = spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.settings),
                        style = typo.titleLarge,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    val closeLabel = stringResource(Res.string.close_settings)
                    QdGlassIconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = closeLabel }, diameter = 32.dp) {
                        if (isDesktop) {
                            QdCloseIcon(tint = colors.textSecondary, size = 16.dp)
                        } else {
                            // Re-use the chevron rotated 90 degrees as a "close down" affordance.
                            QdChevronRightIcon(
                                modifier = Modifier.rotate(90f),
                                tint = colors.textSecondary,
                                size = 16.dp,
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .padding(horizontal = spacing.xl)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.border),
                )

                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(spacing.lg),
                ) {
                    if (onOpenComputer != null) {
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onOpenComputer),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            QdSettingsRow(
                                title = stringResource(Res.string.my_computer),
                                description = stringResource(Res.string.my_computer_hint),
                                trailing = { QdChevronRightIcon(tint = colors.textSecondary) },
                            )
                        }
                    }
                    // Appearance.
                    QdSettingsRow(
                        title = "Appearance",
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

                    // Only desktop has multiple dashboard layouts.
                    if (dashboardLayout != null) QdSettingsRow(
                        title = "Layout",
                        description = "How much of each account the dashboard shows.",
                        control = {
                            QdSegmentedControl(
                                options = DashboardLayout.entries.map { it.label to it },
                                selected = dashboardLayout,
                                onSelect = onDashboardLayoutChange,
                            )
                        },
                    )

                    // Quota display.
                    QdSettingsRow(
                        title = "Quota display",
                        description = "Progress bars show used or remaining quota.",
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
                        description = "Project totals from the current pace.",
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

                    // Spent accounts.
                    QdSettingsRow(
                        title = "Spent accounts",
                        description = "Fade an account when it runs out of quota, and move it down.",
                        control = {
                            QdSegmentedControl(
                                options = listOf(
                                    "Off" to false,
                                    "On" to true,
                                ),
                                selected = sinkExhaustedAccounts,
                                onSelect = onSinkExhaustedAccountsChange,
                            )
                        },
                    )

                    // Email privacy.
                    QdSettingsRow(
                        title = "Email privacy",
                        description = "Hide the middle of account emails.",
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
                        description = "Only while QuotaDog is open.",
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

                    platformSettings?.invoke()
                    QdSettingsRow(
                        title = "About",
                        description = "QuotaDog $versionLabel",
                    )
                }
            }
        }
    }
}

/** Name shown in settings and in the confirmation after a change. */
val DashboardLayout.label: String
    get() = when (this) {
        DashboardLayout.Cards -> "Cards"
        DashboardLayout.Compact -> "Compact"
        DashboardLayout.FoldFull -> "Fold full"
        DashboardLayout.OneLine -> "One line"
    }
