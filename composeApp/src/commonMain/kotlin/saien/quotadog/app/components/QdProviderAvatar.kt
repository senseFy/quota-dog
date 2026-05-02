package saien.quotadog.app.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import saien.quotadog.ProviderId
import saien.quotadog.app.theme.QdTheme
import quotadog.composeapp.generated.resources.Res
import quotadog.composeapp.generated.resources.provider_claudecode
import quotadog.composeapp.generated.resources.provider_codex

/**
 * Provider marks from @lobehub/icons-static-svg 1.88.0 (MIT).
 */
@Composable
fun QdProviderAvatar(
    provider: ProviderId,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val colors = QdTheme.colors
    val tint = when (provider) {
        ProviderId.CODEX -> colors.codexAccent
        ProviderId.CLAUDE_CODE -> colors.claudeAccent
    }
    val icon = when (provider) {
        ProviderId.CODEX -> Res.drawable.provider_codex
        ProviderId.CLAUDE_CODE -> Res.drawable.provider_claudecode
    }
    val iconScale = when (provider) {
        ProviderId.CODEX -> 0.7f
        ProviderId.CLAUDE_CODE -> 0.76f
    }
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = provider.displayName,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(size * iconScale),
        )
    }
}
