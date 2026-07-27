package saien.quotadog.app.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import saien.quotadog.app.theme.QdTheme
import kotlin.math.roundToInt

/**
 * Rectangular segmented selector (tab-like). Animates the active segment background.
 */
@Composable
fun <T> QdSegmentedControl(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = true,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    val density = LocalDensity.current
    val selectedIndex = options.indexOfFirst { it.second == selected }
    val segmentBounds = remember(options.size) { mutableStateMapOf<Int, SegmentBounds>() }
    val indicatorX = remember { Animatable(0f) }
    val indicatorWidth = remember { Animatable(0f) }
    val indicatorHeight = remember { Animatable(0f) }
    var indicatorInitialized by remember { mutableStateOf(false) }
    val selectedBounds = segmentBounds[selectedIndex]
    val shape = QdTheme.shapes.sm

    LaunchedEffect(selectedBounds) {
        val bounds = selectedBounds ?: return@LaunchedEffect
        if (!indicatorInitialized) {
            indicatorX.snapTo(bounds.x.toFloat())
            indicatorWidth.snapTo(bounds.width.toFloat())
            indicatorHeight.snapTo(bounds.height.toFloat())
            indicatorInitialized = true
            return@LaunchedEffect
        }

        val animationSpec = tween<Float>(
            durationMillis = 180,
            easing = FastOutSlowInEasing,
        )
        launch { indicatorX.animateTo(bounds.x.toFloat(), animationSpec) }
        launch { indicatorWidth.animateTo(bounds.width.toFloat(), animationSpec) }
        launch { indicatorHeight.animateTo(bounds.height.toFloat(), animationSpec) }
    }

    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .clip(shape)
            .background(colors.surfaceMuted.copy(alpha = 0.55f))
            .border(1.dp, colors.border, shape)
            .padding(2.dp),
    ) {
        if (indicatorInitialized && selectedBounds != null) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(indicatorX.value.roundToInt(), 0) }
                    .width(with(density) { indicatorWidth.value.toDp() })
                    .height(with(density) { indicatorHeight.value.toDp() })
                    .clip(QdTheme.shapes.xs)
                    .background(colors.surface)
                    .border(1.dp, colors.border, QdTheme.shapes.xs),
            )
        }

        Row(
            modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier,
        ) {
            options.forEachIndexed { index, (label, value) ->
                val active = value == selected
                val targetFg by animateColorAsState(
                    if (active) colors.textPrimary else colors.textSecondary,
                    animationSpec = tween(140),
                )
                Box(
                    modifier = Modifier
                        .then(if (fillWidth) Modifier.weight(1f) else Modifier)
                        .heightIn(min = 28.dp)
                        .onGloballyPositioned { coordinates ->
                            val position = coordinates.positionInParent()
                            val bounds = SegmentBounds(
                                x = position.x.roundToInt(),
                                width = coordinates.size.width,
                                height = coordinates.size.height,
                            )
                            if (segmentBounds[index] != bounds) {
                                segmentBounds[index] = bounds
                            }
                        }
                        .clip(QdTheme.shapes.xs)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(value) },
                        )
                        .padding(horizontal = spacing.md, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = typo.labelLarge, color = targetFg)
                }
            }
        }
    }
}

private data class SegmentBounds(
    val x: Int,
    val width: Int,
    val height: Int,
)
