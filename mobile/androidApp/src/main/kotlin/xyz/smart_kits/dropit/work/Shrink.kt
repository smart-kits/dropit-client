package xyz.smart_kits.dropit.work

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import xyz.smart_kits.dropit.shared.IMAGE_ORIGINAL
import xyz.smart_kits.dropit.shared.IMAGE_SHRINK
import xyz.smart_kits.dropit.shared.JPEG_SCAN_BYTES
import xyz.smart_kits.dropit.shared.JpegTail
import xyz.smart_kits.dropit.shared.SHRINK_QUALITY
import xyz.smart_kits.dropit.shared.SHRINK_TOTAL_MS
import xyz.smart_kits.dropit.shared.jpegHead
import xyz.smart_kits.dropit.shared.noShrinkReason
import xyz.smart_kits.dropit.shared.shrinkWorthIt
import xyz.smart_kits.dropit.shared.withExif
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Making big photos smaller before they are sent: JPEG only, above 2 MB, quality 85, the photo's own details (EXIF) kept, and the
 * smaller copy used only when it is clearly smaller. Anything else, and anything that goes wrong (not enough memory, a file that will
 * not decode, taking too long), is sent as it is without a word: this is an optimisation, not a promise.
 *
 * A photo that is more than its one picture is sent as it is, always: a motion photo's video, a second picture, an HDR gain map, or
 * anything after the end of the picture would be lost by making it again, as would details that could not be found to copy across
 * (see [jpegHead] and [JpegTail]).
 *
 * One photo at a time, on a thread of its own: a decode of a 16-megapixel photo takes about 64 MB, and decoding cannot be interrupted
 * from outside, so a decode that is too late must neither pile up with others nor hold a thread the rest of the app uses.
 *
 * Done once per file: what is looked at is written down in the held file, so a retry sends the same bytes (and the service
 * can tell it is the same thing). The decisions are the shared `noShrinkReason` and `shrinkWorthIt`; this is the part that needs the system.
 * Not tried on a phone yet: colour (wide-gamut photos) and speed are to be looked at there.
 */
class Shrink(private val outbox: Outbox) {
    /** [job] with its big photos made smaller (saved), or as it was when the setting is off or nothing applies */
    suspend fun apply(job: Job, mode: String): Job {
        if (mode == IMAGE_ORIGINAL) return job
        val todo = job.files.indices.filter { !job.files[it].shrinkChecked && job.files[it].copied }
        if (todo.isEmpty()) return job
        val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "dropit-shrink").apply { isDaemon = true } }
        val scope = CoroutineScope(thread.asCoroutineDispatcher() + SupervisorJob())
        val abandoned = AtomicBoolean(false)
        val decoding = AtomicReference<BitmapFactory.Options?>(null)
        val running = todo.associateWith { i -> scope.async { if (abandoned.get()) null else shrinkOne(job.files[i], abandoned, decoding) } }
        val results = try {
            // Too slow: what is not done goes as it is (the work still running is told to stop, and to throw its result away); what is done is used
            withTimeoutOrNull(SHRINK_TOTAL_MS) { running.mapValues { (_, d) -> d.await() } }
                ?: running.mapValues { (_, d) -> if (d.isCompleted && !d.isCancelled) d.getCompleted() else null }.also {
                    abandoned.set(true)
                    decoding.get()?.requestCancelDecode()
                }
        } finally {
            scope.cancel()
            thread.shutdownNow()
        }
        val files = job.files.mapIndexed { i, held ->
            when {
                i !in todo -> held
                else -> results[i] ?: held.copy(shrinkChecked = true)
            }
        }
        val next = job.copy(files = files)
        if (next != job && runCatching { outbox.save(next) }.isSuccess) {
            // The smaller copy is what a retry sends, so the first copy is not kept beside it (it would be twice the room for nothing)
            for (i in files.indices) if (files[i].path != job.files[i].path) runCatching { File(job.files[i].path).delete() }
        }
        return next
    }

    /** The smaller copy as a held file, or null when this one goes as it is */
    private fun shrinkOne(held: HeldFile, abandoned: AtomicBoolean, decoding: AtomicReference<BitmapFactory.Options?>): HeldFile? = try {
        val source = File(held.path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (noShrinkReason(IMAGE_SHRINK, held.mime, held.name, held.size, bounds.outWidth, bounds.outHeight) != null) null
        else {
            // Before any decoding: does it carry something that making it again would lose?
            val head = jpegHead(readHead(source))
            if (head == null || head.blocksShrink || !endsClean(source, head.scanAt, abandoned)) null
            else {
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                decoding.set(options)
                val bitmap = try { BitmapFactory.decodeFile(source.path, options) } finally { decoding.set(null) }
                val encoded = bitmap?.let {
                    try {
                        ByteArrayOutputStream(held.size.coerceAtMost(8L * 1024 * 1024).toInt()).also { out -> it.compress(Bitmap.CompressFormat.JPEG, SHRINK_QUALITY, out) }.toByteArray()
                    } finally {
                        it.recycle()
                    }
                }
                if (encoded == null) null
                else {
                    // The picture's own details come across as they are (no pixel is turned, so the way-up mark still fits)
                    val exif = head.exif
                    val smaller = if (exif != null) withExif(encoded, exif) else encoded
                    if (!shrinkWorthIt(held.size, smaller.size.toLong()) || abandoned.get()) null
                    else {
                        val target = File(source.path + ".s")
                        val tmp = File(source.path + ".s.tmp")
                        tmp.writeBytes(smaller)
                        if (abandoned.get() || !tmp.renameTo(target)) { tmp.delete(); null }
                        else held.copy(path = target.path, size = target.length(), shrunk = true, shrinkChecked = true)
                    }
                }
            }
        }
    } catch (e: Throwable) {
        null       // including running out of memory
    }

    /** The picture ends within the file and only padding follows it, read from the start of the picture data on */
    private fun endsClean(file: File, scanAt: Int, abandoned: AtomicBoolean): Boolean = file.inputStream().use { input ->
        var skipped = 0L
        while (skipped < scanAt) { val n = input.skip(scanAt - skipped); if (n <= 0) return false; skipped += n }
        val tail = JpegTail()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            if (abandoned.get()) return false
            val n = input.read(buffer)
            if (n < 0) break
            tail.feed(buffer, n)
        }
        tail.clean
    }

    private fun readHead(file: File): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(JPEG_SCAN_BYTES)
        var n = 0
        while (n < buffer.size) {
            val got = input.read(buffer, n, buffer.size - n)
            if (got < 0) break
            n += got
        }
        buffer.copyOf(n)
    }
}
