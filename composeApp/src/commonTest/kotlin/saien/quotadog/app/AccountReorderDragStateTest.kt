package saien.quotadog.app

import saien.quotadog.AccountKey
import saien.quotadog.ProviderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountReorderDragStateTest {
    private val first = AccountKey(ProviderId.CODEX, "a")
    private val second = AccountKey(ProviderId.GROK, "b")
    private val third = AccountKey(ProviderId.DROID, "c")

    @Test
    fun draggingAcrossDifferentHeightCardsKeepsTheCardUnderThePointer() {
        val state = dragState(first)
        val moves = mutableListOf<Int>()
        var pointerY = 0f

        for (delta in listOf(86f, 200f, 0f, -200f, -86f)) {
            pointerY += delta
            state.dragBy(delta, itemSpacing = 10f, minimumSwapDistance = 24f) { key, direction ->
                assertEquals(first, key)
                moves += direction
            }
            assertEquals(pointerY, displayedTop(state, first, itemSpacing = 10f))
        }

        assertEquals(listOf(1, 1, -1, -1), moves)
        assertEquals(listOf(first, second, third), state.order)
        assertEquals(0f, state.translationY)
    }

    @Test
    fun swapThresholdIncludesSpacingAndDoesNotImmediatelySwapBack() {
        val state = dragState(first)
        val moves = mutableListOf<Int>()
        val onMove: (AccountKey, Int) -> Unit = { _, direction -> moves += direction }

        assertFalse(state.dragBy(84f, 10f, 24f, onMove))
        assertEquals(listOf(first, second, third), state.order)
        assertTrue(state.dragBy(2f, 10f, 24f, onMove))
        assertEquals(listOf(second, first, third), state.order)
        assertEquals(listOf(1), moves)
        assertFalse(state.dragBy(0f, 10f, 24f, onMove))
        assertEquals(listOf(second, first, third), state.order)
    }

    @Test
    fun upwardDragAccountsForSpacingAcrossMultipleRows() {
        val state = dragState(third)
        val initialTop = displayedTop(state, third, itemSpacing = 20f)
        val moves = mutableListOf<Int>()

        assertTrue(state.dragBy(-280f, 20f, 48f) { key, direction ->
            assertEquals(third, key)
            moves += direction
        })

        assertEquals(listOf(third, first, second), state.order)
        assertEquals(listOf(-1, -1), moves)
        assertEquals(initialTop - 280f, displayedTop(state, third, itemSpacing = 20f))
    }

    private fun dragState(key: AccountKey): AccountReorderDragState {
        return AccountReorderDragState().apply {
            draggingKey = key
            order = listOf(first, second, third)
            heights.putAll(mapOf(first to 100f, second to 160f, third to 80f))
        }
    }

    private fun displayedTop(state: AccountReorderDragState, key: AccountKey, itemSpacing: Float): Float {
        val preceding = state.order.takeWhile { it != key }
        return preceding.sumOf { state.heights.getValue(it).toDouble() }.toFloat() +
            preceding.size * itemSpacing + state.translationY
    }
}
