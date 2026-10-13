package xyz.smart_kits.dropit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import xyz.smart_kits.dropit.shared.Letter
import xyz.smart_kits.dropit.shared.keepRecent
import xyz.smart_kits.dropit.shared.withLetter
import java.io.File

/**
 * The week of letters the app shows: what arrived and what this phone sent. Kept in one small file that is
 * never backed up; pictures get a small copy so the list can show them after the original is gone from the
 * outbox. Nothing here is ever sent anywhere.
 */
class LetterBox(context: Context) {
    private val file = File(context.noBackupFilesDir, "letters.json")
    private val thumbs = File(context.noBackupFilesDir, "thumbs")
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(load())

    val all: StateFlow<List<Letter>> get() = state

    private fun load(): List<Letter> = runCatching {
        keepRecent(json.decodeFromString<List<Letter>>(file.readText()), System.currentTimeMillis())
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(letter: Letter) = save(withLetter(state.value, letter, System.currentTimeMillis()))

    @Synchronized
    fun clear() = save(emptyList())

    private fun save(list: List<Letter>) {
        state.value = list
        runCatching {
            val tmp = File(file.parentFile, "letters.json.tmp")
            tmp.writeText(json.encodeToString(list))
            tmp.renameTo(file)
        }
        // Small copies of pictures whose letter is gone go with it
        val kept = list.flatMap { l -> l.files.mapNotNull { it.thumb } }.toSet()
        thumbs.listFiles()?.filter { it.absolutePath !in kept }?.forEach { it.delete() }
    }

    /** A small copy of a picture, at most [edge] pixels on its long side. Null for anything that is not a picture. */
    fun thumb(source: File, key: String, mime: String?, edge: Int = 360): String? {
        if (mime?.startsWith("image/") != true) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
            val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            thumbs.mkdirs()
            val out = File(thumbs, "$key.jpg")
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            bitmap.recycle()
            out.absolutePath
        }.getOrNull()
    }
}
