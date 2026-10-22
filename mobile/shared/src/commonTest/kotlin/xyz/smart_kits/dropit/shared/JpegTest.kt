package xyz.smart_kits.dropit.shared

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Making big photos smaller (A6): what is decided and the byte work; the picture itself is the system's */
class JpegTest {
    private val big = SHRINK_MIN_BYTES + 1
    private fun reason(
        mode: String? = IMAGE_SHRINK, mime: String? = "image/jpeg", name: String? = "IMG_0001.jpg", size: Long = big, w: Int = 4000, h: Int = 3000,
    ) = noShrinkReason(mode, mime, name, size, w, h)

    @Test fun `A6-1 a big JPEG photo is made smaller`() {
        assertNull(reason())
        assertNull(reason(mime = "image/jpg"))
        assertNull(reason(mime = null, name = "photo.JPEG"))                   // no type given: the name says
        assertNull(reason(mime = "application/octet-stream", name = "a.jpg"))
    }

    @Test fun `A6-2 the setting off sends it as it is whatever the file`() {
        assertEquals(NoShrink.SETTING_OFF, reason(mode = IMAGE_ORIGINAL))
        assertNull(reason(mode = null))                                         // unset is the default: smaller
    }

    @Test fun `A6-3 only JPEG - not PNG HEIC video or PDF`() {
        for (mime in listOf("image/png", "image/heic", "image/heif", "image/webp", "image/gif", "video/mp4", "application/pdf")) {
            assertEquals(NoShrink.NOT_JPEG, reason(mime = mime, name = "x.jpg"), mime)
        }
        assertEquals(NoShrink.NOT_JPEG, reason(mime = null, name = "scan.pdf"))
        assertEquals(NoShrink.NOT_JPEG, reason(mime = null, name = null))
    }

    @Test fun `A6-4 2 MiB or less is left alone`() {
        assertEquals(NoShrink.SMALL, reason(size = SHRINK_MIN_BYTES))
        assertEquals(NoShrink.SMALL, reason(size = 10))
        assertNull(reason(size = SHRINK_MIN_BYTES + 1))
    }

    @Test fun `A6-5 a screenshot saved as JPEG is told by its name`() {
        for (n in listOf("Screenshot_20261022-101500.jpg", "screenshot.jpeg", "Screen Shot 2026.jpg", "截屏2026-10-22.jpg", "微信截图_1.jpg", "屏幕截图 1.jpg")) {
            assertEquals(NoShrink.SCREENSHOT, reason(name = n), n)
        }
        assertFalse(isScreenshotName("IMG_0001.jpg"))
        assertFalse(isScreenshotName(null))
    }

    @Test fun `A6-6 too many pixels or a header that could not be read is left alone`() {
        assertNull(reason(w = 4000, h = 4000))                                   // 16,000,000: at the cap
        assertEquals(NoShrink.TOO_MANY_PIXELS, reason(w = 4001, h = 4000))
        assertEquals(NoShrink.TOO_MANY_PIXELS, reason(w = -1, h = -1))
        assertEquals(NoShrink.TOO_MANY_PIXELS, reason(w = 0, h = 3000))
        assertEquals(NoShrink.TOO_MANY_PIXELS, reason(w = 70_000, h = 70_000))   // the product does not wrap around
    }

    @Test fun `A6-7 the smaller copy is used only when it is at least ten percent smaller`() {
        assertTrue(shrinkWorthIt(1_000, 900))
        assertFalse(shrinkWorthIt(1_000, 901))
        assertFalse(shrinkWorthIt(1_000, 1_200))
        assertFalse(shrinkWorthIt(1_000, 0))
        assertEquals(85, SHRINK_QUALITY)
    }

    // A small JPEG-shaped file: SOI, then segments, then picture data. Not a real picture: only the segment structure matters here.
    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }
    private val exifId = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val sosAndData = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 1, 2, 9, 9, 9, 0xFF.toByte(), 0xD9.toByte())
    private val jfif = segment(0xE0, "JFIF".encodeToByteArray() + byteArrayOf(0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
    private val exif = segment(0xE1, exifId + byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8, 1, 2, 3, 4))
    private val original = soi + exif + segment(0xDB, ByteArray(10) { 7 }) + sosAndData

    @Test fun `A6-8 the EXIF segment is found whole`() {
        assertContentEquals(exif, exifSegment(original))
        // after another small segment, as many cameras write it
        assertContentEquals(exif, exifSegment(soi + jfif + exif + sosAndData))
    }

    @Test fun `A6-9 no EXIF not a JPEG or a cut-off segment give nothing`() {
        assertNull(exifSegment(soi + jfif + sosAndData))
        assertNull(exifSegment(byteArrayOf(1, 2, 3, 4, 5)))
        assertNull(exifSegment(ByteArray(0)))
        assertNull(exifSegment((soi + exif).copyOf(soi.size + exif.size - 3)))   // the start of the file ended inside the segment
        // an APP1 that is not EXIF (XMP) is not taken for it
        assertNull(exifSegment(soi + segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000x".encodeToByteArray()) + sosAndData))
    }

    @Test fun `A6-10 the segment goes right after the first two bytes and nothing else changes`() {
        val encoded = soi + jfif + segment(0xDB, ByteArray(10) { 5 }) + sosAndData
        val out = withExif(encoded, exif)
        assertContentEquals(soi + exif + jfif + segment(0xDB, ByteArray(10) { 5 }) + sosAndData, out)
        assertContentEquals(exif, exifSegment(out))
        assertEquals(encoded.size + exif.size, out.size)
    }

    @Test fun `A6-11 an EXIF segment already in the new file is replaced not doubled`() {
        val other = segment(0xE1, exifId + byteArrayOf(9, 9, 9, 9))
        val encoded = soi + other + jfif + sosAndData
        val out = withExif(encoded, exif)
        assertContentEquals(soi + exif + jfif + sosAndData, out)
    }

    @Test fun `A6-12 given something that is not a JPEG or not an EXIF segment the file is returned as it is`() {
        val notJpeg = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        assertContentEquals(notJpeg, withExif(notJpeg, exif))
        val encoded = soi + jfif + sosAndData
        assertContentEquals(encoded, withExif(encoded, byteArrayOf(1, 2, 3)))
        assertContentEquals(encoded, withExif(encoded, jfif))        // an APP0 is not what is asked for
    }
}
