package saien.quotadog.app

import kotlinx.datetime.Instant
import saien.quotadog.AccountKey
import saien.quotadog.AccountSortMode
import saien.quotadog.AccountUiState
import saien.quotadog.AuthState
import saien.quotadog.ProviderId
import saien.quotadog.ProviderUsageSnapshot
import saien.quotadog.hostsync.Access
import saien.quotadog.hostsync.QuotaDetails
import saien.quotadog.hostsync.QuotaReading
import saien.quotadog.hostsync.QuotaWindow
import saien.quotadog.hostsync.QuotaWindowKind
import saien.quotadog.hostsync.SharedCard
import saien.quotadog.hostsync.SharedSource
import saien.quotadog.hostsync.SourceStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardSourcesTest {
    private val provider = ProviderId.CLAUDE_CODE
    private val collected = Instant.fromEpochMilliseconds(100)
    private val snapshot = ProviderUsageSnapshot(provider, AuthState.LoggedIn, emptyList(), collected,
        accountEmail = "same@example.invalid", loginTag = "local-generation", quotaIdentity = "verified-local-principal-and-scope")
    private val local = AccountUiState(AccountKey(provider, "local"), added = true, authState = AuthState.LoggedIn, snapshot = snapshot)
    private val observed = QuotaReading(provider, 999, listOf(QuotaWindow(QuotaWindowKind.ClaudeWeekly, 0.8)))
    private val card = SharedCard(SharedSource("ref", provider, Access.View, match = "pair-evidence"), observed)

    private fun peer(id: String, vararg cards: SharedCard, tag: String? = null, skew: Boolean = false,
        online: Boolean = true, busy: Set<String> = emptySet(), refresh: (String) -> Unit = {}) =
        DashboardPeer(id, "Phone $id", "Refresh on phone", online, cards.toList(), busy, { tag }, refresh, skew)

    @Test fun matchUsesOnlyThisPairsEvidenceAndKeepsTheLocalReadingAndClock() {
        val phones = listOf(peer("1", card, tag = card.match, skew = true), peer("2", card, tag = "different-pair-evidence"))
        for (state in listOf(AuthState.LoggedIn, AuthState.RequiresRelogin, AuthState.RateLimited, AuthState.Error)) {
            val rows = dashboardAccounts(listOf(local.copy(authState = state)), phones)
            assertEquals(2, rows.size)
            val merged = assertIs<DashboardAccount.Local>(rows[0])
            assertEquals(collected.toEpochMilliseconds(), merged.collectedAt)
            assertEquals(listOf(DashboardKey.Remote("1", "ref")), merged.copies.map { it.key })
            assertEquals(DashboardKey.Remote("2", "ref"), rows[1].key)
        }
    }

    @Test fun newestWholeReadingWinsWithLocalTieBreakAndDoesNotMoveBackOnReplay() {
        val history = DashboardReadingHistory()
        fun row(here: Long, there: Long) = assertIs<DashboardAccount.Local>(dashboardAccounts(
            listOf(local.copy(snapshot = snapshot.copy(collectedAt = Instant.fromEpochMilliseconds(here)))),
            listOf(peer("1", card.copy(reading = observed.copy(collectedAt = there)), tag = card.match)), 1_000, history).single())
        assertEquals(90L, row(80, 90).displayedRemote?.trustedReading?.collectedAt)
        assertEquals(90L, row(80, 70).collectedAt) // remote replay below its previous observation
        assertNull(row(90, 90).displayedRemote) // tie: use the native collector's whole reading
        assertEquals(110L, row(110, 100).collectedAt)
        assertEquals(110L, row(85, 100).localObservation?.collectedAt?.toEpochMilliseconds())
        assertEquals(120L, row(85, 120).displayedRemote?.trustedReading?.collectedAt)
    }

    @Test fun fiveMinuteToleranceAppliesToComparisonNotTheDistanceBetweenSuccessfulCollections() {
        val now = 500_000L
        val history = DashboardReadingHistory()
        fun row(time: Long, uncertain: QuotaReading? = null, conflict: Boolean = false) = assertIs<DashboardAccount.Local>(dashboardAccounts(
            listOf(local), listOf(peer("1", card.copy(reading = observed.copy(collectedAt = time), uncertain = uncertain), tag = card.match, skew = conflict)),
            now, history).single())
        val oldButLaterThanLocal = row(400_000)
        assertEquals(400_000L, oldButLaterThanLocal.collectedAt)
        val boundary = row(now + 300_000L)
        assertEquals(now + 300_000L, boundary.collectedAt)
        assertFalse(boundary.copies.single().clockWarning)
        val future = row(now + 300_001L)
        assertEquals(boundary.collectedAt, future.collectedAt)
        assertTrue(future.copies.single().clockWarning)
        val protocolWarning = row(now + 300_000L, uncertain = observed.copy(collectedAt = now + 900_000L))
        assertEquals(boundary.collectedAt, protocolWarning.collectedAt)
        assertTrue(protocolWarning.copies.single().clockWarning)
        val skew = row(now + 300_001L, conflict = true)
        assertEquals(boundary.collectedAt, skew.collectedAt)
    }

    @Test fun phoneSummaryKeepsTheTightestAndLongestOtherCriticalWindowFromOneReading() {
        val five = QuotaWindow(QuotaWindowKind.ClaudeFiveHour, .964, resetsAt = 10)
        val weekly = QuotaWindow(QuotaWindowKind.ClaudeWeekly, .963, resetsAt = 20)
        val opus = QuotaWindow(QuotaWindowKind.ClaudeOpusWeekly, .900, resetsAt = 30)
        val sonnet = QuotaWindow(QuotaWindowKind.ClaudeSonnetWeekly, .8999, resetsAt = 40)
        val value = observed.copy(windows = listOf(weekly, sonnet, opus, five))
        val account = local.copy(snapshot = snapshot.copy(planLabel = "Local plan", windows = listOf(
            saien.quotadog.UsageWindow("here", "Local", .99, null))))
        fun reading(observation: QuotaReading, now: Long = 1_000) = dashboardAccounts(listOf(account),
            listOf(peer("1", card.copy(reading = observation), tag = card.match)), now).single().phoneReading()!!
        val hot = reading(value)
        assertEquals(listOf(.964, .900), hot.summary.map { it.used })
        assertEquals(1, hot.moreCritical) // .8999 also rounds to 90%, but is not critical.
        assertEquals(4, hot.windows.size)
        assertNull(hot.plan) // An omitted field is not taken from the other source.
        assertNull(hot.commonReset)
        val quiet = reading(value.copy(windows = listOf(five.copy(used = .06), weekly.copy(used = .03, resetsAt = 10))))
        assertEquals(listOf(.06), quiet.summary.map { it.used })
        assertEquals(Instant.fromEpochMilliseconds(10), quiet.commonReset)
        val tied = reading(value.copy(windows = listOf(five.copy(used = 1.0, resetsAt = 11), weekly.copy(used = 1.0, resetsAt = 60), opus)))
        assertEquals(listOf(11L, 60L), tied.summary.map { it.reset?.toEpochMilliseconds() })
        val sample = reading(value.copy(collectedAt = 3_600_000, windows = listOf(five.copy(used = .2, durationSeconds = 18_000, resetsAt = 18_000_000))), now = 14_400_000)
        assertEquals(1.0, sample.estimatedAtReset(sample.windows.single(), Instant.fromEpochMilliseconds(14_400_000)))
        assertNull(sample.estimatedAtReset(sample.windows.single(), Instant.fromEpochMilliseconds(18_000_000)))
    }

    @Test fun phoneResetCreditsOnlyShowDetailsFromTheSourceThatWon() {
        val p = ProviderId.CODEX
        val expires = Instant.fromEpochMilliseconds(10_000)
        val credits = listOf(saien.quotadog.CodexResetCredit("private-id", "available", "Weekly reset", expiresAt = expires),
            saien.quotadog.CodexResetCredit("private-id-2", "available", "Referral reset"))
        val here = local.copy(providerId = p, accountKey = AccountKey(p, "local"), snapshot = snapshot.copy(providerId = p,
            resetCreditsAvailable = 2, resetCredits = credits))
        val localLines = dashboardAccounts(listOf(here), emptyList()).single().phoneReading()!!.amounts
        assertEquals("Resets available: 2", localLines.first())
        assertTrue(localLines[1].startsWith("Weekly reset: ")); assertEquals("Referral reset: No expiry", localLines.last())
        val incoming = SharedCard(card.source.copy(provider = p), QuotaReading(p, 500, emptyList(), QuotaDetails.Codex(2, 1, listOf(42_000, 14_000))))
        val remoteLines = dashboardAccounts(listOf(here), listOf(peer("1", incoming, tag = card.match))).single().phoneReading()!!.amounts
        assertEquals(listOf("Resets available: 2", "Resets eligible: 1"), remoteLines.take(2))
        assertEquals(2, remoteLines.count { it.startsWith("Reset credit expires: ") }); assertTrue(remoteLines.none { "Weekly reset" in it || "Referral reset" in it })
    }

    @Test fun untrustedSampleIsNeverAMergeCandidateAndDoesNotBlockAnEarlierCorrectedSample() {
        val history = DashboardReadingHistory()
        val now = 1_000L
        fun row(cards: List<SharedCard>) = dashboardAccounts(listOf(local), listOf(peer("1", *cards.toTypedArray(), tag = card.match)), now, history)
        val incident = card.copy(reading = null, uncertain = observed.copy(collectedAt = now + 600_000))
        val uncertain = assertIs<DashboardAccount.Local>(row(listOf(incident)).single())
        assertNull(uncertain.displayedRemote)
        assertEquals(collected.toEpochMilliseconds(), uncertain.collectedAt)
        assertTrue(uncertain.copies.single().clockWarning)
        assertEquals(incident.uncertain, uncertain.copies.single().reading)
        assertEquals(observed, assertIs<DashboardAccount.Local>(row(listOf(card)).single()).displayedRemote?.trustedReading)
        assertEquals(1, row(emptyList()).size)
        assertNull(assertIs<DashboardAccount.Local>(row(listOf(card.copy(reading = null))).single()).displayedRemote)
    }

    @Test fun refreshPlansOneTargetPerQuotaRegardlessOfItsDisplayedSource() {
        val sent = mutableListOf<String>()
        val allowed = card.copy(source = card.source.copy(access = Access.Refresh))
        val separate = card.copy(source = card.source.copy(ref = "separate", match = null, access = Access.Refresh))
        val phones = listOf(peer("view", card, tag = card.match), peer("offline", allowed, tag = card.match, online = false),
            peer("1", allowed, separate, tag = card.match) { sent += "1:$it" }, peer("2", allowed, tag = card.match) { sent += "2:$it" })
        for ((status, expected) in listOf(AuthState.LoggedIn to "here", AuthState.TokenExpired to "here", AuthState.Error to "here",
            AuthState.RequiresRelogin to "1:ref", AuthState.Unauthorized to "1:ref", AuthState.NotConfigured to "1:ref",
            AuthState.RateLimited to null, AuthState.Unknown to null)) {
            sent.clear()
            val rows = dashboardAccounts(listOf(local.copy(authState = status)), phones)
            val merged = assertIs<DashboardAccount.Local>(rows.first())
            assertEquals(observed.collectedAt, merged.collectedAt)
            rows.refreshAllTargets().forEach { it.run { sent += "here" } }
            assertEquals(listOfNotNull(expected, "1:separate"), sent)
        }
        val signedIn = assertIs<DashboardAccount.Local>(dashboardAccounts(listOf(local), phones).first())
        assertEquals("1", assertIs<DashboardRefresh.There>(signedIn.refreshTarget(collectors = emptyList())).source.peer.id)
        val signInNeeded = local.copy(authState = AuthState.RequiresRelogin)
        for (unavailable in listOf(peer("view", card, tag = card.match), peer("offline", allowed, tag = card.match, online = false),
            peer("limited", allowed.copy(source = allowed.source.copy(status = SourceStatus.RateLimited)), tag = card.match))) {
            assertNull(dashboardAccounts(listOf(signInNeeded), listOf(unavailable)).single().refreshTarget())
        }
        val busyPeer = peer("already-querying", allowed, tag = card.match, busy = setOf(allowed.ref))
        assertEquals(emptyList(), dashboardAccounts(listOf(signInNeeded), phones.takeLast(1) + busyPeer).refreshAllTargets())
        assertEquals(emptyList(), dashboardAccounts(listOf(signInNeeded.copy(busy = true)), phones.takeLast(1)).refreshAllTargets())

        val failed = local.copy(authState = AuthState.Error)
        val pending = failed.copy(accountKey = AccountKey.pending(provider, "sign-in"))
        val standalone = dashboardAccounts(listOf(failed, pending), emptyList())
        assertEquals(listOf(DashboardRefresh.Here(failed)), standalone.refreshAllTargets(retryStandalone = true))
        assertEquals(emptyList(), standalone.refreshAllTargets()) // Desktop's batch policy stays unchanged.
    }

    @Test fun noGuessFromCachedSnapshotsMissingTagsDuplicateCandidatesOrProviderName() {
        val phone = peer("1", card, tag = card.match)
        for (unproven in listOf(local.copy(snapshot = snapshot.copy(stale = true)), local.copy(snapshot = snapshot.copy(loginTag = null)),
            local.copy(snapshot = snapshot.copy(quotaIdentity = null)),
            local.copy(added = false), local.copy(accountKey = AccountKey.pending(provider, "pending")))) {
            assertEquals(2, dashboardAccounts(listOf(unproven), listOf(phone)).size)
        }
        assertEquals(2, dashboardAccounts(listOf(local), listOf(peer("1", card, tag = null))).size)
        val different = card.copy(source = card.source.copy(provider = ProviderId.CODEX), reading = null)
        assertEquals(2, dashboardAccounts(listOf(local), listOf(peer("1", different, tag = card.match))).size)
        val lookalike = local.copy(accountKey = AccountKey(provider, "another"))
        assertEquals(3, dashboardAccounts(listOf(local, lookalike), listOf(phone)).size)
        assertEquals(3, dashboardAccounts(listOf(local), listOf(peer("1", card, card.copy(source = card.source.copy(ref = "other")), tag = card.match))).size)
    }

    @Test fun workspaceAndAccountStaySeparateEvenWithSameTagAndRejectWrongScopeReading() {
        val amp = SharedSource("individual", ProviderId.AMP, Access.View, match = "same")
        val workspace = amp.copy(ref = "workspace", workspace = true)
        val balance = QuotaReading(ProviderId.AMP, 500, emptyList(), QuotaDetails.AmpWorkspace(42.0))
        val ampLocal = local.copy(accountKey = AccountKey(ProviderId.AMP, "a"), providerId = ProviderId.AMP,
            snapshot = snapshot.copy(providerId = ProviderId.AMP))
        val rows = dashboardAccounts(listOf(ampLocal), listOf(peer("1", SharedCard(amp, balance), SharedCard(workspace, balance), tag = "same")))
        val individual = assertIs<DashboardAccount.Local>(rows[0])
        assertNull(individual.copies.single().reading)
        assertEquals(balance, assertIs<DashboardAccount.Remote>(rows[1]).source.reading)
    }

    @Test fun namesTimesAndFilteredManualSlotsUseBothKindsButOnlyLocalKeysAreSerializablePreferences() {
        val firstSync = card.copy(source = card.source.copy(ref = "first-sync", provider = ProviderId.DEVIN), reading = null)
        val rows = dashboardAccounts(listOf(local), listOf(peer("1", card, firstSync)))
        fun keys(mode: AccountSortMode, reverse: Boolean = false) = rows.inDashboardOrder(mode, emptyList(), emptyList(), reverse).map { it.key }
        val remote = DashboardKey.Remote("1", "ref")
        val pending = DashboardKey.Remote("1", "first-sync")
        val here = DashboardKey.Local(local.accountKey)
        assertEquals(listOf(remote, pending, here), keys(AccountSortMode.Name))
        assertEquals(listOf(remote, here, pending), keys(AccountSortMode.RefreshTime))
        assertEquals(listOf(here, remote, pending), keys(AccountSortMode.RefreshTime, reverse = true))
        val mixed = rows.inDashboardOrder(AccountSortMode.Manual, listOf(local.accountKey), listOf(here, pending, remote), false)
        val moved = moveDashboardKey(mixed, provider, here, 1)
        assertEquals(listOf(remote, pending, here), moved)
        assertEquals(listOf(local.accountKey), moved.filterIsInstance<DashboardKey.Local>().map { it.key })
        assertEquals(moved.reversed(), rows.inDashboardOrder(AccountSortMode.Manual, listOf(local.accountKey), moved, true).map { it.key })
    }

    @Test fun viewOnlyOfflineBusyAndOffNeverDispatchRemoteRefresh() {
        val sent = mutableListOf<String>()
        val refresh = card.copy(source = card.source.copy(access = Access.Refresh))
        for ((online, busy) in listOf(false to emptySet(), true to setOf(refresh.ref), true to emptySet())) {
            val host = DashboardPeer("host", "My computer", "Refresh on computer", online, emptyList(), busy, { null }, sent::add)
            RemoteSource(host, refresh).refresh()
            RemoteSource(host, card).refresh()
        }
        assertEquals(listOf(refresh.ref), sent)
        val off = card.copy(source = card.source.copy(access = Access.Off))
        assertEquals(emptyList(), dashboardAccounts(emptyList(), listOf(peer("1", off))))
    }
}
