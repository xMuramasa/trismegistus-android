package dev.xmuramasa.trismegistus.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val square = RoundedCornerShape(Radius.none)

// Material's own components (menus, dialogs, fields, sheets) read these; ours read the tokens directly.
private val colors = lightColorScheme(
    primary = Palette.yellow,
    onPrimary = Ink.Text.primary,
    secondary = Palette.ink,
    onSecondary = Palette.raised,
    background = Ink.Surface.window,
    onBackground = Ink.Text.primary,
    surface = Ink.Surface.raised,
    onSurface = Ink.Text.primary,
    surfaceVariant = Ink.Surface.sunken,
    onSurfaceVariant = Ink.Text.secondary,
    surfaceContainer = Ink.Surface.raised,
    surfaceContainerLow = Ink.Surface.raised,
    surfaceContainerHigh = Ink.Surface.raised,
    surfaceContainerHighest = Ink.Surface.sunken,
    inverseSurface = Ink.Surface.inverse,
    inverseOnSurface = Ink.Text.onDark,
    inversePrimary = Palette.yellow,
    outline = Ink.Line.strong,
    outlineVariant = Ink.Line.rule,
    error = Ink.Text.error,
    onError = Palette.raised,
    scrim = Palette.ink,
)

private val typography = Typography(
    displaySmall = Type.heading.style,
    headlineSmall = Type.heading.style,
    titleLarge = Type.heading.style,
    titleMedium = Type.title.style,
    titleSmall = Type.bodyStrong.style,
    bodyLarge = Type.body.style,
    bodyMedium = Type.body.style,
    bodySmall = Type.caption.style,
    labelLarge = Type.button.style,
    labelMedium = Type.label.style,
    labelSmall = Type.labelSmall.style,
)

/** Light only: Endfield paper and ink, square edges. */
@Composable
fun TrismegistusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes = Shapes(extraSmall = square, small = square, medium = square, large = square, extraLarge = square),
        content = content,
    )
}
