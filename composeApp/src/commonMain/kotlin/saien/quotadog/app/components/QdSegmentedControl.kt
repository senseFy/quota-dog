package saien.quotadog.app.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import saien.quotadog.app.theme.QdTheme

/**
 * Pill-shaped segmented selector. Animates the active segment background. The whole control
 * shares one rounded outer track; pressing a segment swaps the highlighted index.
 */
@Composable
fun <T> QdSegmentedControl(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(QdTheme.shapes.pill)
            .background(colors.surfaceMuted)
            .padding(4.dp),
    ) {
        options.forEach { (label, value) ->
            val active = value == selected
            val targetBg by animateColorAsState(
                if (active) colors.surface else Color.Transparent,
                animationSpec = tween(160),
            )
            val targetFg by animateColorAsState(
                if (active) colors.textPrimary else colors.textSecondary,
                animationSpec = tween(160),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 32.dp)
                    .clip(QdTheme.shapes.pill)
                    .background(targetBg)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(value) },
                    )
                    .padding(horizontal = spacing.md, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = typo.labelLarge, color = targetFg)
            }
        }
    }
}
