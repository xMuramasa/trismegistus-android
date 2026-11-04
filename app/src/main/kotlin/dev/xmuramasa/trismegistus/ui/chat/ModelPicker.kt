package dev.xmuramasa.trismegistus.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import dev.xmuramasa.trismegistus.core.relay.ModelCatalog
import dev.xmuramasa.trismegistus.core.relay.ModelChoice
import dev.xmuramasa.trismegistus.ui.components.Glyph
import dev.xmuramasa.trismegistus.ui.components.RoleText
import dev.xmuramasa.trismegistus.ui.theme.Glyphs
import dev.xmuramasa.trismegistus.ui.theme.Ink
import dev.xmuramasa.trismegistus.ui.theme.Sizes
import dev.xmuramasa.trismegistus.ui.theme.Space
import dev.xmuramasa.trismegistus.ui.theme.Strokes
import dev.xmuramasa.trismegistus.ui.theme.Type

/** `claude-opus-5-5[1m]` as `opus-5-5[1m]`: the top bar has little room. */
fun shortModel(id: String): String = id.removePrefix("claude-")

/** The conversation's model in the top bar (outlined on steel); a menu of Hermes' models, from the next run. */
@Composable
fun ModelPicker(catalog: ModelCatalog, current: ModelChoice, onPick: (ModelChoice) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.heightIn(min = Sizes.touch).padding(vertical = Space.s)
                .border(Strokes.hairline, Ink.Line.outlineOnDark)
                .clickable(role = Role.DropdownList, onClickLabel = "Choose the model") { open = true }
                .padding(horizontal = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoleText(shortModel(current.model), Type.monoSmall, color = Ink.Text.onDark, maxLines = 1)
            Glyph(Glyphs.chevronDown, size = Sizes.iconSmall, tint = Ink.Text.onDark)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (provider in catalog.providers) {
                for (model in provider.models) {
                    val choice = ModelChoice(provider.slug, model.id)
                    DropdownMenuItem(
                        text = { Text("${shortModel(model.id)}  ·  ${provider.name}", style = Type.monoSmall.style) },
                        leadingIcon = if (choice == current) ({ Glyph(Glyphs.check, size = Sizes.iconSmall) }) else null,
                        onClick = {
                            open = false
                            if (choice != current) onPick(choice)
                        },
                    )
                }
            }
        }
    }
}
