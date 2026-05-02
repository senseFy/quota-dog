package saien.quotadog.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * QuotaDog semantic color palette. Green-leaning, low saturation, designed for long-form
 * reading on dashboards. Always read through [QdTheme.colors] - do not hardcode hex values
 * inside components.
 */
@Immutable
data class QdColors(
    // Brand / primary action surfaces.
    val primary: Color,
    val primaryHover: Color,
    val primaryPressed: Color,
    val primaryMuted: Color,
    val onPrimary: Color,

    // Page + card surfaces.
    val background: Color,
    val backgroundElevated: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val surfaceHover: Color,
    val scrim: Color,

    // Borders & dividers.
    val border: Color,
    val borderStrong: Color,

    // Text hierarchy.
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textOnAccent: Color,

    // Status - used both for text and bar fills.
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val danger: Color,
    val dangerSoft: Color,

    // Provider accents (so all callers get them from theme, not hex).
    val codexAccent: Color,
    val claudeAccent: Color,
)

internal val QdLightColors = QdColors(
    primary = Color(0xFF2F7D5B),
    primaryHover = Color(0xFF286A4D),
    primaryPressed = Color(0xFF1F5840),
    primaryMuted = Color(0xFFE2F0E8),
    onPrimary = Color(0xFFF6FBF8),

    background = Color(0xFFF4F7F4),
    backgroundElevated = Color(0xFFFAFCFA),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFF1F5F2),
    surfaceHover = Color(0xFFEEF3EF),
    scrim = Color(0x55101814),

    border = Color(0xFFE3EAE5),
    borderStrong = Color(0xFFCFDCD4),

    textPrimary = Color(0xFF0F1F17),
    textSecondary = Color(0xFF4D6359),
    textTertiary = Color(0xFF859089),
    textOnAccent = Color(0xFFFFFFFF),

    success = Color(0xFF2F7D5B),
    successSoft = Color(0xFFE2F0E8),
    warning = Color(0xFFB76E1B),
    warningSoft = Color(0xFFFCEFD9),
    danger = Color(0xFFB94545),
    dangerSoft = Color(0xFFF7E2E2),

    codexAccent = Color(0xFF1B2A24),
    claudeAccent = Color(0xFFB75C2C),
)

internal val QdDarkColors = QdColors(
    primary = Color(0xFF6FBE99),
    primaryHover = Color(0xFF7FCAA6),
    primaryPressed = Color(0xFF5BA984),
    primaryMuted = Color(0xFF1F3A2C),
    onPrimary = Color(0xFF0F1F17),

    background = Color(0xFF0F1612),
    backgroundElevated = Color(0xFF161E1A),
    surface = Color(0xFF1A2520),
    surfaceMuted = Color(0xFF202C26),
    surfaceHover = Color(0xFF26342D),
    scrim = Color(0xCC000000),

    border = Color(0xFF2C3833),
    borderStrong = Color(0xFF3D4B45),

    textPrimary = Color(0xFFE6EFEA),
    textSecondary = Color(0xFFA6B3AC),
    textTertiary = Color(0xFF6F7E77),
    textOnAccent = Color(0xFFFFFFFF),

    success = Color(0xFF6FBE99),
    successSoft = Color(0xFF1F3A2C),
    warning = Color(0xFFD89757),
    warningSoft = Color(0xFF3A2B14),
    danger = Color(0xFFE07878),
    dangerSoft = Color(0xFF3C1F1F),

    codexAccent = Color(0xFFD7DBD8),
    claudeAccent = Color(0xFFE89368),
)

internal val LocalQdColors = staticCompositionLocalOf { QdLightColors }
