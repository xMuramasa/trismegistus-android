package dev.xmuramasa.trismegistus.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xmuramasa.trismegistus.AppViewModel
import dev.xmuramasa.trismegistus.Screen
import dev.xmuramasa.trismegistus.chat.ConversationList
import dev.xmuramasa.trismegistus.core.relay.ConnectionState
import dev.xmuramasa.trismegistus.core.state.ConversationKind
import dev.xmuramasa.trismegistus.data.RelaySession
import dev.xmuramasa.trismegistus.ui.components.ButtonKind
import dev.xmuramasa.trismegistus.ui.components.Dot
import dev.xmuramasa.trismegistus.ui.components.EndfieldButton
import dev.xmuramasa.trismegistus.ui.components.Glyph
import dev.xmuramasa.trismegistus.ui.components.RoleText
import dev.xmuramasa.trismegistus.ui.components.SteelTopBar
import dev.xmuramasa.trismegistus.ui.components.SunkenField
import dev.xmuramasa.trismegistus.ui.shell.RelayStatus
import dev.xmuramasa.trismegistus.ui.theme.Glyphs
import dev.xmuramasa.trismegistus.ui.theme.Ink
import dev.xmuramasa.trismegistus.ui.theme.Sizes
import dev.xmuramasa.trismegistus.ui.theme.Space
import dev.xmuramasa.trismegistus.ui.theme.Strokes
import dev.xmuramasa.trismegistus.ui.theme.Type
import dev.xmuramasa.trismegistus.ui.theme.endfieldPaper

/** Chat (01b, 03d, 03e): kind tabs, the tab's header and filter, the rows, the FAB for a new APP conversation. */
@Composable
fun ConversationsScreen(model: AppViewModel, session: RelaySession, status: RelayStatus, onMenu: () -> Unit) {
    val store = session.store
    val connection by store.connection.collectAsStateWithLifecycle()
    val sessionList by store.sessionList.collectAsStateWithLifecycle()
    val overview by store.overview.collectAsStateWithLifecycle()
    val tags by store.tags.collectAsStateWithLifecycle()
    val cached by model.cached.collectAsStateWithLifecycle()
    val storedTab by model.tab.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    // Until the relay answers, the list is the cached one (03e).
    val list = sessionList.ifEmpty { if (status.isLinked) sessionList else cached?.sessions.orEmpty() }
    val summary = ConversationList.summary(list, overview)
    val kind = summary.resolving(storedTab?.let(ConversationKind::fromRawValue) ?: ConversationKind.App)
    val rows = ConversationList.rows(list, overview, tags, kind, filter)
    val elsewhere = ConversationKind.elsewhere(ConversationList.matchingSources(list, tags, filter), kind)
    val now by rememberNow(running = rows.any { it.run != null } || connection is ConnectionState.Reconnecting)
    val open = { id: String -> model.open(Screen.Conversation(id)) }

    Box(Modifier.fillMaxSize().endfieldPaper()) {
        Column(Modifier.fillMaxSize()) {
            SteelTopBar(
                "Chat",
                navigation = Glyphs.menu to "Menu",
                onNavigation = onMenu,
                actions = {
                    LinkBadge(status)
                    IconButton(onClick = { focus.requestFocus() }) { Glyph(Glyphs.search, tint = Ink.Text.onDark, description = "Filter") }
                },
            )
            (connection as? ConnectionState.Reconnecting)?.let { ReconnectBanner(it, now, model::reconnectNow) }
            (connection as? ConnectionState.AuthFailed)?.let {
                Banner("Token refused // pair again", it.message, "Settings") { model.open(Screen.Settings) }
            }
            KindTabs(summary, kind) { model.setTab(it.rawValue) }
            Row(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.l, top = Space.l, bottom = Space.s)) {
                RoleText("${kind.title} // ${summary[kind].count}", Type.label, Modifier.weight(1f), color = Ink.Text.secondary)
                RoleText("Pinned first", Type.labelSmall, color = Ink.Text.muted)
            }
            SunkenField(
                filter,
                { filter = it },
                "Filter conversations",
                Modifier.fillMaxWidth().padding(horizontal = Space.l).focusRequester(focus),
                leading = Glyphs.search,
            )
            HorizontalDivider(Modifier.padding(top = Space.m), thickness = Strokes.hairline, color = Ink.Line.rule)
            if (elsewhere != null) {
                val (other, count) = elsewhere
                TextButton(onClick = { model.setTab(other.rawValue) }, Modifier.padding(horizontal = Space.s)) {
                    RoleText("$count more in ${other.title} ›", Type.button, color = Ink.Text.link)
                }
            }
            if (rows.isEmpty() && elsewhere == null) {
                EmptyTab(kind, filter.isNotBlank(), model::createConversation)
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(rows, key = { it.info.id }) { row ->
                        Column(Modifier.background(Ink.Surface.window)) {
                            ConversationRowView(row, now, model) { open(row.info.id) }
                            HorizontalDivider(thickness = Strokes.hairline, color = Ink.Line.rule)
                        }
                    }
                }
            }
        }
        if (rows.isNotEmpty()) {
            FloatingActionButton(
                onClick = model::createConversation,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(Space.l)
                    .size(Sizes.fab).border(Strokes.hairline, Ink.Line.strong),
                shape = RectangleShape,
                containerColor = Ink.selection,
                contentColor = Ink.Text.primary,
            ) { Glyph(Glyphs.plus, description = "New conversation") }
        }
    }
}

@Composable
private fun LinkBadge(status: RelayStatus) {
    Row(
        Modifier.padding(horizontal = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(status.color)
        RoleText(if (status.isLinked) "Linked" else "Offline", Type.labelSmall, color = Ink.Text.onDark)
    }
}

@Composable
private fun ReconnectBanner(state: ConnectionState.Reconnecting, now: Long, retry: () -> Unit) {
    val seconds = ((state.atMillis - now + 999) / 1000).coerceAtLeast(0)
    Banner("Reconnecting // attempt ${state.attempt}", "next try in $seconds s · cached conversations", "Retry", retry)
}

/** The dark strip under the title rule (03e). */
@Composable
private fun Banner(title: String, detail: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ink.Bar.middle).padding(start = Space.l, end = Space.s, top = Space.s, bottom = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(Glyphs.wifiOff, tint = Ink.Text.onDark)
        Column(Modifier.weight(1f)) {
            RoleText(title, Type.label, color = Ink.Text.onSidebar)
            RoleText(detail, Type.monoSmall, color = Ink.Text.onDark, maxLines = 2)
        }
        TextButton(onClick = onAction) { RoleText(action, Type.button, color = Ink.selection) }
    }
}

/** 03d: the kind's empty line; the APP tab offers a new conversation. */
@Composable
private fun EmptyTab(kind: ConversationKind, filtered: Boolean, create: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(Space.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Glyph(glyphOf(kind), size = Sizes.touch, tint = Ink.Text.muted)
        RoleText(if (filtered) "Nothing matches the filter" else kind.emptyText + " yet", Type.title)
        if (kind == ConversationKind.App && !filtered) {
            Text(
                "Telegram and terminal chats are in their tabs.",
                style = Type.body.style,
                color = Ink.Text.secondary,
                textAlign = TextAlign.Center,
            )
            EndfieldButton("New conversation", create, kind = ButtonKind.PRIMARY, glyph = Glyphs.plus)
        }
    }
}
