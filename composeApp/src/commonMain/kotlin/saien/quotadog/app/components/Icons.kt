package saien.quotadog.app.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.CircleAlert
import com.composables.icons.lucide.Ellipsis
import com.composables.icons.lucide.Gauge
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw

/**
 * Thin wrappers around Lucide Compose Multiplatform icons, keeping the app's local naming
 * and default sizing stable at call sites.
 */

@Composable
internal fun QdLucideIcon(
    imageVector: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    tint: Color = Color.Black,
) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size),
    )
}

@Composable
fun QdRefreshIcon(modifier: Modifier = Modifier, size: Dp = 18.dp, tint: Color = Color.Black) {
    QdLucideIcon(Lucide.RefreshCw, modifier, size, tint)
}

@Composable
fun QdPlusIcon(modifier: Modifier = Modifier, size: Dp = 18.dp, tint: Color = Color.Black) {
    QdLucideIcon(Lucide.Plus, modifier, size, tint)
}

@Composable
fun QdMoreIcon(modifier: Modifier = Modifier, size: Dp = 18.dp, tint: Color = Color.Black) {
    QdLucideIcon(Lucide.Ellipsis, modifier, size, tint)
}

@Composable
fun QdChevronRightIcon(modifier: Modifier = Modifier, size: Dp = 16.dp, tint: Color = Color.Black) {
    QdLucideIcon(Lucide.ChevronRight, modifier, size, tint)
}

@Composable
fun QdAlertIcon(modifier: Modifier = Modifier, size: Dp = 18.dp, tint: Color = Color.Black) {
    QdLucideIcon(Lucide.CircleAlert, modifier, size, tint)
}

@Composable
fun QdGaugeIcon(modifier: Modifier = Modifier, size: Dp = 28.dp, tint: Color = Color.Black) {
    QdLucideIcon(Lucide.Gauge, modifier, size, tint)
}
