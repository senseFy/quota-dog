package saien.quotadog.app.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import saien.quotadog.app.theme.QdTheme

/**
 * Lightweight CMP approximation of iOS 26 Liquid Glass for top-level chrome actions.
 *
 * The native effect depends on Apple's compositing pipeline, so this intentionally stays small:
 * a 44-48dp circular hit target, translucent material, subtle stroke, and top-left highlight.
 */
@Composable
fun QdGlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    diameter: Dp = 48.dp,
    content: @Composable () -> Unit,
) {
    val colors = QdTheme.colors
    val shape = QdTheme.shapes.pill
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()

    val targetMaterial = when {
        !enabled -> colors.backgroundElevated.copy(alpha = 0.38f)
        pressed -> colors.surfaceMuted.copy(alpha = 0.90f)
        hovered -> colors.surfaceHover.copy(alpha = 0.84f)
        else -> colors.backgroundElevated.copy(alpha = 0.70f)
    }
    val material by animateColorAsState(targetMaterial, animationSpec = tween(140))
    val highlightAlpha by animateFloatAsState(
        targetValue = when {
            !enabled -> 0.18f
            pressed -> 0.28f
            else -> 0.42f
        },
        animationSpec = tween(140),
    )

    Box(
        modifier = modifier
            .size(diameter)
            .clip(shape)
            .background(material)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = highlightAlpha),
                        Color.White.copy(alpha = 0.06f),
                        Color.Transparent,
                    ),
                )
            )
            .border(
                width = 1.dp,
                color = colors.borderStrong.copy(alpha = 0.72f),
                shape = shape,
            )
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
