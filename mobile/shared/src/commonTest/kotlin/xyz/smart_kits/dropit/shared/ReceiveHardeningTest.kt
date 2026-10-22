package xyz.smart_kits.dropit.shared

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Things that arrive are only what another device says they are: a link is looked at, a re-send stands alone, files keep the order they were sent in */
class ReceiveHardeningTest {
    private val me = "d_me"
    private fun words(seq: Long, raw: String, kind: String = "text", group: Group? = null, source: String? = "cli", at: Long = 1_000 + seq) =
        Incoming(seq, deviceId = "d_other", kind = kind, text = raw, bytes = raw.length.toLong(), at = at, source = source, group = group)
    private fun file(seq: Long, name: String, group: Group? = null, source: String? = "cli") =
        Incoming(seq, deviceId = "d_other", kind = "image", url = "https://x.test/f/$seq", bytes = 10, at = 1_000 + seq, filename = name, mime = "image/png", group = group, source = source)
    private fun arrive(items: List<Incoming>, known: List<Letter> = emptyList()) = arrive(items, me, known, 0, unmetered = true)

    // ── a re-send is its own letter ──
    @Test fun `H-R1 something sent again is its own letter even when it carries the mark of its first batch`() {
        val g = { i: Int -> Group("gA", i, 2) }
        val first = arrive(listOf(words(1, "hello", group = g(1)), file(2, "a.png", group = g(2))))
        assertEquals(listOf("gA"), first.letters.map { it.id })
        // Later the file is sent again to this phone: the service may still carry the batch mark on it
        val again = arrive(listOf(file(9, "a.png", group = g(2), source = RESEND_SOURCE)), known = first.letters)
        assertEquals(listOf("s9"), again.letters.map { it.id })
        assertEquals(1, again.letters.size)
        assertEquals(listOf(9L), again.letters[0].seqs)
        assertEquals(listOf(1L, 2L), first.letters[0].seqs)           // the first card was not grown
    }

    @Test fun `H-R2 an item sent again alone gets a notice of its own`() {
        val a = arrive(listOf(words(5, "x", source = RESEND_SOURCE, group = Group("gB", 1, 3))))
        assertEquals(listOf("s5"), a.notices.map { it.id })
    }

    @Test fun `H-R3 the same item of a batch is not told apart from a send again by its seq`() {
        val a = arrive(listOf(words(5, "x", group = Group("gB", 1, 2)), file(6, "b.png", group = Group("gB", 2, 2))))
        assertEquals(listOf("gB"), a.letters.map { it.id })
        assertEquals(listOf("gB"), listOf(Incoming(1, source = "cli", group = Group("gB", 1, 2)).letterId))
        assertEquals("s1", Incoming(1, source = RESEND_SOURCE, group = Group("gB", 1, 2)).letterId)
    }

    // ── files in the order they were sent ──
    @Test fun `H-R4 files that reached the service out of order are shown in the order they were sent`() {
        val g = { i: Int -> Group("gC", i, 4) }
        // Numbered as they finished: the third sent finished first
        val a = arrive(listOf(words(10, "caption", group = g(1)), file(11, "third.png", group = g(4)), file(12, "first.png", group = g(2)), file(13, "second.png", group = g(3))))
        val l = a.letters.single()
        assertEquals(listOf("first.png", "second.png", "third.png"), l.files.map { it.name })
        assertEquals(listOf(2, 3, 4), l.files.map { it.i })
    }

    @Test fun `H-R5 the rest of a batch coming later takes its place by where it was sent`() {
        val g = { i: Int -> Group("gD", i, 3) }
        val first = arrive(listOf(file(21, "second.png", group = g(2)), file(22, "third.png", group = g(3))))
        val later = arrive(listOf(file(23, "first.png", group = g(1))), known = first.letters)
        assertEquals(listOf("first.png", "second.png", "third.png"), later.letters.single().files.map { it.name })
    }

    @Test fun `H-R6 files kept by an earlier version have no place written down and stay by number`() {
        val old = listOf(LetterFile("b", 1, seq = 5), LetterFile("a", 1, seq = 3), LetterFile("c", 1, seq = 9))
        assertEquals(listOf("a", "b", "c"), inBatchOrder(old).map { it.name })
        // One with a place and one without: all by number (a place is only trusted when every file has one)
        val mixed = listOf(LetterFile("x", 1, seq = 8, i = 1), LetterFile("y", 1, seq = 4))
        assertEquals(listOf("y", "x"), inBatchOrder(mixed).map { it.name })
        assertEquals(listOf("p", "q"), inBatchOrder(listOf(LetterFile("q", 1, seq = 2, i = 2), LetterFile("p", 1, seq = 7, i = 1))).map { it.name })
    }

    @Test fun `H-R7 the same place keeps the order of the numbers`() {
        val same = listOf(LetterFile("b", 1, seq = 6, i = 2), LetterFile("a", 1, seq = 5, i = 2))
        assertEquals(listOf("a", "b"), inBatchOrder(same).map { it.name })
    }

    @Test fun `H-R8 a letter written before files had a place reads back with none and the place is kept when written`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<LetterFile>("""{"name":"f.png","size":9,"mime":"image/png","seq":4}""")
        assertNull(old.i)
        val withPlace = LetterFile("g.png", 9, "image/png", 5, i = 3)
        assertEquals(withPlace, json.decodeFromString<LetterFile>(json.encodeToString(withPlace)))
        assertEquals(3, json.decodeFromString<LetterFile>(json.encodeToString(withPlace)).i)
    }

    // ── a link is only a link when it is an address to open ──
    @Test fun `H-R9 an address is a link to open only for http and https`() {
        assertTrue(canOpenLink("https://example.com/a?b=1#c"))
        assertTrue(canOpenLink("http://example.com"))
        assertTrue(canOpenLink("  https://example.com  "))
        assertTrue(canOpenLink("HTTPS://EXAMPLE.COM"))
        for (bad in listOf("javascript:alert(1)", "intent://x#Intent;scheme=a;end", "content://media/1", "file:///data/data/x/y", "ftp://a.b/c", "market://details?id=x", "tel:123", "https://", "https:///x", "http:/x.com", "example.com", "", " ")) {
            assertFalse(canOpenLink(bad), bad)
        }
        assertFalse(canOpenLink(null))
    }

    @Test fun `H-R10 an address the service hands back to be opened must be https`() {
        assertTrue(canOpenLink("https://pay.example.com/c/1", httpsOnly = true))
        assertTrue(canOpenLink(" HTTPS://pay.example.com/c/1", httpsOnly = true))
        assertFalse(canOpenLink("http://pay.example.com/c/1", httpsOnly = true))
        assertFalse(canOpenLink("javascript:1", httpsOnly = true))
        assertFalse(canOpenLink(null, httpsOnly = true))
    }

    @Test fun `H-R11 something called a link by the sender that is not one arrives as text`() {
        val a = arrive(listOf(words(1, "javascript:alert(1)", kind = "url"), words(2, "https://ok.example/p", kind = "url")))
        val bad = a.letters.first { it.id == "s1" }
        val good = a.letters.first { it.id == "s2" }
        assertEquals("text", bad.kind)
        assertEquals(LetterShape.Text, bad.shape)
        assertFalse(bad.isLink)
        assertEquals("url", good.kind)
        assertEquals(LetterShape.Link, good.shape)
        assertTrue(good.isLink)
        // The notice for the first has no Open
        val notice = a.notices.first { it.id == "s1" }
        assertFalse(NoticeAction.OPEN in notice.actions)
        assertTrue(NoticeAction.OPEN in a.notices.first { it.id == "s2" }.actions)
    }

    @Test fun `H-R12 a letter kept as a link whose words are not an address is copied - never opened`() {
        val kept = Letter(id = "k", mine = false, at = 1, seqs = listOf(1), kind = "url", text = "javascript:alert(1)")
        assertFalse(kept.isLink)
        assertEquals(LetterShape.Text, kept.shape)
        assertEquals(Act.COPY, kept.primary(10).act)
        assertFalse(kept.menu(10).any { it.act == Act.OPEN_LINK })
        val real = kept.copy(text = "https://example.com")
        assertEquals(Act.OPEN_LINK, real.primary(10).act)
        assertTrue(real.menu(10).any { it.act == Act.OPEN_LINK })
    }

    @Test fun `H-R13 words a phone sent itself as a link keep offering to open and to copy`() {
        val mine = Letter(id = "m", mine = true, at = 1, seqs = listOf(1), kind = "url", text = "https://example.com")
        assertTrue(mine.isLink)
        assertTrue(mine.menu(10).any { it.act == Act.OPEN_LINK })
    }

    @Test fun `H-R14 a link that is not an address is not put on the clipboard by itself`() {
        val bad = Letter(id = "b", mine = false, at = 5, seqs = listOf(1), kind = "url", text = "javascript:alert(1)")
        val older = Letter(id = "o", mine = false, at = 1, seqs = listOf(2), kind = "text", text = "older words")
        assertNull(autoClipLetter(listOf(bad)))
        // Nothing is written in its place: the newest is the one that was looked at
        assertNull(autoClipLetter(listOf(older, bad)))
        assertEquals("g", autoClipLetter(listOf(older, bad.copy(id = "g", text = "https://example.com")))?.id)
        assertEquals("b", autoClipLetter(listOf(older, bad.copy(kind = "text")))?.id)
    }
}
