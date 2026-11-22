package saien.quotadog.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import saien.quotadog.*
import saien.quotadog.app.components.*
import saien.quotadog.app.theme.QdTheme
import saien.quotadog.hostsync.*
import kotlin.math.roundToInt

/** The title and the Name sort observe the same device-local nickname, including after an edit. */
internal class PhoneAccountNames(private val prefs: AppPreferences) {
    private val aliases = mutableMapOf<DashboardKey, MutableState<String?>>()
    private fun entry(key: DashboardKey) = aliases.getOrPut(key) { mutableStateOf(prefs.phoneAlias(key.aliasScope())) }
    fun alias(key: DashboardKey): String? = entry(key).value
    fun name(row: DashboardAccount): String = alias(row.key) ?: when (row) {
        is DashboardAccount.Local -> if (row.account.accountKey.isPending) "Sign-in" else {
            // Only a workspace-only local balance can take the workspace's name. A mixed
            // Amp account is not that workspace, and incoming grants never carry the name.
            row.localObservation?.takeIf { it.providerId == ProviderId.AMP }?.phoneQuotaDisplay()
                ?.workspaceOnlyName?.takeIf { it.isNotBlank() && '@' !in it } ?: row.account.accountKey.localSourceLabel()
        }
        is DashboardAccount.Remote -> row.source.label
    }
    fun rename(key: DashboardKey, input: String) {
        prefs.setPhoneAlias(key.aliasScope(), input)
        entry(key).value = prefs.phoneAlias(key.aliasScope())
    }
}

/** Both phone apps use this row, including before pairing. A refresh never opens the sheet. */
@Composable
internal fun PhoneAccountRow(
    row: DashboardAccount, names: PhoneAccountNames, mode: UsageDisplayMode, projected: Boolean,
    privacy: EmailPrivacyMode, onRefresh: (AccountUiState) -> Unit, modifier: Modifier = Modifier,
    now: Instant = Clock.System.now(), reorder: @Composable () -> Unit = {}, onRemove: (AccountKey) -> Unit,
    signIn: @Composable (AccountUiState) -> Unit,
) {
    val local = row as? DashboardAccount.Local
    val peer = row.readingSource
    var open by remember(row.key) { mutableStateOf(false) }
    val name = names.name(row)
    val target = row.refreshTarget(retryStandalone = true)
    val reading = remember(row) { row.phoneReading() }
    val refresh = { if (!row.busy) target?.run(onRefresh); Unit }
    val pendingLogin = local?.account?.accountKey?.isPending == true
    val meta = buildList {
        if (row.busy && !pendingLogin) add("Refreshing…")
        when (local?.account?.authState) {
            AuthState.RateLimited -> add("Rate limited on this phone")
            AuthState.Unauthorized, AuthState.RequiresRelogin, AuthState.NotConfigured -> add("Sign-in needed on this phone")
            AuthState.Error -> add(if (pendingLogin) "Sign-in failed" else "Local refresh failed")
            else -> Unit
        }
        if (peer != null) {
            if (!peer.peer.online) add("Computer offline")
            peer.problem()?.let(::add)
            if (reading != null) {
                val stamp = reading.collected.shortTime(now)
                add(if (peer.peer.online) "${peer.peer.label} · $stamp" else "Cached $stamp")
            }
            if (peer.card.access == Access.View) add(if (local == null) "View only" else "Computer view only")
            if (!peer.peer.online && target is DashboardRefresh.Here) add("Phone can refresh")
        } else if (local != null && reading != null) {
            if (local.copies.any { !it.peer.online }) add("Computer offline")
            if (local.localObservation?.stale == true) add("Cached ${reading.collected.shortTime(now)}")
            else if (local.copies.isNotEmpty()) add("This phone · ${reading.collected.shortTime(now)}")
        }
    }.joinToString(" · ")
    val clockNote = when {
        peer?.uncertainOnly == true -> "Unverified source time · No trusted reading yet"
        peer?.clockWarning == true || local?.localClockWarning == true || local?.copies.orEmpty().any { it.clockWarning } ->
            "Device clocks differ · Check both devices"
        else -> null
    }
    Box(modifier.background(QdTheme.colors.backgroundElevated)) {
        // Sibling hit targets keep all text accessible and row taps separate from the refresh
        // and reorder buttons. No nested clickable row that can also send a refresh.
        Box(Modifier.matchParentSize().semantics { contentDescription = "Open ${row.provider.displayName} details · $name" }
            .clickable(role = Role.Button) { open = true })
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 3.dp, bottom = 13.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                PhoneTitle(row.provider, name, Modifier.weight(1f), chevron = true)
                reorder()
                if (target != null) QdIconButton(refresh, diameter = 48.dp, enabled = !row.busy,
                    modifier = Modifier.semantics { contentDescription = "${target.label} · ${row.provider.displayName} · $name" }) {
                    QdRefreshIcon(size = 17.dp, tint = QdTheme.colors.primary)
                }
            }
            val amountFirst = reading != null && reading.amounts.isNotEmpty() && reading.windows.none { it.used != null || it.unlimited }
            if (pendingLogin) PhoneNote("Open details for sign-in")
            else if (amountFirst) PhoneAmount(reading.amounts.first())
            else if (reading == null || reading.summary.isEmpty()) PhoneNote(if (reading == null) "Waiting for first reading" else "Quota not reported")
            else reading.summary.forEach { PhoneMeasure(it, mode, now) }
            if (reading != null && reading.moreCritical > 0) PhoneNote("${reading.moreCritical} more ${if (reading.moreCritical == 1) "window" else "windows"} near limit")
            if (meta.isNotEmpty()) PhoneNote(meta)
            clockNote?.let { PhoneNote(it) }
        }
    }

    if (open) PhoneDetailSheet(onClose = { open = false }, header = {
        PhoneTitle(row.provider, name, Modifier.weight(1f))
    }) {
        val email = local?.account?.displayEmail()?.let { if (privacy == EmailPrivacyMode.Masked) it.maskEmailPlain() else it }
        val identity = listOfNotNull(email, reading?.plan).joinToString(" · ")
        if (identity.isNotEmpty()) PhoneNote(identity)
        PhoneSection("All quota")
        if (reading == null) PhoneNote("Waiting for first reading") else {
            reading.windows.forEach { w ->
                PhoneMeasure(w, mode, now, hideReset = reading.commonReset != null, detail = true)
                if (projected && clockNote == null) {
                    reading.estimatedAtReset(w, now)?.let {
                        PhoneNote("Estimated at reset: ${(it * 100).roundToInt()}% used")
                    }
                }
            }
            reading.commonReset?.let { PhoneNote("All windows reset · ${it.exactTime()}") }
            reading.amounts.forEach { PhoneAmount(it) }
            if (reading.windows.isEmpty() && reading.amounts.isEmpty()) PhoneNote("Quota not reported")
            PhoneNote("Collected by ${peer?.peer?.label ?: "this phone"}")
            PhoneNote("Collected · ${reading.collected.exactTime()}")
        }
        clockNote?.let { PhoneNote(it) }
        PhoneSection("Refresh sources")
        if (local != null) PhoneFact("This phone", when {
            local.account.authState == AuthState.RateLimited -> "Rate limited"
            target is DashboardRefresh.Here -> "Can refresh"
            local.account.authState in setOf(AuthState.Unauthorized, AuthState.RequiresRelogin, AuthState.TokenExpired, AuthState.NotConfigured) -> "Sign-in needed"
            else -> "Unavailable"
        })
        val sources = when (row) {
            is DashboardAccount.Local -> row.copies
            is DashboardAccount.Remote -> listOf(row.source)
        }
        for (source in sources) {
            PhoneFact(source.peer.label, listOfNotNull("Offline".takeUnless { source.peer.online },
                if (source.card.access == Access.View) "View only" else if (source.peer.online) "Can refresh" else null).joinToString(" · "))
            source.problem()?.let { PhoneNote(it) }
        }
        if (target != null) QdButton(if (row.busy) "Refreshing…" else target.label, refresh,
            Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !row.busy)
        else if (local?.account?.authState == AuthState.RateLimited) PhoneNote("No request sent to another source.")
        if (local != null) signIn(local.account)
        if (local?.account?.accountKey?.isPending != true) AliasEditor(names.alias(row.key)) {
            names.rename(row.key, it)
        }
        if (local != null) QdButton("Remove account from this device", { open = false; onRemove(local.account.accountKey) },
            variant = QdButtonVariant.Ghost, size = QdButtonSize.Small)
    }
}

private fun RemoteSource.problem() = when (card.status) {
    SourceStatus.Ready -> null
    SourceStatus.LoginRequired -> "Sign-in needed on computer"
    SourceStatus.RateLimited -> "Computer rate limited"
    SourceStatus.Failed -> "Refresh failed on computer"
}

private fun Instant.exactTime() = exactLocalTimeLabel().substringBefore('\n')
private fun Instant.shortTime(now: Instant): String {
    val stamp = exactTime()
    return if (stamp.take(10) == now.exactTime().take(10)) stamp.substring(11, 16) else stamp.take(16)
}

@Composable
private fun PhoneTitle(provider: ProviderId, name: String, modifier: Modifier, chevron: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        QdProviderAvatar(provider, size = 24.dp)
        Text("${provider.displayName} · $name", Modifier.weight(1f, fill = false), color = QdTheme.colors.textPrimary,
            style = QdTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (chevron) QdChevronRightIcon(size = 12.dp, tint = QdTheme.colors.textSecondary)
    }
}

@Composable
private fun PhoneMeasure(w: PhoneWindow, mode: UsageDisplayMode, now: Instant, hideReset: Boolean = false, detail: Boolean = false) {
    val colors = QdTheme.colors
    val used = w.used
    val value = used?.let { if (mode == UsageDisplayMode.Used) it else 1 - it }
    val amount = when {
        w.unlimited -> "Unlimited"
        value == null -> "Not reported"
        else -> "${(value * 100).roundToInt()}% ${if (mode == UsageDisplayMode.Used) "used" else "left"}"
    }
    val reset = w.reset?.let { end ->
        val minutes = ((end - now).inWholeSeconds + 30) / 60
        when {
            end <= now -> "Reset due"
            minutes < 60 -> "Reset in ${minutes.coerceAtLeast(1)}m"
            minutes < 1440 -> "Reset in ${(minutes + 30) / 60}h"
            else -> "Reset in ${(minutes + 720) / 1440}d"
        }
    }
    val fill = when { used == null -> colors.meter; used >= .9 -> colors.danger; used >= .7 -> colors.warning; else -> colors.meter }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val largeText = maxWidth / LocalDensity.current.fontScale < 275.dp
        val label: @Composable (Modifier) -> Unit = { modifier ->
            Text(w.label, modifier, style = QdTheme.typography.bodyMedium, color = colors.textSecondary,
                maxLines = if (detail) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (largeText) label(Modifier)
            Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                if (!largeText) label(Modifier.weight(1.3f))
                if (value != null) QdProgressBar(value.toFloat(), Modifier.weight(1f), fill = fill)
                else Spacer(Modifier.weight(.25f))
                Text(amount, Modifier.widthIn(min = 62.dp), textAlign = TextAlign.End,
                    style = QdTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold),
                    color = if ((used ?: 0.0) >= .9) colors.danger else colors.textPrimary)
                if (!hideReset) {
                    val time: @Composable () -> Unit = {
                        Text(reset ?: "—", Modifier.widthIn(min = 70.dp), textAlign = TextAlign.End,
                            style = QdTheme.typography.caption, color = colors.textSecondary)
                    }
                    if (detail) QdTimestamp(w.reset, time) else time()
                }
            }
        }
    }
}

@Composable
private fun PhoneAmount(line: String) {
    if (": " in line) PhoneFact(line.substringBefore(": "), line.substringAfter(": ")) else PhoneNote(line)
}

@Composable
private fun PhoneFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = QdTheme.typography.bodyMedium, color = QdTheme.colors.textSecondary)
        Text(value, Modifier.widthIn(max = 185.dp), style = QdTheme.typography.numeric, color = QdTheme.colors.textPrimary, textAlign = TextAlign.End)
    }
}

@Composable
private fun PhoneNote(text: String) = Text(text, style = QdTheme.typography.caption, color = QdTheme.colors.textSecondary)

@Composable
private fun PhoneSection(label: String) {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(QdTheme.colors.border))
    Text(label, style = QdTheme.typography.labelLarge, color = QdTheme.colors.textPrimary)
}

@Composable
private fun PhoneDetailSheet(onClose: () -> Unit, header: @Composable RowScope.() -> Unit, content: @Composable () -> Unit) {
    // A real modal owns focus and Back. The sheet is independent of the dashboard's scroll
    // viewport; a permission change or withdrawal recomposes/removes its owning keyed row.
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            val sheetHeight = maxHeight * .90f
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.fillMaxWidth().weight(1f).clickable(interactionSource = null, indication = null, onClick = onClose))
                Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = sheetHeight)
                    .clip(QdTheme.shapes.sheet).background(QdTheme.colors.backgroundElevated)
                    .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 12.dp)) {
                    Spacer(Modifier.align(Alignment.CenterHorizontally).size(32.dp, 3.dp).clip(QdTheme.shapes.pill).background(QdTheme.colors.borderStrong))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        header()
                        QdIconButton(onClose, Modifier.semantics { contentDescription = "Close details" }, diameter = 48.dp) {
                            QdCloseIcon(size = 18.dp, tint = QdTheme.colors.textSecondary)
                        }
                    }
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
                }
            }
        }
    }
}

@Composable
private fun AliasEditor(alias: String?, save: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(alias) { mutableStateOf(alias.orEmpty()) }
    if (!editing) QdButton(if (alias == null) "Add phone nickname" else "Edit phone nickname", { draft = alias.orEmpty(); editing = true },
        variant = QdButtonVariant.Ghost, size = QdButtonSize.Small)
    else {
        OutlinedTextField(draft, { draft = it.take(48) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nickname on this phone") },
            textStyle = QdTheme.typography.bodyLarge.copy(color = QdTheme.colors.textPrimary))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QdButton("Save nickname", { save(draft); editing = false }, size = QdButtonSize.Small)
            QdButton("Cancel", { editing = false }, variant = QdButtonVariant.Ghost, size = QdButtonSize.Small)
        }
    }
}
