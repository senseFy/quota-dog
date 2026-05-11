package saien.quotadog.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import saien.quotadog.AccountKey
import saien.quotadog.AccountUiState
import saien.quotadog.AppPreferences
import saien.quotadog.AuthState
import saien.quotadog.DashboardState
import saien.quotadog.EmailPrivacyMode
import saien.quotadog.ProviderId
import saien.quotadog.QuotaDogStore
import saien.quotadog.ThemeMode
import saien.quotadog.UsageDisplayMode
import saien.quotadog.UsageWindow
import saien.quotadog.projectedUsedRatio
import saien.quotadog.app.components.QdAlertIcon
import saien.quotadog.app.components.QdBottomSheet
import saien.quotadog.app.components.QdButton
import saien.quotadog.app.components.QdButtonSize
import saien.quotadog.app.components.QdButtonVariant
import saien.quotadog.app.components.QdCard
import saien.quotadog.app.components.QdChevronRightIcon
import saien.quotadog.app.components.QdConfirmDialog
import saien.quotadog.app.components.QdEmptyState
import saien.quotadog.app.components.QdGlassIconButton
import saien.quotadog.app.components.QdIconButton
import saien.quotadog.app.components.QdMoreIcon
import saien.quotadog.app.components.QdPlusIcon
import saien.quotadog.app.components.QdProgressBar
import saien.quotadog.app.components.QdProviderAvatar
import saien.quotadog.app.components.QdRefreshIcon
import saien.quotadog.app.components.QdSettingsGearIcon
import saien.quotadog.app.components.QdSettingsSheet
import saien.quotadog.app.components.QdSnackbarHost
import saien.quotadog.app.components.QdSnackbarTone
import saien.quotadog.app.components.rememberQdSnackbarController
import saien.quotadog.app.theme.QdTheme
import saien.quotadog.app.theme.QuotaDogTheme

@Composable
fun App(
    store: QuotaDogStore = remember { QuotaDogStore() },
    preferences: AppPreferences = remember { AppPreferences() },
) {
    val themeMode by preferences.themeMode.collectAsState()
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val effectiveDark = when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    QuotaDogTheme(darkTheme = effectiveDark) {
        ApplyPlatformSystemBars(darkAppearance = effectiveDark)
        QuotaDogScreen(store, preferences)
    }
}

@Composable
private fun QuotaDogScreen(store: QuotaDogStore, preferences: AppPreferences) {
    val state by store.state.collectAsState()
    val themeMode by preferences.themeMode.collectAsState()
    val autoRefreshMinutes by preferences.autoRefreshMinutes.collectAsState()
    val usageDisplayMode by preferences.usageDisplayMode.collectAsState()
    val showProjectedUsage by preferences.showProjectedUsage.collectAsState()
    val emailPrivacyMode by preferences.emailPrivacyMode.collectAsState()
    val callbackInputs = remember { mutableStateMapOf<AccountKey, String>() }
    var showProviderPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AccountKey?>(null) }
    val snackbar = rememberQdSnackbarController()

    val colors = QdTheme.colors
    val spacing = QdTheme.spacing

    LaunchedEffect(Unit) {
        store.startDetectAll()
    }

    // Auto-refresh loop. Re-keys whenever the chosen interval changes so the previous loop
    // is cancelled and a new one starts cleanly.
    LaunchedEffect(autoRefreshMinutes) {
        val minutes = autoRefreshMinutes
        if (minutes <= 0) return@LaunchedEffect
        val intervalMs = minutes * 60_000L
        while (true) {
            delay(intervalMs)
            store.startRefreshAll()
        }
    }

    val accounts = state.accounts.values
        .filter { it.shouldShowAccount() }
        .sortedWith(compareBy<AccountUiState> { it.providerId.ordinal }.thenBy { it.accountSortLabel() })
    val refreshableAccounts = accounts.filter { it.canRefreshUsage() }
    val refreshAllBusy = refreshableAccounts.any { it.busy }
    val refreshAllEnabled = refreshableAccounts.any { !it.busy }

    // Edge-to-edge: outer Box paints the theme background under status bar and home indicator,
    // inner content respects safe drawing insets.
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
    val safeTop = safeInsets.calculateTopPadding()
    val safeBottom = safeInsets.calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.xl)
                .padding(top = safeTop + spacing.xl, bottom = safeBottom + 120.dp),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            DashboardHeader(
                onOpenSettings = { showSettings = true },
            )

            if (accounts.isEmpty()) {
                QdEmptyState(
                    title = "No accounts yet",
                    description = "Add a Codex or Claude Code account to start tracking quota windows in one place.",
                )
            } else {
                accounts.forEach { providerState ->
                    AccountCard(
                        state = providerState,
                        usageDisplayMode = usageDisplayMode,
                        showProjectedUsage = showProjectedUsage,
                        emailPrivacyMode = emailPrivacyMode,
                        callbackInput = callbackInputs[providerState.accountKey].orEmpty(),
                        onCallbackChange = { callbackInputs[providerState.accountKey] = it },
                        onCompleteLogin = {
                            val input = callbackInputs[providerState.accountKey].orEmpty()
                            store.startCompleteLogin(providerState.accountKey, input)
                        },
                        onReopenLogin = { store.startReopenLogin(providerState.accountKey) },
                        onSignInAgain = { store.startLogin(providerState.providerId) },
                        onRefresh = {
                            store.startRefresh(providerState.accountKey)
                            snackbar.show("Refreshing ${providerState.accountTitle(emailPrivacyMode)}...")
                        },
                        onRequestDelete = { pendingDelete = providerState.accountKey },
                    )
                }
            }
        }

        AddAccountButton(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = safeBottom + spacing.xxl),
            onClick = { showProviderPicker = true },
        )

        // Snackbar floats above the FAB (FAB sits at safeBottom + xxl with ~44dp height +
        // baseline padding lg -> leave ~84dp clearance plus safe inset).
        QdSnackbarHost(
            controller = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = safeBottom + 84.dp),
        )

        QdBottomSheet(
            visible = showProviderPicker,
            onDismiss = { showProviderPicker = false },
        ) {
            ProviderPickerContent(
                onSelect = { provider ->
                    showProviderPicker = false
                    store.startLogin(provider)
                    snackbar.show(
                        text = "Opening browser for ${provider.displayName}...",
                        tone = QdSnackbarTone.Info,
                    )
                },
            )
        }

        QdSettingsSheet(
            visible = showSettings,
            themeMode = themeMode,
            onThemeChange = {
                preferences.setThemeMode(it)
                snackbar.show("Theme set to ${it.name.lowercase()}")
            },
            usageDisplayMode = usageDisplayMode,
            onUsageDisplayModeChange = {
                preferences.setUsageDisplayMode(it)
                snackbar.show(
                    text = "Quota display set to ${it.name.lowercase()}",
                    tone = QdSnackbarTone.Success,
                )
            },
            showProjectedUsage = showProjectedUsage,
            onShowProjectedUsageChange = {
                preferences.setShowProjectedUsage(it)
                snackbar.show(
                    text = if (it) "Usage estimate enabled" else "Usage estimate disabled",
                    tone = QdSnackbarTone.Success,
                )
            },
            emailPrivacyMode = emailPrivacyMode,
            onEmailPrivacyModeChange = {
                preferences.setEmailPrivacyMode(it)
                snackbar.show(
                    text = if (it == EmailPrivacyMode.Masked) "Email privacy enabled" else "Email privacy disabled",
                    tone = QdSnackbarTone.Success,
                )
            },
            autoRefreshMinutes = autoRefreshMinutes,
            onAutoRefreshChange = {
                preferences.setAutoRefreshMinutes(it)
                snackbar.show(
                    text = if (it == 0) "Auto refresh disabled" else "Auto refresh every $it min",
                    tone = QdSnackbarTone.Success,
                )
            },
            refreshAllBusy = refreshAllBusy,
            refreshAllEnabled = refreshAllEnabled,
            onRefreshAll = {
                store.startRefreshAll()
                snackbar.show(
                    text = if (refreshAllBusy) "Refreshing remaining accounts..." else "Refreshing all accounts...",
                    tone = QdSnackbarTone.Info,
                )
            },
            onDismiss = { showSettings = false },
            versionLabel = "v1.0.0",
        )

        QdConfirmDialog(
            visible = pendingDelete != null,
            title = "Remove account?",
            message = "The locally stored token for ${pendingDelete?.deleteLabel(state, emailPrivacyMode).orEmpty()} will be deleted. You can sign in again at any time.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = {
                pendingDelete?.let {
                    store.startDelete(it)
                    snackbar.show("${it.deleteLabel(state, emailPrivacyMode)} removed", tone = QdSnackbarTone.Success)
                }
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun DashboardHeader(
    onOpenSettings: () -> Unit,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Text(
                    "Quota",
                    style = typo.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                )
                Text(
                    " / ",
                    style = typo.titleLarge.copy(fontWeight = FontWeight.Black),
                    color = colors.primary,
                )
                Text(
                    "Dog",
                    style = typo.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = colors.textPrimary,
                )
            }
        }
        QdGlassIconButton(onClick = onOpenSettings, diameter = 48.dp) {
            QdSettingsGearIcon(tint = colors.textSecondary, size = 24.dp)
        }
    }
}

@Composable
private fun AccountCard(
    state: AccountUiState,
    usageDisplayMode: UsageDisplayMode,
    showProjectedUsage: Boolean,
    emailPrivacyMode: EmailPrivacyMode,
    callbackInput: String,
    onCallbackChange: (String) -> Unit,
    onCompleteLogin: () -> Unit,
    onReopenLogin: () -> Unit,
    onSignInAgain: () -> Unit,
    onRefresh: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    var menuExpanded by remember { mutableStateOf(false) }

    QdCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                QdProviderAvatar(state.providerId, size = 28.dp)

                Column(modifier = Modifier.weight(1f)) {
                    AccountEmailTitle(state.accountSubtitle(), emailPrivacyMode)
                }

                QdIconButton(onClick = onRefresh, enabled = !state.busy) {
                    val rotation = rememberRefreshRotation(active = state.busy)
                    QdRefreshIcon(
                        modifier = Modifier.rotate(rotation),
                        tint = colors.textSecondary,
                    )
                }

                Box {
                    QdIconButton(onClick = { menuExpanded = true }) {
                        QdMoreIcon(tint = colors.textSecondary)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        if (state.loginStart != null) {
                            DropdownMenuItem(onClick = {
                                menuExpanded = false
                                onReopenLogin()
                            }) {
                                Text("Reopen authorization page", style = typo.bodyMedium)
                            }
                        }
                        DropdownMenuItem(onClick = {
                            menuExpanded = false
                            onRequestDelete()
                        }) {
                            Text("Remove account", color = colors.danger, style = typo.bodyMedium)
                        }
                    }
                }
            }

            val windows = state.snapshot?.windows.orEmpty()
            if (windows.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    windows.forEach { UsageWindowRow(it, usageDisplayMode, showProjectedUsage) }
                }
            } else {
                InlineStatus(
                    text = if (state.loginStart == null) {
                        "No usage data yet - tap refresh to fetch the latest."
                    } else if (state.busy) {
                        "Authorization page opened - waiting for the automatic callback."
                    } else {
                        "Automatic callback not received. Use the manual fallback below."
                    },
                )
            }

            state.loginStart?.let {
                if (!state.busy) {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        OutlinedTextField(
                            value = callbackInput,
                            onValueChange = onCallbackChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Paste callback URL", style = typo.caption) },
                            singleLine = true,
                            shape = QdTheme.shapes.md,
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                focusedBorderColor = colors.primary,
                                unfocusedBorderColor = colors.border,
                                cursorColor = colors.primary,
                                focusedLabelColor = colors.primary,
                                unfocusedLabelColor = colors.textTertiary,
                            ),
                        )
                        QdButton(
                            text = "Finish sign-in",
                            onClick = onCompleteLogin,
                            size = QdButtonSize.Small,
                        )
                    }
                }
            }

            AccountStatusRow(
                state = state,
                onReopenLogin = onReopenLogin,
                onSignInAgain = onSignInAgain,
            )
        }
    }
}

@Composable
private fun AccountEmailTitle(value: String, privacyMode: EmailPrivacyMode) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    if (privacyMode == EmailPrivacyMode.Visible || !value.canMaskEmail()) {
        Text(
            value,
            style = typo.titleMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }

    val fullStop = value.maskFullStop()
    Box(modifier = Modifier.clipToBounds()) {
        Text(
            value,
            style = typo.titleMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(QdTheme.shapes.xs)
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            fullStop to colors.surfaceMuted,
                            0.5f to colors.surfaceMuted,
                            (1f - fullStop) to colors.surfaceMuted,
                            1f to Color.Transparent,
                        ),
                    )
                )
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            fullStop to Color.White.copy(alpha = 0.22f),
                            0.5f to Color.White.copy(alpha = 0.16f),
                            (1f - fullStop) to Color.White.copy(alpha = 0.22f),
                            1f to Color.Transparent,
                        ),
                    )
                ),
        )
    }
}

@Composable
private fun rememberRefreshRotation(active: Boolean): Float {
    if (!active) return 0f
    val transition = rememberInfiniteTransition()
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )
    return rotation
}

@Composable
private fun UsageWindowRow(window: UsageWindow, displayMode: UsageDisplayMode, showProjectedUsage: Boolean) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    val progress = window.displayRatio(displayMode)
    val progressFill = window.progressFill(displayMode)
    val pct = (progress * 100).toInt()
    val projectedPct = if (showProjectedUsage) {
        window.projectedUsedRatio()?.let { (it * 100).toInt() }
    } else {
        null
    }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        QdProgressBar(
            progress = progress.toFloat(),
            fill = progressFill,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                window.infoLabel(),
                style = typo.caption,
                color = colors.textTertiary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${window.remainingTime()} · ",
                    style = typo.caption,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$pct%",
                    style = typo.caption.copy(fontWeight = FontWeight.Bold),
                    color = progressFill,
                    maxLines = 1,
                )
                if (projectedPct != null) {
                    Text(
                        " est $projectedPct%",
                        style = typo.caption,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineStatus(text: String) {
    val colors = QdTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(QdTheme.shapes.md)
            .background(colors.surfaceMuted)
            .padding(horizontal = QdTheme.spacing.md, vertical = QdTheme.spacing.sm),
    ) {
        Text(text, style = QdTheme.typography.bodyMedium, color = colors.textSecondary)
    }
}

@Composable
private fun AccountStatusRow(
    state: AccountUiState,
    onReopenLogin: () -> Unit,
    onSignInAgain: () -> Unit,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    val status = when {
        state.busy -> AccountStatusUi(
            title = "Syncing",
            message = state.message ?: "Refreshing account data...",
            bg = colors.primaryMuted,
            fg = colors.primary,
        )
        state.loginStart != null -> AccountStatusUi(
            title = "Waiting for sign-in",
            message = state.message ?: "Finish authorization in the browser or paste the callback URL.",
            bg = colors.surfaceMuted,
            fg = colors.textSecondary,
            action = AccountStatusAction.ReopenLogin,
            actionLabel = "Open auth page",
        )
        state.authState == AuthState.TokenExpired ||
            state.authState == AuthState.Unauthorized ||
            state.authState == AuthState.RequiresRelogin -> AccountStatusUi(
                title = "Sign-in needed",
                message = state.message ?: "This account needs authorization again.",
                bg = colors.dangerSoft,
                fg = colors.danger,
                action = AccountStatusAction.SignInAgain,
                actionLabel = "Sign in again",
            )
        state.authState == AuthState.RateLimited -> AccountStatusUi(
            title = "Limited",
            message = state.message ?: "This provider is rate limited. Try again later.",
            bg = colors.warningSoft,
            fg = colors.warning,
        )
        state.authState == AuthState.Error -> AccountStatusUi(
            title = "Action needed",
            message = state.message ?: "Something went wrong while syncing this account.",
            bg = colors.dangerSoft,
            fg = colors.danger,
        )
        else -> return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(QdTheme.shapes.md)
            .background(status.bg)
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        QdAlertIcon(tint = status.fg)
        Text(
            text = "${status.title}: ${status.message}",
            style = typo.bodyMedium,
            color = status.fg,
            modifier = Modifier.weight(1f),
        )
        if (status.action != null && status.actionLabel != null) {
            QdButton(
                text = status.actionLabel,
                onClick = {
                    when (status.action) {
                        AccountStatusAction.ReopenLogin -> onReopenLogin()
                        AccountStatusAction.SignInAgain -> onSignInAgain()
                    }
                },
                variant = QdButtonVariant.Secondary,
                size = QdButtonSize.Small,
            )
        }
    }
}

private data class AccountStatusUi(
    val title: String,
    val message: String,
    val bg: Color,
    val fg: Color,
    val action: AccountStatusAction? = null,
    val actionLabel: String? = null,
)

private enum class AccountStatusAction {
    ReopenLogin,
    SignInAgain,
}

@Composable
private fun AddAccountButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    QdButton(
        text = "Add account",
        onClick = onClick,
        modifier = modifier,
        variant = QdButtonVariant.Primary,
        leading = { QdPlusIcon(tint = QdTheme.colors.onPrimary, size = 16.dp) },
    )
}

@Composable
private fun ProviderPickerContent(onSelect: (ProviderId) -> Unit) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text("Add account", style = typo.titleLarge, color = colors.textPrimary)
            Text(
                "Choose a provider - we'll open the browser for OAuth and finish sign-in here.",
                style = typo.bodyMedium,
                color = colors.textSecondary,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            ProviderId.entries.forEach { provider ->
                ProviderOptionRow(provider = provider, onClick = { onSelect(provider) })
            }
        }
    }
}

@Composable
private fun ProviderOptionRow(provider: ProviderId, onClick: () -> Unit) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = if (hovered) colors.surfaceHover else colors.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(QdTheme.shapes.md)
            .background(bg)
            .border(1.dp, colors.border, QdTheme.shapes.md)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        QdProviderAvatar(provider, size = 40.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(provider.displayName, style = typo.titleMedium, color = colors.textPrimary)
            Text(provider.subtitle(), style = typo.caption, color = colors.textTertiary)
        }
        QdChevronRightIcon(tint = colors.textTertiary)
    }
}

private fun AccountUiState.shouldShowAccount(): Boolean {
    return added ||
        (authState != AuthState.NotConfigured && authState != AuthState.Unknown) ||
        snapshot != null ||
        loginStart != null ||
        busy
}

private fun AccountUiState.canRefreshUsage(): Boolean {
    return added && (authState == AuthState.LoggedIn || authState == AuthState.TokenExpired)
}

private fun AccountUiState.accountTitle(emailPrivacyMode: EmailPrivacyMode = EmailPrivacyMode.Visible): String {
    val email = snapshot?.accountEmail?.takeIf { it.isNotBlank() }
    val displayEmail = if (emailPrivacyMode == EmailPrivacyMode.Masked) email?.maskEmailPlain() else email
    return if (displayEmail == null) providerId.displayName else "${providerId.displayName} ($displayEmail)"
}

private fun AccountUiState.accountSubtitle(): String {
    return snapshot?.accountEmail?.takeIf { it.isNotBlank() }
        ?: accountKey.accountId.takeUnless { accountKey.isPending || it == "default" }
        ?: "Account email pending"
}

private fun AccountUiState.accountSortLabel(): String {
    return accountSubtitle().lowercase()
}

private fun AccountKey.deleteLabel(state: DashboardState, emailPrivacyMode: EmailPrivacyMode): String {
    return state.accounts[this]?.accountTitle(emailPrivacyMode) ?: providerId.displayName
}

private fun String.canMaskEmail(): Boolean = contains("@") && length > 4

private fun String.maskFullStop(): Float {
    val last = lastIndex
    if (last <= 0) return 0.5f
    return (2f / last.toFloat()).coerceIn(0.08f, 0.45f)
}

private fun String.maskEmailPlain(): String {
    if (!canMaskEmail()) return this
    return "${take(2)}...${takeLast(2)}"
}

private fun ProviderId.subtitle(): String = when (this) {
    ProviderId.CODEX -> "OpenAI Codex / ChatGPT usage"
    ProviderId.CLAUDE_CODE -> "Anthropic Claude Code usage"
}

private fun UsageWindow.displayRatio(mode: UsageDisplayMode): Double = when (mode) {
    UsageDisplayMode.Used -> usedRatio
    UsageDisplayMode.Remaining -> remainingRatio
}

@Composable
private fun UsageWindow.progressFill(mode: UsageDisplayMode): Color {
    val colors = QdTheme.colors
    val riskRatio = when (mode) {
        UsageDisplayMode.Used -> usedRatio
        UsageDisplayMode.Remaining -> 1.0 - remainingRatio
    }
    return when {
        riskRatio >= 0.9 -> colors.danger
        riskRatio >= 0.7 -> colors.warning
        else -> colors.success
    }
}

private fun UsageWindow.infoLabel(): String = when (id) {
    "primary", "five_hour" -> "Session"
    "secondary", "seven_day" -> "Weekly"
    "seven_day_sonnet" -> "Weekly Sonnet"
    "seven_day_opus" -> "Weekly Opus"
    else -> label
}

private fun UsageWindow.remainingTime(): String {
    val resetAtMillis = resetsAt?.toEpochMilliseconds() ?: return "-"
    val remainingMillis = (resetAtMillis - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0)
    return remainingMillis.formatCompactDuration()
}

private fun Long.formatCompactDuration(): String {
    val totalMinutes = (this / 60_000).coerceAtLeast(0)
    if (totalMinutes < 60) return "${totalMinutes}m"
    val totalHours = totalMinutes / 60
    val minutes = totalMinutes % 60
    if (totalHours < 24) {
        return if (minutes == 0L) "${totalHours}h" else "${totalHours}h${minutes}m"
    }
    val days = totalHours / 24
    val hours = totalHours % 24
    return if (hours == 0L) "${days}d" else "${days}d${hours}h"
}
