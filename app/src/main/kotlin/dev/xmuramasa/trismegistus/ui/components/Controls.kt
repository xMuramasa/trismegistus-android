package dev.xmuramasa.trismegistus.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.xmuramasa.trismegistus.ui.theme.Ink
import dev.xmuramasa.trismegistus.ui.theme.Sizes
import dev.xmuramasa.trismegistus.ui.theme.Space
import dev.xmuramasa.trismegistus.ui.theme.Strokes
import dev.xmuramasa.trismegistus.ui.theme.Type

/** Primary: yellow with an ink outline (the one primary action). Plain: white, ink outline. Danger: red outline. */
enum class ButtonKind { PRIMARY, PLAIN, DANGER }

private class Look(val fill: Color, val content: Color, val border: Color)

private fun look(kind: ButtonKind) = when (kind) {
    ButtonKind.PRIMARY -> Look(Ink.selection, Ink.Text.primary, Ink.Line.strong)
    ButtonKind.PLAIN -> Look(Ink.Surface.raised, Ink.Text.primary, Ink.Line.strong)
    ButtonKind.DANGER -> Look(Ink.Surface.raised, Ink.Status.error, Ink.Status.bad)
}

/** Material's OutlinedButton, square, in words with a trailing icon: consequential actions keep their words. */
@Composable
fun EndfieldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PLAIN,
    glyph: ImageVector? = null,
    enabled: Boolean = true,
) {
    val look = look(kind)
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = Sizes.buttonHeight),
        enabled = enabled,
        shape = RectangleShape,
        border = BorderStroke(Strokes.hairline, if (enabled) look.border else Ink.Line.rule),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = look.fill,
            contentColor = look.content,
            disabledContainerColor = Ink.Surface.sunken,
            disabledContentColor = Ink.Text.muted,
        ),
        contentPadding = PaddingValues(horizontal = Space.l),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            RoleText(text, Type.button, color = if (enabled) look.content else Ink.Text.muted, maxLines = 1)
            if (glyph != null) Glyph(glyph, size = Sizes.iconSmall, tint = if (enabled) look.content else Ink.Text.muted)
        }
    }
}

/** An obvious action as an outlined square icon (Stop): the word is its content description. */
@Composable
fun EndfieldIconButton(
    glyph: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PLAIN,
    enabled: Boolean = true,
    glyphSize: Dp = Sizes.iconSmall,
) {
    val look = look(kind)
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.size(Sizes.buttonHeight),
        enabled = enabled,
        shape = RectangleShape,
        border = BorderStroke(Strokes.hairline, if (enabled) look.border else Ink.Line.rule),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = look.fill,
            contentColor = look.content,
            disabledContainerColor = Ink.Surface.sunken,
            disabledContentColor = Ink.Text.muted,
        ),
        contentPadding = PaddingValues(),
    ) {
        Glyph(glyph, size = glyphSize, tint = if (enabled) look.content else Ink.Text.muted, description = description)
    }
}

/**
 * A text field on `sunken`, square, no underline: Android's input, selection and IME through BasicTextField with our
 * decoration. [maxLines] above 1 grows the field to that many lines, then it scrolls; Enter stays a newline.
 */
@Composable
fun SunkenField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    maxLines: Int = 1,
    textStyle: TextStyle = Type.body.style,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.semantics { contentDescription = placeholder },
        enabled = enabled,
        singleLine = maxLines == 1,
        maxLines = maxLines,
        textStyle = textStyle.copy(color = Ink.Text.primary),
        cursorBrush = SolidColor(Ink.Text.primary),
        keyboardOptions = keyboardOptions,
        decorationBox = { inner ->
            Row(
                Modifier.background(Ink.Surface.sunken).heightIn(min = Sizes.fieldHeight).padding(horizontal = Space.m, vertical = Space.s),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) Glyph(leading, size = Sizes.iconSmall, tint = Ink.Text.secondary)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = textStyle, color = Ink.Text.secondary, maxLines = 1)
                    }
                    inner()
                }
            }
        },
    )
}

/**
 * The settings switch's look: an ink track with a yellow square thumb when on, sunken with a white one when off.
 * Material's Switch draws a round thumb only; the row around it is `toggleable(role = Role.Switch)`.
 */
@Composable
fun EndfieldSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    // The track is touch-wide less its padding (40), the thumb 16: it travels 24.
    val offset by animateDpAsState(if (checked) Space.xxl else 0.dp)
    Box(
        modifier
            .width(Sizes.touch)
            .background(if (checked) Ink.Surface.inverse else Ink.Surface.sunken)
            .border(Strokes.hairline, if (checked) Ink.Line.strong else Ink.Line.rule)
            .padding(Space.xs),
    ) {
        Box(
            Modifier.offset { IntOffset(offset.roundToPx(), 0) }
                .size(Space.l)
                .background(if (checked) Ink.selection else Ink.Surface.raised),
        )
    }
}
