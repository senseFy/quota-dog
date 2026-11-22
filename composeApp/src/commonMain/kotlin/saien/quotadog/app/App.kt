package saien.quotadog.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import saien.quotadog.AccountKey
import saien.quotadog.AccountSortMode
import saien.quotadog.AccountUiState
import saien.quotadog.AppPreferences
import saien.quotadog.AuthState
import saien.quotadog.CloudSyncCoordinator
import saien.quotadog.CloudSyncLocalRepository
import saien.quotadog.CodexResetSummary
import saien.quotadog.DashboardLayout
import saien.quotadog.DashboardState
import saien.quotadog.EmailPrivacyMode
import saien.quotadog.PlatformTokenStore
import saien.quotadog.ProviderId
import saien.quotadog.QuotaDogClient
import saien.quotadog.QuotaDogStore
import saien.quotadog.SettingsUsageSnapshotStore
import saien.quotadog.ThemeMode
import saien.quotadog.TokenStore
import saien.quotadog.UsageDisplayMode
import saien.quotadog.UsageWindow
import saien.quotadog.app.components.QdAlertIcon
import saien.quotadog.app.components.QdBottomSheet
import saien.quotadog.app.components.QdButton
import saien.quotadog.app.components.QdButtonSize
import saien.quotadog.app.components.QdButtonVariant
import saien.quotadog.app.components.QdCard
import saien.quotadog.app.components.QdCheckIcon
import saien.quotadog.app.components.QdChevronDownIcon
import saien.quotadog.app.components.QdChevronRightIcon
import saien.quotadog.app.components.QdChevronUpIcon
import saien.quotadog.app.components.QdConfirmDialog
import saien.quotadog.app.components.QdEmptyState
import saien.quotadog.app.components.QdGlassIconButton
import saien.quotadog.app.components.QdIconButton
import saien.quotadog.app.components.QdMinusIcon
import saien.quotadog.app.components.QdMoreIcon
import saien.quotadog.app.components.QdPlanLabel
import saien.quotadog.app.components.QdPlusIcon
import saien.quotadog.app.components.QdProgressBar
import saien.quotadog.app.components.QdProviderAvatar
import saien.quotadog.app.components.QdRefreshIcon
import saien.quotadog.app.components.QdSegmentedControl
import saien.quotadog.app.components.QdSettingsGearIcon
import saien.quotadog.app.components.QdSettingsSheet
import saien.quotadog.app.components.QdSnackbarHost
import saien.quotadog.app.components.QdSnackbarTone
import saien.quotadog.app.components.QdSortAscendingIcon
import saien.quotadog.app.components.QdSortDescendingIcon
import saien.quotadog.app.components.QdTimestamp
import saien.quotadog.app.components.fadingHorizontalScroll
import saien.quotadog.app.components.label
import saien.quotadog.app.components.rememberQdSnackbarController
import saien.quotadog.app.theme.QdTheme
import saien.quotadog.app.theme.QuotaDogTheme
import saien.quotadog.app.theme.rememberSystemDarkTheme
import saien.quotadog.ampAuthFileHint
import saien.quotadog.ampCliImportAvailable
import saien.quotadog.antigravityAuthHint
import saien.quotadog.antigravityCliImportAvailable
import saien.quotadog.availableProviders
import saien.quotadog.canonicalManualOrder
import saien.quotadog.cliImportAvailable
import saien.quotadog.codexResetSummary
import saien.quotadog.displayPlanLabel
import saien.quotadog.droidAuthFileHint
import saien.quotadog.droidCliImportAvailable
import saien.quotadog.expiryLabel
import saien.quotadog.grokAuthFileHint
import saien.quotadog.grokCliImportAvailable
import saien.quotadog.isExpiringSoon
import saien.quotadog.isQuotaExhausted
import saien.quotadog.oauthAvailable
import saien.quotadog.projectedUsedRatio
import saien.quotadog.reorderDisplayedManualOrder
import saien.quotadog.sortedAccounts
import saien.quotadog.spentWindowResetAt
import saien.quotadog.withExhaustedLast
import kotlin.time.Duration.Companion.minutes

@Composable
fun App(
    tokenStore: TokenStore = remember { PlatformTokenStore() },
    usageSnapshotStore: SettingsUsageSnapshotStore = remember { SettingsUsageSnapshotStore() },
    preferences: AppPreferences = remember { AppPreferences() },
    cloudSync: CloudSyncCoordinator = remember {
        CloudSyncCoordinator(
            CloudSyncLocalRepository(
                tokenStore = tokenStore,
                usageSnapshotStore = usageSnapshotStore,
                preferences = preferences,
            )
        )
    },
    store: QuotaDogStore = remember {
        QuotaDogStore(
            client = QuotaDogClient(tokenStore = tokenStore),
            usageSnapshotStore = usageSnapshotStore,
            onLocalDataChanged = { cloudSync.startPushLocalChanges() },
            onLocalAccountDeleted = { cloudSync.recordAccountDeleted(it) },
        )
    },
    // Desktop tray keeps the process alive after the main window is hidden; host those
    // effects outside App so refresh/detect/sync session survive window teardown.
    manageBackgroundEffects: Boolean = true,
    versionLabel: String = "v1.0.0",
    platformSettings: (@Composable () -> Unit)? = null,
    // Pointer platforms show each account's refresh and menu only while it is hovered in the
    // denser layouts. Touch platforms keep them visible.
    revealActionsOnHover: Boolean = false,
    hostSyncContent: (@Composable () -> Unit)? = null,
    showLocalEmptyState: Boolean = true,
    onConnectComputer: (() -> Unit)? = null,
) {
    val themeMode by preferences.themeMode.collectAsState()
    val systemDark = rememberSystemDarkTheme()
    val effectiveDark = when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    QuotaDogTheme(darkTheme = effectiveDark) {
        ApplyPlatformSystemBars(darkAppearance = effectiveDark)
        if (manageBackgroundEffects) {
            QuotaDogBackgroundEffects(store = store, preferences = preferences, cloudSync = cloudSync)
        }
        QuotaDogScreen(
            store, preferences, cloudSync, versionLabel, platformSettings,
            revealActionsOnHover = revealActionsOnHover,
            hostSyncContent = hostSyncContent,
            showLocalEmptyState = showLocalEmptyState,
            onConnectComputer = onConnectComputer,
        )
    }
}

@Composable
fun QuotaDogBackgroundEffects(
    store: QuotaDogStore,
    preferences: AppPreferences,
    cloudSync: CloudSyncCoordinator,
) {
    val autoRefreshMinutes by preferences.autoRefreshMinutes.collectAsState()
    val state by store.state.collectAsState()
    val nextSpentReset = state.accounts.values
        .filter { it.canRefreshUsage() }
        .mapNotNull { it.spentWindowResetAt() }
        .minOrNull()

    LaunchedEffect(Unit) {
        cloudSync.startRestoreSession()
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

    // A spent window keeps reading 100% after its quota is back until something refreshes it, and
    // auto refresh is off by default. Refresh accounts once their reset passes; each refresh
    // re-keys this effect to the next reset.
    LaunchedEffect(nextSpentReset) {
        val resetAt = nextSpentReset ?: return@LaunchedEffect
        delay(resetAt - Clock.System.now() + SPENT_RESET_REFRESH_GRACE)
        val now = Clock.System.now()
        store.state.value.accounts.values
            .filter { account ->
                val accountResetAt = account.spentWindowResetAt()
                account.canRefreshUsage() && !account.busy && accountResetAt != null && accountResetAt <= now
            }
            .forEach { store.startRefresh(it.accountKey) }
    }
}

/** Gives the provider time to roll a window over before the post-reset refresh. */
private val SPENT_RESET_REFRESH_GRACE = 1.minutes

@Composable
private fun QuotaDogScreen(
    store: QuotaDogStore,
    preferences: AppPreferences,
    cloudSync: CloudSyncCoordinator,
    versionLabel: String,
    platformSettings: (@Composable () -> Unit)?,
    revealActionsOnHover: Boolean,
    hostSyncContent: (@Composable () -> Unit)?,
    showLocalEmptyState: Boolean,
    onConnectComputer: (() -> Unit)?,
) {
    val state by store.state.collectAsState()
    val themeMode by preferences.themeMode.collectAsState()
    val autoRefreshMinutes by preferences.autoRefreshMinutes.collectAsState()
    val usageDisplayMode by preferences.usageDisplayMode.collectAsState()
    val showProjectedUsage by preferences.showProjectedUsage.collectAsState()
    val sinkExhaustedAccounts by preferences.sinkExhaustedAccounts.collectAsState()
    val emailPrivacyMode by preferences.emailPrivacyMode.collectAsState()
    val dashboardLayout by preferences.dashboardLayout.collectAsState()
    val accountSortMode by preferences.accountSortMode.collectAsState()
    val accountSortReversed by preferences.accountSortReversed.collectAsState()
    val accountManualOrder by preferences.accountManualOrder.collectAsState()
    val cloudSyncState by cloudSync.state.collectAsState()
    val callbackInputs = remember { mutableStateMapOf<AccountKey, String>() }
    var showProviderPicker by remember { mutableStateOf(false) }
    var showGrokMethodPicker by remember { mutableStateOf(false) }
    var showAntigravityMethodPicker by remember { mutableStateOf(false) }
    var showDroidMethodPicker by remember { mutableStateOf(false) }
    var showAmpMethodPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var manualReorderEditing by remember { mutableStateOf(false) }
    var selectedProvider by remember { mutableStateOf<ProviderId?>(null) }
    var pendingDelete by remember { mutableStateOf<AccountKey?>(null) }
    var showResetCloudSyncConfirm by remember { mutableStateOf(false) }
    var syncPassphrase by remember { mutableStateOf("") }
    val snackbar = rememberQdSnackbarController()

    val colors = QdTheme.colors
    val spacing = QdTheme.spacing

    val accounts = state.accounts.values
        .filter { it.shouldShowAccount() }
        .sortedAccounts(accountSortMode, accountManualOrder, accountSortReversed)
    val availableProviders = accounts.map { it.providerId }.distinct().sortedBy { it.ordinal }
    val refreshableAccounts = accounts.filter { it.canRefreshUsage() }
    val refreshAllBusy = refreshableAccounts.any { it.busy }
    val refreshAllEnabled = refreshableAccounts.any { !it.busy }

    LaunchedEffect(availableProviders) {
        if (selectedProvider != null && selectedProvider !in availableProviders) {
            selectedProvider = null
        }
    }

    // Edge-to-edge: outer Box paints the theme background under status bar and home indicator,
    // inner content respects safe drawing insets.
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
    val safeTop = safeInsets.calculateTopPadding()
    val safeBottom = safeInsets.calculateBottomPadding()
    val refreshAllAction = {
        store.startRefreshAll()
        snackbar.show(
            text = if (refreshAllBusy) "Refreshing remaining accounts..." else "Refreshing all accounts...",
            tone = QdSnackbarTone.Info,
        )
    }
    val selectSortMode = { mode: AccountSortMode ->
        if (mode == AccountSortMode.Manual && accountManualOrder.isEmpty()) {
            val displayed = accounts.map { it.accountKey }
            val seed = if (accountSortReversed) displayed.reversed() else displayed
            preferences.setAccountManualOrder(
                canonicalManualOrder(
                    state.accounts.values.filter { it.shouldShowAccount() },
                    seed,
                ),
            )
        }
        if (mode != accountSortMode) {
            preferences.setAccountSortMode(mode)
            cloudSync.startPushLocalChanges()
            snackbar.show(
                text = sortChangeLabel(mode, accountSortReversed),
                tone = QdSnackbarTone.Success,
            )
        }
        manualReorderEditing = mode == AccountSortMode.Manual
    }
    val selectSortReversed = { reversed: Boolean ->
        if (reversed != accountSortReversed) {
            preferences.setAccountSortReversed(reversed)
            cloudSync.startPushLocalChanges()
            snackbar.show(
                text = sortChangeLabel(accountSortMode, reversed),
                tone = QdSnackbarTone.Success,
            )
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        val isDesktop = maxWidth >= 900.dp
        val activeProvider = selectedProvider?.takeIf { it in availableProviders }
        val contentMaxWidth = if (isDesktop) 1080.dp else maxWidth
        val horizontalPadding = if (isDesktop) spacing.xxl else spacing.lg
        val topPadding = safeTop + if (isDesktop) spacing.xl else spacing.lg
        val bottomPadding = safeBottom + if (isDesktop) spacing.xxxl else 88.dp

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = contentMaxWidth)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding)
                .padding(top = topPadding, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(if (isDesktop) spacing.lg else spacing.md),
        ) {
            DashboardHeader(
                isDesktop = isDesktop,
                refreshAllBusy = refreshAllBusy,
                refreshAllEnabled = refreshAllEnabled,
                onRefreshAll = refreshAllAction,
                onAddAccount = { showProviderPicker = true },
                sortMode = accountSortMode,
                sortReversed = accountSortReversed,
                showSort = accounts.isNotEmpty(),
                onSelectSort = selectSortMode,
                onSelectSortReversed = selectSortReversed,
                onOpenSettings = { showSettings = true },
            )

            hostSyncContent?.invoke()
            if (accounts.isEmpty()) {
                if (showLocalEmptyState) AccountsEmptyState(isDesktop = isDesktop)
            } else {
                ProviderSwitcher(
                    accounts = accounts,
                    providers = availableProviders,
                    selected = activeProvider,
                    onSelect = { selectedProvider = it },
                )
                if (manualReorderEditing) {
                    ManualReorderBar(
                        onDone = {
                            manualReorderEditing = false
                            cloudSync.startPushLocalChanges()
                        },
                    )
                }
                val filteredAccounts = activeProvider?.let { selected ->
                    accounts.filter { it.providerId == selected }
                } ?: accounts
                // Manual editing keeps the saved order so a move matches the row on screen.
                val visibleAccounts = if (manualReorderEditing || !sinkExhaustedAccounts) {
                    filteredAccounts
                } else {
                    filteredAccounts.withExhaustedLast()
                }
                val moveVisibleAccount = { key: AccountKey, delta: Int, pushSync: Boolean ->
                    val pool = store.state.value.accounts.values.filter { it.shouldShowAccount() }
                    val active = selectedProvider?.takeIf { chosen ->
                        pool.any { it.providerId == chosen }
                    }
                    val updated = reorderDisplayedManualOrder(
                        accounts = pool,
                        stored = preferences.accountManualOrder.value,
                        reversed = preferences.accountSortReversed.value,
                        provider = active,
                        key = key,
                        delta = delta,
                    )
                    if (updated != preferences.accountManualOrder.value) {
                        preferences.setAccountManualOrder(updated)
                        if (pushSync) cloudSync.startPushLocalChanges()
                    }
                }
                AccountCards(
                    accounts = visibleAccounts,
                    isDesktop = isDesktop,
                    layout = dashboardLayout,
                    revealActionsOnHover = revealActionsOnHover,
                    manualReorder = manualReorderEditing,
                    usageDisplayMode = usageDisplayMode,
                    showProjectedUsage = showProjectedUsage,
                    sinkExhaustedAccounts = sinkExhaustedAccounts,
                    emailPrivacyMode = emailPrivacyMode,
                    callbackInput = { callbackInputs[it].orEmpty() },
                    onCallbackChange = { accountKey, value -> callbackInputs[accountKey] = value },
                    onCompleteLogin = { accountKey ->
                        val input = callbackInputs[accountKey].orEmpty()
                        store.startCompleteLogin(accountKey, input)
                    },
                    onReopenLogin = { accountKey -> store.startReopenLogin(accountKey) },
                    onSignInAgain = { providerId -> store.startLogin(providerId) },
                    onRefresh = { providerState ->
                        store.startRefresh(providerState.accountKey)
                        snackbar.show("Refreshing ${providerState.accountTitle(emailPrivacyMode)}...")
                    },
                    onRequestDelete = { accountKey -> pendingDelete = accountKey },
                    onMove = { key, delta, pushSync -> moveVisibleAccount(key, delta, pushSync) },
                    onMoveFinished = { cloudSync.startPushLocalChanges() },
                )
            }
        }

        if (!isDesktop) {
            AddAccountButton(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = safeBottom + spacing.xxl),
                onClick = { showProviderPicker = true },
            )
        }

        // Snackbar floats above the FAB (FAB sits at safeBottom + xxl with ~44dp height +
        // baseline padding lg -> leave ~84dp clearance plus safe inset).
        QdSnackbarHost(
            controller = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = safeBottom + if (isDesktop) spacing.xxl else 84.dp),
        )

        QdBottomSheet(
            visible = showProviderPicker,
            onDismiss = { showProviderPicker = false },
        ) {
            ProviderPickerContent(
                onConnectComputer = onConnectComputer?.let { connect -> { showProviderPicker = false; connect() } },
                onSelect = { provider ->
                    if (provider == ProviderId.GROK && grokCliImportAvailable()) {
                        showProviderPicker = false
                        showGrokMethodPicker = true
                    } else if (provider == ProviderId.ANTIGRAVITY && antigravityCliImportAvailable()) {
                        showProviderPicker = false
                        showAntigravityMethodPicker = true
                    } else if (provider == ProviderId.DROID && droidCliImportAvailable()) {
                        showProviderPicker = false
                        showDroidMethodPicker = true
                    } else if (provider == ProviderId.AMP && ampCliImportAvailable()) {
                        showProviderPicker = false
                        showAmpMethodPicker = true
                    } else {
                        showProviderPicker = false
                        store.startLogin(provider)
                        snackbar.show(
                            text = when (provider) {
                                ProviderId.GROK -> "Opening xAI sign-in..."
                                ProviderId.DROID -> "Opening Factory sign-in..."
                                ProviderId.AMP -> "Opening Amp sign-in..."
                                ProviderId.CURSOR -> "Importing Cursor app or CLI credentials..."
                                ProviderId.ANTIGRAVITY -> "Opening Google sign-in for Antigravity..."
                                ProviderId.DEVIN -> "Importing Devin CLI credentials..."
                                else -> "Opening browser for ${provider.displayName}..."
                            },
                            tone = QdSnackbarTone.Info,
                        )
                    }
                },
            )
        }

        QdBottomSheet(
            visible = showGrokMethodPicker,
            onDismiss = { showGrokMethodPicker = false },
        ) {
            AuthMethodPickerContent(
                title = "Add Grok",
                description = "Sign in with xAI, or import credentials from the Grok CLI on this computer.",
                oauthTitle = "Sign in with xAI",
                oauthSubtitle = "Same device-code login as Grok CLI and CLI Proxy API",
                importTitle = "Import from Grok CLI",
                importSubtitle = "Read credentials from ${grokAuthFileHint()}",
                onSelectOauth = {
                    showGrokMethodPicker = false
                    store.startGrokDeviceLogin()
                    snackbar.show(
                        text = "Opening xAI sign-in...",
                        tone = QdSnackbarTone.Info,
                    )
                },
                onSelectCliImport = {
                    showGrokMethodPicker = false
                    store.startImportGrok()
                    snackbar.show(
                        text = "Importing Grok CLI credentials...",
                        tone = QdSnackbarTone.Info,
                    )
                },
            )
        }

        QdBottomSheet(
            visible = showAntigravityMethodPicker,
            onDismiss = { showAntigravityMethodPicker = false },
        ) {
            AuthMethodPickerContent(
                title = "Add Antigravity",
                description = "Sign in with Google, or import credentials from the Antigravity CLI on this Mac.",
                oauthTitle = "Sign in with Google",
                oauthSubtitle = "Browser OAuth sign-in for Antigravity quota windows",
                importTitle = "Import from Antigravity CLI",
                importSubtitle = "Read credentials from ${antigravityAuthHint()}",
                onSelectOauth = {
                    showAntigravityMethodPicker = false
                    store.startLogin(ProviderId.ANTIGRAVITY)
                    snackbar.show(
                        text = "Opening Google sign-in for Antigravity...",
                        tone = QdSnackbarTone.Info,
                    )
                },
                onSelectCliImport = {
                    showAntigravityMethodPicker = false
                    store.startImportAntigravity()
                    snackbar.show(
                        text = "Importing Antigravity CLI credentials...",
                        tone = QdSnackbarTone.Info,
                    )
                },
            )
        }

        QdBottomSheet(
            visible = showDroidMethodPicker,
            onDismiss = { showDroidMethodPicker = false },
        ) {
            AuthMethodPickerContent(
                title = "Add Droid",
                description = "Sign in with Factory, or import credentials from the droid CLI on this computer.",
                oauthTitle = "Sign in with Factory",
                oauthSubtitle = "Same device-code login as the droid CLI",
                importTitle = "Import from droid CLI",
                importSubtitle = "Read credentials from ${droidAuthFileHint()}",
                onSelectOauth = {
                    showDroidMethodPicker = false
                    store.startDroidDeviceLogin()
                    snackbar.show(
                        text = "Opening Factory sign-in...",
                        tone = QdSnackbarTone.Info,
                    )
                },
                onSelectCliImport = {
                    showDroidMethodPicker = false
                    store.startImportDroid()
                    snackbar.show(
                        text = "Importing droid CLI credentials...",
                        tone = QdSnackbarTone.Info,
                    )
                },
            )
        }

        QdBottomSheet(
            visible = showAmpMethodPicker,
            onDismiss = { showAmpMethodPicker = false },
        ) {
            AuthMethodPickerContent(
                title = "Add Amp",
                description = "Sign in with Amp, or import credentials from the Amp CLI on this computer.",
                oauthTitle = "Sign in with Amp",
                oauthSubtitle = "Same device-code login as `amp login --oauth`",
                importTitle = "Import from Amp CLI",
                importSubtitle = "Read credentials from ${ampAuthFileHint()}",
                onSelectOauth = {
                    showAmpMethodPicker = false
                    store.startAmpDeviceLogin()
                    snackbar.show(
                        text = "Opening Amp sign-in...",
                        tone = QdSnackbarTone.Info,
                    )
                },
                onSelectCliImport = {
                    showAmpMethodPicker = false
                    store.startImportAmp()
                    snackbar.show(
                        text = "Importing Amp CLI credentials...",
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
                cloudSync.startPushLocalChanges()
                snackbar.show("Theme set to ${it.name.lowercase()}")
            },
            dashboardLayout = dashboardLayout,
            onDashboardLayoutChange = {
                preferences.setDashboardLayout(it)
                cloudSync.startPushLocalChanges()
                snackbar.show(
                    text = "Layout set to ${it.label.lowercase()}",
                    tone = QdSnackbarTone.Success,
                )
            },
            usageDisplayMode = usageDisplayMode,
            onUsageDisplayModeChange = {
                preferences.setUsageDisplayMode(it)
                cloudSync.startPushLocalChanges()
                snackbar.show(
                    text = "Quota display set to ${it.name.lowercase()}",
                    tone = QdSnackbarTone.Success,
                )
            },
            showProjectedUsage = showProjectedUsage,
            onShowProjectedUsageChange = {
                preferences.setShowProjectedUsage(it)
                cloudSync.startPushLocalChanges()
                snackbar.show(
                    text = if (it) "Usage estimate enabled" else "Usage estimate disabled",
                    tone = QdSnackbarTone.Success,
                )
            },
            sinkExhaustedAccounts = sinkExhaustedAccounts,
            onSinkExhaustedAccountsChange = {
                preferences.setSinkExhaustedAccounts(it)
                cloudSync.startPushLocalChanges()
                snackbar.show(
                    text = if (it) "Spent accounts enabled" else "Spent accounts disabled",
                    tone = QdSnackbarTone.Success,
                )
            },
            emailPrivacyMode = emailPrivacyMode,
            onEmailPrivacyModeChange = {
                preferences.setEmailPrivacyMode(it)
                cloudSync.startPushLocalChanges()
                snackbar.show(
                    text = if (it == EmailPrivacyMode.Masked) "Email privacy enabled" else "Email privacy disabled",
                    tone = QdSnackbarTone.Success,
                )
            },
            autoRefreshMinutes = autoRefreshMinutes,
            onAutoRefreshChange = {
                preferences.setAutoRefreshMinutes(it)
                cloudSync.startPushLocalChanges()
                snackbar.show(
                    text = if (it == 0) "Auto refresh disabled" else "Auto refresh every $it min",
                    tone = QdSnackbarTone.Success,
                )
            },
            refreshAllBusy = refreshAllBusy,
            refreshAllEnabled = refreshAllEnabled,
            onRefreshAll = refreshAllAction,
            cloudSyncState = cloudSyncState,
            syncPassphrase = syncPassphrase,
            onSyncPassphraseChange = { syncPassphrase = it },
            onConnectDropbox = {
                cloudSync.startConnectDropbox(syncPassphrase) {
                    store.startReloadAccounts()
                }
                snackbar.show("Opening Dropbox authorization...", tone = QdSnackbarTone.Info)
            },
            onUnlockCloudSync = {
                cloudSync.startUnlock(syncPassphrase) {
                    store.startReloadAccounts()
                }
                snackbar.show("Syncing with Dropbox...", tone = QdSnackbarTone.Info)
            },
            onSyncNow = {
                cloudSync.startSyncNow {
                    store.startReloadAccounts()
                }
                snackbar.show("Syncing with Dropbox...", tone = QdSnackbarTone.Info)
            },
            onCancelCloudSync = {
                cloudSync.cancelCurrentOperation()
                snackbar.show("Dropbox sync cancelled", tone = QdSnackbarTone.Info)
            },
            onResetCloudSync = {
                showResetCloudSyncConfirm = true
            },
            onDisconnectCloudSync = {
                cloudSync.disconnect()
                snackbar.show("Dropbox sync disconnected", tone = QdSnackbarTone.Success)
            },
            onDismiss = { showSettings = false },
            versionLabel = versionLabel,
            platformSettings = platformSettings,
        )

        QdConfirmDialog(
            visible = pendingDelete != null,
            title = "Remove account?",
            message = "The locally stored token for ${pendingDelete?.deleteLabel(state, emailPrivacyMode).orEmpty()} will be deleted. If Dropbox sync is enabled, this removal will sync to your other devices.",
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

        QdConfirmDialog(
            visible = showResetCloudSyncConfirm,
            title = "Reset Dropbox sync file?",
            message = "This overwrites the encrypted Dropbox sync file with data currently on this device and the passphrase in the field above. Data that exists only in Dropbox, or only on another device that has not synced here, may be lost. Devices using the old passphrase will need the new passphrase.",
            confirmLabel = "Reset sync file",
            destructive = true,
            onConfirm = {
                cloudSync.startResetCloudSync(syncPassphrase) {
                    store.startReloadAccounts()
                }
                snackbar.show("Resetting Dropbox sync file...", tone = QdSnackbarTone.Info)
                showResetCloudSyncConfirm = false
            },
            onDismiss = { showResetCloudSyncConfirm = false },
        )
    }
}

@Composable
private fun DashboardHeader(
    isDesktop: Boolean,
    refreshAllBusy: Boolean,
    refreshAllEnabled: Boolean,
    onRefreshAll: () -> Unit,
    onAddAccount: () -> Unit,
    sortMode: AccountSortMode,
    sortReversed: Boolean,
    showSort: Boolean,
    onSelectSort: (AccountSortMode) -> Unit,
    onSelectSortReversed: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    val refreshAllLabel = if (refreshAllBusy) "Refresh remaining" else "Refresh all"

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                "Quota",
                style = if (isDesktop) typo.displayLarge else typo.titleLarge,
                color = colors.textPrimary,
            )
            Text(
                " / ",
                style = if (isDesktop) typo.displayLarge else typo.titleLarge,
                color = colors.textTertiary,
            )
            Text(
                "Dog",
                style = if (isDesktop) typo.displayLarge else typo.titleLarge,
                color = colors.textPrimary,
            )
        }

        if (isDesktop) {
            QdButton(
                text = refreshAllLabel,
                onClick = onRefreshAll,
                variant = QdButtonVariant.Secondary,
                size = QdButtonSize.Small,
                enabled = refreshAllEnabled,
            )
            QdButton(
                text = "Add account",
                onClick = onAddAccount,
                variant = QdButtonVariant.Secondary,
                size = QdButtonSize.Small,
                leading = { QdPlusIcon(tint = colors.textPrimary, size = 14.dp) },
            )
        }
        if (showSort) {
            AccountSortButton(
                mode = sortMode,
                reversed = sortReversed,
                onSelect = onSelectSort,
                onSelectReversed = onSelectSortReversed,
            )
        }
        if (!isDesktop && (refreshAllEnabled || refreshAllBusy)) {
            QdGlassIconButton(
                onClick = onRefreshAll,
                modifier = Modifier.semantics { contentDescription = refreshAllLabel },
                enabled = refreshAllEnabled,
                diameter = 32.dp,
                shape = QdTheme.shapes.pill,
            ) {
                val rotation = rememberRefreshRotation(active = refreshAllBusy)
                QdRefreshIcon(
                    modifier = Modifier.rotate(rotation),
                    tint = colors.textSecondary,
                    size = 18.dp,
                )
            }
        }
        QdGlassIconButton(
            onClick = onOpenSettings,
            diameter = 32.dp,
            shape = QdTheme.shapes.pill,
        ) {
            QdSettingsGearIcon(tint = colors.textSecondary, size = 18.dp)
        }
    }
}

@Composable
private fun AccountSortButton(
    mode: AccountSortMode,
    reversed: Boolean,
    onSelect: (AccountSortMode) -> Unit,
    onSelectReversed: (Boolean) -> Unit,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    val shape = QdTheme.shapes.pill
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    var expanded by remember { mutableStateOf(false) }
    val background by animateColorAsState(
        targetValue = when {
            pressed -> colors.surfaceMuted
            hovered -> colors.surfaceHover.copy(alpha = 0.45f)
            else -> colors.surface
        },
        animationSpec = tween(120),
    )

    Box {
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(shape)
                .background(background)
                .border(1.dp, colors.border, shape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { expanded = true },
                )
                .padding(horizontal = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            if (reversed) {
                QdSortDescendingIcon(tint = colors.textSecondary, size = 14.dp)
            } else {
                QdSortAscendingIcon(tint = colors.textSecondary, size = 14.dp)
            }
            Text(mode.shortLabel(), style = typo.labelLarge, color = colors.textPrimary)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 240.dp),
        ) {
            AccountSortMode.entries.forEach { option ->
                SortMenuRow(
                    title = option.shortLabel(),
                    hint = option.hint(),
                    selected = option == mode,
                    onClick = {
                        onSelect(option)
                        if (option == AccountSortMode.Manual) expanded = false
                    },
                )
                if (option == mode) {
                    SortDirectionGroup(
                        mode = option,
                        reversed = reversed,
                        onSelectReversed = onSelectReversed,
                    )
                }
            }
        }
    }
}

@Composable
private fun SortDirectionGroup(
    mode: AccountSortMode,
    reversed: Boolean,
    onSelectReversed: (Boolean) -> Unit,
) {
    val colors = QdTheme.colors
    val spacing = QdTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.sm)
            .padding(bottom = spacing.xs)
            .clip(QdTheme.shapes.sm)
            .background(colors.primaryMuted),
    ) {
        SortDirectionRow(
            title = "Order",
            hint = mode.directionHint(reversed = false),
            selected = !reversed,
            onClick = { onSelectReversed(false) },
        )
        SortDirectionRow(
            title = "Reverse",
            hint = mode.directionHint(reversed = true),
            selected = reversed,
            onClick = { onSelectReversed(true) },
        )
    }
}

@Composable
private fun SortDirectionRow(
    title: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = typo.bodyMedium, color = colors.textPrimary)
            Text(hint, style = typo.caption, color = colors.textSecondary)
        }
        if (selected) {
            QdCheckIcon(tint = colors.primary, size = 16.dp)
        } else {
            Spacer(modifier = Modifier.width(16.dp))
        }
    }
}

@Composable
private fun ManualReorderBar(onDone: () -> Unit) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text(
            "Drag the handle to reorder.",
            style = typo.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        QdButton(
            text = "Done",
            onClick = onDone,
            variant = QdButtonVariant.Primary,
            size = QdButtonSize.Small,
        )
    }
}

@Composable
private fun SortMenuRow(
    title: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    DropdownMenuItem(onClick = onClick) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = typo.bodyMedium, color = colors.textPrimary)
            Text(hint, style = typo.caption, color = colors.textTertiary)
        }
        if (selected) {
            QdCheckIcon(tint = colors.primary, size = 16.dp)
        } else {
            Spacer(modifier = Modifier.width(16.dp))
        }
    }
}

@Composable
private fun AccountsEmptyState(isDesktop: Boolean) {
    val providerNames = providerNamesLabel()
    if (isDesktop) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            QdEmptyState(
                title = "No accounts yet",
                description = "Add a $providerNames account to start tracking quota windows in one place.",
                modifier = Modifier.widthIn(max = 640.dp),
            )
        }
        return
    }
    QdEmptyState(
        title = "No accounts yet",
        description = "Add a $providerNames account to start tracking quota windows in one place.",
    )
}

@Composable
private fun ProviderSwitcher(
    accounts: List<AccountUiState>,
    providers: List<ProviderId>,
    selected: ProviderId?,
    onSelect: (ProviderId?) -> Unit,
) {
    val options: List<Pair<String, ProviderId?>> =
        listOf("All providers (${accounts.size})" to null) +
            providers.map { provider ->
                val accountCount = accounts.count { it.providerId == provider }
                "${provider.displayName} ($accountCount)" to provider
            }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fadingHorizontalScroll(rememberScrollState()),
    ) {
        QdSegmentedControl(
            options = options,
            selected = selected,
            onSelect = onSelect,
            fillWidth = false,
            autoScrollToSelected = true,
        )
    }
}

private fun AccountSortMode.shortLabel(): String = when (this) {
    AccountSortMode.Name -> "Name"
    AccountSortMode.RefreshTime -> "Refresh"
    AccountSortMode.Manual -> "Manual"
}

private fun AccountSortMode.hint(): String = when (this) {
    AccountSortMode.Name -> "Account name."
    AccountSortMode.RefreshTime -> "Last refresh time."
    AccountSortMode.Manual -> "Rearrange, then tap Done."
}

private fun AccountSortMode.directionHint(reversed: Boolean): String = when (this) {
    AccountSortMode.Name -> if (reversed) "Z to A." else "A to Z."
    AccountSortMode.RefreshTime -> if (reversed) "Oldest refresh first." else "Newest refresh first."
    AccountSortMode.Manual -> if (reversed) "Saved order, bottom to top." else "Saved order, top to bottom."
}

private fun sortChangeLabel(mode: AccountSortMode, reversed: Boolean): String = when (mode) {
    AccountSortMode.Name -> if (reversed) "Sorted by name, Z to A" else "Sorted by name, A to Z"
    AccountSortMode.RefreshTime -> if (reversed) "Sorted by oldest refresh" else "Sorted by newest refresh"
    AccountSortMode.Manual -> if (reversed) "Manual order reversed" else "Manual order"
}

@Composable
private fun AccountCards(
    accounts: List<AccountUiState>,
    isDesktop: Boolean,
    layout: DashboardLayout,
    revealActionsOnHover: Boolean,
    manualReorder: Boolean,
    usageDisplayMode: UsageDisplayMode,
    showProjectedUsage: Boolean,
    sinkExhaustedAccounts: Boolean,
    emailPrivacyMode: EmailPrivacyMode,
    callbackInput: (AccountKey) -> String,
    onCallbackChange: (AccountKey, String) -> Unit,
    onCompleteLogin: (AccountKey) -> Unit,
    onReopenLogin: (AccountKey) -> Unit,
    onSignInAgain: (ProviderId) -> Unit,
    onRefresh: (AccountUiState) -> Unit,
    onRequestDelete: (AccountKey) -> Unit,
    onMove: (AccountKey, Int, Boolean) -> Unit,
    onMoveFinished: () -> Unit,
) {
    val colors = QdTheme.colors
    val spacing = QdTheme.spacing
    val dragState = rememberAccountReorderDragState()
    val visibleKeys = accounts.map { it.accountKey }
    // Accounts the user opened or closed by hand in a layout that folds them.
    val expandedAccounts = remember { mutableStateMapOf<AccountKey, Boolean>() }
    // One line separates rows with a 1 dp rule instead of a gap.
    val itemSpacing = if (layout == DashboardLayout.OneLine) 1.dp else spacing.md

    @Composable
    fun cardAt(index: Int, providerState: AccountUiState, inline: Boolean, modifier: Modifier) {
        val accountKey = providerState.accountKey
        val reorder = if (manualReorder) {
            AccountReorderControls(
                canMoveUp = index > 0,
                canMoveDown = index < accounts.lastIndex,
                handleModifier = Modifier.accountDragHandle(
                    accountKey = accountKey,
                    dragState = dragState,
                    visibleKeys = visibleKeys,
                    itemSpacing = itemSpacing,
                    onMove = { key, delta -> onMove(key, delta, false) },
                    onDragFinished = onMoveFinished,
                ),
                onMoveUp = { onMove(accountKey, -1, true) },
                onMoveDown = { onMove(accountKey, 1, true) },
            )
        } else {
            null
        }
        val cardModifier = if (manualReorder) {
            modifier
                .reportAccountReorderHeight(accountKey, dragState)
                .accountReorderVisual(accountKey, dragState)
        } else {
            modifier
        }
        AccountCard(
            state = providerState,
            layout = layout,
            inline = inline,
            expanded = expandedAccounts[accountKey] == true,
            onToggleExpanded = { expandedAccounts[accountKey] = expandedAccounts[accountKey] != true },
            revealActionsOnHover = revealActionsOnHover,
            usageDisplayMode = usageDisplayMode,
            showProjectedUsage = showProjectedUsage,
            sinkExhaustedAccounts = sinkExhaustedAccounts,
            emailPrivacyMode = emailPrivacyMode,
            callbackInput = callbackInput(accountKey),
            onCallbackChange = { onCallbackChange(accountKey, it) },
            onCompleteLogin = { onCompleteLogin(accountKey) },
            onReopenLogin = { onReopenLogin(accountKey) },
            onSignInAgain = { onSignInAgain(providerState.providerId) },
            onRefresh = { onRefresh(providerState) },
            onRequestDelete = { onRequestDelete(accountKey) },
            reorder = reorder,
            modifier = cardModifier,
        )
    }

    // One line is a single list at every width; spent accounts already sit at its end.
    if (layout == DashboardLayout.OneLine) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val inline = maxWidth >= LineInlineMinWidth
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(QdTheme.shapes.md)
                    .background(colors.backgroundElevated)
                    .border(1.dp, colors.border, QdTheme.shapes.md),
            ) {
                accounts.forEachIndexed { index, providerState ->
                    key(providerState.accountKey) {
                        if (index > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(colors.border),
                            )
                        }
                        cardAt(index, providerState, inline, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        return
    }

    // Manual order is a single column so up, down, and drag all move along one axis.
    if (!isDesktop || manualReorder) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
            accounts.forEachIndexed { index, providerState ->
                key(providerState.accountKey) {
                    cardAt(index, providerState, false, Modifier.fillMaxWidth())
                }
            }
        }
        return
    }

    // Spent accounts arrive last, so they start a new row instead of sharing one with a usable account.
    val spentStart = if (sinkExhaustedAccounts) {
        accounts.indexOfFirst { it.isQuotaExhausted() }
    } else {
        -1
    }
    val usableAccounts = if (spentStart < 0) accounts else accounts.subList(0, spentStart)
    val spentAccounts = if (spentStart < 0) emptyList() else accounts.subList(spentStart, accounts.size)

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        listOf(0 to usableAccounts, usableAccounts.size to spentAccounts).forEach { (offset, group) ->
            if (group.isEmpty()) return@forEach
            if (layout == DashboardLayout.Cards) {
                group.chunked(2).forEachIndexed { rowIndex, rowAccounts ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        rowAccounts.forEachIndexed { columnIndex, providerState ->
                            key(providerState.accountKey) {
                                cardAt(
                                    offset + rowIndex * 2 + columnIndex,
                                    providerState,
                                    false,
                                    Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                        }
                        if (rowAccounts.size == 1) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                // Compact cards fill two columns on their own, so a short card leaves no gap.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                    verticalAlignment = Alignment.Top,
                ) {
                    for (column in 0..1) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(spacing.md),
                        ) {
                            group.forEachIndexed { index, providerState ->
                                if (index % 2 == column) {
                                    key(providerState.accountKey) {
                                        cardAt(offset + index, providerState, false, Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class AccountReorderControls(
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val handleModifier: Modifier,
    val onMoveUp: () -> Unit,
    val onMoveDown: () -> Unit,
)

private const val EXHAUSTED_ACCOUNT_ALPHA = 0.5f

/** A window with at least this much left reads as untouched: gray in compact rows, folded in Fold full. */
internal const val QUIET_REMAINING_RATIO = 0.9

internal val CompactActionSize = 24.dp
private val CompactBarWidth = 96.dp
private val CompactPercentWidth = 70.dp
private val CompactResetWidth = 52.dp
private val FoldToggleWidth = 40.dp
private val LineIdentityWidth = 190.dp
private val LineLabelWidth = 160.dp

/** Below this width, One line rows show the tightest window on a second line. */
private val LineInlineMinWidth = 820.dp

/**
 * Rows that [layout] hides behind an account's tightest window while the account is folded, or
 * 0 when it always shows them. Fold full folds only accounts that look untouched everywhere.
 * The dashboard and both desktop trays fold by this rule.
 */
internal fun foldableRowCount(layout: DashboardLayout, windows: List<UsageWindow>, hasResetSummary: Boolean): Int {
    val hidden = windows.size - 1 + if (hasResetSummary) 1 else 0
    val folds = when (layout) {
        DashboardLayout.FoldFull -> windows.all { it.remainingRatio >= QUIET_REMAINING_RATIO }
        DashboardLayout.OneLine -> true
        DashboardLayout.Cards, DashboardLayout.Compact -> false
    }
    return if (folds && hidden > 0) hidden else 0
}

/** Clicking anywhere on a [foldable] account opens or closes it. */
internal fun Modifier.foldToggle(foldable: Boolean, folded: Boolean, onToggle: () -> Unit): Modifier =
    if (foldable) {
        pointerHoverIcon(PointerIcon.Hand).clickable(
            interactionSource = null,
            indication = null,
            onClickLabel = if (folded) "Show all windows" else "Hide windows",
            role = Role.Button,
            onClick = onToggle,
        )
    } else {
        this
    }

/**
 * One account in the chosen [layout]. [inline] puts One line's tightest window beside the
 * account name. Fold full and One line show only the tightest window until [expanded].
 */
@Composable
private fun AccountCard(
    state: AccountUiState,
    layout: DashboardLayout,
    inline: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    revealActionsOnHover: Boolean,
    usageDisplayMode: UsageDisplayMode,
    showProjectedUsage: Boolean,
    sinkExhaustedAccounts: Boolean,
    emailPrivacyMode: EmailPrivacyMode,
    callbackInput: String,
    onCallbackChange: (String) -> Unit,
    onCompleteLogin: () -> Unit,
    onReopenLogin: () -> Unit,
    onSignInAgain: () -> Unit,
    onRefresh: () -> Unit,
    onRequestDelete: () -> Unit,
    reorder: AccountReorderControls? = null,
    modifier: Modifier = Modifier,
) {
    val colors = QdTheme.colors
    val spacing = QdTheme.spacing
    var menuExpanded by remember { mutableStateOf(false) }
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }

    val windows = state.snapshot?.windows.orEmpty()
    val resetSummary = state.snapshot?.codexResetSummary()
    val hiddenCount = foldableRowCount(layout, windows, resetSummary != null)
    val foldable = hiddenCount > 0
    val folded = foldable && !expanded
    val actionsVisible = !revealActionsOnHover || hovered || focused || menuExpanded || state.busy

    val surfaceModifier = modifier
        .fillMaxWidth()
        .alpha(if (sinkExhaustedAccounts && state.isQuotaExhausted()) EXHAUSTED_ACCOUNT_ALPHA else 1f)
        .hoverable(hover)
        .onFocusChanged { focused = it.hasFocus }
        .focusGroup()
        .foldToggle(foldable, folded, onToggleExpanded)

    val content: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            if (layout == DashboardLayout.Cards) {
                AccountCardHeader(
                    state = state,
                    emailPrivacyMode = emailPrivacyMode,
                    menuExpanded = menuExpanded,
                    onMenuExpandedChange = { menuExpanded = it },
                    onRefresh = onRefresh,
                    onReopenLogin = onReopenLogin,
                    onRequestDelete = onRequestDelete,
                    reorder = reorder,
                )
                if (windows.isNotEmpty() || resetSummary != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        windows.forEach { UsageWindowRow(it, usageDisplayMode, showProjectedUsage) }
                        resetSummary?.let { CodexResetCreditsBlock(it) }
                    }
                } else {
                    InlineStatus(state.emptyStatusText())
                }
            } else {
                val trailing: @Composable () -> Unit = {
                    AccountTrailingControls(
                        state = state,
                        fold = if (foldable) FoldToggleUi(folded, hiddenCount) else null,
                        reserveFoldSpace = layout == DashboardLayout.OneLine,
                        actionsVisible = actionsVisible,
                        menuExpanded = menuExpanded,
                        onMenuExpandedChange = { menuExpanded = it },
                        onRefresh = onRefresh,
                        onReopenLogin = onReopenLogin,
                        onRequestDelete = onRequestDelete,
                        reorder = reorder,
                    )
                }
                if (inline) {
                    InlineLineAccount(
                        state = state,
                        windows = windows,
                        resetSummary = resetSummary,
                        folded = folded,
                        usageDisplayMode = usageDisplayMode,
                        showProjectedUsage = showProjectedUsage,
                        emailPrivacyMode = emailPrivacyMode,
                        trailing = trailing,
                    )
                } else {
                    AccountHeaderLine(state = state, emailPrivacyMode = emailPrivacyMode, trailing = trailing)
                    CompactWindows(
                        state = state,
                        windows = windows,
                        resetSummary = resetSummary,
                        folded = folded,
                        usageDisplayMode = usageDisplayMode,
                        showProjectedUsage = showProjectedUsage,
                    )
                }
            }
            AccountSignInDetails(
                state = state,
                callbackInput = callbackInput,
                onCallbackChange = onCallbackChange,
                onCompleteLogin = onCompleteLogin,
                onReopenLogin = onReopenLogin,
                onSignInAgain = onSignInAgain,
            )
        }
    }

    when (layout) {
        DashboardLayout.Cards -> QdCard(
            modifier = surfaceModifier,
            padding = PaddingValues(spacing.md),
            background = colors.backgroundElevated,
            content = content,
        )
        DashboardLayout.Compact, DashboardLayout.FoldFull -> QdCard(
            modifier = surfaceModifier,
            padding = PaddingValues(horizontal = spacing.md, vertical = spacing.sm),
            background = colors.backgroundElevated,
            content = content,
        )
        // Rows sit inside one bordered list; the background keeps a dragged row opaque.
        DashboardLayout.OneLine -> Box(
            modifier = surfaceModifier
                .background(colors.backgroundElevated)
                .padding(horizontal = spacing.md, vertical = spacing.xs),
        ) {
            content()
        }
    }
}

/** Two-line header of the Cards layout: provider and plan above the account email. */
@Composable
private fun AccountCardHeader(
    state: AccountUiState,
    emailPrivacyMode: EmailPrivacyMode,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onReopenLogin: () -> Unit,
    onRequestDelete: () -> Unit,
    reorder: AccountReorderControls?,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        QdProviderAvatar(state.providerId, size = 20.dp)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    state.providerId.displayName,
                    style = typo.caption,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                state.snapshot?.displayPlanLabel()?.let { plan ->
                    QdPlanLabel(plan)
                }
            }
            AccountEmailTitle(state.accountSubtitle(), emailPrivacyMode)
        }

        AccountActionButtons(
            state = state,
            diameter = 28.dp,
            iconSize = 16.dp,
            menuExpanded = menuExpanded,
            onMenuExpandedChange = onMenuExpandedChange,
            onRefresh = onRefresh,
            onReopenLogin = onReopenLogin,
            onRequestDelete = onRequestDelete,
        )
        reorder?.let { AccountReorderButtons(it) }
    }
}

/**
 * One-line header of the denser layouts: provider, plan, and email, then optional [cells]
 * and [trailing] controls. [identityWidth] lines up the emails of One line rows.
 */
@Composable
internal fun AccountHeaderLine(
    state: AccountUiState,
    emailPrivacyMode: EmailPrivacyMode,
    identityWidth: Dp? = null,
    cells: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CompactActionSize),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        QdProviderAvatar(state.providerId, size = 16.dp)
        // Only the account part is weighted, so on a narrow row the email and then the
        // name give way before any cell or control is squeezed.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(
                modifier = if (identityWidth != null) Modifier.width(identityWidth) else Modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    state.providerId.displayName,
                    style = typo.labelLarge,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                state.snapshot?.displayPlanLabel()?.let { plan ->
                    QdPlanLabel(plan)
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                AccountEmailTitle(
                    state.accountSubtitle(),
                    emailPrivacyMode,
                    style = typo.bodyMedium,
                    color = colors.textTertiary,
                )
            }
        }
        cells?.invoke()
        trailing?.invoke()
    }
}

/** One line on a wide window: the tightest window sits beside the account, the rest below it. */
@Composable
private fun InlineLineAccount(
    state: AccountUiState,
    windows: List<UsageWindow>,
    resetSummary: CodexResetSummary?,
    folded: Boolean,
    usageDisplayMode: UsageDisplayMode,
    showProjectedUsage: Boolean,
    emailPrivacyMode: EmailPrivacyMode,
    trailing: @Composable () -> Unit,
) {
    val spacing = QdTheme.spacing
    val tightest = windows.tightestWindow()
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        // The trailing controls stay outside this column, so detail rows end where the
        // tightest window ends and their columns line up with it.
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            AccountHeaderLine(
                state = state,
                emailPrivacyMode = emailPrivacyMode,
                identityWidth = LineIdentityWidth,
                cells = tightest?.let { window ->
                    {
                        CompactUsageWindowRow(
                            window = window,
                            displayMode = usageDisplayMode,
                            showProjectedUsage = showProjectedUsage,
                            label = windows.foldedLabel(window),
                            labelWidth = LineLabelWidth,
                        )
                    }
                },
            )
            if (windows.isEmpty() && resetSummary == null) {
                InlineStatus(state.emptyStatusText())
            } else if (!folded) {
                val rest = if (tightest == null || windows.allShareTightest()) windows else windows - tightest
                if (rest.isNotEmpty() || resetSummary != null) {
                    val cellsWidth = LineLabelWidth + CompactBarWidth + CompactPercentWidth +
                        CompactResetWidth + spacing.md * 3
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.weight(1f))
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                            rest.forEach { window ->
                                CompactUsageWindowRow(
                                    window = window,
                                    displayMode = usageDisplayMode,
                                    showProjectedUsage = showProjectedUsage,
                                    labelWidth = LineLabelWidth,
                                )
                            }
                            resetSummary?.let { summary ->
                                Box(modifier = Modifier.width(cellsWidth)) { CodexResetCreditsBlock(summary) }
                            }
                        }
                    }
                }
            }
        }
        trailing()
    }
}

/** Window rows of Compact, Fold full, and narrow One line: one line each, or the tightest one when [folded]. */
@Composable
internal fun CompactWindows(
    state: AccountUiState,
    windows: List<UsageWindow>,
    resetSummary: CodexResetSummary?,
    folded: Boolean,
    usageDisplayMode: UsageDisplayMode,
    showProjectedUsage: Boolean,
) {
    if (windows.isEmpty() && resetSummary == null) {
        InlineStatus(state.emptyStatusText())
        return
    }
    val tightest = windows.tightestWindow()
    Column(verticalArrangement = Arrangement.spacedBy(QdTheme.spacing.xs)) {
        if (folded && tightest != null) {
            CompactUsageWindowRow(
                window = tightest,
                displayMode = usageDisplayMode,
                showProjectedUsage = showProjectedUsage,
                label = windows.foldedLabel(tightest),
            )
        } else {
            windows.forEach { CompactUsageWindowRow(it, usageDisplayMode, showProjectedUsage) }
            resetSummary?.let { CodexResetCreditsBlock(it) }
        }
    }
}

internal class FoldToggleUi(val folded: Boolean, val hiddenCount: Int)

/**
 * "+N ˅" while [fold] hides rows, "˄" once the account is open. [reserveSpace] keeps the slot
 * for an account that cannot fold, so One line rows stay aligned.
 */
@Composable
internal fun FoldIndicator(fold: FoldToggleUi?, reserveSpace: Boolean) {
    if (fold == null && !reserveSpace) return
    val colors = QdTheme.colors
    // The whole account toggles the fold; this only shows the state.
    Row(
        modifier = if (reserveSpace) Modifier.width(FoldToggleWidth) else Modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QdTheme.spacing.xxs, Alignment.End),
    ) {
        if (fold != null) {
            if (fold.folded) {
                Text("+${fold.hiddenCount}", style = QdTheme.typography.numeric, color = colors.textTertiary, maxLines = 1)
                QdChevronDownIcon(tint = colors.textTertiary, size = 14.dp)
            } else {
                QdChevronUpIcon(tint = colors.textTertiary, size = 14.dp)
            }
        }
    }
}

/**
 * Fold state, then refresh and menu (revealed on hover when asked). While rearranging, the
 * move controls take their place, so a narrow row still has room for the account.
 */
@Composable
private fun AccountTrailingControls(
    state: AccountUiState,
    fold: FoldToggleUi?,
    reserveFoldSpace: Boolean,
    actionsVisible: Boolean,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onReopenLogin: () -> Unit,
    onRequestDelete: () -> Unit,
    reorder: AccountReorderControls?,
) {
    val spacing = QdTheme.spacing
    Row(
        modifier = Modifier.heightIn(min = CompactActionSize),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (reorder != null) {
            AccountReorderButtons(reorder, compact = true)
        } else {
            FoldIndicator(fold, reserveFoldSpace)
            Row(
                modifier = Modifier.alpha(if (actionsVisible) 1f else 0f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                AccountActionButtons(
                    state = state,
                    diameter = CompactActionSize,
                    iconSize = 14.dp,
                    menuExpanded = menuExpanded,
                    onMenuExpandedChange = onMenuExpandedChange,
                    onRefresh = onRefresh,
                    onReopenLogin = onReopenLogin,
                    onRequestDelete = onRequestDelete,
                )
            }
        }
    }
}

@Composable
private fun AccountActionButtons(
    state: AccountUiState,
    diameter: Dp,
    iconSize: Dp,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onReopenLogin: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    QdIconButton(onClick = onRefresh, enabled = !state.busy, diameter = diameter) {
        val rotation = rememberRefreshRotation(active = state.busy)
        QdRefreshIcon(
            modifier = Modifier.rotate(rotation),
            tint = colors.textSecondary,
            size = iconSize,
        )
    }

    Box {
        QdIconButton(onClick = { onMenuExpandedChange(true) }, diameter = diameter) {
            QdMoreIcon(tint = colors.textSecondary, size = iconSize)
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { onMenuExpandedChange(false) },
        ) {
            if (state.loginStart != null || state.deviceLogin != null) {
                DropdownMenuItem(onClick = {
                    onMenuExpandedChange(false)
                    onReopenLogin()
                }) {
                    Text("Reopen authorization page", style = typo.bodyMedium)
                }
            }
            DropdownMenuItem(onClick = {
                onMenuExpandedChange(false)
                onRequestDelete()
            }) {
                Text("Remove account", color = colors.danger, style = typo.bodyMedium)
            }
        }
    }
}

@Composable
private fun AccountReorderButtons(reorder: AccountReorderControls, compact: Boolean = false) {
    val colors = QdTheme.colors
    val diameter = if (compact) CompactActionSize else 28.dp
    val iconSize = if (compact) 14.dp else 16.dp
    QdIconButton(
        onClick = reorder.onMoveUp,
        enabled = reorder.canMoveUp,
        diameter = diameter,
    ) {
        QdChevronUpIcon(
            tint = if (reorder.canMoveUp) colors.textSecondary else colors.textTertiary.copy(alpha = 0.35f),
            size = iconSize,
        )
    }
    QdIconButton(
        onClick = reorder.onMoveDown,
        enabled = reorder.canMoveDown,
        diameter = diameter,
    ) {
        QdChevronDownIcon(
            tint = if (reorder.canMoveDown) colors.textSecondary else colors.textTertiary.copy(alpha = 0.35f),
            size = iconSize,
        )
    }
    QdReorderHandle(modifier = reorder.handleModifier, size = if (compact) CompactActionSize else 32.dp)
}

/** Device-code and callback sign-in steps, then the account's status, the same in every layout. */
@Composable
private fun AccountSignInDetails(
    state: AccountUiState,
    callbackInput: String,
    onCallbackChange: (String) -> Unit,
    onCompleteLogin: () -> Unit,
    onReopenLogin: () -> Unit,
    onSignInAgain: () -> Unit,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing

    state.deviceLogin?.let { device ->
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(
                "Code ${device.userCode}",
                style = typo.titleMedium,
                color = colors.textPrimary,
            )
            Text(
                device.authorizationUrl,
                style = typo.caption,
                color = colors.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!state.busy) {
                QdButton(
                    text = "Open auth page",
                    onClick = onReopenLogin,
                    size = QdButtonSize.Small,
                )
            }
        }
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

@Composable
private fun AccountEmailTitle(
    value: String,
    privacyMode: EmailPrivacyMode,
    style: TextStyle = QdTheme.typography.titleMedium,
    color: Color = QdTheme.colors.textPrimary,
) {
    val colors = QdTheme.colors
    if (privacyMode == EmailPrivacyMode.Visible || !value.canMaskEmail()) {
        Text(
            value,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }

    val fullStop = value.maskFullStop()
    Box(modifier = Modifier.clipToBounds()) {
        Text(
            value,
            style = style,
            color = color,
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
    val progress = window.displayRatio(displayMode)
    val progressFill = window.progressFill(displayMode)
    val pct = (progress * 100).toInt()
    val projectedPct = if (showProjectedUsage) {
        window.projectedUsedRatio()?.let { (it * 100).toInt() }
    } else {
        null
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                window.infoLabel(),
                style = typo.caption,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                QdTimestamp(window.resetsAt) {
                    Text(
                        window.remainingTime(),
                        style = typo.numeric,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    " · ",
                    style = typo.numeric,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when (displayMode) {
                        UsageDisplayMode.Used -> "$pct% used"
                        UsageDisplayMode.Remaining -> "$pct% left"
                    },
                    style = typo.numeric.copy(fontWeight = FontWeight.SemiBold),
                    color = progressFill,
                    maxLines = 1,
                )
                if (projectedPct != null) {
                    Text(
                        " est $projectedPct%",
                        style = typo.numeric,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        QdProgressBar(
            progress = progress.toFloat(),
            fill = progressFill,
        )
    }
}

/**
 * One line per window for the denser layouts: label, a short bar, the value, and the reset.
 * [labelWidth] fixes the label column so One line rows line up; otherwise it takes the slack.
 */
@Composable
private fun CompactUsageWindowRow(
    window: UsageWindow,
    displayMode: UsageDisplayMode,
    showProjectedUsage: Boolean,
    label: String = window.infoLabel(),
    labelWidth: Dp? = null,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val progress = window.displayRatio(displayMode)
    val pct = (progress * 100).toInt()
    val projectedPct = if (showProjectedUsage) {
        window.projectedUsedRatio()?.let { (it * 100).toInt() }
    } else {
        null
    }
    val quiet = window.remainingRatio >= QUIET_REMAINING_RATIO
    Row(
        modifier = if (labelWidth == null) Modifier.fillMaxWidth() else Modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(QdTheme.spacing.md),
    ) {
        Row(
            modifier = if (labelWidth == null) Modifier.weight(1f) else Modifier.width(labelWidth),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = typo.caption,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (projectedPct != null) {
                Text(
                    " est $projectedPct%",
                    style = typo.numeric,
                    color = colors.textTertiary,
                    maxLines = 1,
                )
            }
        }
        QdProgressBar(
            progress = progress.toFloat(),
            modifier = Modifier.width(CompactBarWidth),
            fill = window.progressFill(displayMode),
        )
        Text(
            when (displayMode) {
                UsageDisplayMode.Used -> "$pct% used"
                UsageDisplayMode.Remaining -> "$pct% left"
            },
            style = typo.numeric.copy(fontWeight = if (quiet) FontWeight.Medium else FontWeight.SemiBold),
            color = window.compactValueColor(displayMode),
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(CompactPercentWidth),
        )
        QdTimestamp(window.resetsAt) {
            Text(
                if (window.resetsAt == null) "" else window.remainingTime(),
                style = typo.numeric,
                color = colors.textTertiary,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.width(CompactResetWidth),
            )
        }
    }
}

@Composable
private fun CodexResetCreditsBlock(summary: CodexResetSummary) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val accent = if (summary.expiringSoon) colors.warning else colors.success
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Reset",
                style = typo.caption,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (summary.credits.isEmpty()) summary.compactLabel() else summary.availabilityLabel(),
                style = typo.numeric.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
                maxLines = 1,
            )
        }
        summary.credits.take(4).forEach { credit ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    credit.title ?: "Banked reset",
                    style = typo.caption,
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                QdTimestamp(credit.expiresAt) {
                    Text(
                        credit.expiryLabel(),
                        style = typo.numeric,
                        color = if (credit.isExpiringSoon()) colors.warning else colors.textTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
        val shown = minOf(4, summary.credits.size)
        val extra = summary.availableCount - shown
        if (extra > 0) {
            Text(
                "+$extra more",
                style = typo.caption,
                color = colors.textTertiary,
            )
        }
    }
}

@Composable
private fun InlineStatus(text: String) {
    val colors = QdTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(QdTheme.shapes.sm)
            .background(colors.surfaceMuted.copy(alpha = 0.55f))
            .padding(horizontal = QdTheme.spacing.sm, vertical = QdTheme.spacing.xs),
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
    val deviceLogin = state.deviceLogin
    val status = when {
        deviceLogin != null -> AccountStatusUi(
            title = "Waiting for sign-in",
            message = state.message ?: "Approve access in the browser. If asked, enter ${deviceLogin.userCode}.",
            bg = colors.surfaceMuted,
            fg = colors.textSecondary,
            action = AccountStatusAction.ReopenLogin,
            actionLabel = "Open auth page",
        )
        state.busy -> AccountStatusUi(
            title = "Syncing",
            message = state.message ?: "Refreshing account data...",
            bg = colors.surfaceMuted,
            fg = colors.textSecondary,
        )
        state.loginStart != null -> AccountStatusUi(
            title = "Waiting for sign-in",
            message = state.message ?: "Finish authorization in the browser or paste the callback URL.",
            bg = colors.surfaceMuted,
            fg = colors.textSecondary,
            action = AccountStatusAction.ReopenLogin,
            actionLabel = "Open auth page",
        )
        state.authState == AuthState.NotConfigured ||
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
            .clip(QdTheme.shapes.sm)
            .background(status.bg)
            .padding(horizontal = spacing.sm, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        QdAlertIcon(tint = status.fg, size = 14.dp)
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
        variant = QdButtonVariant.Secondary,
        leading = { QdPlusIcon(tint = QdTheme.colors.textPrimary, size = 16.dp) },
    )
}

@Composable
private fun ProviderPickerContent(onConnectComputer: (() -> Unit)?, onSelect: (ProviderId) -> Unit) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    val providers = availableProviders()
    val description = "Choose a provider. The columns show which sign-in methods work on this device."
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
            Text("Add account", style = typo.titleLarge, color = colors.textPrimary)
            Text(
                description,
                style = typo.bodyMedium,
                color = colors.textSecondary,
            )
        }
        onConnectComputer?.let { QdButton("Connect my computer", it, variant = QdButtonVariant.Secondary) }
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            SignInSupportHeaderRow()
            providers.forEach { provider ->
                ProviderOptionRow(
                    provider = provider,
                    oauthAvailable = provider.oauthAvailable(),
                    cliImportAvailable = provider.cliImportAvailable(),
                    onClick = { onSelect(provider) },
                )
            }
        }
    }
}

/** Column header for the picker rows: labels the two sign-in support cells on the right. */
@Composable
private fun SignInSupportHeaderRow() {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        // Spacer matches the provider avatar in each row so "Provider" lines up with names.
        Spacer(modifier = Modifier.size(ProviderPickerAvatarSize))
        Text(
            text = "Provider",
            style = typo.caption,
            color = colors.textTertiary,
            modifier = Modifier.weight(1f),
        )
        SignInSupportColumnLabel(text = "OAuth", width = SignInSupportColumnWidth)
        SignInSupportColumnLabel(text = "Import from CLI", width = SignInSupportColumnWidth)
    }
}

/** Centered header label sized to its support column so cells stay aligned with the rows below. */
@Composable
private fun SignInSupportColumnLabel(text: String, width: Dp) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    Text(
        text = text,
        style = typo.caption,
        color = colors.textTertiary,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width),
    )
}

/** One support cell: check when the sign-in method works on this device, dash when it does not. */
@Composable
private fun SignInSupportCell(supported: Boolean, width: Dp) {
    val colors = QdTheme.colors
    Box(
        modifier = Modifier.width(width),
        contentAlignment = Alignment.Center,
    ) {
        if (supported) {
            QdCheckIcon(tint = colors.success, size = SignInSupportIconSize)
        } else {
            QdMinusIcon(
                tint = colors.textTertiary.copy(alpha = 0.65f),
                size = SignInSupportIconSize,
            )
        }
    }
}

@Composable
private fun AuthMethodPickerContent(
    title: String,
    description: String,
    oauthTitle: String,
    oauthSubtitle: String,
    importTitle: String,
    importSubtitle: String,
    onSelectOauth: () -> Unit,
    onSelectCliImport: () -> Unit,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(title, style = typo.titleLarge, color = colors.textPrimary)
            Text(
                description,
                style = typo.bodyMedium,
                color = colors.textSecondary,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            AuthMethodOptionRow(
                title = oauthTitle,
                subtitle = oauthSubtitle,
                onClick = onSelectOauth,
            )
            AuthMethodOptionRow(
                title = importTitle,
                subtitle = importSubtitle,
                onClick = onSelectCliImport,
            )
        }
    }
}

@Composable
private fun AuthMethodOptionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
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
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = typo.titleMedium, color = colors.textPrimary)
            Text(subtitle, style = typo.caption, color = colors.textTertiary)
        }
        QdChevronRightIcon(tint = colors.textTertiary)
    }
}

@Composable
private fun ProviderOptionRow(
    provider: ProviderId,
    oauthAvailable: Boolean,
    cliImportAvailable: Boolean,
    onClick: () -> Unit,
) {
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
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        QdProviderAvatar(provider, size = ProviderPickerAvatarSize)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(provider.displayName, style = typo.titleMedium, color = colors.textPrimary)
            Text(provider.subtitle(), style = typo.caption, color = colors.textTertiary)
        }
        SignInSupportCell(supported = oauthAvailable, width = SignInSupportColumnWidth)
        SignInSupportCell(supported = cliImportAvailable, width = SignInSupportColumnWidth)
    }
}

/** Avatar size in the picker rows; the header spacer matches it to keep columns aligned. */
private val ProviderPickerAvatarSize = 28.dp

/** Width of each sign-in support column, shared by the header labels and the row cells. */
private val SignInSupportColumnWidth = 54.dp

private val SignInSupportIconSize = 15.dp

private fun AccountUiState.shouldShowAccount(): Boolean {
    return added ||
        (authState != AuthState.NotConfigured && authState != AuthState.Unknown) ||
        snapshot != null ||
        loginStart != null ||
        deviceLogin != null ||
        busy
}

private fun AccountUiState.canRefreshUsage(): Boolean {
    return added && (authState == AuthState.LoggedIn || authState == AuthState.TokenExpired)
}

internal fun AccountUiState.accountTitle(emailPrivacyMode: EmailPrivacyMode = EmailPrivacyMode.Visible): String {
    val email = snapshot?.accountEmail?.takeIf { it.isNotBlank() }
    val displayEmail = if (emailPrivacyMode == EmailPrivacyMode.Masked) email?.maskEmailPlain() else email
    return if (displayEmail == null) providerId.displayName else "${providerId.displayName} ($displayEmail)"
}

private fun AccountUiState.accountSubtitle(): String {
    return snapshot?.accountEmail?.takeIf { it.isNotBlank() }
        ?: accountKey.accountId.takeUnless { accountKey.isPending || it == "default" }
        ?: "Account email pending"
}

private fun providerNamesLabel(): String {
    val names = availableProviders().map { it.displayName }
    return when {
        names.isEmpty() -> "provider"
        names.size == 1 -> names.first()
        names.size == 2 -> "${names[0]} or ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + ", or " + names.last()
    }
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
    ProviderId.GROK -> "Grok Build / SuperGrok credits"
    ProviderId.CURSOR -> "Cursor plan / on-demand usage (app or CLI)"
    ProviderId.ANTIGRAVITY -> "Google Antigravity quota windows"
    ProviderId.DEVIN -> "Devin daily / weekly quota (CLI import)"
    ProviderId.DROID -> "Factory Droid quota windows"
    ProviderId.AMP -> "Amp Free, tier usage, and credits"
}

private fun UsageWindow.displayRatio(mode: UsageDisplayMode): Double = when (mode) {
    UsageDisplayMode.Used -> usedRatio
    UsageDisplayMode.Remaining -> remainingRatio
}

private fun UsageWindow.riskRatio(mode: UsageDisplayMode): Double = when (mode) {
    UsageDisplayMode.Used -> usedRatio
    UsageDisplayMode.Remaining -> 1.0 - remainingRatio
}

@Composable
private fun UsageWindow.progressFill(mode: UsageDisplayMode): Color {
    val colors = QdTheme.colors
    val riskRatio = riskRatio(mode)
    return when {
        riskRatio >= 0.9 -> colors.danger
        riskRatio >= 0.7 -> colors.warning
        else -> colors.meter
    }
}

/** Value color in compact rows: warnings as on the bar, gray when nearly untouched, bright otherwise. */
@Composable
private fun UsageWindow.compactValueColor(mode: UsageDisplayMode): Color {
    val colors = QdTheme.colors
    val riskRatio = riskRatio(mode)
    return when {
        riskRatio >= 0.9 -> colors.danger
        riskRatio >= 0.7 -> colors.warning
        remainingRatio >= QUIET_REMAINING_RATIO -> colors.textTertiary
        else -> colors.textPrimary
    }
}

private fun UsageWindow.remainingPercent(): Int = (remainingRatio * 100).toInt()

/** The window with the least left, and of those the one that resets first. */
internal fun List<UsageWindow>.tightestWindow(): UsageWindow? = minWithOrNull(
    compareBy<UsageWindow> { it.remainingPercent() }.thenBy { it.resetsAt ?: Instant.DISTANT_FUTURE },
)

internal fun List<UsageWindow>.allShareTightest(): Boolean =
    size > 1 && all { it.remainingPercent() == first().remainingPercent() }

/** Label of a folded account's only row; equal windows read as one. */
internal fun List<UsageWindow>.foldedLabel(tightest: UsageWindow): String =
    if (allShareTightest()) "All $size windows" else tightest.infoLabel()

/** What an account without usage windows shows in their place. */
private fun AccountUiState.emptyStatusText(): String {
    val statusMessage = message?.takeIf { it.isNotBlank() }
    return when {
        statusMessage != null -> statusMessage
        deviceLogin != null && busy ->
            "Approve access in the browser, then return here."
        deviceLogin != null ->
            "xAI sign-in is waiting. Reopen the authorization page or sign in again."
        loginStart == null ->
            "No usage data yet - tap refresh to fetch the latest."
        busy ->
            "Authorization page opened - waiting for the automatic callback."
        else ->
            "Automatic callback not received. Use the manual fallback below."
    }
}

private fun UsageWindow.infoLabel(): String = when (id) {
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

internal fun UsageWindow.remainingTime(): String {
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
