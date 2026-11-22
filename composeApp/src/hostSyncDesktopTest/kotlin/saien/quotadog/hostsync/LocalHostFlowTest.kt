package saien.quotadog.hostsync

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import saien.quotadog.AccountKey
import saien.quotadog.ProviderId
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Runs the same authorization and refresh contract through either mobile app's real UI. */
class LocalHostFlowTest {
    @get:Rule val desktop = createComposeRule()

    @Test fun phoneUsesOnlyTheAuthorizedDesktopSource() {
        val iphone = System.getenv("QD_IOS_SIM") != null
        assumeTrue(iphone || System.getenv("QD_TEST_SERIAL") != null)
        val profile = File(System.getenv("QD_LOCAL_PROFILE"))
        val f = LocalHost(profile)
        val qr = profile.resolve("invitation.png")
        val evidence = File(System.getenv("QD_EVIDENCE"))
        fun record(line: String) { evidence.resolve("host-sync-results.txt").appendText("$line\n") }
        fun hostStatus() = f.host.state.value.let { "enabled=${it.enabled}, online=${it.online}, storageFailure=${it.saveFailed}, connectedPhones=${it.phones.count { p -> p.online }}" }
        fun upstream(path: String, post: Boolean = false): String {
            val c = URI("http://127.0.0.1:18788/$path").toURL().openConnection() as HttpURLConnection
            try { if (post) c.requestMethod = "POST"; return c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        }
        fun phone(vararg args: String, input: String = ""): String {
            val p = ProcessBuilder(listOf("python3", System.getenv("QD_PHONE_DRIVER")) + args).redirectErrorStream(true).start()
            p.outputStream.bufferedWriter().use { it.write(input) }
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) {
                if (args.first() !in setOf("match", "import")) record("FAILED ${args.joinToString(" ")} (${hostStatus()}): ${out.replace(Regex("\\b[0-9]{6}\\b"), "[number]")}")
                "Phone UI ${args.first()} failed: $out"
            }
            return out.trim()
        }
        fun click(label: String) {
            desktop.waitUntil(15_000) { desktop.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
            desktop.onNodeWithText(label).performScrollTo().performClick()
        }
        fun captureDesktop(name: String, node: SemanticsNodeInteraction) {
            evidence.resolve("host-sync-$name.png").writeBytes(Image.makeFromBitmap(node.captureToImage().asSkiaBitmap()).encodeToData()!!.bytes)
        }
        fun grant(label: String, source: AccountKey = f.source) {
            val options = shareableSources(f.store.state.value.accounts.values, f.preferences.emailPrivacyMode.value)
            val index = options.accounts.keys.indexOf(source)
            check(index >= 0) { "Missing ${source.providerId} in production sharing settings" }
            val buttons = desktop.onAllNodes(hasText(label) and hasAnyAncestor(hasTestTag("host-sharing")))
            desktop.waitUntil(10_000) { buttons.fetchSemanticsNodes().size >= options.accounts.size }
            buttons[index].performScrollTo().performClick()
        }
        fun pair() {
            click("Pair a phone")
            desktop.waitUntil(15_000) { desktop.onAllNodesWithTag("pairing-qr").fetchSemanticsNodes().isNotEmpty() }
            val code = desktop.onNodeWithTag("pairing-qr").performScrollTo()
            desktop.waitForIdle()
            qr.writeBytes(Image.makeFromBitmap(code.captureToImage().asSkiaBitmap()).encodeToData()!!.bytes)
            phone("import", qr.path); qr.delete()
            val digits = checkNotNull(f.host.state.value.digits)
            desktop.onNodeWithText(digits).assertExists(); phone("match", input = digits)
            // A desktop that was idle before approval must not ask for the user's Mac password.
            val keychain = profile.resolve("host-sync.keychain-db").also { check(it.isFile) }
            assertEquals(0, ProcessBuilder("/usr/bin/security", "lock-keychain", keychain.absolutePath).start().waitFor())
            click("Allow this phone")
            phone("expect", "Computer connected", "No accounts shared. Choose a workspace on your computer.")
            phone("absent", "Devin", "Refresh on computer")
        }
        try {
            evidence.mkdirs(); evidence.resolve("host-sync-results.txt").writeText("")
            phone("reset"); phone("expect", "No accounts yet", "Open QR image"); phone("capture", "start")
            desktop.setContent { LocalHostDashboard(f) }
            click("Enable host"); pair(); phone("capture", "empty")
            record("Two screens matched; desktop approved; authenticated new session had zero shared accounts.")

            grant("View only"); phone("expect", "75% used"); phone("absent", "25% used", "Refresh on computer")
            phone("open", "Devin"); phone("expect", "25% used", "75% used", "Extra balance", "$2.50", "View only")
            phone("absent", "Refresh on computer", "97% used", "owner@example.invalid")
            assertEquals("1", upstream("count")); assertEquals("1", upstream("unshared-count"))
            captureDesktop("desktop", desktop.onNodeWithTag("host-sharing"))
            phone("capture", "view"); record("View only: daily used 25%, weekly used 75%, USD 2.50; unrelated 97% source absent. Provider count=1.")

            grant("Allow refresh"); phone("expect", "Refresh on computer"); phone("close")
            phone("row-refresh", "Devin"); phone("expect", "Updated by computer"); phone("absent", "Close details")
            phone("open", "Devin"); phone("expect", "38% used")
            assertEquals("2", upstream("count")); val collected = phone("collected")
            phone("capture", "updated"); record("One phone tap: personal GetUserStatus count=2; daily used 38%; collected=$collected.")

            upstream("fail", true); phone("tap", "Refresh on computer"); phone("close"); phone("expect", "Refresh failed on computer")
            phone("open", "Devin"); phone("expect", "38% used")
            assertEquals(collected, phone("collected")); assertEquals("3", upstream("count"))
            phone("capture", "failed")
            record("Provider failure: count=3; 38% and original collection time preserved.")

            upstream("hold", true); phone("tap", "Refresh on computer")
            desktop.waitUntil(5_000) { upstream("count") == "4" }
            click("Pause host"); phone("expect", "Offline", "38% used"); phone("absent", "Refresh on computer")
            assertEquals(collected, phone("collected")); phone("capture", "offline")
            phone("restart"); phone("expect", "Computer unavailable"); phone("open", "Devin")
            phone("expect", "38% used"); assertEquals(collected, phone("collected"))
            upstream("release", true); click("Enable host")
            desktop.waitUntil(10_000) { f.host.state.value.online || f.host.state.value.saveFailed }
            record("Host resumed after request #4: ${hostStatus()}.")
            phone("expect", "53% used", "Refresh on computer")
            assertNotEquals(collected, phone("collected")); assertEquals("4", upstream("count"))
            record("Interrupted after upstream accepted request #4. Phone restart retained 38%; reconnect delivered 53% without a 5th call or another QR.")

            if (iphone) {
                phone("background"); grant("View only"); phone("foreground")
                phone("expect", "View only", "53% used"); phone("absent", "Refresh on computer")
                assertEquals("4", upstream("count"))
                record("iPhone returned from background with the new view-only grant and no repeated query.")
            } else try {
                // The forwarded socket stays routable; only Android's default network change retires it.
                phone("network", "offline"); phone("expect", "Offline", "53% used")
                grant("View only"); phone("network", "cellular")
                phone("expect", "View only", "53% used"); phone("absent", "Refresh on computer")
                assertEquals("4", upstream("count"))
                record("AVD default network lost and restored with only emulated mobile data. Fresh view-only grant, no QR or additional query. Transport was still adb reverse, NOT a public carrier test.")
            } finally { phone("network", "wifi") }

            grant("Not shared"); phone("expect", "No accounts shared. Choose a workspace on your computer.")
            phone("absent", "Devin", "Refresh on computer")
            grant("View only"); phone("open", "Devin"); phone("expect", "53% used", "View only"); click("Revoke phone")
            phone("expect", "Pairing revoked by computer", "No accounts yet"); phone("absent", "Devin")
            phone("capture", "revoked")
            phone("restart"); phone("expect", "Connect your computer", "No accounts yet")
            assertEquals("4", upstream("count")); assertEquals("1", upstream("unshared-count"))
            record("Unshare removed card; regrant was view-only; online revocation and cold restart left zero remote accounts. Final counts: personal=4, unshared=1.")

            pair(); grant("View only"); phone("open", "Devin"); phone("expect", "53% used"); phone("close")
            click("Pause host"); phone("expect", "Computer unavailable"); click("Revoke phone"); click("Enable host")
            phone("expect", "Relay did not accept this pairing", "75% used"); phone("absent", "Refresh on computer")
            phone("tap", "Forget computer…"); phone("tap", "Cancel"); phone("open", "Devin"); phone("expect", "53% used"); phone("close")
            phone("tap", "Forget computer…"); phone("expect", "Remove from phone"); phone("capture", "forget")
            phone("tap", "Remove from phone"); phone("expect", "Computer disconnected", "No accounts yet"); phone("absent", "Devin")
            assertEquals("4", upstream("count")); assertEquals("1", upstream("unshared-count"))
            record("Offline revoke: relay refused the previous ticket. Only explicit forget erased the offline phone's last reading; no upstream query.")

            // Every click uses the production shareableSources filter and HostSharingCard. Adding
            // only a protocol test here would miss the released Devin-only settings regression.
            pair()
            val readings = linkedMapOf(ProviderId.CURSOR to listOf("Included spend", "$392.73 / $400.00"), ProviderId.GROK to listOf("45% used"),
                ProviderId.CODEX to listOf("Resets available", "2"), ProviderId.CLAUDE_CODE to listOf("81% used"),
                ProviderId.ANTIGRAVITY to listOf("56% used"), ProviderId.AMP to listOf("Individual credits", "$14.41"),
                ProviderId.DROID to listOf("Extra usage balance", "$123.45"), ProviderId.DEVIN to listOf("Extra balance", "$2.50"))
            for ((id, expected) in readings) {
                val source = if (id == ProviderId.DEVIN) f.source else runBlocking { f.addProvider(id) }
                grant(if (id == ProviderId.CURSOR) "Allow refresh" else "View only", source)
                val n = readings.keys.indexOf(id) + 1
                phone("expect", "All providers ($n)"); phone("open", id.displayName); phone("expect", *expected.toTypedArray())
                if (id == ProviderId.CURSOR) {
                    phone("expect", "On-demand spend", "$3.47", "16% used", "78% used", "Not reported"); phone("capture", "cursor")
                }
                phone("close")
                record("$id: shared by Mac settings; phone displayed $expected; $n simultaneous sources.")
            }
            phone("reveal", "All providers (8)"); phone("capture", "multi")
            assertEquals("4", upstream("count")); assertEquals("1", upstream("unshared-count"))
            record("All eight remain shared together. The second, unshared Devin login was not queried by the phone.")
            desktop.onNodeWithText("Choose which accounts this phone can view or refresh on this Mac.").performScrollTo()
            captureDesktop("mac-grants", desktop.onNodeWithTag("host-sharing"))

            // Independent synthetic login, same confirmed ChatGPT user and workspace. Do not
            // inject a snapshot, match tag, grant or relay message: both collectors must run.
            fun calls(name: String) = upstream("provider-counts/$name").toInt()
            val before = calls("codex-phone"); val macCount = calls("codex-mac")
            phone("local-codex", input = """{"accessToken":"fixture-codex-phone","refreshToken":"","expiresAtEpochMillis":4102444800000}""")
            phone("expect", "Computer connected", "All providers (8)")
            desktop.waitUntil(5_000) { calls("codex-phone") == before + 1 }
            check(f.host.state.value.phones.single().cards.isEmpty()) { "Local phone account shared without a grant" }
            phone("reveal", "Computer connected"); phone("tap", "Codex (1)"); phone("absent", "Amp")
            phone("open", "Codex"); phone("expect", "Collected by this phone", "This phone", "My computer", "View only", "Refresh on this device")
            phone("capture", "merged"); phone("close")
            phone("tap", "Share from this phone"); phone("tap", "Allow refresh")
            phone("capture", "outgoing")
            desktop.waitUntil(10_000) { f.host.state.value.phones.single().cards.singleOrNull()?.reading != null }
            desktop.onNodeWithText("Source details (2)").assertExists()
            // Retire the Mac credential. Its grant vanishes, but the independently logged-in
            // phone still has one Codex card and its remote-only copy on the Mac is refreshable.
            val codex = f.store.state.value.accounts.values.single { it.providerId == ProviderId.CODEX }.accountKey
            runBlocking { f.store.delete(codex) }
            click("Refresh on phone")
            desktop.waitUntil(10_000) { calls("codex-phone") == before + 2 && f.host.state.value.phones.single().busyRefs.isEmpty() }
            assertEquals(macCount, calls("codex-mac"))
            click("Codex (1)"); desktop.onNodeWithText("Refresh on phone").performScrollTo()
            captureDesktop("mac-phone", desktop.onRoot())
            phone("tap", "Hide phone sharing"); phone("tap", "All providers (8)")
            record("Phone Codex: independent login merged into one card. Explicit reverse grant put it on the Mac. After Mac login removal, one Mac click queried only the phone (+1, Mac +0); the phone kept all eight dashboard entries.")
            val ref = f.host.state.value.phones.single().cards.single().ref
            f.host.pause(); phone("expect", "Computer unavailable"); phone("restart"); phone("expect", "Computer unavailable")
            f.host.enable(); phone("expect", "Computer connected")
            desktop.waitUntil(10_000) { f.host.state.value.phones.single().let { it.online && it.cards.singleOrNull()?.let { c -> c.ref == ref && c.access == Access.Refresh } == true } }
            val onReconnect = calls("codex-phone")
            click("Refresh on phone")
            desktop.waitUntil(10_000) { calls("codex-phone") == onReconnect + 1 && f.host.state.value.phones.single().busyRefs.isEmpty() }
            assertEquals(macCount, calls("codex-mac"))
            record("Cold phone restart preserved the same explicit reverse grant; a Mac tap fetched again on the phone without re-pairing or re-granting access.")
        } finally {
            qr.delete(); try { phone("clean") } finally { f.deleteFixture() }
        }
    }
}
