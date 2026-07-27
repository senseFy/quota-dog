package saien.quotadog.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * QuotaDog semantic color palette. Soft desaturated sage accent on near-neutral surfaces —
 * green is reserved for actions and quiet meter fills, not large washes.
 * Always read through [QdTheme.colors] - do not hardcode hex values inside components.
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

    // Quiet healthy quota-bar fill (lower chroma than [primary]).
    val meter: Color,

    // Provider accents (so all callers get them from theme, not hex).
    val codexAccent: Color,
    val claudeAccent: Color,
    val grokAccent: Color,
    val cursorAccent: Color,
    val antigravityAccent: Color,
)

internal val QdLightColors = QdColors(
    primary = Color(0xFF5A7D6C),
    primaryHover = Color(0xFF4E6F60),
    primaryPressed = Color(0xFF425E51),
    primaryMuted = Color(0xFFE8EEEA),
    onPrimary = Color(0xFFF7FAF8),

    background = Color(0xFFF7F8F7),
    backgroundElevated = Color(0xFFFBFBFB),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFF1F2F1),
    surfaceHover = Color(0xFFEAEBEA),
    scrim = Color(0x55141816),

    border = Color(0xFFE4E6E4),
    borderStrong = Color(0xFFD5D8D5),

    textPrimary = Color(0xFF1A1F1C),
    textSecondary = Color(0xFF5C635E),
    textTertiary = Color(0xFF8B918C),
    textOnAccent = Color(0xFFFFFFFF),

    success = Color(0xFF5A7D6C),
    successSoft = Color(0xFFE8EEEA),
    warning = Color(0xFFB07A2E),
    warningSoft = Color(0xFFF7EFDF),
    danger = Color(0xFFB04A4A),
    dangerSoft = Color(0xFFF5E4E4),

    meter = Color(0xFF8FA094),

    codexAccent = Color(0xFF2A322E),
    claudeAccent = Color(0xFFB75C2C),
    grokAccent = Color(0xFF111111),
    cursorAccent = Color(0xFF3D7A72),
    antigravityAccent = Color(0xFF3B6FA8),
)

internal val QdDarkColors = QdColors(
    primary = Color(0xFF8FB5A1),
    primaryHover = Color(0xFF9FC0AE),
    primaryPressed = Color(0xFF7AA38E),
    primaryMuted = Color(0xFF243029),
    onPrimary = Color(0xFF121614),

    background = Color(0xFF121614),
    backgroundElevated = Color(0xFF171B19),
    surface = Color(0xFF1C211E),
    surfaceMuted = Color(0xFF252A27),
    surfaceHover = Color(0xFF2E3430),
    scrim = Color(0xCC000000),

    border = Color(0xFF313833),
    borderStrong = Color(0xFF3F4842),

    textPrimary = Color(0xFFE6EAE7),
    textSecondary = Color(0xFFA8B0AB),
    textTertiary = Color(0xFF7A837E),
    textOnAccent = Color(0xFFFFFFFF),

    success = Color(0xFF8FB5A1),
    successSoft = Color(0xFF243029),
    warning = Color(0xFFC9A06A),
    warningSoft = Color(0xFF33291A),
    danger = Color(0xFFD08888),
    dangerSoft = Color(0xFF3A2222),

    meter = Color(0xFF6B7F72),

    codexAccent = Color(0xFFD2D7D4),
    claudeAccent = Color(0xFFE0936C),
    grokAccent = Color(0xFFE6E6E6),
    cursorAccent = Color(0xFF6BB8AE),
    antigravityAccent = Color(0xFF8AB0D8),
)

internal val LocalQdColors = staticCompositionLocalOf { QdLightColors }
