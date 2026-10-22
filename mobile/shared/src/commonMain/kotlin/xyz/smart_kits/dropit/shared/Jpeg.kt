package xyz.smart_kits.dropit.shared

// Making a big photo smaller before it is sent (a setting, on by default). Only the decisions and the byte work are here:
// decoding and encoding the picture is the system's, done by the app. A photo's own details (when it was taken, which way up,
// where) are kept: they live in one block of the file, which is copied across as it is.

/** The setting: `shrink` (the default) or `original` */
const val IMAGE_SHRINK = "shrink"
const val IMAGE_ORIGINAL = "original"

/** A photo this size or smaller is sent as it is */
const val SHRINK_MIN_BYTES = 2L * 1024 * 1024

/** JPEG quality of the smaller copy */
const val SHRINK_QUALITY = 85

/** The smaller copy is used only when it is at most this many percent of the original; otherwise the original goes */
const val SHRINK_MAX_PERCENT = 90

/** More pixels than this are not decoded (memory): such a photo goes as it is */
const val SHRINK_PIXEL_CAP = 16_000_000L

/** How long the whole making-smaller step of one drop may take before everything goes as it is */
const val SHRINK_TOTAL_MS = 10_000L

/** Why a file is sent as it is */
enum class NoShrink { SETTING_OFF, NOT_JPEG, SMALL, SCREENSHOT, TOO_MANY_PIXELS }

/**
 * Whether to make a file smaller: null means yes, otherwise the reason it goes as it is.
 * Only JPEG photos (HEIC is left alone for now), only above [SHRINK_MIN_BYTES], never a screenshot (PNG is not JPEG, and a
 * screenshot saved as JPEG is told by its name), never one with more than [SHRINK_PIXEL_CAP] pixels.
 * [width] and [height] come from reading the picture's header, 0 when that could not be read.
 */
fun noShrinkReason(mode: String?, mime: String?, name: String?, size: Long, width: Int, height: Int): NoShrink? = when {
    mode == IMAGE_ORIGINAL -> NoShrink.SETTING_OFF
    !isJpeg(mime, name) -> NoShrink.NOT_JPEG
    size <= SHRINK_MIN_BYTES -> NoShrink.SMALL
    isScreenshotName(name) -> NoShrink.SCREENSHOT
    width <= 0 || height <= 0 || width.toLong() * height > SHRINK_PIXEL_CAP -> NoShrink.TOO_MANY_PIXELS
    else -> null
}

private fun isJpeg(mime: String?, name: String?): Boolean {
    val type = mime?.lowercase()?.substringBefore(';')?.trim()
    if (type == "image/jpeg" || type == "image/jpg") return true
    if (!type.isNullOrEmpty() && type != "application/octet-stream") return false
    val ext = name?.substringAfterLast('.', "")?.lowercase()
    return ext == "jpg" || ext == "jpeg"
}

private val SCREENSHOT_WORDS = listOf("screenshot", "screen shot", "screen_shot", "screen-shot", "截屏", "截图", "屏幕截图", "屏幕快照")

/** A screenshot saved as JPEG: told by what phones call it */
fun isScreenshotName(name: String?): Boolean {
    val n = name?.lowercase() ?: return false
    return SCREENSHOT_WORDS.any { it in n }
}

/** Whether the smaller copy is worth using: clearly smaller, not just a different file */
fun shrinkWorthIt(originalSize: Long, shrunkSize: Long): Boolean =
    shrunkSize > 0 && shrunkSize * 100 <= originalSize * SHRINK_MAX_PERCENT

// ── The photo's own details (the EXIF block) ─────────────────────────────

/** How much of the start of a file is looked at for the block: it is at most 64 KB long and comes right after a few small ones */
const val EXIF_SCAN_BYTES = 66_000

private val EXIF_ID = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)

/** A JPEG's segment: where it starts (at its 0xFF) and where it ends (after its last byte) */
private class Segment(val marker: Int, val start: Int, val end: Int)

/** The segments before the picture data, up to the first one that is not complete in [bytes]; null when this is not a JPEG */
private fun leadingSegments(bytes: ByteArray): List<Segment>? {
    if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
    val out = mutableListOf<Segment>()
    var p = 2
    while (p + 4 <= bytes.size) {
        if (bytes[p] != 0xFF.toByte()) break
        val marker = bytes[p + 1].toInt() and 0xFF
        if (marker == 0xFF) { p++; continue }                            // fill bytes
        if (marker == 0xDA || marker == 0xD9) break                      // the picture data (or the end) starts: no more details
        if (marker == 0x01 || marker in 0xD0..0xD7) { p += 2; continue } // markers with no length
        val length = ((bytes[p + 2].toInt() and 0xFF) shl 8) or (bytes[p + 3].toInt() and 0xFF)
        if (length < 2 || p + 2 + length > bytes.size) break
        out += Segment(marker, p, p + 2 + length)
        p += 2 + length
    }
    return out
}

/** The whole EXIF segment (its marker and length included) from the start of a JPEG, or null when it has none (or it is cut off) */
fun exifSegment(start: ByteArray): ByteArray? {
    val seg = leadingSegments(start)?.firstOrNull { s ->
        s.marker == 0xE1 && s.end - s.start >= 10 && EXIF_ID.indices.all { i -> start[s.start + 4 + i] == EXIF_ID[i] }
    } ?: return null
    return start.copyOfRange(seg.start, seg.end)
}

/**
 * [jpeg] with the EXIF segment [exif] right after its first two bytes (where it belongs), replacing any EXIF segment it already
 * has. Everything else is untouched. Returns [jpeg] as it is when it is not a JPEG or [exif] is not an EXIF segment.
 */
fun withExif(jpeg: ByteArray, exif: ByteArray): ByteArray {
    if (exif.size < 10 || exif[0] != 0xFF.toByte() || exif[1] != 0xE1.toByte()) return jpeg
    val segments = leadingSegments(jpeg.copyOfRange(0, minOf(jpeg.size, EXIF_SCAN_BYTES))) ?: return jpeg
    val old = segments.firstOrNull { s ->
        s.marker == 0xE1 && s.end - s.start >= 10 && EXIF_ID.indices.all { i -> jpeg[s.start + 4 + i] == EXIF_ID[i] }
    }
    val out = ByteArray(jpeg.size - (old?.let { it.end - it.start } ?: 0) + exif.size)
    var o = 0
    jpeg.copyInto(out, o, 0, 2); o += 2
    exif.copyInto(out, o); o += exif.size
    var from = 2
    if (old != null) {
        jpeg.copyInto(out, o, from, old.start); o += old.start - from
        from = old.end
    }
    jpeg.copyInto(out, o, from, jpeg.size)
    return out
}

// ── What re-encoding would lose ──────────────────────────────────────────
// Making a photo smaller means decoding it and writing a new, plain JPEG. Everything beyond the one picture and its EXIF block is lost
// by that: the video of a motion photo, the second picture of a multi-picture file, the gain map of an HDR photo. A photo that carries
// any of them is sent as it is. Only the decisions and the byte reading are here; the app reads the file and feeds them.

/** How much of the start of a file is looked at to see what it carries: all the blocks before the picture data (each at most 64 KB) */
const val JPEG_SCAN_BYTES = 1 shl 20

private fun ascii(s: String) = ByteArray(s.length) { s[it].code.toByte() }
private val MPF_ID = ascii("MPF") + byteArrayOf(0)
private val XMP_ID = ascii("http://ns.adobe.com/xap/1.0/") + byteArrayOf(0)
private val XMP_EXTENSION_ID = ascii("http://ns.adobe.com/xmp/extension/") + byteArrayOf(0)

/** What an XMP block says when the photo is more than one picture: a motion photo's video, a container of pictures, a gain map */
private val XMP_EXTRAS = listOf("GCamera:MicroVideo", "GCamera:MotionPhoto", "Container:Directory", "HDRGainMap", "hdrgm:").map(::ascii)

private fun startsAt(b: ByteArray, at: Int, limit: Int, id: ByteArray): Boolean =
    at + id.size <= limit && id.indices.all { b[at + it] == id[it] }

private fun containsAny(b: ByteArray, from: Int, to: Int, needles: List<ByteArray>): Boolean {
    for (n in needles) {
        var i = from
        while (i + n.size <= to) {
            if (b[i] == n[0] && startsAt(b, i, to, n)) return true
            i++
        }
    }
    return false
}

/** What the start of a JPEG says about it */
class JpegHead(
    /** The whole EXIF segment, when it has one and it is complete in what was looked at */
    val exif: ByteArray?,
    /** An EXIF segment starts but is cut off by the end of what was looked at: its details cannot be copied across */
    val exifCut: Boolean,
    /** More than one picture in the file (an MPF block) */
    val multiPicture: Boolean,
    /** A motion photo's video, a container of pictures or an HDR gain map is described in its XMP */
    val extras: Boolean,
    /** Where the picture data starts (its marker), or -1 when the blocks before it did not end within what was looked at */
    val scanAt: Int,
) {
    /** Whether making this one smaller would lose something that is not the picture itself, or that cannot be checked */
    val blocksShrink: Boolean get() = multiPicture || extras || exifCut || scanAt < 0
}

/** [bytes] is the start of a file ([JPEG_SCAN_BYTES] of it, or all when it is shorter); null when it is not a JPEG */
fun jpegHead(bytes: ByteArray): JpegHead? {
    if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
    var exif: ByteArray? = null
    var exifCut = false
    var mpf = false
    var extras = false
    var scanAt = -1
    var p = 2
    while (p + 2 <= bytes.size) {
        if (bytes[p] != 0xFF.toByte()) break
        val marker = bytes[p + 1].toInt() and 0xFF
        if (marker == 0xFF) { p++; continue }
        if (marker == 0xDA) { scanAt = p; break }
        if (marker == 0xD9) break
        if (marker == 0x01 || marker in 0xD0..0xD7) { p += 2; continue }
        if (p + 4 > bytes.size) break
        val length = ((bytes[p + 2].toInt() and 0xFF) shl 8) or (bytes[p + 3].toInt() and 0xFF)
        if (length < 2) break
        val end = p + 2 + length
        val body = p + 4
        val seen = minOf(end, bytes.size)
        when {
            marker == 0xE1 && startsAt(bytes, body, seen, EXIF_ID) ->
                if (end <= bytes.size) { if (exif == null) exif = bytes.copyOfRange(p, end) } else exifCut = true
            marker == 0xE1 && startsAt(bytes, body, seen, XMP_ID) ->
                if (containsAny(bytes, body, seen, XMP_EXTRAS)) extras = true
            marker == 0xE1 && startsAt(bytes, body, seen, XMP_EXTENSION_ID) -> extras = true
            marker == 0xE2 && startsAt(bytes, body, seen, MPF_ID) -> mpf = true
        }
        if (end > bytes.size) break
        p = end
    }
    return JpegHead(exif, exifCut, mpf, extras, scanAt)
}

/**
 * Finds where the picture ends and says whether anything follows it, from the file's bytes fed in order starting at the picture data
 * ([JpegHead.scanAt]). In the picture data a 0xFF is always followed by 0x00 or a restart mark, so the first 0xFF 0xD9 is the end.
 * What follows (the video of a motion photo, a second picture, a maker's trailer) would be lost; zero bytes of padding are not counted.
 */
class JpegTail {
    private var previous = 0
    var endFound = false
        private set
    var trailing = 0L
        private set
    private var trailingData = false

    fun feed(buffer: ByteArray, n: Int) {
        for (i in 0 until n) {
            val b = buffer[i].toInt() and 0xFF
            if (!endFound) {
                if (previous == 0xFF && b == 0xD9) endFound = true
                previous = b
            } else {
                trailing++
                if (b != 0) trailingData = true
            }
        }
    }

    /** The picture ends, and nothing but padding follows it. A file that ends before its end mark cannot be told, so it is not clean either */
    val clean: Boolean get() = endFound && !trailingData
}
