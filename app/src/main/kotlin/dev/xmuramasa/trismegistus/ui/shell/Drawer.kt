package dev.xmuramasa.trismegistus.ui.shell

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import dev.xmuramasa.trismegistus.ui.components.Dot
import dev.xmuramasa.trismegistus.ui.components.Glyph
import dev.xmuramasa.trismegistus.ui.components.RoleText
import dev.xmuramasa.trismegistus.ui.components.Tag
import dev.xmuramasa.trismegistus.ui.theme.Glyphs
import dev.xmuramasa.trismegistus.ui.theme.Ink
import dev.xmuramasa.trismegistus.ui.theme.Sizes
import dev.xmuramasa.trismegistus.ui.theme.Space
import dev.xmuramasa.trismegistus.ui.theme.Type

/** What the drawer shows next to Chat: an approval waits, or runs go. */
data class ChatMark(val approvals: Int, val running: Int)

/**
 * The Mac sidebar as a drawer (01b2): relay status on top, the sections (v1: Chat only, SPEC), this device and the
 * settings gear at the foot.
 */
@Composable
fun Drawer(
    status: RelayStatus,
    host: String,
    deviceLine: String,
    mark: ChatMark,
    onChat: () -> Unit,
    onSettings: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.width(Sizes.drawerWidth),
        drawerShape = RectangleShape,
        drawerContainerColor = Ink.Surface.sidebar,
        drawerContentColor = Ink.Text.onSidebar,
    ) {
        Column(Modifier.padding(horizontal = Space.xl, vertical = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            RoleText("TRISMEGISTUS", Type.heading, color = Ink.Text.onSidebar)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                Dot(status.color)
                RoleText("${RelayStatus.shortHost(host)} · ${status.word}", Type.monoSmall, color = Ink.Text.onSidebarSecondary)
            }
        }
        HorizontalDivider(color = Ink.Line.onSidebar)
        NavigationDrawerItem(
            label = { RoleText("Chat", Type.title, color = Ink.Text.primary) },
            selected = true,
            onClick = onChat,
            modifier = Modifier.padding(Space.s).height(Sizes.drawerItem).border(Sizes.selectedOutline, Ink.selection),
            icon = { Glyph(Glyphs.chat, tint = Ink.Text.primary) },
            badge = { ChatBadge(mark) },
            shape = RectangleShape,
            colors = NavigationDrawerItemDefaults.colors(
                selectedContainerColor = Ink.Surface.raised,
                selectedIconColor = Ink.Text.primary,
                selectedTextColor = Ink.Text.primary,
            ),
        )
        Spacer(Modifier.weight(1f))
        HorizontalDivider(color = Ink.Line.onSidebar)
        Row(
            Modifier.fillMaxWidth().padding(start = Space.xl, end = Space.s, top = Space.s, bottom = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoleText(deviceLine, Type.monoSmall, Modifier.weight(1f), color = Ink.Text.onSidebarSecondary, maxLines = 1)
            IconButton(onClick = onSettings) { Glyph(Glyphs.gear, tint = Ink.Text.onSidebarSecondary, description = "Settings") }
        }
    }
}

@Composable
private fun ChatBadge(mark: ChatMark) {
    when {
        mark.approvals > 0 -> Tag("Approval", Ink.marker)
        mark.running > 0 -> Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            Dot(Ink.Status.online)
            RoleText("${mark.running} running", Type.monoSmall, color = Ink.Text.primary)
        }
    }
}
