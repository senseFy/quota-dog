package saien.quotadog.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale. All paddings, gaps, sizes go through these tokens - never raw `.dp` literals
 * in components. Step sizes follow a 4dp baseline grid.
 */
@Immutable
data class QdSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
    val huge: Dp = 48.dp,
)

@Immutable
data class QdElevation(
    val none: Dp = 0.dp,
    val low: Dp = 2.dp,
    val medium: Dp = 8.dp,
    val high: Dp = 18.dp,
)

internal val LocalQdSpacing = staticCompositionLocalOf { QdSpacing() }
internal val LocalQdElevation = staticCompositionLocalOf { QdElevation() }
