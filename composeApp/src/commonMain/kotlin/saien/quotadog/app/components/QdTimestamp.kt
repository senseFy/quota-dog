package saien.quotadog.app.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import saien.quotadog.app.theme.QdTheme
import saien.quotadog.exactLocalTimeLabel

/** Keeps the compact label; mouse dwell or a single touch reveals the exact timestamp. */
@Composable
fun QdTimestamp(
    timestamp: Instant?,
    content: @Composable () -> Unit,
) {
    if (timestamp == null) {
        content()
        return
    }

    val interaction = remember(timestamp) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var hoverVisible by remember(timestamp) { mutableStateOf(false) }
    var tappedOpen by remember(timestamp) { mutableStateOf(false) }
    LaunchedEffect(hovered, timestamp) {
        hoverVisible = false
        if (hovered) {
            delay(500)
            hoverVisible = true
        }
    }
    val gap = with(LocalDensity.current) { QdTheme.spacing.xs.roundToPx() }
    val positionProvider = remember(gap) { TimestampPositionProvider(gap) }

    Box(
        modifier = Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            role = Role.Button,
            onClickLabel = "Show exact time",
            onClick = { if (!hovered) tappedOpen = true },
        ),
    ) {
        content()
        if (hoverVisible || tappedOpen) {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = {
                    tappedOpen = false
                    hoverVisible = false
                },
                // Hover must not steal focus. Touch opens a dismissible popup: an outside tap
                // (including the time itself) or Back closes it without clicking through.
                properties = PopupProperties(focusable = tappedOpen),
            ) {
                Surface(
                    modifier = Modifier.widthIn(max = 320.dp),
                    shape = QdTheme.shapes.sm,
                    color = QdTheme.colors.backgroundElevated,
                    border = BorderStroke(1.dp, QdTheme.colors.border),
                    elevation = QdTheme.elevation.medium,
                ) {
                    Text(
                        text = timestamp.exactLocalTimeLabel(),
                        style = QdTheme.typography.numeric,
                        color = QdTheme.colors.textPrimary,
                        modifier = Modifier.padding(
                            horizontal = QdTheme.spacing.md,
                            vertical = QdTheme.spacing.sm,
                        ),
                    )
                }
            }
        }
    }
}

private class TimestampPositionProvider(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.right - popupContentSize.width
        } else {
            anchorBounds.left
        }
        val below = anchorBounds.bottom + gap
        val y = if (below + popupContentSize.height <= windowSize.height) {
            below
        } else {
            anchorBounds.top - gap - popupContentSize.height
        }
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}
