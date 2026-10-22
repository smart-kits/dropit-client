package xyz.smart_kits.dropit.shared

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The list the phone keeps on disk: letters written before there were devices to choose still read, and what is new reads back as written */
class LetterJsonTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `U-T33 a letter written by an earlier version reads with everything new left empty`() {
        val old = """{"id":"a","mine":true,"at":5,"seqs":[3],"kind":"text","text":"hi","files":[{"name":"f.png","size":9,"mime":"image/png"}]}"""
        val l = json.decodeFromString<Letter>(old)
        assertEquals(emptyList(), l.toIds)
        assertEquals(emptyList(), l.toNames)
        assertFalse(l.again)
        assertNull(l.until)
        assertNull(l.failure)
    }

    @Test fun `U-T34 where it went when it ends and a send that stopped read back as written`() {
        val l = Letter(
            id = "j", mine = true, at = 7, seqs = listOf(1, 2), toIds = listOf("d_a", "d_b"), toNames = listOf("Mac", "Linux"), again = true, until = 99,
            failure = SendFailure(reason = "No connection", files = listOf(1), words = false, retry = true, target = false),
        )
        val back = json.decodeFromString<Letter>(json.encodeToString(l))
        assertEquals(l, back)
        assertTrue(back.again)
        assertEquals(listOf(1), back.failure!!.files)
    }

    @Test fun `a send failure written with only its reason reads with the usual defaults`() {
        val f = json.decodeFromString<SendFailure>("""{"reason":"x"}""")
        assertEquals(SendFailure("x", emptyList(), false, true, false), f)
    }
}
