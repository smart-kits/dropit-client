package xyz.smart_kits.dropit

import android.content.Intent
import xyz.smart_kits.dropit.shared.FileInfo
import xyz.smart_kits.dropit.shared.Item
import xyz.smart_kits.dropit.shared.planBatch
import xyz.smart_kits.dropit.work.Inbound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What each way in hands over, and what that sends */
class InboundTest {
    // Intent's action constants are compile-time strings, so they are real here even without Android
    @Test fun `a browser shares a page - the link with its title`() {
        val inb = Inbound.of(Intent.ACTION_SEND, "https://a.com/p", "A page", null, emptyList(), null)
        val item = assertIs<Item.Text>(planBatch(inb.text, emptyList(), inb.title).items.single())
        assertEquals("url", item.kind)
        assertEquals("A page", item.meta!!.title)
    }

    @Test fun `selected text arrives whole and nothing else`() {
        val inb = Inbound.of(Intent.ACTION_PROCESS_TEXT, "ignored", "ignored", "  a quote \n", emptyList(), null)
        assertEquals(Inbound(text = "  a quote \n"), inb)
    }

    @Test fun `several photos from the gallery`() {
        val uris = listOf("content://media/1", "content://media/2", "content://media/3")
        val inb = Inbound.of(Intent.ACTION_SEND_MULTIPLE, null, null, null, uris, null)
        assertEquals(uris, inb.streams)
        val plan = planBatch(inb.text, uris.map { FileInfo(null, "image/jpeg", 10) })
        assertEquals(listOf(1, 2, 3), plan.items.map { it.group!!.i })
    }

    @Test fun `a photo with a caption goes as one batch`() {
        val inb = Inbound.of(Intent.ACTION_SEND, "look", null, null, listOf("content://media/1"), null)
        val plan = planBatch(inb.text, listOf(FileInfo("a.jpg", "image/jpeg", 10)))
        assertEquals(2, plan.items.size)
        assertIs<Item.Text>(plan.items[0])
    }

    @Test fun `open with - the file being opened`() {
        assertEquals(listOf("content://wechat/f"), Inbound.of(Intent.ACTION_VIEW, null, null, null, emptyList(), "content://wechat/f").streams)
    }

    @Test fun `anything else is nothing`() {
        assertTrue(Inbound.of("other", "x", null, null, emptyList(), null).isEmpty)
        assertTrue(Inbound.of(Intent.ACTION_SEND, "  ", null, null, emptyList(), null).isEmpty)
    }
}
