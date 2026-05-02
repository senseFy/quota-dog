package saien.quotadog.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.Colors
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/** Single entry point - `QdTheme.colors`, `QdTheme.spacing`, etc. */
object QdTheme {
    val colors: QdColors
        @Composable @ReadOnlyComposable
        get() = LocalQdColors.current

    val typography: QdTypography
        @Composable @ReadOnlyComposable
        get() = LocalQdTypography.current

    val shapes: QdShapes
        @Composable @ReadOnlyComposable
        get() = LocalQdShapes.current

    val spacing: QdSpacing
        @Composable @ReadOnlyComposable
        get() = LocalQdSpacing.current

    val elevation: QdElevation
        @Composable @ReadOnlyComposable
        get() = LocalQdElevation.current
}

/**
 * Wrap the app once at the top level; everything beneath uses [QdTheme]. Internally still
 * configures M2 [MaterialTheme] so wrapped M2 widgets (TextField, Snackbar, AlertDialog)
 * pick up the same identity.
 */
@Composable
fun QuotaDogTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val qd = if (darkTheme) QdDarkColors else QdLightColors
    val typo = QdDefaultTypography
    val shapes = QdDefaultShapes

    val materialColors = Colors(
        primary = qd.primary,
        primaryVariant = qd.primaryHover,
        secondary = qd.primary,
        secondaryVariant = qd.primaryHover,
        background = qd.background,
        surface = qd.surface,
        error = qd.danger,
        onPrimary = qd.onPrimary,
        onSecondary = qd.onPrimary,
        onBackground = qd.textPrimary,
        onSurface = qd.textPrimary,
        onError = Color.White,
        isLight = !darkTheme,
    )

    val materialTypography = Typography(
        defaultFontFamily = androidx.compose.ui.text.font.FontFamily.Default,
        h4 = typo.displayLarge,
        h5 = typo.titleLarge,
        h6 = typo.titleMedium,
        body1 = typo.bodyLarge,
        body2 = typo.bodyMedium,
        button = typo.labelLarge,
        caption = typo.caption,
    )

    val materialShapes = Shapes(
        small = shapes.sm,
        medium = shapes.md,
        large = shapes.lg,
    )

    CompositionLocalProvider(
        LocalQdColors provides qd,
        LocalQdTypography provides typo,
        LocalQdShapes provides shapes,
        LocalQdSpacing provides QdSpacing(),
        LocalQdElevation provides QdElevation(),
    ) {
        MaterialTheme(
            colors = materialColors,
            typography = materialTypography,
            shapes = materialShapes,
            content = content,
        )
    }
}
