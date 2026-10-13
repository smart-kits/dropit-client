package xyz.smart_kits.dropit.ui

import xyz.smart_kits.dropit.shared.Qr
import xyz.smart_kits.dropit.shared.pairingCodeIn
import xyz.smart_kits.dropit.shared.pairingLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The code this app draws on "Add a device" is read back by an independent reader, through the same path
 * a camera frame takes: brightness bytes in, text out. Neither side can pass by agreeing with itself.
 */
class ScanTest {
    /** The code as a camera would see it: dark modules on white, a quiet margin around, `scale` pixels a module */
    private fun picture(text: String, scale: Int = 6, quiet: Int = 4): Triple<ByteArray, Int, Int> {
        val m = Qr.matrix(text)
        val side = (m.size + 2 * quiet) * scale
        val luma = ByteArray(side * side) { 255.toByte() }
        for (r in m.indices) for (c in m.indices) if (m[r][c] == 1) {
            for (y in 0 until scale) for (x in 0 until scale) luma[((r + quiet) * scale + y) * side + (c + quiet) * scale + x] = 0
        }
        return Triple(luma, side, side)
    }

    @Test fun `a drawn pairing link is read back and gives its code`() {
        for (code in listOf("7KQ2MD", "VCR46D", "000000", "ZZZZZZ")) {
            val (luma, w, h) = picture(pairingLink(code))
            val text = QrReader().read(luma, w, w, h)
            assertEquals(pairingLink(code), text)
            assertEquals(code, pairingCodeIn(text!!))
        }
    }

    @Test fun `text of every size the encoder takes is read back`() {
        for (text in listOf("A", "中文也要能编码", "x".repeat(60), "y".repeat(106))) {
            val (luma, w, h) = picture(text)
            assertEquals(text, QrReader().read(luma, w, w, h))
        }
    }

    @Test fun `another code is read, and is not ours`() {
        val (luma, w, h) = picture("https://example.com/pay?to=someone")
        val text = QrReader().read(luma, w, w, h)
        assertEquals("https://example.com/pay?to=someone", text)
        assertNull(pairingCodeIn(text!!))
    }

    @Test fun `a blank picture reads as nothing`() {
        assertNull(QrReader().read(ByteArray(200 * 200) { 255.toByte() }, 200, 200, 200))
    }
}
