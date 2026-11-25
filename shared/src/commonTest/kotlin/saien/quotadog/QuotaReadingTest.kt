package saien.quotadog

import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import saien.quotadog.hostsync.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuotaReadingTest {
    private val now = Instant.parse("2026-11-22T14:00:00Z")
    private val secret = "PRIVATE-SENTINEL"
    private val token = OAuthTokenBundle("fixture-bearer", "", expiresAtEpochMillis = Long.MAX_VALUE)
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun snapshot(q: LocalQuota) = ProviderUsageSnapshot(q.providerId, AuthState.LoggedIn,
        listOf(UsageWindow("$secret-key", "$secret label", 0.0, null)), now, accountEmail = "$secret@example.invalid",
        message = "Source: /Users/$secret/credentials", planLabel = secret,
        resetCredits = listOf(CodexResetCredit(secret, "available", title = secret)), localQuota = q)

    private fun reading(q: LocalQuota, workspace: Boolean = false): QuotaReading {
        val r = assertNotNull(snapshot(q).toQuotaReading(workspace))
        val wire = Json.encodeToString(r)
        assertFalse(wire.contains(secret), wire)
        assertFalse(r.detailLines().any { secret in it })
        assertEquals(r, Json.decodeFromString<QuotaReading>(wire))
        assertEquals(q.providerId, r.provider)
        assertEquals(now.toEpochMilliseconds(), r.collectedAt)
        return r
    }

    @Test fun verifiedIdentityKeepsItsPairScopedMatchAcrossVersions() {
        val pair = "01".repeat(32)
        val reading = ProviderUsageSnapshot(ProviderId.CODEX, AuthState.LoggedIn, emptyList(), now,
            loginTag = "local-login", quotaIdentity = """["subscriber","workspace"]""")
        // HMAC-SHA256 of the v2 match message using the decoded pairing key.
        assertEquals("1ef06c1181c864654e99013156f1ad97b8d2f114d079f34d75d888903cfb275d", matchForPair(pair, reading))
        assertNotEquals(matchForPair(pair, reading), matchForPair("02".repeat(32), reading))
        assertNull(matchForPair(pair, reading.copy(loginTag = null)))
    }

    @Test fun futureOrReplayedReadingsCannotDisplaceTheLatestAuthorizedObservation() {
        val ref = "ab".repeat(16)
        val source = SharedSource(ref, ProviderId.GROK, Access.View)
        val here = now.toEpochMilliseconds()
        fun observation(at: Long, used: Double, senderNow: Long = here) = Msg.Observation(1, ref,
            QuotaReading(ProviderId.GROK, at, listOf(QuotaWindow(QuotaWindowKind.GrokCredits, used))), senderNow)
        var cache = ReceivedShares().manifest(Msg.Sources(1, listOf(source), here), here)
        val first = observation(here - 100, .8); val reset = observation(here, .05)
        cache = cache.observation(first, here).observation(reset, here).observation(first, here)
        val future = observation(here + 300_001, .95)
        cache = cache.observation(future, here)
        cache = Json.decodeFromString<ReceivedShares>(Json.encodeToString(cache))
        assertEquals(reset.value, cache.cards.single().reading); assertEquals(future.value, cache.cards.single().uncertain)
        cache = cache.observation(future, here + 1) // Time passing doesn't make this the new authoritative sample.
        assertEquals(reset.value, cache.cards.single().reading)
        val skewed = observation(here + 1, .76, senderNow = here - 300_001)
        cache = cache.observation(skewed, here); assertTrue(cache.clockConflict); assertEquals(reset.value, cache.cards.single().reading)
        val corrected = observation(here + 2, .06)
        cache = cache.observation(corrected, here + 2); assertFalse(cache.clockConflict); assertNull(cache.cards.single().uncertain)
        assertEquals(corrected.value, cache.cards.single().reading)
        cache = cache.manifest(Msg.Sources(2, emptyList(), here), here).observation(future, here)
        assertTrue(cache.cards.isEmpty())
    }

    @Test fun codexCopiesOnlyActualWindowsAndReadOnlyResetFields() {
        val usage = CodexUsageParser.parseUsage("""{"user_id":"user-$secret","account_id":"acct-$secret","plan_type":"$secret",
            "rate_limit":{"primary_window":{"used_percent":1,"reset_at":1782770922,"limit_window_seconds":18000},
            "secondary_window":{"used_percent":4,"limit_window_seconds":604800}},"rate_limit_reset_credits":{"available_count":2}}""")
        val credits = CodexUsageParser.parseResetCredits("""{"available_count":2,"applicable_available_count":1,"credits":[
            {"id":"redeem-$secret","status":"available","title":"$secret","source":"$secret","expires_at":1800000000},
            {"id":"used-$secret","status":"redeemed","expires_at":1700000000}]}""")
        val q = usage.localQuota(token, CodexUsageParser.mergeResetCredits(usage.resetCreditsAvailable, null, credits))
        assertEquals(listOf("user-$secret", "acct-$secret"), q.matchParts)
        val r = reading(q)
        assertEquals(listOf(QuotaWindowKind.CodexPrimary, QuotaWindowKind.CodexSecondary), r.windows.map { it.kind })
        assertEquals(listOf(0.01, 0.04), r.windows.map { it.used })
        assertEquals(listOf(18_000L, 604_800L), r.windows.map { it.durationSeconds })
        assertEquals(1_782_770_922_000L, r.windows.first().resetsAt)
        assertEquals(QuotaDetails.Codex(2, 1, listOf(1_800_000_000_000L)), r.details)
    }

    @Test fun neitherCredentialsNorOneWhamIdProveACodexMatchAndConflictsVetoIt() {
        fun claims(user: String, account: String) = "e30." + base64UrlNoPadding(
            """{"https://api.openai.com/auth":{"chatgpt_user_id":"$user","chatgpt_account_id":"$account"}}""".encodeToByteArray()) + ".sig"
        val good = token.copy(accessToken = claims("u", "a"), accountId = "a", idToken = claims("u", "a"))
        fun quota(ids: String, credential: OAuthTokenBundle) = CodexUsageParser.parseUsage(
            """{$ids"rate_limit":{"secondary_window":{"reset_at":1800000000}}}""")
            .localQuota(credential, CodexResetCreditsMerge(null, null, emptyList()))
        val both = """"user_id":"u","account_id":"a","""
        assertEquals(listOf("u", "a"), quota(both, good).matchParts)
        for (q in listOf(quota("", good), quota(""""user_id":"u",""", good), quota(""""account_id":"a",""", good),
            quota(""""user_id":"issuer|u","account_id":"a",""", token),
            quota(both, good.copy(accountId = "b")), quota(both, good.copy(principalId = "different")),
            quota(both, good.copy(idToken = claims("different", "a"))),
            quota(both, good.copy(accessToken = claims("u", "different"))))) {
            assertNull(q.matchParts)
            assertNull(reading(q).windows.single().used) // a real reset, not a fabricated 0%.
        }
    }

    @Test fun claudeMatchesOnlyWithBothSubscriptionUuidsAndIncludesModelWindows() {
        val body = obj("""{"five_hour":{"utilization":0,"resets_at":"2026-11-23T00:00:00Z"},"seven_day":{"utilization":1},
            "seven_day_sonnet":{"utilization":60},"seven_day_opus":{"utilization":23},"unknown_$secret":{"utilization":99},
            "account":{"email":"$secret@example.invalid"},"organization":{"name":"$secret"}}""")
        val account = "af000000-0000-4000-8000-000000000001"
        val org = "bb000000-0000-4000-8000-000000000002"
        val q = claudeLocalQuota(body, account.uppercase(), org)
        assertEquals(listOf(account, org), q.matchParts)
        assertEquals(listOf(0.0, 0.01, 0.6, 0.23), reading(q).windows.map { it.used })
        assertNull(claudeLocalQuota(body, null, org).matchParts)
        assertNull(claudeLocalQuota(body, account, null).matchParts)
        assertNull(claudeLocalQuota(body, "$secret@example.invalid", org).matchParts)
    }

    @Test fun droidHasDistinctRollingAndLegacyPoolsButNoInferredSeat() {
        val b = DroidUsageFetcher.parseBillingLimitsJson("""{"usesTokenRateLimitsBilling":true,"extraUsageBalanceCents":12345,
            "organization":{"id":"$secret"},"limits":{"standard":{"fiveHour":{"windowEnd":"2026-11-23T00:00:00Z"}},
            "core":{"weekly":{"usedPercent":40}}}}""")
        val q = DroidUsageFetcher.droidLocalQuota(DroidUsageFetcher.billingQuotaWindows(b, now), b.extraUsageBalanceCents)
        assertNull(q.matchParts)
        val r = reading(q)
        assertEquals(listOf(QuotaWindowKind.DroidStandardFiveHour, QuotaWindowKind.DroidCoreWeekly), r.windows.map { it.kind })
        assertEquals(listOf(null, 0.4), r.windows.map { it.used })
        assertEquals(QuotaDetails.Droid(12345L), r.details)
        assertEquals(listOf("Extra usage balance: $123.45"), r.detailLines())
        val old = DroidUsageFetcher.legacyQuotaWindows(obj("""{"usage":{"startDate":1740787200000,"endDate":1743379200000,
            "standard":{"usedRatio":0.0},"premium":{"usedRatio":0.9,"totalAllowance":1000000}}}"""))
        val legacy = DroidUsageFetcher.droidLocalQuota(old, null)
        assertEquals(listOf(QuotaWindowKind.DroidStandardPeriod, QuotaWindowKind.DroidPremiumPeriod), reading(legacy).windows.map { it.kind })
        assertNull(legacy.matchParts)
    }

    @Test fun antigravityModelsAreSeparateBoundedAndNeverExposeProviderLabelsOrImplyPoolIdentity() {
        val models = (listOf("claude-sonnet-4-5", "gemini-3-flash") + (1..25).map { "gpt-$secret-$it" }).joinToString(",") {
            """"$it":{"displayName":"$secret","quotaInfo":{"remainingFraction":0.4}}"""
        }
        val parsed = AntigravityUsageFetcher.availableModels(obj("""{"models":{$models}}"""))
        val q = parsed.localQuota("123456", "project-$secret", "google")
        assertNull(q.matchParts)
        val r = reading(q)
        assertEquals(12, r.windows.size)
        assertEquals(12, r.windows.map { it.label }.distinct().size)
        assertTrue(r.windows.any { it.label == "Gemini 3 Flash" })
        assertTrue(r.windows.any { it.label == "Claude Sonnet 4.5" })
        assertTrue(r.windows.all { it.used == 0.6 && it.durationSeconds == null })
    }

    @Test fun grokKeepsTheActualPeriodAndSeparatesMissingPercentFromExplicitZero() {
        fun quota(percent: String) = GrokCreditsProxyFetcher.parseCreditsJson("""{"config":{$percent
            "currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY","start":"2026-11-22T00:00:00Z","end":"2026-11-29T00:00:00Z"},
            "subscriptionTier":"$secret"}}""", now).localQuota
        val missing = quota("")
        val w = reading(missing).windows.single()
        assertNull(missing.matchParts)
        assertNull(w.used)
        assertEquals(QuotaWindowKind.GrokWeeklyCredits, w.kind)
        assertEquals(604800L, w.durationSeconds)
        assertEquals(Instant.parse("2026-11-29T00:00:00Z").toEpochMilliseconds(), w.resetsAt)
        assertEquals(0.0, reading(quota(""""creditUsagePercent":0,""")).windows.single().used)
        val separate = GrokCreditsProxyFetcher.parseCreditsJson("""{"config":{"creditUsagePercent":40,"currentPeriod":{"type":"WEEKLY",
            "start":"2026-11-22T00:00:00Z"},"billingPeriodEnd":"2026-12-15T00:00:00Z"}}""", now)
        assertNull(reading(separate.localQuota).windows.single().resetsAt)
    }

    @Test fun ampReportsNumericTierDetailsButWorkspaceRequiresItsOwnProjection() {
        val q = AmpUsageFetcher.parseDisplayText("""
            Signed in as $secret@example.invalid
            Amp Free: $7.50/$10.00 remaining (replenishes +$0.50/hour)
            Amp $secret Tier: agent usage $15.00 of $20.00 remaining (75%), orb usage 600h of 750h a1.small orb hours remaining (80%) - period 2026-10-30 to 2026-11-30
            Individual credits: $14.41 remaining - https://ampcode.com/settings
            $secret credits: $9876.54 remaining - https://ampcode.com/workspaces/private-workspace/settings
        """.trimIndent(), now).localQuota
        assertNull(q.matchParts)
        val r = reading(q)
        assertEquals(setOf(QuotaWindowKind.AmpFree, QuotaWindowKind.AmpAgent, QuotaWindowKind.AmpOrb), r.windows.map { it.kind }.toSet())
        assertEquals(QuotaDetails.AmpIndividual(14.41, 7.5, 10.0, 0.5, 15.0, 20.0, 600.0, 750.0), r.details)
        assertFalse(Json.encodeToString(r).contains("9876"))
        val workspace = reading(q, workspace = true)
        assertEquals(QuotaDetails.AmpWorkspace(9876.54), workspace.details)
        assertTrue(workspace.windows.isEmpty())
        assertFalse(Json.encodeToString(workspace).contains("14.41"))
        assertFalse(Json.encodeToString(workspace).contains("private-workspace"))
        assertNotNull(snapshot(q).ampWorkspaceScope)
    }

    @Test fun phoneCacheKeepsDisplaySemanticsButCannotRestoreSharingEvidence() {
        val settings = com.russhwolf.settings.MapSettings()
        fun roundTrip(q: LocalQuota): PhoneQuotaDisplay {
            val key = AccountKey(q.providerId, "record")
            val current = snapshot(q).copy(loginTag = "live-login", quotaIdentity = "verified", quotaTarget = "original")
            SettingsUsageSnapshotStore(settings, phoneDisplay = true).save(key, current)
            val restored = assertNotNull(SettingsUsageSnapshotStore(settings, phoneDisplay = true).load(key))
            assertNull(restored.loginTag); assertNull(restored.quotaIdentity); assertNull(restored.quotaTarget)
            assertNull(restored.toQuotaReading()); assertNull(restored.toQuotaReading(workspace = true))
            assertFalse("phoneDisplay" in Json.encodeToString(restored))
            assertNull(SettingsUsageSnapshotStore(settings).load(key)?.phoneQuotaDisplay())
            return assertNotNull(restored.copy(stale = true).phoneQuotaDisplay()).also { assertEquals(current.phoneQuotaDisplay(), it) }
        }
        for ((percent, expected) in listOf("" to null, "\"creditUsagePercent\":0," to 0.0, "\"creditUsagePercent\":40," to .4)) {
            val q = GrokCreditsProxyFetcher.parseCreditsJson("""{"config":{$percent"currentPeriod":{"type":"WEEKLY"},
                "billingPeriodEnd":"2026-12-15T00:00:00Z"}}""", now).localQuota
            val window = roundTrip(q).windows.single()
            assertEquals(expected, window.used); assertNull(window.reset)
        }
        val workspace = "Individual credits: $99 remaining - https://ampcode.com/workspaces/fixture/settings"
        val balances = roundTrip(AmpUsageFetcher.parseDisplayText("Individual credits: $10 remaining - https://ampcode.com/settings\n$workspace", now).localQuota)
        assertEquals(listOf("Individual credits: $10.00", "Workspace credits: $99.00"), balances.amounts)
        val ambiguous = roundTrip(AmpUsageFetcher.parseDisplayText("Amp Free: $7.50/$10.00 remaining\nIndividual credits: $14.41 remaining", now).localQuota)
        assertEquals(listOf("Amp Free remaining: $7.50 / $10.00", "Credits (scope unverified): $14.41"), ambiguous.amounts)
        val model = AntigravityUsageFetcher.availableModels(obj("""{"models":{"some-other-model":{"displayName":"Local label","quotaInfo":{"remainingFraction":0.1}}}}"""))
        val q = model.localQuota(null, null, "google")
        assertEquals("Local label", roundTrip(q).windows.single().label)
        assertEquals(.9, roundTrip(q).windows.single().used)
        assertEquals("Model 1", reading(q).windows.single().label)
    }

    @Test fun ampPayAsYouGoAndWorkspaceOnlyNeverProjectTheSyntheticCreditsWindow() {
        fun quota(text: String) = AmpUsageFetcher.parseDisplayText(text, now).localQuota
        val individual = quota("Individual credits: $0 remaining - https://ampcode.com/settings")
        assertEquals(QuotaDetails.AmpIndividual(creditsUsd = 0.0), reading(individual).details)
        assertTrue(reading(individual).windows.isEmpty())
        assertNull(snapshot(individual).toQuotaReading(true))
        val text = "Workspace $secret: $98 remaining"
        val only = quota(text)
        assertNull(snapshot(only).toQuotaReading())
        assertEquals(QuotaDetails.AmpWorkspace(98.0), reading(only, true).details)
        assertNotEquals(snapshot(only).ampWorkspaceScope, snapshot(quota(text)).ampWorkspaceScope)
        val ambiguous = quota("$text\nWorkspace Another: $50 remaining")
        assertNull(snapshot(ambiguous).ampWorkspaceScope)
        assertNull(snapshot(ambiguous).toQuotaReading(true))
    }

    @Test fun ampUsesOnlyUnambiguousBalanceScopesNotLookalikeNamesOrEmbeddedLinks() {
        fun account(text: String) = snapshot(AmpUsageFetcher.parseDisplayText(text, now).localQuota)
        val individual = "Individual credits: $10 remaining - https://ampcode.com/settings"
        val workspace = "Individual credits: $99 remaining - https://ampcode.com/workspaces/fixture/settings"
        val both = account("$individual\n$workspace")
        assertEquals(QuotaDetails.AmpIndividual(creditsUsd = 10.0), both.toQuotaReading()?.details)
        assertEquals(QuotaDetails.AmpWorkspace(99.0), both.toQuotaReading(true)?.details)
        assertEquals(both.ampWorkspaceScope, account(workspace.replace("$99", "$125")).ampWorkspaceScope)
        assertNotEquals(both.ampWorkspaceScope, account(workspace.replace("fixture", "other")).ampWorkspaceScope)
        assertNull(account(workspace).toQuotaReading())
        assertNull(account("Individual credits: $99 remaining").toQuotaReading(), "A workspace can also be named Individual")
        assertNull(account("$individual\nIndividual credits: $42 remaining").toQuotaReading())
        val linkInQuery = "Workspace $secret: $42 remaining - https://example.invalid/?link=https://ampcode.com/workspaces/fixture"
        assertNotEquals(account(linkInQuery).ampWorkspaceScope, account(linkInQuery).ampWorkspaceScope)
    }

    @Test fun cursorSeparatesUnknownUnlimitedZeroSpendAndReportedPercent() {
        fun quota(body: String) = CursorUsageFetcher.parseUsageSummaryJson(body, now).localQuota
        val q = quota("""{"membershipType":"$secret","individualUsage":{"plan":{"enabled":true,"used":39273,"limit":40000,
            "totalPercentUsed":15.7092,"autoPercentUsed":0.171,"apiPercentUsed":77.862},"onDemand":{"enabled":true,"used":99}}}""")
        val r = reading(q)
        assertNull(q.matchParts)
        assertEquals(listOf("Included", "Auto", "API", "On-demand"), r.windows.map { it.label })
        assertEquals(0.157092, r.windows[0].used!!, 1e-9)
        assertEquals(0.00171, r.windows[1].used!!, 1e-9)
        assertEquals(0.77862, r.windows[2].used!!, 1e-9)
        assertNull(r.windows[3].used)
        assertEquals(QuotaDetails.Cursor(39273, 40000, 99, null), r.details)
        assertEquals("Included spend: $392.73 / $400.00", r.detailLines().first())
        val unlimited = reading(quota("""{"isUnlimited":true}""")).windows.single()
        val unknown = reading(quota("""{"individualUsage":{"plan":{"enabled":true}}}""")).windows.single()
        val zero = reading(quota("""{"individualUsage":{"plan":{"totalPercentUsed":0}}}""")).windows.single()
        assertTrue(unlimited.unlimited); assertNull(unlimited.used)
        assertFalse(unknown.unlimited); assertNull(unknown.used)
        assertFalse(zero.unlimited); assertEquals(0.0, zero.used)
        assertNull(snapshot(quota("{}")).toQuotaReading())
    }

    @Test fun devinCopiesVisiblePercentAndResetAndSupportsBalanceOnly() {
        val q = DevinUsageFetcher.parseUserStatusJson("""{"userStatus":{"email":"$secret@example.invalid","planStatus":{
            "planInfo":{"planName":"$secret","hideDailyQuota":true},"dailyQuotaRemainingPercent":60,
            "weeklyQuotaRemainingPercent":75,"weeklyQuotaResetAtUnix":"1800000000","overageBalanceMicros":"2500000"}}}""").localQuota
        val r = reading(q)
        assertNull(q.matchParts)
        assertEquals(QuotaWindow(QuotaWindowKind.DevinWeekly, 0.25, 1_800_000_000_000L, 604_800L), r.windows.single())
        assertEquals(QuotaDetails.Devin(2.5), r.details)
        val balanceOnly = DevinUsageFetcher.parseUserStatusJson("""{"userStatus":{"planStatus":{"overageBalanceMicros":"0"}}}""").localQuota
        assertEquals(QuotaDetails.Devin(0.0), reading(balanceOnly).details)
        assertTrue(reading(balanceOnly).windows.isEmpty())
    }

    @Test fun wrongProviderErrorsAndCachedSnapshotsCannotBeProjected() {
        val s = snapshot(grokLocalQuota(20.0, now))
        assertNull(s.toQuotaReading()?.plan) // The local label holds a private test marker.
        assertEquals(QuotaPlan.SuperGrok, s.copy(planLabel = "SuperGrok").toQuotaReading()?.plan)
        assertNull(s.copy(stale = true).toQuotaReading())
        assertNull(s.copy(authState = AuthState.Error).toQuotaReading())
        assertNull(s.copy(providerId = ProviderId.DEVIN).toQuotaReading())
        assertNull(s.toQuotaReading(workspace = true))
        val cached = Json.decodeFromString<ProviderUsageSnapshot>(Json.encodeToString(s))
        assertNull(cached.toQuotaReading())
        val w = QuotaWindow(QuotaWindowKind.DevinDaily, 0.0)
        assertFailsWith<IllegalArgumentException> { QuotaReading(ProviderId.DEVIN, 0, List(17) { w }) }
        assertFailsWith<IllegalArgumentException> { QuotaReading(ProviderId.CURSOR, 0, listOf(w)) }
        assertFailsWith<IllegalArgumentException> { QuotaReading(ProviderId.AMP, 0, listOf(w.copy(kind = QuotaWindowKind.AmpFree)), QuotaDetails.AmpWorkspace(10.0)) }
        assertFailsWith<IllegalArgumentException> { w.copy(used = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { w.copy(durationSeconds = -10) }
        assertFailsWith<IllegalArgumentException> { w.copy(unlimited = true) }
    }
}
