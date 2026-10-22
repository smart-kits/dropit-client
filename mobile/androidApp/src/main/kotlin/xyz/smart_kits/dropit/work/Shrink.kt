package xyz.smart_kits.dropit.work

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import xyz.smart_kits.dropit.shared.EXIF_SCAN_BYTES
import xyz.smart_kits.dropit.shared.IMAGE_ORIGINAL
import xyz.smart_kits.dropit.shared.IMAGE_SHRINK
import xyz.smart_kits.dropit.shared.SHRINK_QUALITY
import xyz.smart_kits.dropit.shared.SHRINK_TOTAL_MS
import xyz.smart_kits.dropit.shared.UPLOAD_CONCURRENCY
import xyz.smart_kits.dropit.shared.exifSegment
import xyz.smart_kits.dropit.shared.noShrinkReason
import xyz.smart_kits.dropit.shared.shrinkWorthIt
import xyz.smart_kits.dropit.shared.withExif
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Making big photos smaller before they are sent: JPEG only, above 2 MB, quality 85, the photo's own details (EXIF) kept, and the
 * smaller copy used only when it is clearly smaller. Anything else, and anything that goes wrong (not enough memory, a file that will
 * not decode, taking too long), is sent as it is without a word: this is an optimisation, not a promise.
 *
 * Done once per file: what is looked at is written down in the held file, so a retry sends the same bytes (and the service
 * can tell it is the same thing). The decisions are the shared `noShrinkReason` and `shrinkWorthIt`; this is the part that needs the system.
 * Not tried on a phone yet: colour (wide-gamut photos) and speed are to be looked at there.
 */
class Shrink(private val outbox: Outbox) {
    /** [job] with its big photos made smaller (saved), or as it was when the setting is off or nothing applies */
    suspend fun apply(job: Job, mode: String): Job {
        if (mode == IMAGE_ORIGINAL) return job
        val todo = job.files.indices.filter { !job.files[it].shrinkChecked }
        if (todo.isEmpty()) return job
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val abandoned = AtomicBoolean(false)
        val gate = Semaphore(UPLOAD_CONCURRENCY)       // decoding a photo is heavy on memory: a few at a time
        val results = try {
            val running = todo.associateWith { i -> scope.async { gate.withPermit { if (abandoned.get()) null else shrinkOne(job.files[i], abandoned) } } }
            // Too slow: all of it goes as it is (the work still running is told to throw its result away)
            withTimeoutOrNull(SHRINK_TOTAL_MS) { running.mapValues { (_, d) -> d.await() } }.also { if (it == null) abandoned.set(true) }
        } finally {
            scope.cancel()
        }
        val files = job.files.mapIndexed { i, held ->
            when {
                i !in todo -> held
                else -> results?.get(i) ?: held.copy(shrinkChecked = true)
            }
        }
        val next = job.copy(files = files)
        if (next != job) runCatching { outbox.save(next) }
        return next
    }

    /** The smaller copy as a held file, or null when this one goes as it is */
    private fun shrinkOne(held: HeldFile, abandoned: AtomicBoolean): HeldFile? = try {
        val source = File(held.path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (noShrinkReason(IMAGE_SHRINK, held.mime, held.name, held.size, bounds.outWidth, bounds.outHeight) != null) null
        else {
            val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
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
                val exif = exifSegment(readHead(source))
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
    } catch (e: Throwable) {
        null       // including running out of memory
    }

    private fun readHead(file: File): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(EXIF_SCAN_BYTES)
        var n = 0
        while (n < buffer.size) {
            val got = input.read(buffer, n, buffer.size - n)
            if (got < 0) break
            n += got
        }
        buffer.copyOf(n)
    }
}
