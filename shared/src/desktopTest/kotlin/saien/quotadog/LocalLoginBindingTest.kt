package saien.quotadog

import com.russhwolf.settings.MapSettings
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.URLProtocol
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import saien.quotadog.hostsync.Access
import saien.quotadog.hostsync.GrantEdit
import saien.quotadog.hostsync.LocalShares
import saien.quotadog.hostsync.QuotaDetails
import saien.quotadog.hostsync.toQuotaReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalLoginBindingTest {
    @Test fun aFailedFirstReadingStillIdentifiesTheCurrentLocalLogin() = runBlocking {
        Fixture { 503 to "{}" }.use { f ->
            val key = f.tokens.save(ProviderId.CODEX, token("first"))
            f.store.detectAll()
            val first = f.store.state.value.accounts.getValue(key)
            assertNull(first.snapshot)
            assertEquals(AuthState.Error, first.authState)
            assertEquals("owner@example.invalid", first.loginEmail)

            f.tokens.save(key, token("replacement").copy(email = "other@example.invalid"))
            assertFalse(f.store.refresh(key))
            val replacement = f.store.state.value.accounts.getValue(key)
            assertNull(replacement.snapshot)
            assertEquals("other@example.invalid", replacement.loginEmail)
        }
    }

    @Test fun localAndGrantedRefreshJoinOneQueryAndObserveItsSuccessOrFailure() = runBlocking {
        for (remoteFirst in listOf(false, true)) for (ok in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            Fixture { entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS)); (if (ok) 200 else 503) to claudeUsage }.use { f ->
                val key = f.tokens.save(ProviderId.CLAUDE_CODE, token("first"))
                val login = assertNotNull(f.store.currentLogin(key))
                val one = async(start = CoroutineStart.UNDISPATCHED) { f.store.refresh(key, login.takeIf { remoteFirst }) }
                try {
                    withTimeout(5_000) { entered.await() }
                    val two = async(start = CoroutineStart.UNDISPATCHED) { f.store.refresh(key, login.takeUnless { remoteFirst }) }
                    assertFalse(two.isCompleted)
                    assertFalse(f.store.refresh(key, "another-login"))
                    release.countDown()
                    assertEquals(ok, one.await()); assertEquals(ok, two.await())
                    assertEquals(1, f.requests.size)
                    val ui = f.store.state.value.accounts.getValue(key)
                    assertFalse(ui.busy); assertEquals(if (ok) AuthState.LoggedIn else AuthState.Error, ui.authState)
                    assertEquals(login.takeIf { ok }, ui.snapshot?.loginTag)
                } finally { release.countDown() }
            }
        }
    }

    @Test fun lateRenewalAndLateQuotaNeverWriteIntoDeletedReplacedOrRestoredRows() = runBlocking {
        for (atRenewal in listOf(false, true)) for (mutation in listOf("delete", "replace", "restore")) {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            Fixture { r ->
                if (!entered.isCompleted) { entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS)) }
                200 to if (r.path.endsWith("/token")) """{"access_token":"late-rotation","refresh_token":"late-grant"}""" else claudeUsage
            }.use { f ->
                val original = token("original", expired = atRenewal)
                val key = f.tokens.save(ProviderId.CLAUDE_CODE, original)
                val login = assertNotNull(f.store.currentLogin(key))
                val work = async { f.store.refresh(key, login) }
                try {
                    withTimeout(5_000) { entered.await() }
                    when (mutation) {
                        "delete" -> f.store.delete(key)
                        "replace" -> f.tokens.save(key, token("replacement"))
                        else -> { f.tokens.delete(key); f.tokens.save(key, original) }
                    }
                    assertNotEquals(login, f.store.currentLogin(key))
                    // The new login does not have to wait for the obsolete HTTP operation.
                    if (mutation == "replace") assertTrue(f.store.refresh(key))
                    val expectedRow = f.tokens.load(key)
                    val expectedCache = f.snapshots.load(key)
                    release.countDown()
                    assertFalse(work.await(), "$atRenewal/$mutation")
                    assertFalse(f.store.refresh(key, login))
                    assertEquals(expectedRow, f.tokens.load(key)); assertEquals(expectedCache, f.snapshots.load(key))
                    assertEquals(if (mutation == "replace") 2 else 1, f.requests.size)
                    val ui = f.store.state.value.accounts[key]
                    if (mutation == "delete") assertNull(ui)
                    else if (mutation == "replace") assertEquals(expectedRow?.loginGeneration, ui?.snapshot?.loginTag)
                    else assertNull(ui?.snapshot)
                } finally { release.countDown() }
            }
        }
    }

    @Test fun claudeSubscriptionLoginAndBoundRenewalUseSubscriberAndOrganizationNotEmail() = runBlocking {
        Fixture { r ->
            if (!r.path.endsWith("/token")) return@Fixture 200 to claudeUsage
            val request = Json.parseToJsonElement(r.body).jsonObject
            val code = request["code"]?.jsonPrimitive?.content
            if (code == null) return@Fixture 200 to when (request["refresh_token"]?.jsonPrimitive?.content) {
                "bad-org" -> """{"access_token":"wrong","organization":{"uuid":"$orgB"}}"""
                "bad-user" -> """{"access_token":"wrong","account":{"uuid":"$orgB"}}"""
                else -> """{"access_token":"bound-renewal","refresh_token":"rotated"}"""
            }
            val org = if (code == "workspace-b") orgB else orgA
            val scope = if (code == "console") "org:create_api_key" else "user:inference"
            200 to """{"access_token":"bearer-$code","refresh_token":"$code","expires_in":1,"scope":"$scope",
                "account":{"email_address":"owner@example.invalid","uuid":"$user"},"organization":{"uuid":"$org"}}"""
        }.use { f ->
            val a = f.login(ProviderId.CLAUDE_CODE, "workspace-a")
            val grant = assertNotNull(f.store.currentLogin(a))
            val b = f.login(ProviderId.CLAUDE_CODE, "workspace-b")
            assertNotEquals(a, b)
            assertEquals(user, f.tokens.load(a)?.principalId); assertEquals(orgA, f.tokens.load(a)?.accountId)
            assertTrue(f.store.refresh(a, grant)); assertTrue(f.store.refresh(b))
            assertEquals(grant, f.store.currentLogin(a)); assertEquals("rotated", f.tokens.load(a)?.refreshToken)
            assertEquals(listOf(user, orgA), f.store.state.value.accounts[a]?.snapshot?.localQuota?.matchParts)
            assertEquals(listOf(user, orgB), f.store.state.value.accounts[b]?.snapshot?.localQuota?.matchParts)
            assertNull(f.snapshots.load(a)?.quotaIdentity)
            assertFailsWith<ProviderException> { f.login(ProviderId.CLAUDE_CODE, "console") }
            for (bad in listOf("bad-org", "bad-user")) {
                assertEquals(a, f.login(ProviderId.CLAUDE_CODE, bad))
                val replacement = assertNotNull(f.tokens.load(a))
                val requests = f.requests.size
                assertFalse(f.store.refresh(a, grant)); assertEquals(requests, f.requests.size)
                assertFalse(f.store.refresh(a)); assertEquals(requests + 1, f.requests.size)
                assertEquals(replacement, f.tokens.load(a)); assertNull(f.store.state.value.accounts[a]?.snapshot)
                assertEquals(AuthState.RequiresRelogin, f.store.state.value.accounts[a]?.authState)
            }
        }
    }

    @Test fun legacyClaudeCredentialsCannotCreateAMatchWithoutLocalOAuthProofOfBothIds() = runBlocking {
        Fixture { r ->
            200 to if (!r.path.endsWith("/token")) claudeUsage else if ("partial" in r.body) {
                """{"access_token":"first","refresh_token":"full","expires_in":1,"account":{"uuid":"$user"}}"""
            } else """{"access_token":"second","account":{"uuid":"$user"},"organization":{"uuid":"$orgA"}}"""
        }.use { f ->
            f.tokens.importLegacyFile(ProviderId.CLAUDE_CODE, token("legacy", true).copy(refreshToken = "partial", accountId = orgA, principalId = user))
            val key = f.tokens.list().single().accountKey
            assertNull(f.tokens.load(key)?.principalId)
            val grant = assertNotNull(f.store.currentLogin(key))
            assertTrue(f.store.refresh(key, grant))
            assertNull(f.store.state.value.accounts[key]?.snapshot?.quotaIdentity)
            assertNotNull(f.store.state.value.accounts[key]?.snapshot?.toQuotaReading())
            assertFalse(f.store.refresh(key, grant)) // Complete fresh proof makes this a new grant, not a retroactive match.
            assertNotEquals(grant, f.store.currentLogin(key))
            assertEquals(listOf(user, orgA), f.store.state.value.accounts[key]?.snapshot?.localQuota?.matchParts)
        }
    }

    @Test fun codexSelectedWorkspaceSurvivesRenewalButConflictingClaimsDoNot() = runBlocking {
        fun id(workspace: String) = jwt("""{"email":"owner@example.invalid","https://api.openai.com/auth":{
            "chatgpt_user_id":"user","chatgpt_account_id":"$workspace","organizations":[{"id":"never-use-first"}]}}""")
        Fixture { r ->
            if (r.path.endsWith("/token")) {
                if ("code=contradiction&" in r.body) return@Fixture 200 to
                    """{"access_token":"login","refresh_token":"no","account_id":"one","id_token":"${id("two")}"}"""
                if ("code=explicit&" in r.body) return@Fixture 200 to
                    """{"access_token":"bound","account_id":"explicit-workspace"}"""
                val code = listOf("one", "two", "conflict").firstOrNull { "code=$it&" in r.body }
                if (code != null) return@Fixture 200 to """{"access_token":"initial-$code","refresh_token":"$code","id_token":"${id(code)}","expires_in":1}"""
                return@Fixture 200 to if ("refresh_token=conflict" in r.body) """{"access_token":"wrong","id_token":"${id("other")}"}"""
                    else """{"access_token":"renewed","refresh_token":"rotated"}"""
            }
            200 to """{"user_id":"user","account_id":"${r.target}","rate_limit":{"primary_window":{"used_percent":32}}}"""
        }.use { f ->
            val one = f.login(ProviderId.CODEX, "one"); val two = f.login(ProviderId.CODEX, "two")
            assertNotEquals(one, two)
            for ((key, workspace) in listOf(one to "one", two to "two")) {
                val grant = assertNotNull(f.store.currentLogin(key))
                assertTrue(f.store.refresh(key, grant))
                assertEquals(workspace, f.requests.last().target); assertEquals("Bearer renewed", f.requests.last().bearer)
                assertEquals(workspace, f.tokens.load(key)?.accountId); assertEquals(grant, f.store.currentLogin(key))
                assertEquals(listOf("user", workspace), f.store.state.value.accounts[key]?.snapshot?.localQuota?.matchParts)
            }
            val bad = f.login(ProviderId.CODEX, "conflict"); val before = f.tokens.load(bad)
            val requests = f.requests.size
            assertFalse(f.store.refresh(bad)); assertEquals(before, f.tokens.load(bad)); assertEquals(requests + 1, f.requests.size)
            assertTrue(f.requests.last().path.endsWith("/token"))
            assertFailsWith<ProviderException> { f.login(ProviderId.CODEX, "contradiction") }
            assertTrue(f.store.refresh(f.login(ProviderId.CODEX, "explicit")))
            assertEquals("explicit-workspace", f.requests.last().target)
        }
    }

    @Test fun codexDoesNotPublishAnotherTargetsReadingEvenToAnUnmatchedGrant() = runBlocking {
        val phase = AtomicInteger()
        val ids = listOf("\"account_id\":\"a\"", "\"account_id\":\"b\"", "\"user_id\":\"other\"", "\"account_id\":123", "\"user_id\":\"me\"")
        Fixture {
            val i = phase.get()
            200 to """{${ids[i]},"rate_limit":{"primary_window":{"used_percent":${13 + i * 17}}}}"""
        }.use { f ->
            val key = f.tokens.save(ProviderId.CODEX, token("selected").copy(accountId = "a", principalId = "me"))
            assertTrue(f.store.refresh(key))
            val login = assertNotNull(f.store.currentLogin(key))
            val original = assertNotNull(f.store.state.value.accounts[key]?.snapshot)
            val edit = GrantEdit(key, false, null, Access.Refresh, original)
            val grant = LocalShares().change("01".repeat(32), edit, edit.checkedSource(f.store))
            assertNull(original.quotaIdentity); assertEquals(.13, original.windows.single().usedRatio)
            val cached = f.snapshots.load(key)
            for (i in 1..3) {
                phase.set(i)
                assertFalse(f.store.refresh(key, login), "Contradictory or malformed quota identifier at response $i")
                assertEquals(original, f.store.state.value.accounts[key]?.snapshot); assertEquals(cached, f.snapshots.load(key))
            }
            // Both responses have too little evidence to merge. Changing *which* scope is known
            // still breaks continuity for the old grant, without discarding the valid local result.
            phase.set(4); assertFalse(f.store.refresh(key, login))
            val latest = assertNotNull(f.store.state.value.accounts[key]?.snapshot)
            assertEquals(.81, latest.windows.single().usedRatio); assertNull(latest.quotaIdentity)
            assertNotEquals(login, f.store.currentLogin(key)); assertTrue(grant.valid(f.store).entries.isEmpty())
            assertTrue(f.requests.all { it.target == "a" })
        }
    }

    @Test fun cursorUsesStoredBearerAndOnlyAcceptsACliRenewalOfTheSameBoundGrant() = runBlocking {
        Fixture { 200 to """{"membershipType":"pro","individualUsage":{"plan":{"totalPercentUsed":15}}}""" }.use { f ->
            val jwtA = jwt("""{"sub":"user","team_id":"one","jti":"first"}""")
            val first = token(jwtA).copy(accountId = "pro")
            val key = f.tokens.save(ProviderId.CURSOR, first)
            assertTrue(f.store.refresh(key)); assertEquals("Bearer $jwtA", f.requests.single().bearer)
            f.tokens.save(key, first.copy(expiresAtEpochMillis = 1))
            val login = assertNotNull(f.store.currentLogin(key))
            val expired = f.tokens.load(key)
            val rotated = first.copy(accessToken = jwt("""{"sub":"user","team_id":"one","jti":"renewed"}"""))
            for (different in listOf(rotated.copy(refreshToken = "unrelated-grant"),
                rotated.copy(accessToken = jwt("""{"sub":"user","team_id":"two"}""")))) {
                f.cursor = different
                assertFalse(f.store.refresh(key, login)); assertEquals(expired, f.tokens.load(key)); assertEquals(1, f.requests.size)
            }
            f.cursor = rotated
            assertTrue(f.store.refresh(key, login)); assertEquals(login, f.store.currentLogin(key))
            assertEquals(rotated.accessToken, f.tokens.load(key)?.accessToken); assertEquals(2, f.requests.size)
        }
    }

    @Test fun lostAndChangedAntigravityScopeRevokeOldGrantAndStillPublishLocalQuota() = runBlocking {
        val phase = AtomicInteger()
        Fixture { r ->
            val project = if (phase.get() >= 3) "project-b" else "project-a"
            val remaining = 0.8 - 0.1 * phase.get()
            when {
                r.path.endsWith(":loadCodeAssist") -> 200 to """{"cloudaicompanionProject":"$project"}"""
                r.path.endsWith(":retrieveUserQuotaSummary") && project in r.body -> 200 to
                    """{"groups":[{"displayName":"Gemini","buckets":[{"bucketId":"gemini-weekly","window":"weekly","remainingFraction":$remaining}]}]}"""
                r.path.endsWith("/userinfo") && phase.get() != 1 -> 200 to """{"id":"member","email":"owner@example.invalid"}"""
                else -> 503 to "{}"
            }
        }.use { f ->
            val key = f.tokens.save(ProviderId.ANTIGRAVITY, token("google-grant"))
            assertTrue(f.store.refresh(key))
            for (nextPhase in 1..3) {
                val pair = "01".repeat(32)
                val old = assertNotNull(f.store.currentLogin(key))
                val edit = GrantEdit(key, false, null, Access.Refresh, f.store.state.value.accounts[key]?.snapshot)
                val grant = LocalShares().change(pair, edit, edit.checkedSource(f.store))
                assertEquals(old, grant.entries.single().login)
                phase.set(nextPhase)
                assertFalse(f.store.refresh(key, old), "new result must not satisfy the old grant")
                val next = assertNotNull(f.store.currentLogin(key))
                assertNotEquals(old, next)
                val local = assertNotNull(f.store.state.value.accounts[key]?.snapshot)
                assertEquals(next, local.loginTag); assertEquals(AuthState.LoggedIn, local.authState)
                val used = assertNotNull(local.toQuotaReading()?.windows?.single()?.used)
                assertEquals(0.2 + nextPhase * 0.1, used, 1e-9)
                assertEquals("google-grant", f.tokens.load(key)?.accessToken)
                if (nextPhase == 1) assertNull(local.quotaIdentity) else assertNotNull(local.quotaIdentity)
                assertEquals(if (nextPhase == 3) "project-b" else "project-a", local.quotaTarget)
                assertTrue(grant.valid(f.store).entries.isEmpty())
                val requests = f.requests.size
                assertFalse(f.store.refresh(key, old)); assertEquals(requests, f.requests.size)
                assertTrue(f.store.refresh(key, next)) // Includes an explicitly granted unmatched collector.
                assertEquals(next, f.store.currentLogin(key))
            }
        }
    }

    @Test fun factoryChecksRenewedOrganizationAndGrokUsesItsOwnStoredGrant() = runBlocking {
        Fixture { r ->
            when {
                "grant_type=refresh_token" in r.body && "refresh_token=factory" in r.body ->
                    200 to """{"access_token":"factory-new","organization_id":"org-a"}"""
                "grant_type=refresh_token" in r.body && "refresh_token=grok" in r.body ->
                    200 to """{"access_token":"grok-new"}"""
                r.path.endsWith("/billing/limits") -> 200 to """{"usesTokenRateLimitsBilling":true,"limits":{"standard":{"fiveHour":{"usedPercent":2}}}}"""
                r.path.endsWith("/auth/me") -> 200 to """{"organization":{"id":"org-a"}}"""
                r.path.endsWith("/billing") -> 200 to """{"config":{"creditUsagePercent":27,"subscriptionTier":"super"}}"""
                else -> 401 to "{}"
            }
        }.use { f ->
            for ((provider, name) in listOf(ProviderId.DROID to "factory", ProviderId.GROK to "grok")) {
                val key = f.tokens.save(provider, token(name, true).copy(refreshToken = name, accountId = "org-a"))
                val grant = assertNotNull(f.store.currentLogin(key)); assertTrue(f.store.refresh(key, grant))
                assertEquals(grant, f.store.state.value.accounts[key]?.snapshot?.loginTag)
                assertEquals("$name-new", f.tokens.load(key)?.accessToken)
                assertEquals(name, f.tokens.load(key)?.refreshToken)
                assertEquals("Bearer $name-new", f.requests.last().bearer)
            }
            assertTrue("organization_id=org-a" in f.requests.first().body)
            val other = f.tokens.save(ProviderId.DROID, token("different", true).copy(refreshToken = "factory", accountId = "org-b"))
            val before = f.tokens.load(other); val requests = f.requests.size
            assertFalse(f.store.refresh(other)); assertEquals(before, f.tokens.load(other)); assertEquals(requests + 1, f.requests.size)
            assertTrue("organization_id=org-b" in f.requests.last().body)
            val badJwt = f.tokens.save(ProviderId.DROID, token(jwt("""{"org_id":"org-a"}""")).copy(accountId = "org-b"))
            assertFalse(f.store.refresh(badJwt)); assertEquals(requests + 1, f.requests.size)
            val badBilling = f.tokens.save(ProviderId.DROID, token("valid-bearer").copy(accountId = "org-b"))
            assertFalse(f.store.refresh(badBilling)); assertNull(f.store.state.value.accounts[badBilling]?.snapshot)
            val rejected = f.tokens.save(ProviderId.GROK, token("rejected", true))
            assertFalse(f.store.refresh(rejected)); assertEquals("rejected", f.tokens.load(rejected)?.accessToken)
        }
    }

    @Test fun workspaceSelectionCannotMoveToAnotherLoginOrWorkspaceWhileThePickerIsOpen() = runBlocking {
        val phase = AtomicInteger()
        Fixture {
            val workspace = if (phase.get() == 0) "a" else "b"
            200 to """{"result":{"displayText":"Signed in as owner@example.invalid\nStudio credits: ${'$'}${10 + phase.get()} remaining - https://ampcode.com/workspaces/$workspace/settings"}}"""
        }.use { f ->
            val key = f.tokens.save(ProviderId.AMP, token("first"))
            fun selection() = GrantEdit(key, true, null, Access.Refresh, f.store.state.value.accounts[key]?.snapshot)
            suspend fun grant(edit: GrantEdit) = LocalShares().change("01".repeat(32), edit, edit.checkedSource(f.store))
            assertTrue(f.store.refresh(key)); val a = selection()
            phase.incrementAndGet(); assertTrue(f.store.refresh(key)); val b = selection()
            assertTrue(grant(a).entries.isEmpty(), "Same label and credential, but the workspace changed")
            phase.incrementAndGet(); assertTrue(f.store.refresh(key))
            assertEquals(QuotaDetails.AmpWorkspace(12.0), grant(b).entries.single().last?.details, "A newer reading of B is still B")
            f.tokens.save(key, token("replacement")); assertTrue(f.store.refresh(key))
            assertTrue(grant(b).entries.isEmpty(), "Same workspace, but the login changed")
        }
    }

    @Test fun identicalAmpKeyAtSeparateInstancesNeverQueriesTheOtherInstance() = runBlocking {
        Fixture { 200 to """{"ok":true,"result":{"displayText":"Signed in as owner@example.invalid\nIndividual credits: $14.00 remaining"}}""" }.use { f ->
            val first = token("same-key").copy(refreshToken = "", apiServerUrl = "https://one.example.invalid")
            val a = f.tokens.save(ProviderId.AMP, first)
            val b = f.tokens.save(ProviderId.AMP, first.copy(apiServerUrl = "https://two.example.invalid"))
            assertNotEquals(a, b)
            val grant = assertNotNull(f.store.currentLogin(a))
            assertFalse(f.store.refresh(b, grant)); assertTrue(f.store.refresh(a, grant)); assertTrue(f.store.refresh(b))
            assertEquals(listOf("one.example.invalid", "two.example.invalid"), f.hosts.toList())
            f.tokens.save(a, first.copy(expiresAtEpochMillis = 1))
            assertFalse(f.store.refresh(a)); assertEquals(2, f.requests.size)
            val malformed = f.tokens.save(ProviderId.AMP, first.copy(apiServerUrl = "not-a-service"))
            assertFalse(f.store.refresh(malformed)); assertEquals(2, f.requests.size)
        }
    }

    private data class Request(val path: String, val body: String, val target: String?, val bearer: String?)

    /** Always loopback; no provider, browser, callback, real token storage or local CLI is used. */
    private class Fixture(handler: (Request) -> Pair<Int, String>) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Request>()
        val hosts = CopyOnWriteArrayList<String>()
        val tokens = SettingsTokenStore(MapSettings())
        val snapshots = SettingsUsageSnapshotStore(MapSettings())
        var cursor: OAuthTokenBundle? = null
        private val workers = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = workers
            createContext("/") { r ->
                val request = Request(r.requestURI.path, r.requestBody.bufferedReader().use { it.readText() },
                    r.requestHeaders.getFirst("Chatgpt-Account-Id"), r.requestHeaders.getFirst("Authorization"))
                requests += request
                val (status, body) = handler(request)
                val bytes = body.encodeToByteArray()
                r.sendResponseHeaders(status, bytes.size.toLong()); r.responseBody.use { it.write(bytes) }
            }
            start()
        }
        private val http = HttpClient(Java) { followRedirects = false }.also { client ->
            client.plugin(HttpSend).intercept { r ->
                hosts += r.url.host
                r.url.apply { protocol = URLProtocol.HTTP; host = "127.0.0.1"; port = server.address.port }
                execute(r)
            }
        }
        private val client = QuotaDogClient(tokens, httpClient = http, cursorCredentials = { checkNotNull(cursor) { "Unexpected CLI lookup" } })
        val store = QuotaDogStore(client, snapshots)
        suspend fun login(provider: ProviderId, code: String): AccountKey {
            val start = client.beginLogin(provider, openBrowser = false)
            return client.completeLogin(start, "${start.redirectUri}?state=${start.state}&code=$code")
        }
        override fun close() { http.close(); server.stop(0); workers.shutdownNow() }
    }

    private companion object {
        const val user = "af000000-0000-4000-8000-000000000001"
        const val orgA = "bb000000-0000-4000-8000-000000000002"
        const val orgB = "cc000000-0000-4000-8000-000000000003"
        const val claudeUsage = """{"five_hour":{"utilization":17}}"""
        fun token(access: String, expired: Boolean = false) = OAuthTokenBundle(access, "stored-grant", email = "owner@example.invalid",
            expiresAtEpochMillis = if (expired) 1 else Long.MAX_VALUE)
        fun jwt(payload: String) = "e30.${base64UrlNoPadding(payload.encodeToByteArray())}.fixture"
    }
}
