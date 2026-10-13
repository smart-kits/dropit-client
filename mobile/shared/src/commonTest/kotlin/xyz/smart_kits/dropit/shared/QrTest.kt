package xyz.smart_kits.dropit.shared

import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Nothing here checks "it drew something": a wrong code still draws, it just never scans. Each test compares
 * with a fact from outside this code.
 */
class QrTest {
    // 'HELLO WORLD' at version 1-Q, from a public worked example
    @Test fun `error correction matches a public vector`() {
        val got = Qr.ecc(intArrayOf(32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236), 13)
        assertContentEquals(intArrayOf(168, 72, 22, 82, 217, 54, 156, 0, 46, 15, 180, 122, 16), got)
    }

    // The standard's table for level L
    @Test fun `format bits match the standard for all eight masks`() {
        val known = listOf(
            "111011111000100", "111001011110011", "111110110101010", "111100010011101",
            "110011000101111", "110001100011000", "110110001000001", "110100101110110",
        )
        for ((mask, want) in known.withIndex()) assertEquals(want, Qr.formatBits(mask).toString(2).padStart(15, '0'))
    }

    private fun fingerprint(m: Array<IntArray>) = m.joinToString("") { it.joinToString("") }.encodeUtf8().sha256().hex().take(16)

    // The same fingerprints the web encoder's tests hold: they were checked bit by bit against an independent
    // encoder and by decoding the picture. If one changes here, this code is wrong — the expected value is
    // never to be recomputed from this code. (A pairing link, one size up from the second case, is covered
    // another way: the Android tests draw one and read it back with an independent reader.)
    @Test fun `whole codes match the fingerprints checked outside`() {
        val golden = listOf(
            Triple("A", 21, "c70a942b285013ce"),
            Triple("中文也要能编码", 25, "5ca9524f316bc3d1"),
            Triple("x".repeat(60), 33, "46ce7fb396051eff"),
        )
        for ((text, size, hash) in golden) {
            val m = Qr.matrix(text)
            assertEquals(size, m.size, text)
            assertEquals(hash, fingerprint(m), text)
        }
    }

    @Test fun `the fixed parts are where they belong`() {
        val m = Qr.matrix("A")
        fun finder(br: Int, bc: Int) = listOf(0, 6).all { m[br][bc + it] == 1 && m[br + it][bc] == 1 } && m[br + 1][bc + 1] == 0 && m[br + 3][bc + 3] == 1
        assertTrue(finder(0, 0) && finder(0, 14) && finder(14, 0))
        assertEquals("10101", m[6].slice(8..12).joinToString(""))
        assertEquals(1, m[m.size - 8][8])
    }

    @Test fun `106 bytes fit and 107 are refused out loud`() {
        assertEquals(37, Qr.matrix("y".repeat(106)).size)
        assertFailsWith<IllegalArgumentException> { Qr.matrix("y".repeat(107)) }
    }
}
