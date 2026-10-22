package xyz.smart_kits.dropit.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Text from another device on the clipboard, when asked for: only the newest of what came, only words */
class AutoClipTest {
    private fun words(id: String, at: Long, text: String? = id, mine: Boolean = false, kind: String = "text") =
        Letter(id = id, mine = mine, at = at, kind = kind, text = text)

    @Test fun `U-S2 three texts that came together - only the newest is copied`() {
        val got = listOf(words("a", 10), words("c", 30), words("b", 20))
        assertEquals("c", autoClipLetter(got)?.id)
    }

    @Test fun `a link counts as words`() {
        assertEquals("l", autoClipLetter(listOf(words("t", 1), words("l", 2, "https://a.com", kind = "url")))?.id)
    }

    @Test fun `files are never copied and a drop with a file is not words alone`() {
        val file = Letter(id = "f", mine = false, at = 99, files = listOf(LetterFile("a.png", 1)))
        val withWords = Letter(id = "g", mine = false, at = 98, text = "hi", files = listOf(LetterFile("a.png", 1)))
        assertEquals("t", autoClipLetter(listOf(file, withWords, words("t", 1)))?.id)
        assertNull(autoClipLetter(listOf(file, withWords)))
    }

    @Test fun `what this phone sent itself is not copied back`() {
        assertNull(autoClipLetter(listOf(words("m", 5, mine = true))))
        assertEquals("o", autoClipLetter(listOf(words("m", 9, mine = true), words("o", 5)))?.id)
    }

    @Test fun `nothing or empty words is nothing`() {
        assertNull(autoClipLetter(emptyList()))
        assertNull(autoClipLetter(listOf(words("e", 1, text = ""), words("n", 2, text = null))))
    }
}
