package saien.quotadog.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import saien.quotadog.app.theme.QdTheme

/**
 * Single primitive for elevated content surfaces - a soft, very low elevation shadow plus a
 * 1px border for definition on light backgrounds.
 */
@Composable
fun QdCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(QdTheme.spacing.xl),
    background: Color = QdTheme.colors.surface,
    elevated: Boolean = true,
    content: @Composable () -> Unit,
) {
    val shape = QdTheme.shapes.lg
    val border = QdTheme.colors.border
    val shadow = if (elevated) 6.dp else 0.dp
    Box(
        modifier = modifier
            .shadow(shadow, shape, clip = false, ambientColor = Color(0x14000000), spotColor = Color(0x14000000))
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .padding(padding),
    ) {
        content()
    }
}
