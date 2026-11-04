package dev.xmuramasa.trismegistus.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xmuramasa.trismegistus.AppViewModel
import dev.xmuramasa.trismegistus.chat.ChatItem
import dev.xmuramasa.trismegistus.chat.ChatTimeline
import dev.xmuramasa.trismegistus.chat.ConversationList
import dev.xmuramasa.trismegistus.chat.RelativeTime
import dev.xmuramasa.trismegistus.core.protocol.RunStatus
import dev.xmuramasa.trismegistus.core.relay.ConnectionState
import dev.xmuramasa.trismegistus.core.state.CommandLog
import dev.xmuramasa.trismegistus.core.state.ConversationKind
import dev.xmuramasa.trismegistus.data.RelaySession
import dev.xmuramasa.trismegistus.ui.components.Glyph
import dev.xmuramasa.trismegistus.ui.components.RoleText
import dev.xmuramasa.trismegistus.ui.components.SteelTopBar
import dev.xmuramasa.trismegistus.ui.theme.Glyphs
import dev.xmuramasa.trismegistus.ui.theme.Ink
import dev.xmuramasa.trismegistus.ui.theme.Space
import dev.xmuramasa.trismegistus.ui.theme.Type
import dev.xmuramasa.trismegistus.ui.theme.endfieldPaper
import kotlinx.coroutines.launch

/** 02a-02d: the approval banner, the timeline, WORKING / WAITING, and the composer (none for read-only sources). */
@Composable
fun ConversationScreen(model: AppViewModel, session: RelaySession, id: String) {
    val store = session.store
    val sessions by store.sessions.collectAsStateWithLifecycle()
    val sessionList by store.sessionList.collectAsStateWithLifecycle()
    val overview by store.overview.collectAsStateWithLifecycle()
    val tags by store.tags.collectAsStateWithLifecycle()
    val cached by model.cached.collectAsStateWithLifecycle()
    val trays by model.trays.collectAsStateWithLifecycle()
    val me by session.me.collectAsStateWithLifecycle()
    val catalog by session.catalog.collectAsStateWithLifecycle()
    val sessionModels by session.sessionModels.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val connection by store.connection.collectAsStateWithLifecycle()
    LaunchedEffect(id) { store.open(id) }
    DisposableEffect(id) { onDispose { store.close(id) } }

    val live = sessions[id]
    // The store retries a failed history only when asked: ask again once the relay is back.
    val linked = connection is ConnectionState.Connected
    LaunchedEffect(linked) { if (linked && live?.historyError != null) store.loadHistory(id) }
    // Offline (or before the history arrives) the cached timeline stands in, read-only.
    val state = live?.takeIf { it.historyLoaded } ?: cached?.state(id) ?: live
    val info = sessionList.firstOrNull { it.id == id } ?: cached?.sessions?.firstOrNull { it.id == id }
    val readOnly = info?.isReadOnly ?: false
    val run = live?.run
    val approval = live?.approvals?.firstOrNull() ?: overview[id]?.pendingApproval
    val items = remember(state) { state?.let(ChatTimeline::items).orEmpty() }
    var transcript by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val now by rememberNow(running = run != null)

    Column(Modifier.fillMaxSize().endfieldPaper().imePadding()) {
        SteelTopBar(
            info?.let(ConversationList::title) ?: "Conversation",
            subtitle = listOfNotNull(ConversationKind.of(info?.source).title.lowercase(), run?.let { "run ${it.id.take(4)}" }).joinToString(" · "),
            navigation = Glyphs.back to "Back",
            onNavigation = { model.back() },
            actions = {
                val c = catalog
                if (!readOnly && c != null) {
                    ModelPicker(c, sessionModels[id] ?: c.current) { model.setModel(id, it) }
                }
                if (info != null) {
                    Box {
                        IconButton(onClick = { menu = true }) { Glyph(Glyphs.more, tint = Ink.Text.onDark, description = "Conversation actions") }
                        ConversationMenu(info, tags[id].orEmpty(), menu, { menu = false }, model)
                    }
                }
            },
        )
        approval?.let { a -> ApprovalBanner(a) { model.approve(a, it) } }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.l, Alignment.Bottom),
        ) {
            if (run != null) {
                item(key = "working") {
                    val events = live.events.filter { it.runId == run.id }
                    val first = events.firstOrNull()?.ts
                    val commands = CommandLog.entries(events).count { it.isCommand }
                    val waiting = run.status == RunStatus.WAITING_FOR_APPROVAL
                    val elapsed = first?.let { RelativeTime.clock(now - it) } ?: "00:00"
                    WorkingLine(waiting, "$elapsed · " + if (waiting) "approval" else "$commands ${if (commands == 1) "command" else "commands"}")
                }
            }
            items(items.asReversed(), key = { it.key }) { item ->
                when (item) {
                    is ChatItem.User -> UserMessage(item, session) { item.promptId?.let { model.retry(id, it) } }
                    is ChatItem.Answer -> AnswerMessage(item)
                    is ChatItem.Tool -> ToolRowView(item)
                    is ChatItem.Subagent -> SubagentRowView(item)
                    is ChatItem.Marker -> RunMarkerView(item.summary) { transcript = item.runId }
                    is ChatItem.Note -> NoteView(item)
                }
            }
            item(key = "status") {
                val error = live?.historyError
                when {
                    error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        RoleText(error, Type.caption, Modifier.weight(1f), color = Ink.Text.error)
                        TextButton(onClick = { scope.launch { store.loadHistory(id) } }) { RoleText("Retry", Type.button) }
                    }
                    state == null || (!state.historyLoaded && items.isEmpty()) ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Ink.Text.primary) }
                }
            }
        }
        if (readOnly) {
            RoleText(
                "Read-only: this conversation comes from ${info.source ?: "another app"}; reply there.",
                Type.caption,
                Modifier.fillMaxWidth().navigationBarsPadding().padding(Space.l),
                color = Ink.Text.secondary,
            )
        } else {
            val isViewer = me?.isManager == false
            Composer(
                model,
                id,
                ComposerState(run?.id, approval != null, canAttach = me?.isManager == true, isViewer = isViewer),
                trays[id].orEmpty(),
            )
        }
    }
    transcript?.let { runId ->
        TranscriptSheet(runId, CommandLog.entries(live?.events ?: state?.events.orEmpty())) { transcript = null }
    }
}
