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
    xs = RoundedCornerShape(4.dp),
    sm = RoundedCornerShape(6.dp),
    md = RoundedCornerShape(8.dp),
    lg = RoundedCornerShape(10.dp),
    xl = RoundedCornerShape(12.dp),
    pill = RoundedCornerShape(percent = 50),
    sheet = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
)

internal val LocalQdShapes = staticCompositionLocalOf { QdDefaultShapes }
