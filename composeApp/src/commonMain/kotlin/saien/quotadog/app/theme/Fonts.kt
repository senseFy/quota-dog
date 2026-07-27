package saien.quotadog.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import quotadog.composeapp.generated.resources.Res
import quotadog.composeapp.generated.resources.ibm_plex_mono_medium
import quotadog.composeapp.generated.resources.ibm_plex_mono_regular
import quotadog.composeapp.generated.resources.ibm_plex_sans_bold
import quotadog.composeapp.generated.resources.ibm_plex_sans_medium
import quotadog.composeapp.generated.resources.ibm_plex_sans_regular
import quotadog.composeapp.generated.resources.ibm_plex_sans_semibold
import quotadog.composeapp.generated.resources.ibm_plex_serif_bold
import quotadog.composeapp.generated.resources.ibm_plex_serif_regular
import quotadog.composeapp.generated.resources.ibm_plex_serif_semibold

@Composable
fun rememberQdSansFamily(): FontFamily {
    val regular = Font(Res.font.ibm_plex_sans_regular, FontWeight.Normal)
    val medium = Font(Res.font.ibm_plex_sans_medium, FontWeight.Medium)
    val semibold = Font(Res.font.ibm_plex_sans_semibold, FontWeight.SemiBold)
    val bold = Font(Res.font.ibm_plex_sans_bold, FontWeight.Bold)
    return remember(regular, medium, semibold, bold) {
        FontFamily(regular, medium, semibold, bold)
    }
}

@Composable
fun rememberQdSerifFamily(): FontFamily {
    val regular = Font(Res.font.ibm_plex_serif_regular, FontWeight.Normal)
    val semibold = Font(Res.font.ibm_plex_serif_semibold, FontWeight.SemiBold)
    val bold = Font(Res.font.ibm_plex_serif_bold, FontWeight.Bold)
    return remember(regular, semibold, bold) {
        FontFamily(regular, semibold, bold)
    }
}

@Composable
fun rememberQdMonoFamily(): FontFamily {
    val regular = Font(Res.font.ibm_plex_mono_regular, FontWeight.Normal)
    val medium = Font(Res.font.ibm_plex_mono_medium, FontWeight.Medium)
    return remember(regular, medium) {
        FontFamily(regular, medium)
    }
}
