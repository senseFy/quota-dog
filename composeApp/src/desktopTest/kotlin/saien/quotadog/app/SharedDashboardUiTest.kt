package saien.quotadog.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.russhwolf.settings.PropertiesSettings
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import saien.quotadog.AccountKey
import saien.quotadog.AccountUiState
import saien.quotadog.AppPreferences
import saien.quotadog.AuthState
import saien.quotadog.DashboardLayout
import saien.quotadog.EmailPrivacyMode
import saien.quotadog.ProviderId
import saien.quotadog.ProviderUsageSnapshot
import saien.quotadog.UsageDisplayMode
import saien.quotadog.UsageWindow
import saien.quotadog.app.theme.QuotaDogTheme
import saien.quotadog.hostsync.*
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SharedDashboardUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phoneShowsLocalEmailWithPrivacyAndNicknameOverrides() {
        val email = "riley@example.test"
        val id = ProviderId.CODEX
        val snapshot = ProviderUsageSnapshot(id, AuthState.LoggedIn, emptyList(), Instant.fromEpochMilliseconds(100), accountEmail = email)
        val account = AccountUiState(AccountKey(id, "opaque-record"), added = true, authState = AuthState.LoggedIn, snapshot = snapshot)
        val row = dashboardAccounts(listOf(account), emptyList()).single()
        val privacy = mutableStateOf(EmailPrivacyMode.Visible)
        val names = PhoneAccountNames(AppPreferences(PropertiesSettings(Properties())))
        compose.setContent {
            QuotaDogTheme(darkTheme = false) {
                val title = names.titles(listOf(row), privacy.value).getValue(row.key)
                PhoneAccountRow(row, title, UsageDisplayMode.Used, false, { names.rename(row.key, it) }, {}, onRemove = {}, signIn = {})
            }
        }
        compose.onNodeWithText("Codex · $email").assertIsDisplayed()
        compose.runOnIdle { privacy.value = EmailPrivacyMode.Masked }
        compose.onNodeWithText("Codex · ri...st").assertIsDisplayed()
        compose.onAllNodes(hasText(email, substring = true) or hasContentDescription(email, substring = true),
            useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Open Codex details · ri...st").performClick()
        compose.onNodeWithText("Add phone nickname").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Studio")
        compose.onNodeWithText("Save nickname").performScrollTo().performClick()
        compose.onNodeWithText("ri...st").assertExists()
        compose.onNodeWithContentDescription("Close details").performClick()
        compose.onNodeWithText("Codex · Studio").assertIsDisplayed()
    }

    @Test fun matchingPhoneTitlesStayDistinctAfterMaskingAndReordering() {
        val provider = ProviderId.CODEX
        val first = AccountUiState(AccountKey(provider, "Aa"), added = true, authState = AuthState.Error, loginEmail = "alex@one.com")
        val second = first.copy(accountKey = AccountKey(provider, "BB"), loginEmail = "alice@two.com")
        val accounts = mutableStateOf(listOf(first, second))
        val privacy = mutableStateOf(EmailPrivacyMode.Visible)
        val names = PhoneAccountNames(AppPreferences(PropertiesSettings(Properties())))
        val refreshed = mutableListOf<AccountKey>()
        compose.setContent {
            val rows = dashboardAccounts(accounts.value, emptyList())
            val titles = names.titles(rows, privacy.value)
            QuotaDogTheme(darkTheme = false) {
                Column(Modifier.width(300.dp)) {
                    rows.forEach { row -> key(row.key) {
                        PhoneAccountRow(row, titles.getValue(row.key), UsageDisplayMode.Used, false, { names.rename(row.key, it) },
                            { refreshed += it.accountKey }, onRemove = {}, signIn = {})
                    } }
                }
            }
        }
        compose.onNodeWithContentDescription("Open Codex details · alex@one.com").assertExists()
        compose.runOnIdle { accounts.value = listOf(first, second.copy(loginEmail = first.loginEmail)) }
        fun actions(email: String) = compose.onAllNodes(hasContentDescription("Refresh on this device · Codex · $email · #", substring = true))
            .fetchSemanticsNodes().map { it.config[SemanticsProperties.ContentDescription].single() }
        val same = actions("alex@one.com")
        assertEquals(2, same.size)
        assertNotEquals(same[0], same[1])
        compose.onNodeWithContentDescription(same.first()).performClick()
        compose.runOnIdle { accounts.value = listOf(second, first); privacy.value = EmailPrivacyMode.Masked }
        val masked = actions("al...om")
        assertEquals(same.reversed().map { it.substringAfterLast('#') }, masked.map { it.substringAfterLast('#') })
        compose.onNodeWithContentDescription(masked.first()).performClick()
        compose.runOnIdle { assertEquals(listOf(first.accountKey, second.accountKey), refreshed) }
        for (email in listOf(first.loginEmail!!, second.loginEmail!!)) {
            compose.onAllNodes(hasText(email, substring = true) or hasContentDescription(email, substring = true),
                useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test fun receivedCardShowsItsOwnActionAmountsTimestampsAndStates() {
        val ref = "abcd0123".repeat(4)
        val grants = SharedSource(ref, ProviderId.DEVIN, Access.Refresh)
        val observation = QuotaReading(ProviderId.DEVIN, 1000,
            listOf(QuotaWindow(QuotaWindowKind.DevinDaily, 0.25, resetsAt = 2000)), QuotaDetails.Devin(2.50))
        val value = mutableStateOf(SharedCard(grants, observation))
        val online = mutableStateOf(true)
        val mode = mutableStateOf(UsageDisplayMode.Used)
        val requests = mutableListOf<String>()
        compose.setContent {
            val card = value.value
            val peer = DashboardPeer("computer", "My computer", "Refresh on computer", online.value, listOf(card),
                emptySet(), { null }, { requests += it })
            QuotaDogTheme(darkTheme = true) {
                Column(Modifier.width(360.dp)) { RemoteSourceContent(RemoteSource(peer, card), mode.value, DashboardLayout.Compact) }
            }
        }
        listOf("Daily quota", "25% used", "Extra balance · USD 2.50", "My computer · Account ABCD0123").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Collected · ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Resets · ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Refresh on computer").performClick()
        compose.runOnIdle {
            assertEquals(listOf(ref), requests)
            online.value = false
            mode.value = UsageDisplayMode.Remaining
            value.value = SharedCard(grants.copy(access = Access.View, status = SourceStatus.LoginRequired), observation)
        }
        listOf("75% left", "View only", "Source offline · Last reading kept on this device").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Sign-in required on the source device", substring = true).assertIsDisplayed()
        listOf("Refresh on computer", "Sign in again", "Remove account").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(0)
        }
        compose.runOnIdle { value.value = SharedCard(grants, null) }
        compose.onNodeWithText("Waiting for first reading").assertIsDisplayed()
        compose.onAllNodesWithText("Collected · ", substring = true).assertCountEquals(0)
        val uncertain = observation.copy(windows = listOf(QuotaWindow(QuotaWindowKind.DevinWeekly, 0.9)))
        compose.runOnIdle { value.value = SharedCard(grants, observation, uncertain) }
        compose.onNodeWithText("Device clocks differ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Daily quota").assertIsDisplayed()
        compose.onAllNodesWithText("Weekly quota").assertCountEquals(0)
        compose.runOnIdle { value.value = SharedCard(grants, null, uncertain) }
        compose.onNodeWithText("Unverified source time · No trusted reading yet").assertIsDisplayed()
        compose.onNodeWithText("Weekly quota").assertIsDisplayed()
    }

    @Test fun mergedFaceShowsExactlyOneObservationAndRefreshesIndependentlyOfThatSource() {
        val provider = ProviderId.CODEX
        val key = AccountKey(provider, "local-codex")
        val first = ProviderUsageSnapshot(provider, AuthState.LoggedIn,
            listOf(UsageWindow("primary", "Local quota", 0.5, null)), Instant.fromEpochMilliseconds(100),
            loginTag = "generation", quotaIdentity = "verified-local-principal-and-scope")
        val account = mutableStateOf(AccountUiState(key, added = true, authState = AuthState.LoggedIn, snapshot = first))
        val source = SharedSource("abcd1234", provider, Access.View, match = "proof")
        val value = QuotaReading(provider, 200, listOf(QuotaWindow(QuotaWindowKind.CodexSecondary, 0.25)), QuotaDetails.Codex(2, 1))
        val shared = mutableStateOf(SharedCard(source, value))
        val calls = mutableListOf<String>()
        compose.setContent {
            val history = remember { DashboardReadingHistory() }
            val peer = DashboardPeer("mac", "My computer", "Refresh on computer", true, listOf(shared.value), emptySet(),
                { "proof" }, { calls += "remote:$it" })
            val row = dashboardAccounts(listOf(account.value), listOf(peer), 1000, history).single() as DashboardAccount.Local
            QuotaDogTheme(darkTheme = false) {
                Column(Modifier.width(360.dp)) {
                    MergedAccountContent(row, UsageDisplayMode.Used, DashboardLayout.Cards, false, { calls += "local:${it.accountKey.accountId}" })
                }
            }
        }
        for (label in listOf("My computer · Account ABCD1234", "View only", "25% used", "Resets available: 2")) {
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        compose.onAllNodesWithText("50% used").assertCountEquals(0)
        compose.onNodeWithText("Refresh on this device").performClick()
        compose.onNodeWithText("Source details (2)").performClick()
        compose.onNodeWithText("50% used").assertIsDisplayed()
        compose.onAllNodesWithText("Refresh on computer").assertCountEquals(0)
        compose.onNodeWithText("Hide source details").performClick()
        compose.runOnIdle {
            assertEquals(listOf("local:local-codex"), calls)
            account.value = account.value.copy(authState = AuthState.RequiresRelogin, snapshot = first.copy(collectedAt = Instant.fromEpochMilliseconds(300)))
            shared.value = SharedCard(source.copy(access = Access.Refresh), value)
        }
        compose.onNodeWithText("50% used").assertIsDisplayed()
        compose.onAllNodesWithText("25% used").assertCountEquals(0)
        compose.onAllNodesWithText("Resets available: 2").assertCountEquals(0)
        compose.onNodeWithText("Refresh on computer").performClick()
        compose.runOnIdle {
            assertEquals(listOf("local:local-codex", "remote:abcd1234"), calls)
            account.value = account.value.copy(authState = AuthState.RateLimited)
            shared.value = shared.value.copy(uncertain = value.copy(collectedAt = 600_000))
        }
        compose.onNodeWithText("Device clocks differ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Rate limited on this device", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Refresh on computer").assertCountEquals(0)
    }

    @Test fun accountAndWorkspacePermissionsTargetSeparateGrantsEvenWhenTheSourceBecomesUnavailable() {
        val source = AccountKey(ProviderId.AMP, "local")
        val snapshot = ProviderUsageSnapshot(ProviderId.AMP, AuthState.LoggedIn, emptyList(), Instant.fromEpochMilliseconds(100))
        val candidates = mutableStateOf(GrantSources(mapOf(source to GrantOption("My Amp account", snapshot)),
            mapOf(source to GrantOption("Team workspace credits", snapshot))))
        val accountAccess = mutableStateOf(Access.Off)
        val workspaceAccess = mutableStateOf(Access.Off)
        fun permission(ref: String, access: Access) = if (access == Access.Off) emptyMap() else mapOf(source to GrantPermission(ref, access))
        compose.setContent {
            QuotaDogTheme(darkTheme = false) {
                Column(Modifier.width(360.dp)) {
                    SourceGrants(candidates.value, permission("account", accountAccess.value), permission("team", workspaceAccess.value)) { edit ->
                        assertEquals(source, edit.key)
                        if (edit.access == Access.Off) assertEquals(if (edit.workspace) "team" else "account", edit.previous?.ref)
                        if (edit.workspace) workspaceAccess.value = edit.access else accountAccess.value = edit.access
                    }
                }
            }
        }
        compose.onAllNodesWithText("Allow refresh")[0].performClick()
        compose.runOnIdle {
            assertEquals(Access.Refresh, accountAccess.value)
            assertEquals(Access.Off, workspaceAccess.value)
        }
        compose.onAllNodesWithText("View only")[1].performClick()
        compose.runOnIdle {
            assertEquals(Access.Refresh, accountAccess.value)
            assertEquals(Access.View, workspaceAccess.value)
            candidates.value = GrantSources(emptyMap(), emptyMap())
        }
        compose.onNodeWithText("Stop sharing unavailable workspace").performClick()
        compose.runOnIdle { assertEquals(Access.Refresh, accountAccess.value); assertEquals(Access.Off, workspaceAccess.value) }
        compose.onNodeWithText("Stop sharing unavailable account").performClick()
        compose.onAllNodesWithText("Stop sharing", substring = true).assertCountEquals(0)
    }
}
