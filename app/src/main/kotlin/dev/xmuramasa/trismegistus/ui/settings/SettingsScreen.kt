package dev.xmuramasa.trismegistus.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xmuramasa.trismegistus.AppViewModel
import dev.xmuramasa.trismegistus.data.RelaySession
import dev.xmuramasa.trismegistus.ui.components.ButtonKind
import dev.xmuramasa.trismegistus.ui.components.EndfieldButton
import dev.xmuramasa.trismegistus.ui.components.EndfieldSwitch
import dev.xmuramasa.trismegistus.ui.components.RoleText
import dev.xmuramasa.trismegistus.ui.components.SteelTopBar
import dev.xmuramasa.trismegistus.ui.shell.RelayStatus
import dev.xmuramasa.trismegistus.ui.theme.Glyphs
import dev.xmuramasa.trismegistus.ui.theme.Ink
import dev.xmuramasa.trismegistus.ui.theme.RowPadding
import dev.xmuramasa.trismegistus.ui.theme.Space
import dev.xmuramasa.trismegistus.ui.theme.Strokes
import dev.xmuramasa.trismegistus.ui.theme.Type
import dev.xmuramasa.trismegistus.ui.theme.endfieldPaper
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/** 03c: the connection, this device, the notification switches (kept for #5), push status, unpair. */
@Composable
fun SettingsScreen(model: AppViewModel, session: RelaySession) {
    val connection by session.store.connection.collectAsStateWithLifecycle()
    val since by session.linkedSince.collectAsStateWithLifecycle()
    val me by session.me.collectAsStateWithLifecycle()
    val n by model.notifications.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val status = RelayStatus.of(connection)
    val name = me?.device ?: session.device

    Column(Modifier.fillMaxSize().endfieldPaper()) {
        SteelTopBar("Settings", navigation = Glyphs.back to "Back", onNavigation = { model.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Section("Connection")
            Item("Relay", session.host)
            Item("Status", listOfNotNull(status.word, since?.let { "since " + hhmm.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }).joinToString(" · "))
            Item("This device", listOfNotNull(name, me?.role).joinToString(" · "))
            Section("Notifications")
            Toggle("Approval requests", n.approvals) { model.setNotifications(n.copy(approvals = it)) }
            Toggle("Run finished while closed", n.runFinished) { model.setNotifications(n.copy(runFinished = it)) }
            Toggle("Sound for approvals", n.approvalSound) { model.setNotifications(n.copy(approvalSound = it)) }
            Item("Push", "not set up yet")
            Section("Device")
            Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                EndfieldButton("Unpair", { confirm = true }, kind = ButtonKind.DANGER, glyph = Glyphs.close)
                RoleText("Removes the token from this phone. To revoke it on the relay:", Type.caption, color = Ink.Text.secondary)
                SelectionContainer { RoleText("trismegistus-relay devices revoke $name", Type.monoSmall, color = Ink.Text.secondary) }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { RoleText("Unpair this phone?", Type.title) },
            text = {
                RoleText(
                    "The token, the cached conversations and the images go. Pairing again needs a new code from the relay.",
                    Type.body,
                    color = Ink.Text.secondary,
                )
            },
            confirmButton = { EndfieldButton("Unpair", { confirm = false; model.unpair() }, kind = ButtonKind.DANGER) },
            dismissButton = { TextButton({ confirm = false }) { RoleText("Cancel", Type.button) } },
        )
    }
}

@Composable
private fun Section(title: String) {
    RoleText(title, Type.label, Modifier.fillMaxWidth().padding(start = Space.l, top = Space.xl, bottom = Space.s), color = Ink.Text.secondary)
}

@Composable
private fun Item(title: String, value: String) {
    Column(
        Modifier.fillMaxWidth().background(Ink.Surface.raised)
            .padding(start = RowPadding.start, end = RowPadding.end, top = RowPadding.vertical, bottom = RowPadding.vertical),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        RoleText(title, Type.body)
        RoleText(value, Type.monoSmall, color = Ink.Text.secondary)
    }
    HorizontalDivider(thickness = Strokes.hairline, color = Ink.Line.rule)
}

@Composable
private fun Toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row is the switch, so TalkBack reads its title with its state.
    Row(
        Modifier.fillMaxWidth().background(Ink.Surface.raised)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(start = RowPadding.start, end = RowPadding.end, top = RowPadding.vertical, bottom = RowPadding.vertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoleText(title, Type.body, Modifier.weight(1f))
        EndfieldSwitch(checked)
    }
    HorizontalDivider(thickness = Strokes.hairline, color = Ink.Line.rule)
}
