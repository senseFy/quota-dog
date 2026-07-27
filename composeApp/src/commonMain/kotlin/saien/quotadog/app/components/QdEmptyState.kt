package saien.quotadog.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import saien.quotadog.app.theme.QdTheme

@Composable
fun QdEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit = {
        QdGaugeIcon(size = 22.dp, tint = QdTheme.colors.textSecondary)
    },
) {
    val colors = QdTheme.colors
    val spacing = QdTheme.spacing
    val typo = QdTheme.typography

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = spacing.xxxl, bottom = spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(QdTheme.shapes.md)
                .background(colors.surfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Text(title, style = typo.titleLarge, color = colors.textPrimary)
        Text(
            description,
            style = typo.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = spacing.xl),
        )
    }
}
