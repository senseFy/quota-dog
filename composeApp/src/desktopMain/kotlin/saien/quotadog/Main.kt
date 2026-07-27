package saien.quotadog

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.datetime.Clock
import saien.quotadog.app.App
import saien.quotadog.app.QuotaDogBackgroundEffects
import saien.quotadog.app.components.QdButton
import saien.quotadog.app.components.QdButtonSize
import saien.quotadog.app.components.QdButtonVariant
import saien.quotadog.app.components.QdCard
import saien.quotadog.app.components.QdCloseIcon
import saien.quotadog.app.components.QdGlassIconButton
import saien.quotadog.app.components.QdProgressBar
import saien.quotadog.app.components.QdProviderAvatar
import saien.quotadog.app.components.QdRefreshIcon
import saien.quotadog.app.theme.QdTheme
import saien.quotadog.app.theme.QuotaDogTheme
import saien.quotadog.app.theme.SystemThemeRefresh
import saien.quotadog.app.theme.rememberSystemDarkTheme
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlin.math.roundToInt
import java.awt.Window as AwtWindow
import kotlin.time.Duration.Companion.minutes

fun main() = application {
    val tokenStore = remember { PlatformTokenStore() }
    val usageSnapshotStore = remember { SettingsUsageSnapshotStore() }
    val preferences = remember { AppPreferences() }
    val cloudSync = remember {
        CloudSyncCoordinator(
            CloudSyncLocalRepository(
                tokenStore = tokenStore,
                usageSnapshotStore = usageSnapshotStore,
                preferences = preferences,
            )
        )
    }
    val store = remember {
        QuotaDogStore(
            client = QuotaDogClient(tokenStore = tokenStore),
            usageSnapshotStore = usageSnapshotStore,
            onLocalDataChanged = { cloudSync.startPushLocalChanges() },
            onLocalAccountDeleted = { cloudSync.recordAccountDeleted(it) },
        )
    }
    val windowState = rememberWindowState(width = 1100.dp, height = 760.dp)
    var windowVisible by remember { mutableStateOf(true) }
    var mainWindowFocusToken by remember { mutableIntStateOf(0) }
    var statusBarAvailable by remember { mutableStateOf(false) }

    // Keep detect / auto-refresh / sync session alive while the tray process is running,
    // even if the main Compose window is hidden.
    QuotaDogBackgroundEffects(store = store, preferences = preferences, cloudSync = cloudSync)

    QuotaDogTray(
        store = store,
        preferences = preferences,
        onOpenWindow = {
            windowVisible = true
            mainWindowFocusToken += 1
        },
        onQuit = ::exitApplication,
        onStatusBarAvailabilityChanged = { statusBarAvailable = it },
    )

    if (windowVisible) {
        Window(
            onCloseRequest = {
                if (statusBarAvailable) {
                    windowVisible = false
                } else {
                    exitApplication()
                }
            },
            title = "QuotaDog",
            state = windowState,
        ) {
            if (isMacOs()) {
                DisposableEffect(window) {
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)

                    onDispose {
                        window.rootPane.putClientProperty("apple.awt.windowTitleVisible", null)
                        window.rootPane.putClientProperty("apple.awt.transparentTitleBar", null)
                        window.rootPane.putClientProperty("apple.awt.fullWindowContent", null)
                    }
                }
            }
            BringMainWindowToFront(
                window = window,
                focusToken = mainWindowFocusToken,
                onRestore = { windowState.isMinimized = false },
            )
            RefreshSystemThemeOnWindowResume(window)
            App(
                tokenStore = tokenStore,
                usageSnapshotStore = usageSnapshotStore,
                preferences = preferences,
                cloudSync = cloudSync,
                store = store,
                manageBackgroundEffects = false,
            )
        }
    }
}

@Composable
private fun BringMainWindowToFront(
    window: AwtWindow,
    focusToken: Int,
    onRestore: () -> Unit,
) {
    LaunchedEffect(focusToken) {
        if (focusToken <= 0) return@LaunchedEffect
        onRestore()
        if (window is Frame) {
            window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
        }
        window.isVisible = true
        window.toFront()
        window.requestFocus()
        // Tray "Open" / re-show is a resume path for System theme.
        SystemThemeRefresh.request()
    }
}

/** Re-query OS appearance when the main window is shown or becomes active again. */
@Composable
private fun RefreshSystemThemeOnWindowResume(window: AwtWindow) {
    DisposableEffect(window) {
        val listener = object : WindowAdapter() {
            override fun windowActivated(event: WindowEvent?) {
                SystemThemeRefresh.request()
            }
        }
        window.addWindowListener(listener)
        SystemThemeRefresh.request()
        onDispose {
            window.removeWindowListener(listener)
        }
    }
}

@Composable
private fun QuotaDogTray(
    store: QuotaDogStore,
    preferences: AppPreferences,
    onOpenWindow: () -> Unit,
    onQuit: () -> Unit,
    onStatusBarAvailabilityChanged: (Boolean) -> Unit,
) {
    val state by store.state.collectAsState()
    val emailPrivacyMode by preferences.emailPrivacyMode.collectAsState()
    val usageDisplayMode by preferences.usageDisplayMode.collectAsState()
    val themeMode by preferences.themeMode.collectAsState()
    val systemDark = rememberSystemDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    var panelVisible by remember { mutableStateOf(false) }
    var selectedStatusBarProvider by remember { mutableStateOf<ProviderId?>(null) }
    val panelState = rememberWindowState(
        position = WindowPosition.Aligned(Alignment.TopEnd),
        width = TRAY_PANEL_WIDTH.dp,
        height = TRAY_PANEL_HEIGHT.dp,
    )
    val accounts = state.accounts.values
        .filter { it.shouldShowInTray() }
        .sortedWith(compareBy<AccountUiState> { it.providerId.ordinal }.thenBy { it.traySortLabel() })
    val availableProviders = accounts.map { it.providerId }.distinct()
    val activeStatusBarProvider =
        selectedStatusBarProvider?.takeIf { it in availableProviders }
    val statusBarAccounts = activeStatusBarProvider?.let { selected ->
        accounts.filter { it.providerId == selected }
    } ?: accounts
    val refreshableAccounts = accounts.filter { it.canRefreshFromTray() }
    val statusBarState = statusBarAccounts.toDesktopStatusBarState(
        allAccounts = accounts,
        availableProviders = availableProviders,
        selectedProvider = activeStatusBarProvider,
        refreshableAccounts = refreshableAccounts,
        emailPrivacyMode = emailPrivacyMode,
        usageDisplayMode = usageDisplayMode,
        darkTheme = darkTheme,
    )

    LaunchedEffect(availableProviders) {
        if (selectedStatusBarProvider != null &&
            selectedStatusBarProvider !in availableProviders
        ) {
            selectedStatusBarProvider = null
        }
    }

    val maybeRefreshOnOpen = {
        // Menu-bar / tray open is the other resume path for System theme.
        SystemThemeRefresh.request()
        if (shouldAutoRefreshOnTrayOpen(refreshableAccounts)) {
            store.startRefreshAll()
        }
    }

    DesktopStatusBarIcon(
        state = statusBarState,
        onRefresh = { store.startRefreshAll() },
        onShow = maybeRefreshOnOpen,
        onOpenWindow = onOpenWindow,
        onQuit = onQuit,
        onSelectProvider = { providerName ->
            selectedStatusBarProvider = ProviderId.entries.firstOrNull {
                it.name == providerName && it in availableProviders
            }
        },
        onFallbackClick = { x, y ->
            panelState.position = statusBarPanelPosition(x, y)
            val opening = !panelVisible
            panelVisible = !panelVisible
            if (opening) maybeRefreshOnOpen()
        },
        onAvailabilityChanged = onStatusBarAvailabilityChanged,
    )

    if (panelVisible) {
        Window(
            onCloseRequest = { panelVisible = false },
            title = "QuotaDog Usage",
            state = panelState,
            undecorated = true,
            transparent = true,
            resizable = false,
            alwaysOnTop = true,
            onPreviewKeyEvent = {
                if (it.key == Key.Escape) {
                    panelVisible = false
                    true
                } else {
                    false
                }
            },
        ) {
            ClosePanelOnFocusLost(window) {
                panelVisible = false
            }
            QuotaDogTrayPanel(
                preferences = preferences,
                accounts = accounts,
                refreshableAccounts = refreshableAccounts,
                emailPrivacyMode = emailPrivacyMode,
                usageDisplayMode = usageDisplayMode,
                onRefresh = { store.startRefreshAll() },
                onOpenWindow = {
                    panelVisible = false
                    onOpenWindow()
                },
                onClose = { panelVisible = false },
                onQuit = onQuit,
            )
        }
    }
}

@Composable
private fun ClosePanelOnFocusLost(window: AwtWindow, onClose: () -> Unit) {
    val currentOnClose = rememberUpdatedState(onClose)
    DisposableEffect(window) {
        val listener = object : WindowAdapter() {
            override fun windowLostFocus(event: WindowEvent) {
                currentOnClose.value()
            }
        }
        window.addWindowFocusListener(listener)
        window.requestFocus()
        onDispose {
            window.removeWindowFocusListener(listener)
        }
    }
}

@Composable
private fun QuotaDogTrayPanel(
    preferences: AppPreferences,
    accounts: List<AccountUiState>,
    refreshableAccounts: List<AccountUiState>,
    emailPrivacyMode: EmailPrivacyMode,
    usageDisplayMode: UsageDisplayMode,
    onRefresh: () -> Unit,
    onOpenWindow: () -> Unit,
    onClose: () -> Unit,
    onQuit: () -> Unit,
) {
    val themeMode by preferences.themeMode.collectAsState()
    val systemDark = rememberSystemDarkTheme()
    val effectiveDark = when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    QuotaDogTheme(darkTheme = effectiveDark) {
        QuotaDogTrayPanelContent(
            accounts = accounts,
            refreshableAccounts = refreshableAccounts,
            emailPrivacyMode = emailPrivacyMode,
            usageDisplayMode = usageDisplayMode,
            onRefresh = onRefresh,
            onOpenWindow = onOpenWindow,
            onClose = onClose,
            onQuit = onQuit,
        )
    }
}

@Composable
private fun QuotaDogTrayPanelContent(
    accounts: List<AccountUiState>,
    refreshableAccounts: List<AccountUiState>,
    emailPrivacyMode: EmailPrivacyMode,
    usageDisplayMode: UsageDisplayMode,
    onRefresh: () -> Unit,
    onOpenWindow: () -> Unit,
    onClose: () -> Unit,
    onQuit: () -> Unit,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    val visibleAccounts = accounts.take(TRAY_ACCOUNT_LIMIT)
    val refreshBusy = refreshableAccounts.any { it.busy }
    val refreshEnabled = refreshableAccounts.isNotEmpty() && !refreshBusy

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .shadow(
                    elevation = 12.dp,
                    shape = QdTheme.shapes.lg,
                    clip = false,
                    ambientColor = Color(0x22000000),
                    spotColor = Color(0x22000000),
                )
                .clip(QdTheme.shapes.lg)
                .background(colors.backgroundElevated)
                .border(1.dp, colors.border, QdTheme.shapes.lg)
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = "QuotaDog",
                        style = typo.titleLarge,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = accounts.trayPanelSummaryLabel(usageDisplayMode),
                        style = typo.caption,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                QdGlassIconButton(
                    onClick = onRefresh,
                    enabled = refreshEnabled,
                    diameter = 30.dp,
                ) {
                    val rotation = rememberTrayRefreshRotation(active = refreshBusy)
                    QdRefreshIcon(
                        tint = colors.textSecondary,
                        size = 15.dp,
                        modifier = Modifier.rotate(rotation),
                    )
                }
                QdGlassIconButton(onClick = onClose, diameter = 30.dp) {
                    QdCloseIcon(tint = colors.textSecondary, size = 15.dp)
                }
            }

            if (accounts.isEmpty()) {
                TrayEmptyState(
                    modifier = Modifier.weight(1f),
                    onOpenWindow = onOpenWindow,
                )
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    visibleAccounts.forEach { account ->
                        TrayAccountCard(
                            account = account,
                            emailPrivacyMode = emailPrivacyMode,
                            usageDisplayMode = usageDisplayMode,
                        )
                    }
                    if (accounts.size > TRAY_ACCOUNT_LIMIT) {
                        val moreAccounts = accounts.size - TRAY_ACCOUNT_LIMIT
                        Text(
                            text = "+$moreAccounts more account${if (moreAccounts == 1) "" else "s"} in the app",
                            style = typo.caption,
                            color = colors.textTertiary,
                            modifier = Modifier.padding(horizontal = spacing.sm),
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                QdButton(
                    text = "Open app",
                    onClick = onOpenWindow,
                    modifier = Modifier.fillMaxWidth(),
                    size = QdButtonSize.Small,
                    variant = QdButtonVariant.Ghost,
                )
                QdButton(
                    text = "Quit QuotaDog",
                    onClick = onQuit,
                    modifier = Modifier.fillMaxWidth(),
                    variant = QdButtonVariant.Ghost,
                    size = QdButtonSize.Small,
                )
            }
        }
    }
}

@Composable
private fun TrayEmptyState(modifier: Modifier = Modifier, onOpenWindow: () -> Unit) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text("No usage data yet", style = typo.titleMedium, color = colors.textPrimary)
            Text(
                text = "Add an account in QuotaDog to show live usage here.",
                style = typo.bodyMedium,
                color = colors.textSecondary,
            )
            QdButton(
                text = "Open QuotaDog",
                onClick = onOpenWindow,
                size = QdButtonSize.Small,
            )
        }
    }
}

@Composable
private fun TrayAccountCard(
    account: AccountUiState,
    emailPrivacyMode: EmailPrivacyMode,
    usageDisplayMode: UsageDisplayMode,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    val windows = account.snapshot?.windows.orEmpty()

    QdCard(
        padding = PaddingValues(spacing.sm),
        background = colors.surface,
        elevated = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                QdProviderAvatar(account.providerId, size = 18.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.trayAccountTitle(emailPrivacyMode),
                        style = typo.titleMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = account.trayStatusLabel(),
                        style = typo.caption,
                        color = if (account.busy) colors.textSecondary else colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (windows.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(QdTheme.shapes.sm)
                        .background(colors.surfaceMuted.copy(alpha = 0.55f))
                        .padding(horizontal = spacing.sm, vertical = spacing.xs),
                ) {
                    Text(
                        text = account.message ?: "No usage data yet",
                        style = typo.caption,
                        color = colors.textSecondary,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    windows.forEach { window ->
                        TrayUsageWindowRow(window, usageDisplayMode)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrayUsageWindowRow(window: UsageWindow, displayMode: UsageDisplayMode) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val usedPct = (window.usedRatio * 100).roundToInt().coerceIn(0, 100)
    val remainingPct = (window.remainingRatio * 100).roundToInt().coerceIn(0, 100)
    val primaryPct = when (displayMode) {
        UsageDisplayMode.Used -> usedPct
        UsageDisplayMode.Remaining -> remainingPct
    }
    val primaryLabel = when (displayMode) {
        UsageDisplayMode.Used -> "$primaryPct% used"
        UsageDisplayMode.Remaining -> "$primaryPct% left"
    }
    val fill = window.trayUsageFill()

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = window.trayWindowLabel(),
                style = typo.caption,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$primaryLabel · ${window.trayResetLabel()}",
                style = typo.numeric.copy(fontWeight = FontWeight.SemiBold),
                color = fill,
                maxLines = 1,
            )
        }
        QdProgressBar(
            progress = primaryPct / 100f,
            height = 3.dp,
            fill = fill,
        )
    }
}

private const val TRAY_ACCOUNT_LIMIT = 6
private val TRAY_OPEN_AUTO_REFRESH_THRESHOLD = 10.minutes

@Composable
private fun rememberTrayRefreshRotation(active: Boolean): Float {
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

private fun shouldAutoRefreshOnTrayOpen(refreshableAccounts: List<AccountUiState>): Boolean {
    if (refreshableAccounts.isEmpty()) return false
    if (refreshableAccounts.any { it.busy }) return false
    val now = Clock.System.now().toEpochMilliseconds()
    val thresholdMs = TRAY_OPEN_AUTO_REFRESH_THRESHOLD.inWholeMilliseconds
    return refreshableAccounts.any { account ->
        val collectedAt = account.snapshot?.collectedAt?.toEpochMilliseconds()
        collectedAt == null || now - collectedAt >= thresholdMs
    }
}

private fun AccountUiState.shouldShowInTray(): Boolean {
    return added ||
        snapshot != null ||
        loginStart != null ||
        busy ||
        (authState != AuthState.NotConfigured && authState != AuthState.Unknown)
}

private fun AccountUiState.canRefreshFromTray(): Boolean {
    return added && (authState == AuthState.LoggedIn || authState == AuthState.TokenExpired)
}

private fun AccountUiState.traySortLabel(): String {
    return snapshot?.accountEmail?.takeIf { it.isNotBlank() }
        ?: accountKey.accountId.takeUnless { accountKey.isPending || it == "default" }
        ?: providerId.displayName
}

private fun AccountUiState.trayAccountTitle(emailPrivacyMode: EmailPrivacyMode): String {
    val email = snapshot?.accountEmail?.takeIf { it.isNotBlank() }
        ?: accountKey.accountId.takeUnless { accountKey.isPending || it == "default" }
    val displayEmail = if (emailPrivacyMode == EmailPrivacyMode.Masked) {
        email?.maskEmailForTray()
    } else {
        email
    }
    return if (displayEmail == null) providerId.displayName else "${providerId.displayName} · $displayEmail"
}

private fun List<AccountUiState>.toDesktopStatusBarState(
    allAccounts: List<AccountUiState>,
    availableProviders: List<ProviderId>,
    selectedProvider: ProviderId?,
    refreshableAccounts: List<AccountUiState>,
    emailPrivacyMode: EmailPrivacyMode,
    usageDisplayMode: UsageDisplayMode,
    darkTheme: Boolean,
): DesktopStatusBarState {
    val visibleAccounts = take(STATUS_BAR_ACCOUNT_LIMIT)
    val refreshBusy = refreshableAccounts.any { it.busy }
    return DesktopStatusBarState(
        tooltip = allAccounts.traySummaryLabel(usageDisplayMode),
        summary = trayPanelSummaryLabel(usageDisplayMode),
        providerFilters =
            listOf(DesktopStatusBarProviderFilter(id = "", label = "All (${allAccounts.size})")) +
                availableProviders.map { provider ->
                    val count = allAccounts.count { it.providerId == provider }
                    DesktopStatusBarProviderFilter(
                        id = provider.name,
                        label = "${provider.displayName} ($count)",
                    )
                },
        selectedProvider = selectedProvider?.name.orEmpty(),
        accounts = visibleAccounts.map { account ->
            DesktopStatusBarAccount(
                title = account.trayAccountTitle(emailPrivacyMode),
                status = account.trayStatusLabel(),
                provider = account.providerId.name,
                busy = account.busy || account.loginStart != null,
                windows = account.snapshot?.windows.orEmpty().map { window ->
                    DesktopStatusBarUsageWindow(
                        label = window.trayWindowLabel(),
                        usedPct = (window.usedRatio * 100).roundToInt().coerceIn(0, 100),
                        remainingPct = (window.remainingRatio * 100).roundToInt().coerceIn(0, 100),
                        resetLabel = window.trayResetLabel(),
                    )
                },
            )
        },
        moreAccounts = (size - visibleAccounts.size).coerceAtLeast(0),
        refreshEnabled = refreshableAccounts.isNotEmpty() && !refreshBusy,
        refreshBusy = refreshBusy,
        darkTheme = darkTheme,
        usageDisplayMode = usageDisplayMode.name,
    )
}

private fun List<AccountUiState>.traySummaryLabel(displayMode: UsageDisplayMode): String {
    if (isEmpty()) return "QuotaDog"
    val windows = flatMap { it.snapshot?.windows.orEmpty() }
    val extreme = windows.extremeDisplayRatio(displayMode)
    return if (extreme == null) {
        "QuotaDog · ${size} account${if (size == 1) "" else "s"}"
    } else {
        val pct = (extreme * 100).roundToInt()
        when (displayMode) {
            UsageDisplayMode.Used -> "QuotaDog · $pct% max used"
            UsageDisplayMode.Remaining -> "QuotaDog · $pct% min left"
        }
    }
}

private fun List<AccountUiState>.trayPanelSummaryLabel(displayMode: UsageDisplayMode): String {
    if (isEmpty()) return "No accounts yet"
    val accountLabel = "${size} account${if (size == 1) "" else "s"}"
    val windows = flatMap { it.snapshot?.windows.orEmpty() }
    val extreme = windows.extremeDisplayRatio(displayMode) ?: return accountLabel
    val pct = (extreme * 100).roundToInt()
    return when (displayMode) {
        UsageDisplayMode.Used -> "$accountLabel · $pct% max used"
        UsageDisplayMode.Remaining -> "$accountLabel · $pct% min left"
    }
}

private fun List<UsageWindow>.extremeDisplayRatio(displayMode: UsageDisplayMode): Double? {
    return when (displayMode) {
        UsageDisplayMode.Used -> maxOfOrNull { it.usedRatio }
        UsageDisplayMode.Remaining -> minOfOrNull { it.remainingRatio }
    }
}

private fun AccountUiState.trayStatusLabel(): String {
    if (busy) return message ?: "Refreshing usage..."
    if (loginStart != null) return message ?: "Waiting for sign-in"
    if (authState == AuthState.TokenExpired ||
        authState == AuthState.Unauthorized ||
        authState == AuthState.RequiresRelogin
    ) {
        return message ?: "Sign-in needed"
    }
    if (authState == AuthState.RateLimited) return message ?: "Rate limited"
    if (authState == AuthState.Error) return message ?: "Action needed"
    snapshot?.collectedAt?.let { return "Updated ${it.toEpochMilliseconds().agoLabel()}" }
    return message ?: "No usage data yet"
}

@Composable
private fun UsageWindow.trayUsageFill(): Color {
    val colors = QdTheme.colors
    return when {
        usedRatio >= 0.9 -> colors.danger
        usedRatio >= 0.7 -> colors.warning
        else -> colors.meter
    }
}

private fun UsageWindow.trayWindowLabel(): String = when (id) {
    "primary", "five_hour" -> "Session"
    "secondary", "seven_day" -> "Weekly"
    "seven_day_sonnet" -> "Weekly Sonnet"
    "seven_day_opus" -> "Weekly Opus"
    "credits" -> "Credits"
    "plan-usage" -> "Plan"
    "on-demand" -> "On-demand"
    else -> label
}

private fun UsageWindow.trayResetLabel(): String {
    val resetAtMillis = resetsAt?.toEpochMilliseconds() ?: return "—"
    val remainingMillis = (resetAtMillis - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0)
    return remainingMillis.formatTrayDuration()
}

private fun Long.agoLabel(): String {
    val elapsedMillis = (Clock.System.now().toEpochMilliseconds() - this).coerceAtLeast(0)
    return when {
        elapsedMillis < 60_000L -> "just now"
        else -> "${elapsedMillis.formatTrayDuration()} ago"
    }
}

private fun Long.formatTrayDuration(): String {
    val totalMinutes = (this / 60_000L).coerceAtLeast(0)
    if (totalMinutes < 60) return "${totalMinutes}m"
    val totalHours = totalMinutes / 60
    val minutes = totalMinutes % 60
    if (totalHours < 24) {
        return if (minutes == 0L) "${totalHours}h" else "${totalHours}h ${minutes}m"
    }
    val days = totalHours / 24
    val hours = totalHours % 24
    return if (hours == 0L) "${days}d" else "${days}d ${hours}h"
}

private fun String.maskEmailForTray(): String {
    if (!contains("@") || length <= 4) return this
    return "${take(2)}...${takeLast(2)}"
}
