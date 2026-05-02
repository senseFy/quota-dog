package saien.quotadog.app.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

@Immutable
data class QdShapes(
    val xs: RoundedCornerShape,
    val sm: RoundedCornerShape,
    val md: RoundedCornerShape,
    val lg: RoundedCornerShape,
    val xl: RoundedCornerShape,
    val pill: RoundedCornerShape,
    val sheet: RoundedCornerShape,
)

internal val QdDefaultShapes = QdShapes(
    xs = RoundedCornerShape(6.dp),
    sm = RoundedCornerShape(10.dp),
    md = RoundedCornerShape(14.dp),
    lg = RoundedCornerShape(18.dp),
    xl = RoundedCornerShape(24.dp),
    pill = RoundedCornerShape(percent = 50),
    sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
)

internal val LocalQdShapes = staticCompositionLocalOf { QdDefaultShapes }
