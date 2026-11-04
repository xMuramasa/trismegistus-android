package dev.xmuramasa.trismegistus.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import dev.xmuramasa.trismegistus.core.state.ConversationKind
import dev.xmuramasa.trismegistus.ui.components.Dot
import dev.xmuramasa.trismegistus.ui.components.Glyph
import dev.xmuramasa.trismegistus.ui.components.RoleText
import dev.xmuramasa.trismegistus.ui.theme.Glyphs
import dev.xmuramasa.trismegistus.ui.theme.Ink
import dev.xmuramasa.trismegistus.ui.theme.Sizes
import dev.xmuramasa.trismegistus.ui.theme.Space
import dev.xmuramasa.trismegistus.ui.theme.Strokes
import dev.xmuramasa.trismegistus.ui.theme.Type

/** A kind's icon in the tabs and on its rows. */
fun glyphOf(kind: ConversationKind): ImageVector = when (kind) {
    ConversationKind.App -> Glyphs.app
    ConversationKind.Telegram -> Glyphs.telegram
    ConversationKind.Terminal -> Glyphs.terminal
    ConversationKind.Cron -> Glyphs.jobs
    ConversationKind.OneShot -> Glyphs.commands
    ConversationKind.All, is ConversationKind.Other -> Glyphs.chat
}

private fun attentionColor(a: ConversationKind.Attention?): Color? = when (a) {
    ConversationKind.Attention.APPROVAL -> Ink.marker
    ConversationKind.Attention.RUNNING -> Ink.Status.online
    null -> null
}

/** APP / TELEGRAM / TERMINAL as bare icon tabs with mono counts and badge dots, then MORE ▾ with the rest. */
@Composable
fun KindTabs(summary: ConversationKind.Summary, selected: ConversationKind, onSelect: (ConversationKind) -> Unit) {
    val index = ConversationKind.tabs.indexOf(selected).takeIf { it >= 0 } ?: ConversationKind.tabs.size
    var more by remember { mutableStateOf(false) }
    PrimaryTabRow(
        selectedTabIndex = index,
        containerColor = Color.Transparent,
        contentColor = Ink.Text.primary,
        indicator = {
            TabRowDefaults.PrimaryIndicator(
                Modifier.tabIndicatorOffset(index, matchContentSize = true),
                width = Dp.Unspecified,
                height = Sizes.tabIndicator,
                color = Ink.Line.strong,
                shape = RectangleShape,
            )
        },
        divider = { HorizontalDivider(thickness = Strokes.hairline, color = Ink.Line.rule) },
    ) {
        for (kind in ConversationKind.tabs) {
            val entry = summary[kind]
            Tab(selected = kind == selected, onClick = { onSelect(kind) }) {
                TabContent(glyphOf(kind), entry.count, attentionColor(entry.attention), kind.title, kind == selected)
            }
        }
        val moreKinds = summary.more
        val moreCount = moreKinds.filter { it != ConversationKind.All }.sumOf { summary[it].count }
        Tab(selected = selected.isInMore, onClick = { more = true }) {
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TabContent(
                        if (selected.isInMore) glyphOf(selected) else Glyphs.more,
                        if (selected.isInMore) summary[selected].count else moreCount,
                        attentionColor(summary.moreAttention),
                        "More",
                        selected.isInMore,
                    )
                    Glyph(Glyphs.chevronDown, size = Sizes.iconSmall, tint = Ink.Text.secondary)
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    for (kind in moreKinds) {
                        DropdownMenuItem(
                            text = { Text("${kind.title}  ${summary[kind].count}") },
                            leadingIcon = { Glyph(glyphOf(kind), size = Sizes.iconSmall) },
                            onClick = {
                                more = false
                                onSelect(kind)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabContent(glyph: ImageVector, count: Int, dot: Color?, title: String, selected: Boolean) {
    Row(
        Modifier.padding(vertical = Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Glyph(glyph, size = Sizes.iconTab, tint = if (selected) Ink.Text.primary else Ink.Text.secondary, description = title)
            if (dot != null) Dot(dot, Modifier.align(Alignment.TopEnd).offset(x = Space.xs, y = -Space.xs))
        }
        RoleText(count.toString(), if (selected) Type.monoBold else Type.monoSmall, color = if (selected) Ink.Text.primary else Ink.Text.secondary)
    }
}
