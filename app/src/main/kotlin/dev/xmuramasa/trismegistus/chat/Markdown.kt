package dev.xmuramasa.trismegistus.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/** One block of an answer: prose with inline styles, or a fenced code block drawn on its own (language, copy). */
sealed interface MarkdownBlock {
    data class Prose(val text: AnnotatedString) : MarkdownBlock

    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock
}

/** The styles prose uses: [code] for inline code spans, [link] for links. */
class MarkdownStyle(val code: SpanStyle, val link: SpanStyle)

/**
 * Hermes' Markdown through commonmark into blocks. Line breaks are kept as written (a soft break stays a newline, as
 * the Mac app reads answers); lists keep their markers; only http(s) and mailto links are clickable.
 */
object Markdown {
    private val parser = Parser.builder().build()

    fun blocks(text: String, style: MarkdownStyle): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        var prose = AnnotatedString.Builder()
        fun flush() {
            val s = prose.toAnnotatedString()
            val end = s.text.trimEnd().length
            if (end > 0) blocks += MarkdownBlock.Prose(s.subSequence(0, end))
            prose = AnnotatedString.Builder()
        }
        var node = parser.parse(text).firstChild
        while (node != null) {
            when (node) {
                is FencedCodeBlock -> {
                    flush()
                    blocks += MarkdownBlock.CodeBlock(node.info?.trim()?.split(' ')?.first()?.takeIf { it.isNotEmpty() }, node.literal.trimEnd('\n'))
                }
                is IndentedCodeBlock -> {
                    flush()
                    blocks += MarkdownBlock.CodeBlock(null, node.literal.trimEnd('\n'))
                }
                else -> {
                    if (prose.length > 0) prose.append("\n\n")
                    Writer(prose, style).block(node, indent = "")
                }
            }
            node = node.next
        }
        flush()
        return blocks
    }

    /** Writes separators only between blocks, never after the last: a builder can't take text back. */
    private class Writer(val out: AnnotatedString.Builder, val style: MarkdownStyle) {
        fun block(node: Node, indent: String) {
            when (node) {
                is Paragraph -> inlines(node)
                is Heading -> out.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inlines(node) }
                is BulletList -> items(node, indent) { "• " }
                is OrderedList -> {
                    var n = node.markerStartNumber ?: 1
                    items(node, indent) { "${n++}. " }
                }
                is BlockQuote -> {
                    out.append("│ ")
                    children(node, indent + "│ ")
                }
                is ThematicBreak -> out.append("———")
                is HtmlBlock -> out.append(node.literal.trimEnd('\n'))
                is FencedCodeBlock -> out.withStyle(style.code) { append(node.literal.trimEnd('\n')) }
                is IndentedCodeBlock -> out.withStyle(style.code) { append(node.literal.trimEnd('\n')) }
                else -> inlines(node)
            }
        }

        /** Block children separated by one newline (and the indent of the list or quote they are in). */
        private fun children(parent: Node, indent: String) {
            var child = parent.firstChild
            var first = true
            while (child != null) {
                if (!first) out.append("\n" + indent)
                block(child, indent)
                first = false
                child = child.next
            }
        }

        private fun items(list: Node, indent: String, marker: () -> String) {
            var item = list.firstChild
            var first = true
            while (item != null) {
                if (item is ListItem) {
                    if (!first) out.append("\n" + indent)
                    val m = marker()
                    out.append(m)
                    children(item, indent + " ".repeat(m.length))
                    first = false
                }
                item = item.next
            }
        }

        private fun inlines(parent: Node) {
            var n = parent.firstChild
            while (n != null) {
                inline(n)
                n = n.next
            }
        }

        private fun inline(n: Node) {
            when (n) {
                is Text -> out.append(n.literal)
                is Code -> out.withStyle(style.code) { append(n.literal) }
                is Emphasis -> out.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inlines(n) }
                is StrongEmphasis -> out.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inlines(n) }
                is SoftLineBreak, is HardLineBreak -> out.append("\n")
                is Link -> if (isSafe(n.destination)) {
                    out.withLink(LinkAnnotation.Url(n.destination, TextLinkStyles(style.link.copy(textDecoration = TextDecoration.Underline)))) {
                        inlines(n)
                    }
                } else {
                    inlines(n)
                }
                is Image -> inlines(n) // the alt text
                is HtmlInline -> out.append(n.literal)
                else -> inlines(n)
            }
        }

        private fun isSafe(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:")
    }
}
