package saien.quotadog.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.compose.resources.stringResource
import quotadog.composeapp.generated.resources.*
import saien.quotadog.*
import saien.quotadog.app.components.*
import saien.quotadog.app.theme.QdTheme
import saien.quotadog.hostsync.*
import kotlin.math.roundToInt

internal data class PhoneAccountTitle(val name: String, val email: String?, val alias: String?, val suffix: String? = null) {
    val label: String get() = if (suffix == null) name else "$name · $suffix"
}

/** Resolve names across the whole dashboard, before filtering, sorting or choosing an action. */
internal class PhoneAccountNames(private val prefs: AppPreferences) {
    private val aliases = mutableMapOf<DashboardKey, MutableState<String?>>()
    private fun entry(key: DashboardKey) = aliases.getOrPut(key) { mutableStateOf(prefs.phoneAlias(key.aliasScope())) }

    @Composable
    fun titles(rows: List<DashboardAccount>, privacy: EmailPrivacyMode): Map<DashboardKey, PhoneAccountTitle> {
        fun String.privateLabel() = if (privacy == EmailPrivacyMode.Masked) maskEmailPlain() else this
        val titles = rows.associate { row ->
            val alias = entry(row.key).value
            val account = (row as? DashboardAccount.Local)?.account
            val email = account?.phoneEmail()?.privateLabel()
            val name = alias?.privateLabel() ?: email ?: when (row) {
                is DashboardAccount.Remote -> stringResource(
                    if (row.source.card.workspace) Res.string.phone_workspace else Res.string.phone_account,
                    row.source.card.ref.take(8).uppercase(),
                )
                is DashboardAccount.Local -> when {
                    row.account.accountKey.isPending -> stringResource(Res.string.phone_sign_in)
                    else -> row.localObservation?.takeIf { it.providerId == ProviderId.AMP }?.phoneQuotaDisplay()
                        ?.workspaceOnlyName?.takeIf { it.isNotBlank() && '@' !in it }
                        ?: stringResource(Res.string.phone_account, row.key.shortCode().take(8))
                }
            }
            row.key to PhoneAccountTitle(name, email, alias)
        }
        val distinct = titles.toMutableMap()
        for (group in rows.groupBy { it.provider to titles.getValue(it.key).name.lowercase() }.values) {
            if (group.size == 1) continue
            val codes = group.associate { it.key to it.key.shortCode() }
            val length = (4..64).first { n -> codes.values.distinctBy { it.take(n) }.size == group.size }
            for ((key, code) in codes) distinct[key] = titles.getValue(key).copy(suffix = "#${code.take(length)}")
        }
        return distinct
    }

    // A current reading can refine the stored email. A disk cache cannot override a newer login.
    private fun AccountUiState.phoneEmail() = snapshot?.takeUnless { it.stale }?.accountEmail?.takeIf(String::isNotBlank)
        ?: loginEmail?.takeIf(String::isNotBlank) ?: displayEmail()

    private fun DashboardKey.shortCode() = sha256(aliasScope().encodeToByteArray()).toHexString().uppercase()

    fun rename(key: DashboardKey, input: String) {
        prefs.setPhoneAlias(key.aliasScope(), input)
        entry(key).value = prefs.phoneAlias(key.aliasScope())
    }
}

/** Both phone apps use this row, including before pairing. A refresh never opens the sheet. */
@Composable
internal fun PhoneAccountRow(
    row: DashboardAccount, title: PhoneAccountTitle, mode: UsageDisplayMode, projected: Boolean,
    onRename: (String) -> Unit, onRefresh: (AccountUiState) -> Unit, modifier: Modifier = Modifier,
    now: Instant = Clock.System.now(), reorder: @Composable () -> Unit = {}, onRemove: (AccountKey) -> Unit,
    signIn: @Composable (AccountUiState) -> Unit,
) {
    val local = row as? DashboardAccount.Local
    val peer = row.readingSource
    var open by remember(row.key) { mutableStateOf(false) }
    val target = row.refreshTarget(retryStandalone = true)
    val reading = remember(row) { row.phoneReading() }
    val refresh = { if (!row.busy) target?.run(onRefresh); Unit }
    val pendingLogin = local?.account?.accountKey?.isPending == true
    val sources = when (row) {
        is DashboardAccount.Local -> row.copies
        is DashboardAccount.Remote -> listOf(row.source)
    }
    val timeout = stringResource(Res.string.phone_refresh_unconfirmed)
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
        val openLabel = stringResource(Res.string.phone_open_details, row.provider.displayName, title.label)
        Box(Modifier.matchParentSize().semantics { contentDescription = openLabel }
            .clickable(role = Role.Button) { open = true })
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 3.dp, bottom = 13.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                PhoneTitle(row.provider, title, Modifier.weight(1f), chevron = true)
                reorder()
                if (target != null) {
                    val action = stringResource(Res.string.phone_refresh_action, target.phoneLabel(), row.provider.displayName, title.label)
                    QdIconButton(refresh, diameter = 48.dp, enabled = !row.busy,
                        modifier = Modifier.semantics { contentDescription = action }) {
                        QdRefreshIcon(size = 17.dp, tint = QdTheme.colors.primary)
                    }
                }
            }
            val amountFirst = reading != null && reading.amounts.isNotEmpty() && reading.windows.none { it.used != null || it.unlimited }
            if (pendingLogin) PhoneNote("Open details for sign-in")
            else if (amountFirst) PhoneAmount(reading.amounts.first())
            else if (reading == null || reading.summary.isEmpty()) PhoneNote(if (reading == null) "Waiting for first reading" else "Quota not reported")
            else reading.summary.forEach { PhoneMeasure(it, mode, now) }
            if (reading != null && reading.moreCritical > 0) PhoneNote("${reading.moreCritical} more ${if (reading.moreCritical == 1) "window" else "windows"} near limit")
            if (meta.isNotEmpty()) PhoneNote(meta)
            if (sources.any { it.card.ref in it.peer.unconfirmedRefs }) PhoneNote(timeout)
            clockNote?.let { PhoneNote(it) }
        }
    }

    if (open) QdPhoneSheet(stringResource(Res.string.close_details), onClose = { open = false }, header = {
        PhoneTitle(row.provider, title, Modifier.weight(1f))
    }) {
        val identity = listOfNotNull(title.email?.takeUnless { it.equals(title.name, ignoreCase = true) }, reading?.plan).joinToString(" · ")
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
        for (source in sources) {
            PhoneFact(source.peer.label, listOfNotNull("Offline".takeUnless { source.peer.online },
                if (source.card.access == Access.View) "View only" else if (source.peer.online) "Can refresh" else null).joinToString(" · "))
            source.problem()?.let { PhoneNote(it) }
            if (source.card.ref in source.peer.unconfirmedRefs) PhoneNote(timeout)
        }
        if (target != null) QdButton(if (row.busy) "Refreshing…" else target.phoneLabel(), refresh,
            Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !row.busy)
        else if (local?.account?.authState == AuthState.RateLimited) PhoneNote("No request sent to another source.")
        if (local != null) signIn(local.account)
        if (local?.account?.accountKey?.isPending != true) AliasEditor(title.alias, onRename)
        if (local != null) QdButton(stringResource(Res.string.remove_local_account), { open = false; onRemove(local.account.accountKey) },
            variant = QdButtonVariant.Ghost, size = QdButtonSize.Small)
    }
}

@Composable
private fun DashboardRefresh.phoneLabel() = when (this) {
    is DashboardRefresh.Here -> stringResource(Res.string.phone_refresh_here)
    is DashboardRefresh.There -> source.peer.refreshLabel
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
private fun PhoneTitle(provider: ProviderId, title: PhoneAccountTitle, modifier: Modifier, chevron: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        QdProviderAvatar(provider, size = 24.dp)
        Text("${provider.displayName} · ${title.name}", Modifier.weight(1f, fill = false), color = QdTheme.colors.textPrimary,
            style = QdTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        title.suffix?.let { Text(it, style = QdTheme.typography.caption, color = QdTheme.colors.textSecondary) }
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
private fun AliasEditor(alias: String?, save: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(alias) { mutableStateOf(alias.orEmpty()) }
    if (!editing) QdButton(stringResource(if (alias == null) Res.string.nickname_add else Res.string.nickname_edit), { draft = alias.orEmpty(); editing = true },
        variant = QdButtonVariant.Ghost, size = QdButtonSize.Small)
    else {
        OutlinedTextField(draft, { draft = it.take(48) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(Res.string.nickname_label)) },
            textStyle = QdTheme.typography.bodyLarge.copy(color = QdTheme.colors.textPrimary))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QdButton(stringResource(Res.string.nickname_save), { save(draft); editing = false }, size = QdButtonSize.Small)
            QdButton(stringResource(Res.string.cancel), { editing = false }, variant = QdButtonVariant.Ghost, size = QdButtonSize.Small)
        }
    }
}
