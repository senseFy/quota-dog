package saien.quotadog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.jetbrains.skia.Image
import saien.quotadog.app.theme.QdTheme
import saien.quotadog.app.theme.QuotaDogTheme
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DesktopUpdateTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun installationRequiresAnExplicitClickAndCannotStartAnotherDownload() {
        val actions = mutableListOf<DesktopUpdateAction>()
        compose.setContent {
            QuotaDogTheme(darkTheme = true) {
                Box(Modifier.width(600.dp).background(QdTheme.colors.background).padding(32.dp)) {
                    DesktopUpdateControls(DesktopUpdateUiState(
                        message = "Ready to install 1.0.12. QuotaDog will close and reopen.",
                        canInstall = true, canDiscard = true,
                        notes = "Improved usage refresh and account sync.",
                    ), actions::add)
                }
            }
        }
        compose.waitForIdle()
        assertEquals(emptyList(), actions)
        compose.onNodeWithText("Check for updates").assertDoesNotExist()
        compose.onNodeWithText("Download update").assertDoesNotExist()
        val image = Image.makeFromBitmap(compose.onRoot().captureToImage().asSkiaBitmap())
        val output = java.io.File("build/reports/updater-ui.png")
        output.parentFile.mkdirs()
        output.writeBytes(checkNotNull(image.encodeToData()).bytes)
        compose.onNodeWithText("Install & restart").performClick()
        assertEquals(listOf(DesktopUpdateAction.Install), actions)
    }

    @Test
    fun receiptsSurviveRestartAndRejectCorruptionWithoutResettingState() {
        val directory = Files.createTempDirectory("quotadog-receipt-test-")
        try {
            val expected = UpdateReceipt(UUID.randomUUID().toString(), 24, "/Applications/QuotaDog.app")
            UpdateReceiptStore(directory).write(expected)
            val restored = UpdateReceiptStore(directory)
            assertEquals(expected, restored.read())
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("pending-installation"))))
            Files.writeString(directory.resolve("pending-installation"), "../../wrong-transaction\n24\n/Applications/QuotaDog.app\n")
            assertFailsWith<IllegalArgumentException> { restored.read() }
            assertEquals(true, Files.exists(directory.resolve("pending-installation")))
            restored.clear()
            assertNull(restored.read())
        } finally {
            Files.list(directory).use { stream -> stream.forEach(Files::delete) }
            Files.delete(directory)
        }
    }
}
