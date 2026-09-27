package saien.quotadog.app

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import saien.quotadog.AccountKey
import saien.quotadog.app.components.QdGripVerticalIcon
import saien.quotadog.app.theme.QdTheme
import saien.quotadog.moveAccountKey

internal class AccountReorderDragState {
    var draggingKey: AccountKey? by mutableStateOf(null)
    var translationY: Float by mutableFloatStateOf(0f)
    val heights = mutableStateMapOf<AccountKey, Float>()

    /** Visible order, updated as soon as a row swaps so the gesture does not wait for recomposition. */
    var order: List<AccountKey> = emptyList()

    fun dragBy(
        deltaY: Float,
        itemSpacing: Float,
        minimumSwapDistance: Float,
        onMove: (AccountKey, Int) -> Unit,
    ): Boolean {
        val accountKey = draggingKey ?: return false
        var offset = translationY + deltaY
        var moved = false
        var swaps = 0
        while (swaps < order.size) {
            val index = order.indexOf(accountKey)
            if (index < 0) break
            val height = heights[accountKey] ?: 0f
            if (index < order.lastIndex) {
                val nextHeight = heights[order[index + 1]] ?: height
                val distance = nextHeight + itemSpacing
                val threshold = (distance / 2f).coerceAtLeast(minimumSwapDistance)
                if (offset > threshold) {
                    order = moveAccountKey(order, accountKey, 1)
                    onMove(accountKey, 1)
                    offset -= distance
                    moved = true
                    swaps++
                    continue
                }
            }
            if (index > 0) {
                val prevHeight = heights[order[index - 1]] ?: height
                val distance = prevHeight + itemSpacing
                val threshold = (distance / 2f).coerceAtLeast(minimumSwapDistance)
                if (offset < -threshold) {
                    order = moveAccountKey(order, accountKey, -1)
                    onMove(accountKey, -1)
                    offset += distance
                    moved = true
                    swaps++
                    continue
                }
            }
            break
        }
        translationY = offset
        return moved
    }
}

@Composable
internal fun rememberAccountReorderDragState(): AccountReorderDragState {
    return remember { AccountReorderDragState() }
}

internal fun Modifier.reportAccountReorderHeight(
    accountKey: AccountKey,
    state: AccountReorderDragState,
): Modifier = onGloballyPositioned { coordinates ->
    val height = coordinates.size.height.toFloat()
    if (state.heights[accountKey] != height) {
        state.heights[accountKey] = height
    }
}

@Composable
internal fun Modifier.accountReorderVisual(
    accountKey: AccountKey,
    state: AccountReorderDragState,
): Modifier {
    val shape = QdTheme.shapes.md
    val elevation = with(LocalDensity.current) { QdTheme.elevation.high.toPx() }
    val draggingNow = state.draggingKey == accountKey
    return zIndex(if (draggingNow) 1f else 0f).graphicsLayer {
        val dragging = state.draggingKey == accountKey
        translationY = if (dragging) state.translationY else 0f
        if (dragging) {
            shadowElevation = elevation
            this.shape = shape
            clip = false
        }
    }
}

/**
 * Drag handle gesture. Pointers are consumed on the initial pass so the page's vertical
 * scroll does not move while the account row is being reordered.
 */
@Composable
internal fun Modifier.accountDragHandle(
    accountKey: AccountKey,
    dragState: AccountReorderDragState,
    visibleKeys: List<AccountKey>,
    itemSpacing: Dp,
    onMove: (AccountKey, Int) -> Unit,
    onDragFinished: () -> Unit,
): Modifier {
    val visibleKeysState = rememberUpdatedState(visibleKeys)
    val onMoveState = rememberUpdatedState(onMove)
    val onDragFinishedState = rememberUpdatedState(onDragFinished)
    val spacingPx = with(LocalDensity.current) { itemSpacing.toPx() }
    val minimumSwapDistance = with(LocalDensity.current) { 24.dp.toPx() }
    return pointerInput(accountKey, spacingPx, minimumSwapDistance) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            down.consume()
            dragState.draggingKey = accountKey
            dragState.translationY = 0f
            dragState.order = visibleKeysState.value
            var moved = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val deltaY = change.positionChangeIgnoreConsumed().y
                    change.consume()
                    if (!change.pressed) break
                    if (dragState.dragBy(deltaY, spacingPx, minimumSwapDistance, onMoveState.value)) {
                        moved = true
                    }
                }
            } finally {
                dragState.draggingKey = null
                dragState.translationY = 0f
                dragState.order = emptyList()
                if (moved) onDragFinishedState.value()
            }
        }
    }
}

@Composable
internal fun QdReorderHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        QdGripVerticalIcon(tint = QdTheme.colors.textTertiary, size = 16.dp)
    }
}
