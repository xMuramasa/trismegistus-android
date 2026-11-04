package dev.xmuramasa.trismegistus.chat

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownTests {
    private val code = SpanStyle(fontFamily = FontFamily.Monospace)
    private val style = MarkdownStyle(code, SpanStyle())

    private fun prose(b: MarkdownBlock) = (b as MarkdownBlock.Prose).text

    @Test
    fun fencedCodeIsItsOwnBlockBetweenProse() {
        val blocks = Markdown.blocks("Before\n\n```bash\nls -la\npwd\n```\n\nAfter", style)
        assertEquals(3, blocks.size)
        assertEquals("Before", prose(blocks[0]).text)
        assertEquals(MarkdownBlock.CodeBlock("bash", "ls -la\npwd"), blocks[1])
        assertEquals("After", prose(blocks[2]).text)
    }

    @Test
    fun indentedCodeHasNoLanguage() {
        val blocks = Markdown.blocks("Run:\n\n    make all\n", style)
        assertEquals(MarkdownBlock.CodeBlock(null, "make all"), blocks[1])
    }

    @Test
    fun inlineStylesAndKeptLineBreaks() {
        val text = prose(Markdown.blocks("Hello **world**\nand `code` here", style).single())
        assertEquals("Hello world\nand code here", text.text)
        val bold = text.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("world", text.text.substring(bold.start, bold.end))
        val mono = text.spanStyles.single { it.item == code }
        assertEquals("code", text.text.substring(mono.start, mono.end))
    }

    @Test
    fun onlyWebAndMailLinksAreClickable() {
        val text = prose(Markdown.blocks("[docs](https://example.com/a) and [x](javascript:alert(1))", style).single())
        assertEquals("docs and x", text.text)
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(listOf("https://example.com/a"), links.map { (it.item as LinkAnnotation.Url).url })
        assertEquals("docs", text.text.substring(links.single().start, links.single().end))
    }

    @Test
    fun listsKeepTheirMarkersAndNesting() {
        val text = prose(Markdown.blocks("- a\n- b\n  - c\n\n3. three\n4. four", style).single()).text
        assertEquals("• a\n• b\n  • c\n\n3. three\n4. four", text)
    }

    @Test
    fun headingsAndQuotes() {
        val text = prose(Markdown.blocks("# Title\n\n> quoted\n\nend", style).single()).text
        assertEquals("Title\n\n│ quoted\n\nend", text)
    }
}
