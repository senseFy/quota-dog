package saien.quotadog.app.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import saien.quotadog.app.theme.QdTheme

/**
 * Flat bordered chrome icon button (replaces the former liquid-glass treatment).
 */
@Composable
fun QdGlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    diameter: Dp = 34.dp,
    content: @Composable () -> Unit,
) {
    val colors = QdTheme.colors
    val shape = QdTheme.shapes.sm
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()

    val targetMaterial = when {
        !enabled -> colors.surface.copy(alpha = 0.5f)
        pressed -> colors.surfaceMuted
        hovered -> colors.surfaceHover.copy(alpha = 0.45f)
        else -> colors.surface
    }
    val material by animateColorAsState(targetMaterial, animationSpec = tween(120))

    Box(
        modifier = modifier
            .size(diameter)
            .clip(shape)
            .background(material)
            .border(1.dp, colors.border, shape)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.55f),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
