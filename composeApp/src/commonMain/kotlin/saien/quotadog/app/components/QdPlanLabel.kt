package saien.quotadog.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import saien.quotadog.app.theme.QdTheme

@Composable
fun QdPlanLabel(text: String, modifier: Modifier = Modifier) {
    val colors = QdTheme.colors
    Text(
        text = text,
        modifier = modifier
            .widthIn(max = 148.dp)
            .clip(QdTheme.shapes.pill)
            .background(colors.primaryMuted)
            .padding(horizontal = QdTheme.spacing.sm, vertical = QdTheme.spacing.xxs),
        style = QdTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
        color = colors.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
