package xyz.smart_kits.dropit.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rules the phone apps add on top of the shared vectors */
class PayloadTest {
    @Test fun `U-P2 a link is sent trimmed · text as typed`() {
        assertEquals("https://a.com", rawFor("url", "  https://a.com \n"))
        assertEquals("  note \n", rawFor("text", "  note \n"))
    }

    @Test fun `U-P5 every batch passes the service's own check`() {
        // worker/src/items.js groupOf: id 6–32 of [A-Za-z0-9_-], 2 ≤ n ≤ 16, 1 ≤ i ≤ n
        for (n in 2..GROUP_MAX) {
            val gs = groupsFor(n)
            assertEquals(n, gs.size)
            assertEquals(1, gs.map { it!!.id }.toSet().size)
            gs.forEachIndexed { k, g ->
                g!!
                assertTrue(Regex("^[A-Za-z0-9_-]{6,32}$").matches(g.id), g.id)
                assertEquals(k + 1, g.i)
                assertTrue(g.i in 1..g.n && g.n in 2..16)
            }
        }
    }

    @Test fun `U-P3 one thing is no batch`() {
        assertEquals(listOf(null), groupsFor(1))
        assertEquals(emptyList(), groupsFor(0))
    }

    @Test fun `U-P11 when even the batch alone is over budget · the batch is kept`() {
        val m = fitMeta(Meta(title = "x".repeat(50), group = Group("abcdefgh", 1, 3)), 10)
        assertEquals(Meta(group = Group("abcdefgh", 1, 3)), m)
    }

    @Test fun `a source that is not a web address is left out`() {
        assertNull(fromMeta("content://media/1", "x"))
        assertNull(fromMeta("https://", "x"))
        assertEquals(From("HTTPS://A.COM/p"), fromMeta("HTTPS://A.COM/p", null))
    }
}
