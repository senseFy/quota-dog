package saien.quotadog

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import org.jetbrains.compose.resources.painterResource
import quotadog.composeapp.generated.resources.Res
import quotadog.composeapp.generated.resources.quotadog_app_icon
import kotlinx.datetime.Clock
import saien.quotadog.app.AccountHeaderLine
import saien.quotadog.app.App
import saien.quotadog.app.CompactActionSize
import saien.quotadog.app.CompactWindows
import saien.quotadog.app.FoldIndicator
import saien.quotadog.app.FoldToggleUi
import saien.quotadog.app.QUIET_REMAINING_RATIO
import saien.quotadog.app.QuotaDogBackgroundEffects
import saien.quotadog.app.foldToggle
import saien.quotadog.app.foldableRowCount
import saien.quotadog.app.foldedLabel
import saien.quotadog.app.remainingTime
import saien.quotadog.app.tightestWindow
import saien.quotadog.app.components.QdButton
import saien.quotadog.app.components.QdButtonSize
import saien.quotadog.app.components.QdButtonVariant
import saien.quotadog.app.components.QdCard
import saien.quotadog.app.components.QdCloseIcon
import saien.quotadog.app.components.QdIconButton
import saien.quotadog.app.components.QdPlanLabel
import saien.quotadog.app.components.QdProgressBar
import saien.quotadog.app.components.QdProviderAvatar
import saien.quotadog.app.components.QdRefreshIcon
import saien.quotadog.app.components.QdSegmentedControl
import saien.quotadog.app.components.QdTimestamp
import saien.quotadog.app.components.fadingHorizontalScroll
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
    val desktopMetadata = remember { DesktopUpdateMetadata.load() }
    val updater = remember { if (isMacOs()) DesktopUpdater(desktopMetadata) else null }
    DisposableEffect(updater) { onDispose { updater?.close() } }
    val quit: () -> Unit = { updater?.quit(::exitApplication) ?: exitApplication() }
    DesktopQuitHandler(quit)
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
        onQuit = quit,
        onStatusBarAvailabilityChanged = { statusBarAvailable = it },
    )

    if (windowVisible) {
        Window(
            onCloseRequest = {
                if (statusBarAvailable) {
                    windowVisible = false
                } else {
                    quit()
                }
            },
            title = "QuotaDog",
            icon = painterResource(Res.drawable.quotadog_app_icon),
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
                versionLabel = "v${desktopMetadata.version} (${desktopMetadata.sequence})",
                updatesContent = updater?.let { { DesktopUpdateSettings(it) } },
                revealActionsOnHover = true,
            )
        }
    }
    LaunchedEffect(updater) { updater?.start(::exitApplication) }
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
    val accountSortMode by preferences.accountSortMode.collectAsState()
    val accountSortReversed by preferences.accountSortReversed.collectAsState()
    val accountManualOrder by preferences.accountManualOrder.collectAsState()
    val emailPrivacyMode by preferences.emailPrivacyMode.collectAsState()
    val usageDisplayMode by preferences.usageDisplayMode.collectAsState()
    val sinkExhaustedAccounts by preferences.sinkExhaustedAccounts.collectAsState()
    val dashboardLayout by preferences.dashboardLayout.collectAsState()
    val themeMode by preferences.themeMode.collectAsState()
    val systemDark = rememberSystemDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    // Accounts opened by hand in a layout that folds them, kept while the app runs.
    val expandedTrayAccounts = remember { mutableStateMapOf<AccountKey, Boolean>() }
    var panelVisible by remember { mutableStateOf(false) }
    var selectedStatusBarProvider by remember { mutableStateOf<ProviderId?>(null) }
    val panelState = rememberWindowState(
        position = WindowPosition.Aligned(Alignment.TopEnd),
        width = TRAY_PANEL_WIDTH.dp,
        height = TRAY_PANEL_HEIGHT.dp,
    )
    val accounts = state.accounts.values
        .filter { it.shouldShowInTray() }
        .sortedAccounts(accountSortMode, accountManualOrder, accountSortReversed)
    val availableProviders = accounts.map { it.providerId }.distinct().sortedBy { it.ordinal }
    val activeStatusBarProvider =
        selectedStatusBarProvider?.takeIf { it in availableProviders }
    val filteredStatusBarAccounts = activeStatusBarProvider?.let { selected ->
        accounts.filter { it.providerId == selected }
    } ?: accounts
    val statusBarAccounts = if (sinkExhaustedAccounts) {
        filteredStatusBarAccounts.withExhaustedLast()
    } else {
        filteredStatusBarAccounts
    }
    val refreshableAccounts = accounts.filter { it.canRefreshFromTray() }
    val statusBarState = statusBarAccounts.toDesktopStatusBarState(
        allAccounts = accounts,
        availableProviders = availableProviders,
        selectedProvider = activeStatusBarProvider,
        refreshableAccounts = refreshableAccounts,
        emailPrivacyMode = emailPrivacyMode,
        usageDisplayMode = usageDisplayMode,
        sinkExhaustedAccounts = sinkExhaustedAccounts,
        darkTheme = darkTheme,
        layout = dashboardLayout,
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
        onRefreshAccount = { raw ->
            parseStatusBarAccountKey(raw)?.let { store.startRefresh(it) }
        },
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
                accounts = statusBarAccounts,
                allAccounts = accounts,
                availableProviders = availableProviders,
                selectedProvider = activeStatusBarProvider,
                refreshableAccounts = refreshableAccounts,
                emailPrivacyMode = emailPrivacyMode,
                usageDisplayMode = usageDisplayMode,
                sinkExhaustedAccounts = sinkExhaustedAccounts,
                dashboardLayout = dashboardLayout,
                isExpanded = { expandedTrayAccounts[it] == true },
                onToggleExpanded = { expandedTrayAccounts[it] = expandedTrayAccounts[it] != true },
                onSelectProvider = { selectedStatusBarProvider = it },
                onRefresh = { store.startRefreshAll() },
                onRefreshAccount = { store.startRefresh(it) },
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
    allAccounts: List<AccountUiState>,
    availableProviders: List<ProviderId>,
    selectedProvider: ProviderId?,
    refreshableAccounts: List<AccountUiState>,
    emailPrivacyMode: EmailPrivacyMode,
    usageDisplayMode: UsageDisplayMode,
    sinkExhaustedAccounts: Boolean,
    dashboardLayout: DashboardLayout,
    isExpanded: (AccountKey) -> Boolean,
    onToggleExpanded: (AccountKey) -> Unit,
    onSelectProvider: (ProviderId?) -> Unit,
    onRefresh: () -> Unit,
    onRefreshAccount: (AccountKey) -> Unit,
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
            allAccounts = allAccounts,
            availableProviders = availableProviders,
            selectedProvider = selectedProvider,
            refreshableAccounts = refreshableAccounts,
            emailPrivacyMode = emailPrivacyMode,
            usageDisplayMode = usageDisplayMode,
            sinkExhaustedAccounts = sinkExhaustedAccounts,
            dashboardLayout = dashboardLayout,
            isExpanded = isExpanded,
            onToggleExpanded = onToggleExpanded,
            onSelectProvider = onSelectProvider,
            onRefresh = onRefresh,
            onRefreshAccount = onRefreshAccount,
            onOpenWindow = onOpenWindow,
            onClose = onClose,
            onQuit = onQuit,
        )
    }
}

@Composable
internal fun QuotaDogTrayPanelContent(
    accounts: List<AccountUiState>,
    allAccounts: List<AccountUiState>,
    availableProviders: List<ProviderId>,
    selectedProvider: ProviderId?,
    refreshableAccounts: List<AccountUiState>,
    emailPrivacyMode: EmailPrivacyMode,
    usageDisplayMode: UsageDisplayMode,
    sinkExhaustedAccounts: Boolean,
    dashboardLayout: DashboardLayout,
    isExpanded: (AccountKey) -> Boolean,
    onToggleExpanded: (AccountKey) -> Unit,
    onSelectProvider: (ProviderId?) -> Unit,
    onRefresh: () -> Unit,
    onRefreshAccount: (AccountKey) -> Unit,
    onOpenWindow: () -> Unit,
    onClose: () -> Unit,
    onQuit: () -> Unit,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    // Cards keeps its short list; the denser layouts have room for every account.
    val visibleAccounts = if (dashboardLayout == DashboardLayout.Cards) {
        accounts.take(TRAY_ACCOUNT_LIMIT)
    } else {
        accounts
    }
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
                // Takes the room the buttons leave; more providers than fit scroll sideways, as in the main window.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fadingHorizontalScroll(rememberScrollState()),
                ) {
                    if (availableProviders.size > 1) {
                        QdSegmentedControl(
                            options = listOf("All (${allAccounts.size})" to null) +
                                availableProviders.map { provider ->
                                    val count = allAccounts.count { it.providerId == provider }
                                    "${provider.displayName} ($count)" to provider
                                },
                            selected = selectedProvider,
                            onSelect = onSelectProvider,
                            fillWidth = false,
                            autoScrollToSelected = true,
                        )
                    }
                }
                QdIconButton(
                    onClick = onRefresh,
                    enabled = refreshEnabled,
                    diameter = 28.dp,
                ) {
                    val rotation = rememberTrayRefreshRotation(active = refreshBusy)
                    QdRefreshIcon(
                        tint = colors.textSecondary,
                        size = 15.dp,
                        modifier = Modifier.rotate(rotation),
                    )
                }
                QdIconButton(onClick = onClose, diameter = 28.dp) {
                    QdCloseIcon(tint = colors.textSecondary, size = 15.dp)
                }
            }

            if (allAccounts.isEmpty()) {
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
                    @Composable
                    fun denseAccount(account: AccountUiState) {
                        TrayDenseAccount(
                            account = account,
                            layout = dashboardLayout,
                            expanded = isExpanded(account.accountKey),
                            onToggleExpanded = { onToggleExpanded(account.accountKey) },
                            emailPrivacyMode = emailPrivacyMode,
                            usageDisplayMode = usageDisplayMode,
                            sinkExhaustedAccounts = sinkExhaustedAccounts,
                            onRefresh = { onRefreshAccount(account.accountKey) },
                        )
                    }
                    when (dashboardLayout) {
                        DashboardLayout.Cards -> visibleAccounts.forEach { account ->
                            TrayAccountCard(
                                account = account,
                                emailPrivacyMode = emailPrivacyMode,
                                usageDisplayMode = usageDisplayMode,
                                sinkExhaustedAccounts = sinkExhaustedAccounts,
                                onRefresh = { onRefreshAccount(account.accountKey) },
                            )
                        }
                        DashboardLayout.Compact, DashboardLayout.FoldFull -> visibleAccounts.forEach { denseAccount(it) }
                        // One line is a single bordered list, as in the main window.
                        DashboardLayout.OneLine -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(QdTheme.shapes.md)
                                .background(colors.surface)
                                .border(1.dp, colors.border, QdTheme.shapes.md),
                        ) {
                            visibleAccounts.forEachIndexed { index, account ->
                                if (index > 0) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(colors.border),
                                    )
                                }
                                denseAccount(account)
                            }
                        }
                    }
                    if (accounts.size > visibleAccounts.size) {
                        val moreAccounts = accounts.size - visibleAccounts.size
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
    sinkExhaustedAccounts: Boolean,
    onRefresh: () -> Unit,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    val windows = account.snapshot?.windows.orEmpty()
    val resetSummary = account.snapshot?.codexResetSummary()
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    val refreshable = account.canRefreshFromTray()
    val showRefresh = refreshable && (hovered || account.busy)

    QdCard(
        modifier = Modifier
            .hoverable(hoverInteraction)
            .alpha(if (sinkExhaustedAccounts && account.isQuotaExhausted()) EXHAUSTED_ACCOUNT_ALPHA else 1f),
        padding = PaddingValues(spacing.sm),
        background = colors.surface,
        elevated = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                QdProviderAvatar(account.providerId, size = 18.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        Text(
                            text = account.trayAccountTitle(emailPrivacyMode),
                            style = typo.titleMedium,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        account.snapshot?.displayPlanLabel()?.let { plan ->
                            QdPlanLabel(plan)
                        }
                    }
                    val status = account.trayStatusLabel()
                    if (status.isNotEmpty()) {
                        Text(
                            text = status,
                            style = typo.caption,
                            color = if (account.busy) colors.textSecondary else colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (refreshable) {
                    Box(
                        modifier = Modifier.size(18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (showRefresh) {
                            QdIconButton(
                                onClick = onRefresh,
                                enabled = !account.busy,
                                diameter = 18.dp,
                                hoverColor = Color.Transparent,
                                pressedColor = Color.Transparent,
                            ) {
                                val rotation = rememberTrayRefreshRotation(active = account.busy)
                                QdRefreshIcon(
                                    tint = colors.textSecondary,
                                    size = 12.dp,
                                    modifier = Modifier.rotate(rotation),
                                )
                            }
                        }
                    }
                }
            }

            if (windows.isEmpty() && resetSummary == null) {
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
                    resetSummary?.let { TrayResetCreditsRow(it) }
                }
            }
        }
    }
}

/**
 * One account in Compact, Fold full, or One line, built from the main window's one-line header and
 * window rows. Refresh shows on hover, and clicking an account that can fold opens or closes it.
 */
@Composable
private fun TrayDenseAccount(
    account: AccountUiState,
    layout: DashboardLayout,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    emailPrivacyMode: EmailPrivacyMode,
    usageDisplayMode: UsageDisplayMode,
    sinkExhaustedAccounts: Boolean,
    onRefresh: () -> Unit,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    val windows = account.snapshot?.windows.orEmpty()
    val resetSummary = account.snapshot?.codexResetSummary()
    val hiddenCount = foldableRowCount(layout, windows, resetSummary != null)
    val folded = hiddenCount > 0 && !expanded
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val refreshable = account.canRefreshFromTray()

    val content: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            AccountHeaderLine(
                state = account,
                emailPrivacyMode = emailPrivacyMode,
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        FoldIndicator(
                            fold = if (hiddenCount > 0) FoldToggleUi(folded, hiddenCount) else null,
                            reserveSpace = layout == DashboardLayout.OneLine,
                        )
                        // Hidden rather than removed, so the header does not shift on hover.
                        QdIconButton(
                            onClick = onRefresh,
                            enabled = refreshable && !account.busy,
                            diameter = CompactActionSize,
                            modifier = Modifier.alpha(if (refreshable && (hovered || account.busy)) 1f else 0f),
                        ) {
                            val rotation = rememberTrayRefreshRotation(active = account.busy)
                            QdRefreshIcon(
                                tint = colors.textSecondary,
                                size = 14.dp,
                                modifier = Modifier.rotate(rotation),
                            )
                        }
                    }
                },
            )
            account.trayAlertLabel()?.let { alert ->
                Text(
                    text = alert,
                    style = typo.caption,
                    color = colors.warning,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CompactWindows(
                state = account,
                windows = windows,
                resetSummary = resetSummary,
                folded = folded,
                usageDisplayMode = usageDisplayMode,
                showProjectedUsage = false,
            )
        }
    }

    val modifier = Modifier
        .fillMaxWidth()
        .alpha(if (sinkExhaustedAccounts && account.isQuotaExhausted()) EXHAUSTED_ACCOUNT_ALPHA else 1f)
        .hoverable(hover)
        .foldToggle(hiddenCount > 0, folded, onToggleExpanded)
    if (layout == DashboardLayout.OneLine) {
        Box(modifier = modifier.padding(horizontal = spacing.md, vertical = spacing.xs)) {
            content()
        }
    } else {
        QdCard(
            modifier = modifier,
            padding = PaddingValues(horizontal = spacing.md, vertical = spacing.sm),
            background = colors.surface,
            elevated = false,
            content = content,
        )
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
                text = "$primaryLabel · ",
                style = typo.numeric.copy(fontWeight = FontWeight.SemiBold),
                color = fill,
                maxLines = 1,
            )
            QdTimestamp(window.resetsAt) {
                Text(
                    text = window.trayResetLabel(),
                    style = typo.numeric.copy(fontWeight = FontWeight.SemiBold),
                    color = fill,
                    maxLines = 1,
                )
            }
        }
        QdProgressBar(
            progress = primaryPct / 100f,
            height = 3.dp,
            fill = fill,
        )
    }
}

@Composable
private fun TrayResetCreditsRow(summary: CodexResetSummary) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val fill = if (summary.expiringSoon) colors.warning else colors.success
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Reset",
                style = typo.caption,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            QdTimestamp(summary.nearestExpiresAt) {
                Text(
                    text = summary.compactLabel(),
                    style = typo.numeric.copy(fontWeight = FontWeight.SemiBold),
                    color = fill,
                    maxLines = 1,
                )
            }
        }
        summary.credits.take(4).forEach { credit ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = credit.title ?: "Banked reset",
                    style = typo.caption,
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                QdTimestamp(credit.expiresAt) {
                    Text(
                        text = credit.expiryLabel(),
                        style = typo.numeric,
                        color = if (credit.isExpiringSoon()) colors.warning else colors.textTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private const val TRAY_ACCOUNT_LIMIT = 6
private const val EXHAUSTED_ACCOUNT_ALPHA = 0.5f
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

private fun AccountUiState.trayAccountTitle(emailPrivacyMode: EmailPrivacyMode): String {
    val displayEmail = trayEmail(emailPrivacyMode)
    return if (displayEmail == null) providerId.displayName else "${providerId.displayName} · $displayEmail"
}

private fun AccountUiState.trayEmail(emailPrivacyMode: EmailPrivacyMode): String? {
    val email = snapshot?.accountEmail?.takeIf { it.isNotBlank() }
        ?: accountKey.accountId.takeUnless { accountKey.isPending || it == "default" }
    return if (emailPrivacyMode == EmailPrivacyMode.Masked) email?.maskEmailForTray() else email
}

/**
 * A problem worth its own line in the denser layouts, which leave out the usual "updated" time.
 * An account without windows already shows its message in their place.
 */
private fun AccountUiState.trayAlertLabel(): String? {
    if (busy || snapshot?.windows.isNullOrEmpty()) return null
    val needsAttention = loginStart != null || authState in TRAY_ALERT_STATES
    return if (needsAttention) trayStatusLabel() else null
}

private val TRAY_ALERT_STATES = setOf(
    AuthState.TokenExpired,
    AuthState.Unauthorized,
    AuthState.RequiresRelogin,
    AuthState.RateLimited,
    AuthState.Error,
)

private fun List<AccountUiState>.toDesktopStatusBarState(
    allAccounts: List<AccountUiState>,
    availableProviders: List<ProviderId>,
    selectedProvider: ProviderId?,
    refreshableAccounts: List<AccountUiState>,
    emailPrivacyMode: EmailPrivacyMode,
    usageDisplayMode: UsageDisplayMode,
    sinkExhaustedAccounts: Boolean,
    darkTheme: Boolean,
    layout: DashboardLayout,
): DesktopStatusBarState {
    // Cards keeps its short list; the denser layouts have room for every account.
    val visibleAccounts = if (layout == DashboardLayout.Cards) take(STATUS_BAR_ACCOUNT_LIMIT) else this
    val refreshBusy = refreshableAccounts.any { it.busy }
    return DesktopStatusBarState(
        tooltip = allAccounts.traySummaryLabel(usageDisplayMode),
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
            val windows = account.snapshot?.windows.orEmpty()
            val resetSummary = account.snapshot?.codexResetSummary()
            val tightest = windows.tightestWindow()
            DesktopStatusBarAccount(
                id = account.accountKey.toStatusBarId(),
                title = account.trayAccountTitle(emailPrivacyMode),
                name = account.providerId.displayName,
                subtitle = account.trayEmail(emailPrivacyMode).orEmpty(),
                planLabel = account.snapshot?.displayPlanLabel().orEmpty(),
                status = account.trayStatusLabel(),
                alert = account.trayAlertLabel().orEmpty(),
                emptyLabel = account.trayEmptyLabel(),
                provider = account.providerId.name,
                busy = account.busy || account.loginStart != null,
                refreshable = account.canRefreshFromTray(),
                exhausted = sinkExhaustedAccounts && account.isQuotaExhausted(),
                windows = windows.map { window ->
                    DesktopStatusBarUsageWindow(
                        label = window.trayWindowLabel(),
                        usedPct = (window.usedRatio * 100).roundToInt().coerceIn(0, 100),
                        remainingPct = (window.remainingRatio * 100).roundToInt().coerceIn(0, 100),
                        resetLabel = window.trayResetLabel(),
                        resetTooltip = window.resetsAt?.exactLocalTimeLabel(),
                        compactResetLabel = if (window.resetsAt == null) "" else window.remainingTime(),
                        quiet = window.remainingRatio >= QUIET_REMAINING_RATIO,
                    )
                },
                foldedHiddenCount = foldableRowCount(layout, windows, resetSummary != null),
                tightestIndex = if (tightest == null) -1 else windows.indexOf(tightest),
                foldedLabel = tightest?.let { windows.foldedLabel(it) }.orEmpty(),
                resetAvailable = resetSummary?.availableCount ?: 0,
                resetLabel = resetSummary?.compactLabel(),
                resetTooltip = resetSummary?.nearestExpiresAt?.exactLocalTimeLabel(),
                resetUrgent = resetSummary?.expiringSoon == true,
                resetCredits = resetSummary?.credits.orEmpty().take(4).map { credit ->
                    DesktopStatusBarResetCredit(
                        title = credit.title ?: "Banked reset",
                        label = credit.expiryLabel(),
                        tooltip = credit.expiresAt?.exactLocalTimeLabel(),
                        urgent = credit.isExpiringSoon(),
                    )
                },
            )
        },
        moreAccounts = (size - visibleAccounts.size).coerceAtLeast(0),
        refreshEnabled = refreshableAccounts.isNotEmpty() && !refreshBusy,
        refreshBusy = refreshBusy,
        darkTheme = darkTheme,
        usageDisplayMode = usageDisplayMode.name,
        layout = layout.name,
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
    snapshot?.collectedAt?.let { return it.toEpochMilliseconds().agoLabel() }
    return message ?: "No usage data yet"
}

private fun AccountUiState.trayEmptyLabel(): String {
    return message?.takeIf { it.isNotBlank() } ?: "No usage data yet"
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
    "plan-usage" -> "Included"
    "cursor-auto" -> "Auto"
    "cursor-api" -> "API"
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
