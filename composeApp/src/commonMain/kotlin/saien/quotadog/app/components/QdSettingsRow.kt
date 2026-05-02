package saien.quotadog.app.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import saien.quotadog.app.theme.QdTheme

/**
 * Standard settings row: a label + optional supporting text on the left, with the control
 * (or another composable) stacked beneath. For row-with-trailing-control style, use the
 * [trailing] slot instead and pass `null` for [control].
 */
@Composable
fun QdSettingsRow(
    title: String,
    description: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    control: (@Composable () -> Unit)? = null,
) {
    val colors = QdTheme.colors
    val typo = QdTheme.typography
    val spacing = QdTheme.spacing

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = typo.titleMedium, color = colors.textPrimary)
                if (description != null) {
                    Text(description, style = typo.caption, color = colors.textTertiary)
                }
            }
            trailing?.invoke()
        }
        control?.invoke()
    }
}
