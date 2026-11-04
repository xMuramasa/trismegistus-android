package dev.xmuramasa.trismegistus.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import dev.xmuramasa.trismegistus.core.protocol.ApprovalChoice
import dev.xmuramasa.trismegistus.core.state.CommandLog
import dev.xmuramasa.trismegistus.core.state.PendingApproval
import dev.xmuramasa.trismegistus.ui.components.ButtonKind
import dev.xmuramasa.trismegistus.ui.components.Dot
import dev.xmuramasa.trismegistus.ui.components.EndfieldButton
import dev.xmuramasa.trismegistus.ui.components.RoleText
import dev.xmuramasa.trismegistus.ui.theme.Glyphs
import dev.xmuramasa.trismegistus.ui.theme.Ink
import dev.xmuramasa.trismegistus.ui.theme.Space
import dev.xmuramasa.trismegistus.ui.theme.Strokes
import dev.xmuramasa.trismegistus.ui.theme.Type

/**
 * 02b: the command in full, the reason, ONCE and SESSION on the left and DENY set apart on the right; `always` is
 * never offered (the protocol can't even say it). The buttons answer through the relay socket.
 */
@Composable
fun ApprovalBanner(approval: PendingApproval, answer: (ApprovalChoice) -> Unit) {
    val choices = approval.request.choices
    Column(
        Modifier.fillMaxWidth().padding(Space.l).shadow(Space.s, RectangleShape).background(Ink.Surface.raised),
    ) {
        Box(Modifier.fillMaxWidth().height(Strokes.mark).background(Ink.selection))
        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                Dot(Ink.marker)
                RoleText("Approval // required", Type.label, Modifier.weight(1f))
                // Hermes asks approval for terminal commands only; the request doesn't name the tool.
                RoleText(CommandLog.TERMINAL_TOOL, Type.monoSmall, color = Ink.Text.secondary)
            }
            SelectionContainer {
                RoleText(approval.request.command, Type.mono, Modifier.fillMaxWidth().background(Ink.Surface.sunken).padding(Space.m))
            }
            approval.request.description?.takeIf { it.isNotBlank() }?.let { RoleText(it, Type.body, color = Ink.Text.secondary) }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                if (ApprovalChoice.ONCE in choices) {
                    EndfieldButton("Once", { answer(ApprovalChoice.ONCE) }, kind = ButtonKind.PRIMARY, glyph = Glyphs.check)
                }
                if (ApprovalChoice.SESSION in choices) {
                    EndfieldButton("Session", { answer(ApprovalChoice.SESSION) }, glyph = Glyphs.jobs)
                }
                Spacer(Modifier.weight(1f))
                if (ApprovalChoice.DENY in choices) {
                    EndfieldButton("Deny", { answer(ApprovalChoice.DENY) }, kind = ButtonKind.DANGER, glyph = Glyphs.close)
                }
            }
            RoleText("“always” is never offered", Type.caption, color = Ink.Text.secondary)
        }
    }
}
