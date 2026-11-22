package saien.quotadog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Rule
import org.junit.Test
import saien.quotadog.app.theme.QuotaDogTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tray panel on Windows and Linux, which macOS also uses when its native panel is unavailable. */
class TrayPanelTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun providerFiltersScrollInsteadOfCrowdingTheHeader() {
        val accounts = ProviderId.entries.map { AccountUiState(AccountKey(it, "someone@example.com"), added = true) }
        compose.setContent {
            QuotaDogTheme(darkTheme = true) {
                Box(Modifier.size(TRAY_PANEL_WIDTH.dp, TRAY_PANEL_HEIGHT.dp)) {
                    QuotaDogTrayPanelContent(
                        // No account rows, so refresh and close are the only controls without a label.
                        accounts = emptyList(),
                        allAccounts = accounts,
                        availableProviders = ProviderId.entries,
                        selectedProvider = null,
                        refreshableAccounts = accounts,
                        emailPrivacyMode = EmailPrivacyMode.Visible,
                        usageDisplayMode = UsageDisplayMode.Remaining,
                        sinkExhaustedAccounts = false,
                        dashboardLayout = DashboardLayout.Compact,
                        isExpanded = { false },
                        onToggleExpanded = {},
                        onSelectProvider = {},
                        onRefresh = {},
                        onRefreshAccount = {},
                        onOpenWindow = {},
                        onClose = {},
                        onQuit = {},
                    )
                }
            }
        }

        // Every filter keeps its label on one line instead of wrapping into the room left over.
        val filterHeight = compose.onNodeWithText("All (${accounts.size})").getUnclippedBoundsInRoot().height
        ProviderId.entries.forEach { compose.onNodeWithText("${it.displayName} (1)").assertHeightIsEqualTo(filterHeight) }

        val buttons = compose.onAllNodes(
            hasClickAction() and SemanticsMatcher("has no text") { SemanticsProperties.Text !in it.config },
        )
        buttons.assertCountEquals(2)
        repeat(2) { index ->
            // Unclipped, because a button squeezed to no width has empty clipped bounds.
            val bounds = buttons[index].getUnclippedBoundsInRoot()
            assertEquals(bounds.height, bounds.width, "header buttons keep their full size")
            assertTrue(bounds.right <= TRAY_PANEL_WIDTH.dp, "header buttons stay inside the panel")
        }
    }
}
