package dev.xmuramasa.trismegistus.ui.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.xmuramasa.trismegistus.AppViewModel
import dev.xmuramasa.trismegistus.chat.ConversationList
import dev.xmuramasa.trismegistus.core.relay.SessionInfo
import dev.xmuramasa.trismegistus.ui.components.ButtonKind
import dev.xmuramasa.trismegistus.ui.components.EndfieldButton
import dev.xmuramasa.trismegistus.ui.components.RoleText
import dev.xmuramasa.trismegistus.ui.components.SunkenField
import dev.xmuramasa.trismegistus.ui.theme.Ink
import dev.xmuramasa.trismegistus.ui.theme.Type

// The relay deletes only these (403 read_only otherwise).
private val deletable = setOf("api_server", "cron", "cli")

private enum class Ask { RENAME, TAGS, DELETE }

/** Pin, rename, tags, delete: Material's DropdownMenu, then a dialog for the ones that ask something. */
@Composable
fun ConversationMenu(
    info: SessionInfo,
    tags: List<String>,
    expanded: Boolean,
    onDismiss: () -> Unit,
    model: AppViewModel,
) {
    var ask by remember { mutableStateOf<Ask?>(null) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(if (info.isPinned) "Unpin" else "Pin") },
            onClick = {
                onDismiss()
                model.setPinned(info.id, !info.isPinned)
            },
        )
        DropdownMenuItem(text = { Text("Rename") }, onClick = { onDismiss(); ask = Ask.RENAME })
        DropdownMenuItem(text = { Text("Tags") }, onClick = { onDismiss(); ask = Ask.TAGS })
        if (info.source in deletable) {
            DropdownMenuItem(text = { Text("Delete", color = Ink.Status.error) }, onClick = { onDismiss(); ask = Ask.DELETE })
        }
    }
    when (ask) {
        Ask.RENAME -> TextDialog("Rename", ConversationList.title(info), "Title", { ask = null }) { title ->
            if (title.isNotBlank()) model.rename(info.id, title.trim().take(200))
        }
        Ask.TAGS -> TextDialog("Tags", tags.joinToString(", "), "tag, another tag", { ask = null }) { text ->
            model.setTags(info.id, text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct())
        }
        Ask.DELETE -> AlertDialog(
            onDismissRequest = { ask = null },
            title = { RoleText("Delete this conversation?", Type.title) },
            text = { RoleText("Hermes deletes it and its history. This can't be undone.", Type.body, color = Ink.Text.secondary) },
            confirmButton = {
                EndfieldButton("Delete", { ask = null; model.delete(info.id) }, kind = ButtonKind.DANGER)
            },
            dismissButton = { TextButton({ ask = null }) { RoleText("Cancel", Type.button) } },
        )
        null -> Unit
    }
}

@Composable
private fun TextDialog(title: String, initial: String, placeholder: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { RoleText(title, Type.title) },
        text = { SunkenField(text, { text = it }, placeholder) },
        confirmButton = {
            EndfieldButton("Save", { onDismiss(); onDone(text) }, kind = ButtonKind.PRIMARY)
        },
        dismissButton = { TextButton(onDismiss) { RoleText("Cancel", Type.button) } },
    )
}
