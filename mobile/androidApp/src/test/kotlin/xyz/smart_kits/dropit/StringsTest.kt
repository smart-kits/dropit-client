package xyz.smart_kits.dropit

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Interface text in both languages: every string and plural has an English and a Chinese version, the two take the same arguments,
 * and everything the code asks for exists in both. (A Chinese string whose name starts with label_ also has to be in the label face's
 * character set: see LabelFontTest.)
 */
class StringsTest {
    private val res = File("src/main/res")

    /** name → text (a plural's items joined), for every strings file of a language's resource directory */
    private fun read(dir: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        File(res, dir).listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty().sortedBy { it.name }.forEach { f ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement.childNodes
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                val name = n.attributes?.getNamedItem("name")?.nodeValue ?: continue
                out[name] = n.textContent
            }
        }
        return out
    }

    /** The product's name is the same in every language: it is written once */
    private val sameEverywhere = setOf("app_name")

    private val en get() = read("values")
    private val zh get() = read("values-zh") + sameEverywhere.associateWith { read("values").getValue(it) }

    /** The placeholders in a text, as a sorted list: %1$d, %2$s, … */
    private fun args(text: String) = Regex("%(\\d+\\$)?[sd]").findAll(text).map { it.value }.sorted().toList()

    @Test fun `every string has both languages`() {
        val missingZh = en.keys - zh.keys
        val missingEn = zh.keys - en.keys
        assertTrue(missingZh.isEmpty(), "no Chinese for: $missingZh")
        assertTrue(missingEn.isEmpty(), "no English for: $missingEn")
    }

    @Test fun `the two languages take the same arguments`() {
        val a = en; val b = zh
        val differ = a.keys.intersect(b.keys).filter { k ->
            // a plural is several items joined: only the set of placeholders it uses at all has to agree
            args(a.getValue(k)).toSet() != args(b.getValue(k)).toSet()
        }
        assertTrue(differ.isEmpty(), "different arguments in: $differ")
    }

    @Test fun `everything the code asks for exists in both languages`() {
        val asked = HashSet<String>()
        File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            Regex("R\\.(?:string|plurals)\\.(\\w+)").findAll(f.readText()).forEach { asked += it.groupValues[1] }
        }
        val e = en.keys; val z = zh.keys
        val missing = asked.filter { it !in e || it !in z }
        // Names from libraries and generated code are not in these files; the app's own all are
        assertTrue(missing.isEmpty(), "asked for but not in both languages: $missing")
    }

    @Test fun `new Chinese labels are not added where the label face cannot show them`() {
        // Strings named label_ are set in a face that holds only the characters already used; its source font is not in this repository,
        // so a new one would silently fall back to the system's face in the middle of a word
        val v6 = File(res, "values-zh/strings_v6.xml").readText()
        assertEquals(emptyList(), Regex("name=\"(label_\\w+)\"").findAll(v6).map { it.groupValues[1] }.toList())
    }
}
