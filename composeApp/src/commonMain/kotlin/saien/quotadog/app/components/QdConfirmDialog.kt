package saien.quotadog.app.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import saien.quotadog.app.theme.QdTheme

@Composable
fun QdConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    cancelLabel: String = "Cancel",
) {
    val colors = QdTheme.colors
    val spacing = QdTheme.spacing
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(160)),
            exit = fadeOut(animationSpec = tween(160)),
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
            enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.92f, animationSpec = tween(180)),
            exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.96f, animationSpec = tween(140)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                modifier = Modifier
                    .padding(spacing.xxl)
                    .widthIn(max = 380.dp)
                    .fillMaxWidth()
                    .clip(QdTheme.shapes.lg)
                    .background(colors.surface)
                    .padding(spacing.xxl),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    Text(title, style = QdTheme.typography.titleLarge, color = colors.textPrimary)
                    Text(message, style = QdTheme.typography.bodyMedium, color = colors.textSecondary)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
                    ) {
                        QdButton(text = cancelLabel, onClick = onDismiss, variant = QdButtonVariant.Ghost, size = QdButtonSize.Small)
                        QdButton(
                            text = confirmLabel,
                            onClick = onConfirm,
                            variant = if (destructive) QdButtonVariant.Danger else QdButtonVariant.Primary,
                            size = QdButtonSize.Small,
                        )
                    }
                }
            }
        }
    }
}
