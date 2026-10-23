package saien.quotadog.app.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import saien.quotadog.app.theme.QuotaDogTheme
import saien.quotadog.exactLocalTimeLabel

class QdTimestampTest {
    @get:Rule
    val compose = createComposeRule()

    private val resetAt = Instant.parse("2026-12-31T19:04:59Z")
    private val expiresAt = Instant.parse("2027-01-02T00:07:13Z")

    private fun showTimes() {
        compose.setContent {
            QuotaDogTheme(darkTheme = false) {
                Column(Modifier.size(360.dp, 240.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    QdTimestamp(resetAt) { Text("3h12m") }
                    Text("27% used")
                    QdTimestamp(null) { Text("No expiry") }
                    QdTimestamp(expiresAt) { Text("1d5h") }
                }
            }
        }
    }

    @Test
    fun hoverRevealsOnlyTheTimeUnderThePointerAndClosesOnExit() {
        showTimes()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("3h12m").performMouseInput { enter(center) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText(resetAt.exactLocalTimeLabel()).assertDoesNotExist()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText(resetAt.exactLocalTimeLabel()).assertIsDisplayed()
        compose.onNodeWithText("27% used").performMouseInput { moveTo(center) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText(resetAt.exactLocalTimeLabel()).assertDoesNotExist()
        compose.onNodeWithText("1d5h").performMouseInput { moveTo(center) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText(expiresAt.exactLocalTimeLabel()).assertIsDisplayed()
        compose.onNodeWithText("1d5h").performMouseInput { exit() }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText(expiresAt.exactLocalTimeLabel()).assertDoesNotExist()
    }

    @Test
    fun singleTouchOpensAndOutsideTouchDismissesWithoutReopening() {
        showTimes()
        compose.onNodeWithText("3h12m").performTouchInput { click() }
        compose.onNodeWithText(resetAt.exactLocalTimeLabel()).assertIsDisplayed()
        compose.onNodeWithText("3h12m").performTouchInput { click() }
        compose.onNodeWithText(resetAt.exactLocalTimeLabel()).assertDoesNotExist()
        compose.onNodeWithText("1d5h").performTouchInput { click() }
        compose.onNodeWithText(expiresAt.exactLocalTimeLabel()).assertIsDisplayed()
        compose.onNodeWithText("27% used").performTouchInput { click() }
        compose.onNodeWithText(expiresAt.exactLocalTimeLabel()).assertDoesNotExist()
    }

    @Test
    fun missingTimestampHasNoInteraction() {
        showTimes()
        compose.onNodeWithText("No expiry").assertHasNoClickAction()
        compose.onNodeWithText("No expiry").performMouseInput { enter(center) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText(resetAt.exactLocalTimeLabel()).assertDoesNotExist()
        compose.onNodeWithText(expiresAt.exactLocalTimeLabel()).assertDoesNotExist()
    }
}
