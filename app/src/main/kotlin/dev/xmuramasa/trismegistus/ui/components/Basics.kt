package dev.xmuramasa.trismegistus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.xmuramasa.trismegistus.ui.theme.Ink
import dev.xmuramasa.trismegistus.ui.theme.Sizes
import dev.xmuramasa.trismegistus.ui.theme.Space
import dev.xmuramasa.trismegistus.ui.theme.Type
import dev.xmuramasa.trismegistus.ui.theme.TypeRole

/** Text in a type role, in capitals when the role says so. */
@Composable
fun RoleText(
    text: String,
    role: TypeRole,
    modifier: Modifier = Modifier,
    color: Color = Ink.Text.primary,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        if (role.caps) text.uppercase() else text,
        modifier,
        color = color,
        style = role.style,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/** One of the draft's stroke icons; decorative unless it has a [description]. */
@Composable
fun Glyph(
    glyph: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = Sizes.icon,
    tint: Color = Ink.Text.primary,
    description: String? = null,
) {
    Icon(glyph, description, modifier.size(size), tint = tint)
}

/** A small filled square: status dots and badge dots. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = Sizes.badgeDot) {
    Box(modifier.size(size).background(color))
}

/** A caps tag on a fill (APPROVAL on magenta, NEW on yellow), ink text. */
@Composable
fun Tag(text: String, fill: Color, modifier: Modifier = Modifier, textColor: Color = Ink.Text.primary) {
    RoleText(
        text,
        Type.labelSmall,
        modifier.background(fill).padding(horizontal = Space.xs, vertical = Space.xxs),
        color = textColor,
    )
}
