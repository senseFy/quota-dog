package saien.quotadog.app.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
        QdSnackbarTone.Info -> Triple(colors.surface, colors.textPrimary, colors.primary)
        QdSnackbarTone.Success -> Triple(colors.successSoft, colors.success, colors.success)
        QdSnackbarTone.Warning -> Triple(colors.warningSoft, colors.warning, colors.warning)
        QdSnackbarTone.Danger -> Triple(colors.dangerSoft, colors.danger, colors.danger)
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(animationSpec = tween(160)) { it } + fadeIn(tween(160)),
        exit = slideOutVertically(animationSpec = tween(140)) { it / 2 } + fadeOut(tween(140)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(QdTheme.spacing.md),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .clip(QdTheme.shapes.sm)
                    .background(bg)
                    .border(1.dp, colors.border, QdTheme.shapes.sm)
                    .padding(horizontal = QdTheme.spacing.md, vertical = QdTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(QdTheme.spacing.sm),
            ) {
                Box(
                    modifier = Modifier
                        .clip(QdTheme.shapes.xs)
                        .background(accent)
                        .padding(horizontal = 2.dp, vertical = 6.dp),
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
