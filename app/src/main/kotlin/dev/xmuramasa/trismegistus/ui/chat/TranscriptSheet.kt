package dev.xmuramasa.trismegistus.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import dev.xmuramasa.trismegistus.core.state.CommandLog
import dev.xmuramasa.trismegistus.core.state.TerminalEntry
import dev.xmuramasa.trismegistus.ui.components.RoleText
import dev.xmuramasa.trismegistus.ui.theme.Ink
import dev.xmuramasa.trismegistus.ui.theme.Opacity
import dev.xmuramasa.trismegistus.ui.theme.Sizes
import dev.xmuramasa.trismegistus.ui.theme.Space
import dev.xmuramasa.trismegistus.ui.theme.Type
import dev.xmuramasa.trismegistus.ui.theme.endfieldContours
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val seconds = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

/** 02c: the Mac's terminal pane as an ink bottom sheet, one run's calls as item cards; commands only or all tools. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptSheet(runId: String, entries: List<TerminalEntry>, onDismiss: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf(CommandLog.Filter.COMMANDS) }
    val shown = entries.filter { it.runId == runId && (filter == CommandLog.Filter.ALL || it.isCommand) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RectangleShape,
        containerColor = Ink.Surface.terminal,
        contentColor = Ink.Text.onDark,
        dragHandle = {
            Box(
                Modifier.padding(vertical = Space.s).width(Sizes.sheetHandleWidth).height(Sizes.sheetHandleHeight)
                    .background(Ink.Line.rule),
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().endfieldContours(Ink.Surface.sunken, Opacity.terminalContours)) {
            Row(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.s), verticalAlignment = Alignment.CenterVertically) {
                RoleText("Transcript // run ${runId.take(4)}", Type.label, Modifier.weight(1f), color = Ink.Text.onSidebar)
                FilterTab("Commands", filter == CommandLog.Filter.COMMANDS) { filter = CommandLog.Filter.COMMANDS }
                FilterTab("All tools", filter == CommandLog.Filter.ALL) { filter = CommandLog.Filter.ALL }
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                items(shown, key = { it.id }) { ItemCard(it) }
            }
            RoleText(
                "Output arrives when a command finishes, cut at 4,000 characters.",
                Type.caption,
                Modifier.padding(Space.l),
                color = Ink.Text.onDark,
            )
        }
    }
}

@Composable
private fun FilterTab(text: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            RoleText(text, Type.labelSmall, color = if (selected) Ink.Text.onSidebar else Ink.Text.onDark)
            Box(Modifier.width(Sizes.touch).height(Sizes.tabIndicator).background(if (selected) Ink.selection else Ink.Surface.terminal))
        }
    }
}

/** An orange head (red for a failed call) that folds a white body: `$ command`, the output, the exit code. */
@Composable
private fun ItemCard(entry: TerminalEntry) {
    var open by rememberSaveable(entry.id) { mutableStateOf(entry.failed) }
    val time = seconds.format(Instant.ofEpochMilli(entry.time).atZone(ZoneId.systemDefault()))
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(if (entry.failed) Ink.Status.bad else Ink.Card.header)
                .clickable(role = Role.Button, onClickLabel = if (open) "Fold" else "Unfold") { open = !open }
                .heightIn(min = Sizes.touch).padding(horizontal = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoleText(if (open) "−" else "+", Type.monoBold, color = Ink.Card.onHeader)
            RoleText(time, Type.monoBold, color = Ink.Card.onHeader)
            RoleText(
                entry.tool + (entry.exitCode?.takeIf { it != 0 }?.let { " · exit $it" } ?: ""),
                Type.monoSmall,
                Modifier.weight(1f),
                color = Ink.Card.onHeader,
                maxLines = 1,
            )
            RoleText(
                if (entry.isRunning) "…" else entry.duration?.let { "%.1f s".format(Locale.ROOT, it) }.orEmpty(),
                Type.monoBold,
                color = Ink.Card.onHeader,
            )
        }
        if (open) {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().background(Ink.Surface.raised).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    entry.command?.let { RoleText(if (entry.isCommand) "$ $it" else it, Type.monoBold) }
                    entry.output?.takeIf { it.isNotEmpty() }?.let { RoleText(it, Type.monoSmall) }
                    entry.errorText?.let { RoleText(it, Type.monoSmall, color = Ink.Status.error) }
                    if (entry.isTruncated) RoleText("… cut by the relay", Type.monoSmall, color = Ink.Text.secondary)
                    entry.exitCode?.let {
                        RoleText("exit $it", Type.monoSmall, color = if (it == 0) Ink.Status.ok else Ink.Status.error)
                    }
                    if (entry.isRunning) RoleText("running…", Type.monoSmall, color = Ink.Text.secondary)
                }
            }
        }
    }
}
