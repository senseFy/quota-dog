package saien.quotadog.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import saien.quotadog.app.theme.QdTheme

/**
 * Flat bordered panel surface. Zed-style: 1px stroke, no soft elevation shadow.
 * [elevated] is retained for call-site compatibility but does not change rendering.
 */
@Composable
fun QdCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(QdTheme.spacing.lg),
    background: Color = QdTheme.colors.surface,
    @Suppress("UNUSED_PARAMETER") elevated: Boolean = true,
    content: @Composable () -> Unit,
) {
    val shape = QdTheme.shapes.md
    val border = QdTheme.colors.border
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .padding(padding),
    ) {
        content()
    }
}
