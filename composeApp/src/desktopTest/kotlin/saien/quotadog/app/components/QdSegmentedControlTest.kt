package saien.quotadog.app.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import saien.quotadog.app.theme.QuotaDogTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QdSegmentedControlTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun openingScrollableSettingsPreservesTopPosition() {
        val scroll = ScrollState(0)
        compose.setContent {
            QuotaDogTheme(darkTheme = false) {
                Column(Modifier.size(360.dp, 220.dp).verticalScroll(scroll)) {
                    repeat(5) {
                        Spacer(Modifier.height(80.dp))
                        QdSegmentedControl(
                            options = listOf("Off" to false, "On" to true),
                            selected = false,
                            onSelect = {},
                        )
                    }
                    Spacer(Modifier.height(300.dp))
                }
            }
        }

        compose.runOnIdle {
            assertTrue(scroll.maxValue > 0)
            assertEquals(0, scroll.value)
        }
    }

    @Test
    fun scrollableProviderSelectorKeepsSelectionVisible() {
        val scroll = ScrollState(0)
        val selected = mutableStateOf(7)
        compose.setContent {
            QuotaDogTheme(darkTheme = false) {
                Box(Modifier.size(240.dp, 60.dp).horizontalScroll(scroll)) {
                    QdSegmentedControl(
                        options = (0..7).map { "Provider $it" to it },
                        selected = selected.value,
                        onSelect = { selected.value = it },
                        fillWidth = false,
                        autoScrollToSelected = true,
                    )
                }
            }
        }

        compose.onNodeWithText("Provider 7").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(scroll.value > 0)
            selected.value = 0
        }
        compose.onNodeWithText("Provider 0").assertIsDisplayed()
    }
}
