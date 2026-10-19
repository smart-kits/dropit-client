package xyz.smart_kits.dropit.receive

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.smart_kits.dropit.LetterBox
import xyz.smart_kits.dropit.shared.ApiError
import xyz.smart_kits.dropit.shared.DropitClient
import xyz.smart_kits.dropit.shared.Letter
import xyz.smart_kits.dropit.shared.LetterFile
import xyz.smart_kits.dropit.shared.OfflineError
import xyz.smart_kits.dropit.shared.Requests
import xyz.smart_kits.dropit.shared.freshAddress
import java.net.HttpURLConnection
import java.net.URL

/**
 * Saves a received file into the phone's Downloads (Downloads/dropit), on a tap.
 *
 * The address a file arrived with works for five minutes, so a tap first asks the service for the item again (one read, nothing
 * written): the service leaves out what has expired, and that is how an expired file is told from one that can be saved.
 * Each save counts against the account's downloads for the day; nothing is downloaded by itself.
 * A file already on the phone is never fetched again: [LetterFile.saved] holds where it is.
 */
class Downloader(private val context: Context, private val client: DropitClient, private val letters: LetterBox) {
    sealed interface Result {
        data class Saved(val uri: String) : Result
        /** The service no longer has it (dropit delivers, it does not store) */
        data object Expired : Result
        /** The account's downloads for today are used up */
        data object LimitToday : Result
        data object Offline : Result
        /** Needs Android 10 or newer: where the file is put is the system's shared Downloads */
        data object Unsupported : Result
        data object Failed : Result
    }

    suspend fun save(letter: Letter, file: LetterFile): Result = withContext(Dispatchers.IO) {
        val seq = file.seq ?: return@withContext Result.Failed
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext Result.Unsupported
        val address = try {
            freshAddress(client.send(Requests.itemAgain(seq)), seq)
        } catch (e: OfflineError) {
            return@withContext Result.Offline
        } catch (e: ApiError) {
            return@withContext Result.Failed
        }
        if (address == null) {
            letters.update(letter.id, seq) { it.copy(expires = 0) }   // from now on the list says so without asking
            return@withContext Result.Expired
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, cleanName(file.name))
            put(MediaStore.Downloads.MIME_TYPE, file.mime ?: "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/dropit")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        // The system may refuse the entry (a name or type it does not take), and the address is the service's to give:
        // neither may take the app down, and an entry already made must not be left half there
        val target = try { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) } catch (e: Exception) { null }
            ?: return@withContext Result.Failed
        val connection = try {
            (URL(address).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 60_000 }
        } catch (e: Exception) {
            runCatching { resolver.delete(target, null, null) }
            return@withContext Result.Failed
        }
        // An IOException while writing is the phone's storage (full, or refused), not the network: said as a failure, not "offline"
        var storageFailed = false
        try {
            when (connection.responseCode) {
                200 -> {
                    val out = resolver.openOutputStream(target) ?: throw IllegalStateException("no output")
                    connection.inputStream.use { input ->
                        out.use { sink ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                try { sink.write(buffer, 0, n) } catch (e: java.io.IOException) { storageFailed = true; throw e }
                            }
                        }
                    }
                    resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    letters.update(letter.id, seq) { it.copy(saved = target.toString()) }
                    Result.Saved(target.toString())
                }
                429 -> { resolver.delete(target, null, null); Result.LimitToday }
                404 -> { resolver.delete(target, null, null); letters.update(letter.id, seq) { it.copy(expires = 0) }; Result.Expired }
                else -> { resolver.delete(target, null, null); Result.Failed }
            }
        } catch (e: java.io.IOException) {
            runCatching { resolver.delete(target, null, null) }
            if (storageFailed) Result.Failed else Result.Offline
        } catch (e: Throwable) {
            runCatching { resolver.delete(target, null, null) }
            Result.Failed
        } finally {
            connection.disconnect()
        }
    }

    /** Whether what was saved is still there: the person may have deleted it from the phone */
    fun stillThere(uri: String): Boolean = runCatching { context.contentResolver.openInputStream(Uri.parse(uri))?.use { true } ?: false }.getOrDefault(false)

    /** A name the file system takes: no path, no characters it reserves */
    private fun cleanName(name: String): String =
        name.substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifEmpty { "file" }.take(120)
}
