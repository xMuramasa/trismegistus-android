package dev.xmuramasa.trismegistus.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The generated Tokens.kt against the design files it comes from, and the compiled values against both. */
class TokensTests {
    private val design = File(System.getProperty("trismegistus.designDir") ?: "../design")
    private val tokens = json("tokens.json")
    private val endfield = json("endfield-palette.json")["colors"]!!.jsonObject
    private val phone = json("android-tokens.json")
    private val source = File(design.parentFile, "app/src/main/kotlin/dev/xmuramasa/trismegistus/ui/theme/Tokens.kt").readText()

    private fun json(name: String) = Json.parseToJsonElement(File(design, name).readText()).jsonObject

    private fun camel(kebab: String) = kebab.split('-').let { p -> p.first() + p.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) } }

    /** `public val <name>: <type> = <value>` lines of one generated object. */
    private fun generated(objectName: String): Map<String, String> {
        val body = source.substringAfter("public object $objectName {\n").substringBefore("\n}")
        return Regex("""public val (\w+): \w+ = (.+)""").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun leaves(o: JsonObject) = o.filterKeys { !it.startsWith("$") }

    private fun hex(ref: String): String = when {
        ref.startsWith("{endfield.") -> endfield[ref.removePrefix("{endfield.").removeSuffix("}")]!!.jsonObject["hex"]!!.jsonPrimitive.content
        ref.startsWith("{color.palette.") ->
            hex(tokens["color"]!!.jsonObject["palette"]!!.jsonObject[ref.removePrefix("{color.palette.").removeSuffix("}")]!!.jsonObject["\$value"]!!.jsonPrimitive.content)
        else -> ref
    }.removePrefix("#").uppercase()

    @Test
    fun everyPaletteColourResolvesAsInTheDesignFiles() {
        val palette = leaves(tokens["color"]!!.jsonObject["palette"]!!.jsonObject)
        val expected = palette.map { (name, t) -> camel(name) to "Color(0xFF${hex(t.jsonObject["\$value"]!!.jsonPrimitive.content)})" }.toMap()
        assertEquals(expected, generated("Palette"))
    }

    @Test
    fun theSpacingScaleAndPhoneSizesAreTheDesignNumbers() {
        fun dp(v: Double) = if (v % 1.0 == 0.0) "${v.toInt()}.dp" else "$v.dp"
        val space = leaves(tokens["space"]!!.jsonObject).map { (k, v) -> camel(k) to dp(v.jsonObject["\$value"]!!.jsonPrimitive.double) }.toMap()
        assertEquals(space, generated("Space"))
        val sizes = phone["size"]!!.jsonObject.map { (k, v) -> camel(k) to dp(v.jsonPrimitive.double) }.toMap()
        assertEquals(sizes, generated("Sizes"))
    }

    @Test
    fun typeRolesUseThePhoneSizes() {
        val type = generated("Type")
        for ((name, role) in phone["type"]!!.jsonObject) {
            val size = role.jsonObject["size"]!!.jsonPrimitive.double.toInt()
            assertEquals(true, type.getValue(camel(name)).contains("fontSize = $size.sp"), name)
        }
    }

    @Test
    fun theCompiledValuesMatch() {
        assertEquals(Color(0xFFF2F2F2), Palette.paper)
        assertEquals(Color(0xFFFFFA00), Ink.selection)
        assertEquals(Palette.onSteel.copy(alpha = 0.24f), Ink.Line.onSidebar)
        assertEquals(16.dp, Space.l)
        assertEquals(316.dp, Sizes.drawerWidth)
        assertEquals(listOf(Palette.secondary, Palette.darkPanel, Palette.ink), Gradient.steel)
    }
}
