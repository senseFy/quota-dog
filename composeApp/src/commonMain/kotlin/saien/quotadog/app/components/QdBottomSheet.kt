package saien.quotadog.app.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import saien.quotadog.app.theme.QdTheme

/**
 * Animated bottom sheet. Scrim fades, content slides up. Tapping scrim dismisses.
 *
 * `visible` controls the animation so callers can keep the sheet mounted briefly to allow
 * clean exit transitions instead of popping out.
 */
@Composable
fun QdBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val spacing = QdTheme.spacing
    val colors = QdTheme.colors
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(180)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(animationSpec = tween(220)) { it } + fadeIn(animationSpec = tween(220)),
            exit = slideOutVertically(animationSpec = tween(180)) { it } + fadeOut(animationSpec = tween(180)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .padding(start = spacing.md, end = spacing.md, top = spacing.md, bottom = spacing.md + safeBottom)
                    .clip(QdTheme.shapes.xl)
                    .background(colors.backgroundElevated),
            ) {
                Column(modifier = Modifier.padding(spacing.xxl)) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = spacing.lg)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(QdTheme.shapes.pill)
                            .background(colors.borderStrong),
                    )
                    content()
                }
            }
        }
    }
}
