package saien.quotadog.app.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import saien.quotadog.app.theme.QdTheme

/**
 * Rounded, animated progress bar. The fill colour is derived from the ratio so the same
 * primitive expresses both healthy and at-risk usage.
 */
@Composable
fun QdProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    track: Color = QdTheme.colors.surfaceMuted,
    fill: Color? = null,
) {
    val colors = QdTheme.colors
    val target = progress.coerceIn(0f, 1f)
    val animated by animateFloatAsState(target, animationSpec = tween(durationMillis = 500))

    val resolvedFill = fill ?: when {
        target >= 0.9f -> colors.danger
        target >= 0.7f -> colors.warning
        else -> colors.success
    }

    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val r = size.height / 2f
        val radius = CornerRadius(r, r)
        drawRoundRect(color = track, size = size, cornerRadius = radius)
        if (animated > 0f) {
            val w = (size.width * animated).coerceAtLeast(size.height) // never narrower than the cap
            drawRoundRect(
                color = resolvedFill,
                topLeft = Offset.Zero,
                size = Size(w, size.height),
                cornerRadius = radius,
            )
        }
    }
}
