package xyz.smart_kits.dropit.ui

import java.awt.Font
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Chinese label face holds only the characters the label strings use. A character missing from it does
 * not fail anywhere: it silently shows in the system's face, in the middle of a word. So every character of
 * every Chinese string named label_… is looked up in the font file itself.
 */
class LabelFontTest {
    private val res = File("src/main/res")

    private fun labelStrings(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val out = LinkedHashMap<String, String>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            val name = n.attributes?.getNamedItem("name")?.nodeValue ?: continue
            if (name.startsWith("label_")) out[name] = n.textContent
        }
        return out
    }

    private fun isChinese(c: Char) = c in '　'..'鿿' || c in '＀'..'￯'

    @Test fun `the Chinese label face has every character the Chinese labels use`() {
        val font = Font.createFont(Font.TRUETYPE_FONT, File(res, "font/dropit_label_zh.ttf"))
        val missing = labelStrings("values-zh").values.joinToString("").filter(::isChinese).toSet().filterNot(font::canDisplay)
        assertTrue(missing.isEmpty(), "dropit_label_zh.ttf is missing ${missing.joinToString("")} — run tools/label_font.py")
    }

    @Test fun `every label has both languages`() {
        assertEquals(labelStrings("values").keys, labelStrings("values-zh").keys)
    }

    // The Latin face is cut down to basic Latin and a few marks: a label must not reach outside it
    @Test fun `the Latin label face has every character the English labels use`() {
        val font = Font.createFont(Font.TRUETYPE_FONT, File(res, "font/barlow_condensed_600.ttf"))
        val missing = labelStrings("values").values.joinToString("").filter { it > ' ' && it != '\n' && !it.isDigit() && it != '%' && it != '$' }
            .toSet().filterNot(font::canDisplay)
        assertTrue(missing.isEmpty(), "barlow_condensed_600.ttf has no ${missing.joinToString("")}")
    }
}
