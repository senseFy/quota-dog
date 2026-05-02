package saien.quotadog.app.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import saien.quotadog.app.theme.QdTheme

enum class QdSnackbarTone { Info, Success, Warning, Danger }

data class QdSnackbarMessage(
    val text: String,
    val tone: QdSnackbarTone = QdSnackbarTone.Info,
    val durationMs: Long = 2800L,
    /** A monotonically increasing key so the same text twice still re-triggers display. */
    val id: Long = nextId(),
) {
    companion object {
        private var counter = 0L
        private fun nextId(): Long = ++counter
    }
}

class QdSnackbarController {
    var current: QdSnackbarMessage? by mutableStateOf(null)
        private set

    fun show(text: String, tone: QdSnackbarTone = QdSnackbarTone.Info, durationMs: Long = 2800L) {
        current = QdSnackbarMessage(text = text, tone = tone, durationMs = durationMs)
    }

    fun dismiss() {
        current = null
    }
}

@Composable
fun rememberQdSnackbarController(): QdSnackbarController = remember { QdSnackbarController() }

@Composable
fun QdSnackbarHost(controller: QdSnackbarController, modifier: Modifier = Modifier) {
    val msg = controller.current
    LaunchedEffect(msg?.id) {
        if (msg != null) {
            delay(msg.durationMs)
            controller.dismiss()
        }
    }
    val visible = msg != null
    val colors = QdTheme.colors
    val tone = msg?.tone ?: QdSnackbarTone.Info
    val (bg, fg, accent) = when (tone) {
        QdSnackbarTone.Info -> Triple(colors.textPrimary, colors.surface, colors.primary)
        QdSnackbarTone.Success -> Triple(colors.success, colors.onPrimary, colors.onPrimary)
        QdSnackbarTone.Warning -> Triple(colors.warning, Color.White, Color.White)
        QdSnackbarTone.Danger -> Triple(colors.danger, Color.White, Color.White)
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(animationSpec = tween(180)) { it } + fadeIn(tween(180)),
        exit = slideOutVertically(animationSpec = tween(160)) { it / 2 } + fadeOut(tween(160)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(QdTheme.spacing.lg),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .shadow(QdTheme.elevation.medium, QdTheme.shapes.md, clip = false)
                    .clip(QdTheme.shapes.md)
                    .background(bg)
                    .padding(horizontal = QdTheme.spacing.lg, vertical = QdTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(QdTheme.spacing.md),
            ) {
                Box(
                    modifier = Modifier
                        .clip(QdTheme.shapes.pill)
                        .background(accent.copy(alpha = 0.6f))
                        .padding(2.dp),
                )
                Text(
                    text = msg?.text.orEmpty(),
                    style = QdTheme.typography.bodyMedium,
                    color = fg,
                )
            }
        }
    }
}
