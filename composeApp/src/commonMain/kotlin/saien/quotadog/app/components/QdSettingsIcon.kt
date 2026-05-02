package saien.quotadog.app.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings

@Composable
fun QdSettingsGearIcon(modifier: Modifier = Modifier, size: Dp = 18.dp, tint: Color = Color.Black) {
    QdLucideIcon(Lucide.Settings, modifier, size, tint)
}
