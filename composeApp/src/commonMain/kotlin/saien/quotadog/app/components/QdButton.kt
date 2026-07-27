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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import saien.quotadog.app.theme.QdTheme

enum class QdButtonVariant { Primary, Secondary, Ghost, Danger }
enum class QdButtonSize { Small, Medium }

/**
 * Single button primitive used everywhere. Rectangular with small radius for tool density;
 * pressed/hover are animated subtly (color + 1% scale).
 */
@Composable
fun QdButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: QdButtonVariant = QdButtonVariant.Primary,
    size: QdButtonSize = QdButtonSize.Medium,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()

    val (bgRest, bgHover, bgPressed, fg, borderColor) = when (variant) {
        QdButtonVariant.Primary -> Quintet(colors.primary, colors.primaryHover, colors.primaryPressed, colors.onPrimary, Color.Transparent)
        QdButtonVariant.Secondary -> Quintet(colors.surface, colors.surfaceHover.copy(alpha = 0.35f), colors.surfaceMuted.copy(alpha = 0.55f), colors.textPrimary, colors.border)
        QdButtonVariant.Ghost -> Quintet(Color.Transparent, colors.surfaceHover.copy(alpha = 0.35f), colors.surfaceMuted.copy(alpha = 0.55f), colors.textPrimary, Color.Transparent)
        QdButtonVariant.Danger -> Quintet(colors.danger, Color(0xFFB91C1C), Color(0xFF991B1B), Color.White, Color.Transparent)
    }

    val target = when {
        !enabled -> bgRest.copy(alpha = 0.5f)
        pressed -> bgPressed
        hovered -> bgHover
        else -> bgRest
    }
    val animatedBg by animateColorAsState(target, animationSpec = tween(120))
    val scale by animateFloatAsState(if (pressed && enabled) 0.98f else 1f, animationSpec = tween(100))

    val pad = when (size) {
        QdButtonSize.Small -> PaddingValues(horizontal = spacing.md, vertical = spacing.xs)
        QdButtonSize.Medium -> PaddingValues(horizontal = spacing.lg, vertical = spacing.sm)
    }
    val minHeight = if (size == QdButtonSize.Small) 30.dp else 34.dp
    val shape = QdTheme.shapes.sm

    Row(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(animatedBg)
            .then(if (borderColor != Color.Transparent) Modifier.border(1.dp, borderColor, shape) else Modifier)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .heightIn(min = minHeight)
            .padding(pad),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        androidx.compose.material.Text(
            text = text,
            style = typo.labelLarge,
            color = fg,
        )
    }
}

private data class Quintet(val a: Color, val b: Color, val c: Color, val d: Color, val e: Color)
