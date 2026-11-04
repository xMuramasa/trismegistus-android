package dev.xmuramasa.trismegistus.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.xmuramasa.trismegistus.AppViewModel
import dev.xmuramasa.trismegistus.chat.ConversationList
import dev.xmuramasa.trismegistus.chat.ConversationRow
import dev.xmuramasa.trismegistus.chat.RelativeTime
import dev.xmuramasa.trismegistus.core.state.ConversationKind
import dev.xmuramasa.trismegistus.ui.components.Glyph
import dev.xmuramasa.trismegistus.ui.components.RoleText
import dev.xmuramasa.trismegistus.ui.components.Tag
import dev.xmuramasa.trismegistus.ui.theme.Glyphs
import dev.xmuramasa.trismegistus.ui.theme.Ink
import dev.xmuramasa.trismegistus.ui.theme.RowPadding
import dev.xmuramasa.trismegistus.ui.theme.Sizes
import dev.xmuramasa.trismegistus.ui.theme.Space
import dev.xmuramasa.trismegistus.ui.theme.Type

/** A conversation row: title, source icon, lock, pin, mono meta, RUN / APPROVAL; long-press opens its menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationRowView(row: ConversationRow, now: Long, model: AppViewModel, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true }, onLongClickLabel = "Conversation actions")
                .padding(start = RowPadding.start, end = RowPadding.end, top = RowPadding.vertical, bottom = RowPadding.vertical),
            horizontalArrangement = Arrangement.spacedBy(RowPadding.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                RoleText(ConversationList.title(row.info), Type.title, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    if (row.info.isPinned) Glyph(Glyphs.pin, size = Sizes.iconSmall, tint = Ink.Text.secondary, description = "Pinned")
                    Glyph(glyphOf(ConversationKind.of(row.info.source)), size = Sizes.iconSmall, tint = Ink.Text.secondary)
                    if (row.info.isReadOnly) Glyph(Glyphs.lock, size = Sizes.iconSmall, tint = Ink.Text.secondary, description = "Read-only")
                    val meta = listOfNotNull(
                        "pinned".takeIf { row.info.isPinned },
                        row.lastActivity?.let { RelativeTime.format(it, now) },
                    ) + row.tags.map { "#$it" }
                    RoleText(meta.joinToString(" · "), Type.monoSmall, color = Ink.Text.secondary, maxLines = 1)
                }
            }
            when (row.attention) {
                ConversationKind.Attention.APPROVAL -> Tag("Approval", Ink.marker)
                ConversationKind.Attention.RUNNING -> Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    RoleText("Run", Type.label, color = Ink.Text.link)
                    row.run?.let { RoleText(RelativeTime.clock(now - it.startedAt), Type.monoSmall) }
                }
                null -> Unit
            }
        }
        ConversationMenu(row.info, row.tags, menu, { menu = false }, model)
    }
}
