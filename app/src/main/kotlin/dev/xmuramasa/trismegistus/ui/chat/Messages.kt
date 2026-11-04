package dev.xmuramasa.trismegistus.ui.chat

import android.content.ClipData
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.IntOffset
import coil3.compose.AsyncImage
import dev.xmuramasa.trismegistus.chat.ChatImage
import dev.xmuramasa.trismegistus.chat.ChatItem
import dev.xmuramasa.trismegistus.chat.Markdown
import dev.xmuramasa.trismegistus.chat.MarkdownBlock
import dev.xmuramasa.trismegistus.chat.MarkdownStyle
import dev.xmuramasa.trismegistus.core.state.OutgoingPrompt
import dev.xmuramasa.trismegistus.core.state.RunSummary
import dev.xmuramasa.trismegistus.data.RelaySession
import dev.xmuramasa.trismegistus.ui.components.Glyph
import dev.xmuramasa.trismegistus.ui.components.RoleText
import dev.xmuramasa.trismegistus.ui.theme.Glyphs
import dev.xmuramasa.trismegistus.ui.theme.Ink
import dev.xmuramasa.trismegistus.ui.theme.Sizes
import dev.xmuramasa.trismegistus.ui.theme.Space
import dev.xmuramasa.trismegistus.ui.theme.Strokes
import dev.xmuramasa.trismegistus.ui.theme.Type
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/** `YOU // 23:41`. */
fun stamp(who: String, time: Long?): String =
    who + (time?.let { " // " + clock.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "")

private val markdownStyle = MarkdownStyle(
    code = SpanStyle(fontFamily = Type.mono.style.fontFamily, background = Ink.Surface.sunken),
    link = SpanStyle(color = Ink.Text.link),
)

/** A message of yours: white with a rule outline, its images above. */
@Composable
fun UserMessage(item: ChatItem.User, session: RelaySession, retry: () -> Unit) {
    val pending = item.status is OutgoingPrompt.Status.Pending
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        RoleText(stamp("You", item.time) + if (pending) " // sending" else "", Type.label, color = Ink.Text.secondary)
        if (item.images.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                for (image in item.images) {
                    AsyncImage(
                        model = when (image) {
                            is ChatImage.Remote -> session.attachmentUrl(image.id)
                            is ChatImage.Local -> image.bytes
                        },
                        contentDescription = "Image",
                        imageLoader = session.images,
                        modifier = Modifier.size(Sizes.thumbnail).background(Ink.Surface.sunken),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
        if (item.text.isNotEmpty() || item.images.isEmpty()) {
            SelectionContainer {
                RoleText(
                    item.text,
                    Type.body,
                    Modifier.fillMaxWidth().background(Ink.Surface.raised).border(Strokes.hairline, Ink.Line.rule).padding(Space.m),
                )
            }
        }
        val failed = item.status as? OutgoingPrompt.Status.Failed
        if (failed != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoleText(failed.message, Type.caption, Modifier.weight(1f), color = Ink.Text.error, maxLines = 2)
                TextButton(onClick = retry) { RoleText("Retry", Type.button) }
            }
        }
    }
}

/** Hermes' answer: plain on paper, Markdown prose and separate code blocks. Parsed once per text. */
@Composable
fun AnswerMessage(item: ChatItem.Answer) {
    val blocks = remember(item.text) { Markdown.blocks(item.text, markdownStyle) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        RoleText(stamp("Hermes", item.time), Type.label, color = Ink.Text.secondary)
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Prose -> SelectionContainer {
                    Text(block.text, Modifier.padding(horizontal = Space.m), style = Type.body.style, color = Ink.Text.primary)
                }
                is MarkdownBlock.CodeBlock -> CodeBlockView(block.language, block.code)
            }
        }
    }
}

/** A sunken head with the language and a copy button, the code on white in mono, scrolling sideways. */
@Composable
fun CodeBlockView(language: String?, code: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().border(Strokes.hairline, Ink.Line.rule)) {
        Row(Modifier.fillMaxWidth().background(Ink.Surface.sunken).padding(start = Space.m), verticalAlignment = Alignment.CenterVertically) {
            RoleText(language ?: "code", Type.labelSmall, Modifier.weight(1f), color = Ink.Text.secondary)
            IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(language ?: "code", code))) } }) {
                Glyph(Glyphs.copy, size = Sizes.iconSmall, description = "Copy")
            }
        }
        SelectionContainer {
            Text(
                code,
                Modifier.fillMaxWidth().background(Ink.Surface.raised).horizontalScroll(rememberScrollState()).padding(Space.m),
                style = Type.mono.style,
                color = Ink.Text.primary,
                softWrap = false,
            )
        }
    }
}

/** `▸ terminal git status --short   0.2 s`; tap to unfold what came back. */
@Composable
fun ToolRowView(item: ChatItem.Tool) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().border(Strokes.hairline, Ink.Line.rule).background(Ink.Surface.raised)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Sizes.touch)
                .clickable(enabled = item.output != null, role = Role.Button, onClickLabel = if (open) "Fold" else "Unfold") { open = !open }
                .padding(horizontal = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(if (open) Glyphs.chevronDown else Glyphs.chevronRight, size = Sizes.iconSmall, tint = Ink.Text.secondary)
            RoleText(item.tool, Type.monoBold, color = if (item.isError) Ink.Status.error else Ink.Text.primary)
            RoleText(item.summary.orEmpty(), Type.monoSmall, Modifier.weight(1f), color = Ink.Text.secondary, maxLines = 1)
            RoleText(
                if (item.isRunning) "…" else item.duration?.let { "%.1f s".format(Locale.ROOT, it) }.orEmpty(),
                Type.monoSmall,
                color = Ink.Text.secondary,
            )
        }
        if (open && item.output != null) {
            SelectionContainer {
                Text(
                    item.output,
                    Modifier.fillMaxWidth().background(Ink.Surface.sunken).padding(Space.m),
                    style = Type.monoSmall.style,
                    color = Ink.Text.primary,
                )
            }
        }
    }
}

@Composable
fun SubagentRowView(item: ChatItem.Subagent) {
    Row(
        Modifier.fillMaxWidth().border(Strokes.hairline, Ink.Line.rule).padding(horizontal = Space.m, vertical = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(Glyphs.agents, size = Sizes.iconSmall, tint = Ink.Text.secondary)
        RoleText(item.goal, Type.monoSmall, Modifier.weight(1f), maxLines = 2)
        RoleText(
            listOfNotNull(item.status, item.duration?.let { "%.1f s".format(Locale.ROOT, it) }).joinToString(" · ").ifEmpty { "…" },
            Type.monoSmall,
            color = Ink.Text.secondary,
        )
    }
}

/** `>_ 4 COMMANDS 6.1 s · 1 failed  transcript ›`: opens the run's transcript. */
@Composable
fun RunMarkerView(summary: RunSummary, onOpen: () -> Unit) {
    val parts = summary.label.split(" · ")
    Row(
        Modifier.border(Strokes.hairline, Ink.Line.rule).background(Ink.Surface.raised)
            .clickable(role = Role.Button, onClickLabel = "Open the transcript", onClick = onOpen)
            .heightIn(min = Sizes.touch).padding(horizontal = Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoleText(">_", Type.monoBold, color = Ink.Text.link)
        RoleText(parts.first(), Type.label)
        RoleText(parts.drop(1).joinToString(" · "), Type.monoSmall, color = Ink.Text.secondary)
        RoleText("transcript", Type.monoSmall, color = Ink.Text.link)
        Glyph(Glyphs.chevronRight, size = Sizes.iconSmall, tint = Ink.Text.link)
    }
}

@Composable
fun NoteView(item: ChatItem.Note) {
    RoleText(item.text, Type.caption, color = if (item.isError) Ink.Text.error else Ink.Text.secondary)
}

/** WORKING / WAITING with its detail over a rule a yellow segment runs along. */
@Composable
fun WorkingLine(waiting: Boolean, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            RoleText(if (waiting) "Waiting" else "Working", Type.label)
            RoleText(detail, Type.monoSmall, color = Ink.Text.secondary)
        }
        val sweep by rememberInfiniteTransition(label = "working").animateFloat(
            0f,
            1f,
            infiniteRepeatable(tween(SWEEP_MS, easing = LinearEasing), RepeatMode.Reverse),
            label = "sweep",
        )
        BoxWithConstraints(Modifier.fillMaxWidth().height(Strokes.accent).background(Ink.Line.rule)) {
            val segment = maxWidth * SEGMENT
            val travel = maxWidth - segment
            Box(
                Modifier.offset { IntOffset((travel.toPx() * sweep).toInt(), 0) }
                    .width(segment).height(Strokes.accent).background(Ink.selection),
            )
        }
    }
}

private const val SWEEP_MS = 1400
private const val SEGMENT = 0.25f
