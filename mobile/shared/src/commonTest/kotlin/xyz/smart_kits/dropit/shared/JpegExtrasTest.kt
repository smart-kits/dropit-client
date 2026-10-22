package xyz.smart_kits.dropit.shared

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What making a photo smaller would lose, found from the file's own bytes (built here, by hand) */
class JpegExtrasTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ascii(s: String) = ByteArray(s.length) { s[it].code.toByte() }
    private fun segment(marker: Int, body: ByteArray): ByteArray {
        val length = body.size + 2
        return b(0xFF, marker, length shr 8, length and 0xFF) + body
    }
    private val soi = b(0xFF, 0xD8)
    private val eoi = b(0xFF, 0xD9)
    private val scanHeader = segment(0xDA, b(1, 1, 0, 0, 63, 0))
    // Picture data: a stuffed 0xFF (followed by 0x00), a restart mark, and plain bytes; never an end mark inside
    private val picture = b(1, 2, 3, 0xFF, 0x00, 4, 5, 0xFF, 0xD0, 6, 7)
    private val exif = segment(0xE1, ascii("Exif") + b(0, 0) + ByteArray(40) { 7 })
    private fun xmp(text: String) = segment(0xE1, ascii("http://ns.adobe.com/xap/1.0/") + b(0) + ascii(text))
    private fun jpeg(vararg before: ByteArray, tail: ByteArray = ByteArray(0)): ByteArray =
        before.fold(soi) { a, s -> a + s } + scanHeader + picture + eoi + tail

    @Test fun `H-J1 a plain photo with its details is clean and its details are found`() {
        val file = jpeg(segment(0xE0, ascii("JFIF") + b(0, 1, 1, 0, 0, 1, 0, 1, 0, 0)), exif)
        val head = jpegHead(file)!!
        assertContentEquals(exif, head.exif)
        assertFalse(head.blocksShrink)
        assertEquals(file.size - eoi.size - picture.size - scanHeader.size, head.scanAt)
    }

    @Test fun `H-J2 a file that is not a JPEG has no head`() {
        assertNull(jpegHead(ByteArray(0)))
        assertNull(jpegHead(ascii("GIF89a....")))
        assertNull(jpegHead(b(0xFF, 0xD8)))
    }

    @Test fun `H-J3 a multi-picture block means more than one picture`() {
        val mpf = segment(0xE2, ascii("MPF") + b(0) + ByteArray(30))
        val head = jpegHead(jpeg(exif, mpf))!!
        assertTrue(head.multiPicture)
        assertTrue(head.blocksShrink)
        // An ICC profile is also an APP2 and is not that
        val icc = segment(0xE2, ascii("ICC_PROFILE") + b(0, 1, 1) + ByteArray(30))
        val plain = jpegHead(jpeg(exif, icc))!!
        assertFalse(plain.multiPicture)
        assertFalse(plain.blocksShrink)
    }

    @Test fun `H-J4 a motion photo - a container or a gain map in the XMP block means more than the picture`() {
        for (marker in listOf("GCamera:MicroVideo=\"1\"", "GCamera:MotionPhoto=\"1\"", "<Container:Directory>", "<GContainer:Item HDRGainMap>", "hdrgm:Version=\"1.0\"")) {
            val head = jpegHead(jpeg(exif, xmp("<x:xmpmeta>$marker</x:xmpmeta>")))!!
            assertTrue(head.extras, marker)
            assertTrue(head.blocksShrink, marker)
        }
    }

    @Test fun `H-J5 an ordinary XMP block is not a reason to leave the photo alone`() {
        val head = jpegHead(jpeg(exif, xmp("<x:xmpmeta xmp:CreatorTool=\"Camera\" dc:title=\"Beach\"/>")))!!
        assertFalse(head.extras)
        assertFalse(head.blocksShrink)
    }

    @Test fun `H-J6 an extended XMP block counts as more than the picture`() {
        val extension = segment(0xE1, ascii("http://ns.adobe.com/xmp/extension/") + b(0) + ByteArray(20))
        assertTrue(jpegHead(jpeg(exif, extension))!!.blocksShrink)
    }

    @Test fun `H-J7 details past the first 66000 bytes are found when enough is read - and mean no shrinking when they are cut off`() {
        // Two big blocks (such as colour profiles) ahead of the EXIF block put it past 66000 bytes
        val big = segment(0xE2, ascii("ICC_PROFILE") + b(0, 1, 1) + ByteArray(40_000))
        val file = jpeg(big, big, exif)
        assertTrue(file.indexOfSubArray(exif) > EXIF_SCAN_BYTES)
        // What the old reading saw: no details, though the file has them
        assertNull(exifSegment(file.copyOf(EXIF_SCAN_BYTES)))
        val cut = jpegHead(file.copyOf(EXIF_SCAN_BYTES))!!
        assertNull(cut.exif)
        assertTrue(cut.scanAt < 0)
        assertTrue(cut.blocksShrink)
        // Read far enough: found, and the photo is clean
        val whole = jpegHead(file.copyOf(minOf(file.size, JPEG_SCAN_BYTES)))!!
        assertContentEquals(exif, whole.exif)
        assertFalse(whole.blocksShrink)
    }

    @Test fun `H-J8 an EXIF block cut off by the end of what was read is noticed`() {
        val file = jpeg(exif)
        val cutInsideExif = file.copyOf(2 + exif.size / 2)
        val head = jpegHead(cutInsideExif)!!
        assertNull(head.exif)
        assertTrue(head.exifCut)
        assertTrue(head.blocksShrink)
    }

    @Test fun `H-J9 blocks that do not end before the picture data cannot be checked`() {
        val file = soi + exif       // no picture data at all
        val head = jpegHead(file)!!
        assertEquals(-1, head.scanAt)
        assertTrue(head.blocksShrink)
        assertNotNull(head.exif)
    }

    private fun tailOf(file: ByteArray, chunk: Int = 7): JpegTail {
        val head = jpegHead(file)!!
        val tail = JpegTail()
        var at = head.scanAt
        while (at < file.size) {
            val n = minOf(chunk, file.size - at)
            tail.feed(file.copyOfRange(at, at + n), n)
            at += n
        }
        return tail
    }

    @Test fun `H-J10 a picture that ends with nothing after it is clean - whatever the size of the pieces it is read in`() {
        for (chunk in listOf(1, 2, 3, 7, 1000)) assertTrue(tailOf(jpeg(exif), chunk).clean, "chunk $chunk")
    }

    @Test fun `H-J11 a video or a second picture after the end means the photo is more than its picture`() {
        val mp4 = ascii("....ftypmp42") + ByteArray(100) { 9 }
        val withVideo = tailOf(jpeg(exif, tail = mp4))
        assertTrue(withVideo.endFound)
        assertFalse(withVideo.clean)
        assertEquals(mp4.size.toLong(), withVideo.trailing)
        val second = tailOf(jpeg(exif, tail = soi + b(1, 2, 3) + eoi))
        assertFalse(second.clean)
    }

    @Test fun `H-J12 zero padding after the end is not data`() {
        val padded = tailOf(jpeg(exif, tail = ByteArray(16)))
        assertTrue(padded.clean)
        assertEquals(16L, padded.trailing)
    }

    @Test fun `H-J13 a file that stops before its end mark cannot be told to be whole`() {
        val whole = jpeg(exif)
        val broken = whole.copyOf(whole.size - 2)
        assertFalse(tailOf(broken).clean)
        assertFalse(tailOf(broken).endFound)
    }

    @Test fun `H-J14 an end mark inside a block ahead of the picture is not the end - the picture data is where reading starts`() {
        // A thumbnail inside the EXIF block ends with its own end mark
        val withThumb = segment(0xE1, ascii("Exif") + b(0, 0) + soi + b(1, 2) + eoi + b(5, 5))
        val file = jpeg(withThumb)
        assertTrue(tailOf(file).clean)
        assertFalse(jpegHead(file)!!.blocksShrink)
    }

    private fun ByteArray.indexOfSubArray(sub: ByteArray): Int {
        outer@ for (i in 0..size - sub.size) {
            for (j in sub.indices) if (this[i + j] != sub[j]) continue@outer
            return i
        }
        return -1
    }
}
