package xyz.smart_kits.dropit.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What one drop from a phone sends */
class BatchTest {
    private fun files(n: Int, size: Long = 10) = List(n) { FileInfo("f$it.jpg", "image/jpeg", size) }

    @Test fun `U-P7 text and ten files - text first and numbered 1`() {
        val plan = planBatch("note", files(10))
        assertEquals(11, plan.items.size)
        val text = assertIs<Item.Text>(plan.items[0])
        assertEquals(1, text.group!!.i)
        assertEquals(text.group, text.meta!!.group)          // text carries its batch in meta
        assertEquals((2..11).toList(), plan.items.drop(1).map { it.group!!.i })
        assertTrue(plan.items.all { it.group!!.n == 11 && it.group!!.id == text.group!!.id })
    }

    @Test fun `U-P8 an 11th file is left out and said`() {
        val plan = planBatch(null, files(11))
        assertEquals(10, plan.items.size)
        assertEquals(listOf(10), plan.overCount)
    }

    @Test fun `U-P9 a file over the limit is left out before anything is sent`() {
        val plan = planBatch(null, listOf(FileInfo("big.mp4", "video/mp4", 12L shl 20)), itemLimit = 5L shl 20)
        assertTrue(plan.isEmpty)
        assertEquals(listOf(0), plan.tooLarge)
    }

    @Test fun `the rest of a batch still goes when one file is too large`() {
        val list = listOf(FileInfo("a", null, 1), FileInfo("big", null, 99), FileInfo("c", null, 1))
        val plan = planBatch(null, list, itemLimit = 50)
        assertEquals(listOf(0, 2), plan.items.map { (it as Item.File).index })
        assertEquals(listOf(1, 2), plan.items.map { it.group!!.i })
        assertEquals(listOf(1), plan.tooLarge)
    }

    @Test fun `one file alone is no batch`() {
        val plan = planBatch(null, files(1))
        assertNull(plan.items.single().group)
    }

    @Test fun `U-P12 a shared page - the link with the page title`() {
        val item = assertIs<Item.Text>(planBatch(" https://a.com/p ", emptyList(), title = "A  page\n").items.single())
        assertEquals("url", item.kind)
        assertEquals("https://a.com/p", item.raw)
        assertEquals("A page", item.meta!!.title)
        assertNull(item.group)
    }

    @Test fun `U-P13 title and address in one text stay text as shared`() {
        val shared = "A page\nhttps://a.com/p"
        val item = assertIs<Item.Text>(planBatch(shared, emptyList(), title = "A page").items.single())
        assertEquals("text", item.kind)
        assertEquals(shared, item.raw)
    }

    @Test fun `selected text keeps where it came from when known`() {
        val item = assertIs<Item.Text>(planBatch("quote", emptyList(), title = "Page", fromUrl = "https://a.com/p").items.single())
        assertEquals(From("https://a.com/p", "Page"), item.meta!!.from)
    }

    @Test fun `whitespace only sends nothing`() {
        assertTrue(planBatch("  \n", emptyList()).isEmpty)
    }
}
