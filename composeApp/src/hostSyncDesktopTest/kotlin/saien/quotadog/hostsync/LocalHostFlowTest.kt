package saien.quotadog.hostsync

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.jetbrains.skia.Image
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Requires the local relay, counted Devin fixture and dedicated Android AVD (scripts/host-sync/local-e2e.sh). */
class LocalHostFlowTest {
    @get:Rule val desktop = createComposeRule()

    @Test fun androidUsesOnlyTheAuthorizedDesktopSource() {
        assumeTrue(System.getenv("QD_TEST_SERIAL") != null)
        val profile = File(System.getenv("QD_LOCAL_PROFILE"))
        val f = LocalHost(profile)
        val qr = profile.resolve("invitation.png")
        val evidence = File(System.getenv("QD_EVIDENCE"))
        fun record(line: String) { evidence.resolve("host-sync-results.txt").appendText("$line\n") }
        fun upstream(path: String, post: Boolean = false): String {
            val c = URI("http://127.0.0.1:18788/$path").toURL().openConnection() as HttpURLConnection
            try { if (post) c.requestMethod = "POST"; return c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        }
        fun phone(vararg args: String, input: String = ""): String {
            val p = ProcessBuilder(listOf("python3", System.getenv("QD_PHONE_DRIVER")) + args).redirectErrorStream(true).start()
            p.outputStream.bufferedWriter().use { it.write(input) }
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "Phone UI ${args.first()} failed: $out" }; return out.trim()
        }
        fun click(label: String) {
            desktop.waitUntil(15_000) { desktop.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
            desktop.onNodeWithText(label).performClick()
        }
        fun pair() {
            click("Pair a phone")
            desktop.waitUntil(15_000) { desktop.onAllNodesWithTag("pairing-qr").fetchSemanticsNodes().isNotEmpty() }
            qr.writeBytes(Image.makeFromBitmap(desktop.onNodeWithTag("pairing-qr").captureToImage().asSkiaBitmap()).encodeToData()!!.bytes)
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

            click("View only"); phone("expect", "25% used", "75% used", "Extra balance · USD 2.50", "View only")
            phone("absent", "Refresh on computer", "97% used", "owner@example.invalid")
            assertEquals("1", upstream("count")); assertEquals("1", upstream("unshared-count"))
            evidence.resolve("host-sync-desktop.png").writeBytes(Image.makeFromBitmap(desktop.onNodeWithTag("host-sharing").captureToImage().asSkiaBitmap()).encodeToData()!!.bytes)
            phone("capture", "view"); record("View only: daily used 25%, weekly used 75%, USD 2.50; unrelated 97% source absent. Provider count=1.")

            click("Allow refresh"); phone("tap", "Refresh on computer"); phone("expect", "38% used", "Updated by computer")
            assertEquals("2", upstream("count")); val collected = phone("collected")
            phone("capture", "updated"); record("One phone tap: personal GetUserStatus count=2; daily used 38%; collected=$collected.")

            upstream("fail", true); phone("tap", "Refresh on computer"); phone("expect", "Refresh failed on computer", "38% used")
            assertEquals(collected, phone("collected")); assertEquals("3", upstream("count"))
            phone("capture", "failed")
            record("Provider failure: count=3; 38% and original collection time preserved.")

            upstream("hold", true); phone("tap", "Refresh on computer")
            desktop.waitUntil(5_000) { upstream("count") == "4" }
            click("Pause host"); phone("expect", "Computer unavailable", "38% used")
            assertEquals(collected, phone("collected")); phone("capture", "offline")
            phone("restart"); phone("expect", "Computer unavailable", "38% used"); assertEquals(collected, phone("collected"))
            upstream("release", true); click("Enable host")
            phone("expect", "Computer connected", "53% used", "Refresh on computer")
            assertNotEquals(collected, phone("collected")); assertEquals("4", upstream("count"))
            record("Interrupted after upstream accepted request #4. Phone restart retained 38%; reconnect delivered 53% without a 5th call or another QR.")

            // The forwarded socket stays routable. Only Android's default-network change tells
            // a foreground app to retire it. A changed grant distinguishes resync from stale UI.
            try {
                phone("network", "offline"); phone("expect", "Computer unavailable", "53% used")
                click("View only"); phone("network", "cellular")
                phone("expect", "Computer connected", "View only", "53% used"); phone("absent", "Refresh on computer")
                assertEquals("4", upstream("count"))
                record("AVD default network lost and restored with only emulated mobile data. Fresh view-only grant, no QR or additional query. Transport was still adb reverse, NOT a public carrier test.")
            } finally { phone("network", "wifi") }

            click("Not shared"); phone("expect", "No accounts shared. Choose a workspace on your computer.")
            phone("absent", "Devin", "Refresh on computer")
            click("View only"); phone("expect", "53% used", "View only"); click("Revoke phone")
            phone("expect", "Pairing revoked by computer", "No accounts yet"); phone("absent", "Devin")
            phone("capture", "revoked")
            phone("restart"); phone("expect", "Connect your computer", "No accounts yet")
            assertEquals("4", upstream("count")); assertEquals("1", upstream("unshared-count"))
            record("Unshare removed card; regrant was view-only; online revocation and cold restart left zero remote accounts. Final counts: personal=4, unshared=1.")

            pair(); click("View only"); phone("expect", "53% used")
            click("Pause host"); phone("expect", "Computer unavailable"); click("Revoke phone"); click("Enable host")
            phone("expect", "Relay did not accept this pairing", "53% used"); phone("absent", "Refresh on computer")
            phone("tap", "Forget computer…"); phone("tap", "Cancel"); phone("expect", "53% used")
            phone("tap", "Forget computer…"); phone("expect", "Remove from phone"); phone("capture", "forget")
            phone("tap", "Remove from phone"); phone("expect", "Computer disconnected", "No accounts yet"); phone("absent", "Devin")
            assertEquals("4", upstream("count")); assertEquals("1", upstream("unshared-count"))
            record("Offline revoke: relay refused the previous ticket. Only explicit forget erased the offline phone's last reading; no upstream query.")
        } finally {
            qr.delete(); try { phone("clean") } finally { f.deleteFixture() }
        }
    }
}
