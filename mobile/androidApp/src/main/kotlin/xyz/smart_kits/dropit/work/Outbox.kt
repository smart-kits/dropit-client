package xyz.smart_kits.dropit.work

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.smart_kits.dropit.shared.randomGroupId
import java.io.File
import java.util.UUID

/** A copied-in file waiting to be sent */
@Serializable
data class HeldFile(val path: String, val name: String?, val mime: String?, val size: Long)

/**
 * One drop, held until it is sent. Files are copied in at once: another app's permission to read them ends
 * when its task does, and a retry needs them again. `done` lists the items already sent, so a retry sends
 * only the rest, under the same batch id.
 */
@Serializable
data class Job(
    val id: String,
    val text: String? = null,
    val title: String? = null,
    val files: List<HeldFile> = emptyList(),
    val groupId: String = randomGroupId(),
    val done: Set<Int> = emptySet(),
    val seqs: List<Long> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** Files handed over that could not be read, so the result can say so instead of leaving them out quietly */
    val unreadable: Int = 0,
)

class Outbox(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "outbox")
    private val json = Json { ignoreUnknownKeys = true }

    fun dir(id: String) = File(root, id)
    fun load(id: String): Job? = runCatching { json.decodeFromString<Job>(File(dir(id), "job.json").readText()) }.getOrNull()
    fun save(job: Job) {
        dir(job.id).mkdirs()
        val tmp = File(dir(job.id), "job.json.tmp")
        tmp.writeText(json.encodeToString(job))
        tmp.renameTo(File(dir(job.id), "job.json"))
    }
    fun drop(id: String) { dir(id).deleteRecursively() }

    /** Copy what was handed over into the outbox. Runs while the handing-over screen is still open. */
    fun hold(inbound: Inbound): Job {
        val id = UUID.randomUUID().toString()
        val d = dir(id).apply { mkdirs() }
        val files = inbound.streams.mapIndexedNotNull { k, s -> copyIn(Uri.parse(s), File(d, "f$k")) }
        return Job(id, text = inbound.text, title = inbound.title, files = files, unreadable = inbound.streams.size - files.size)
            .also(::save)
    }

    private fun copyIn(uri: Uri, to: File): HeldFile? = runCatching {
        val cr = context.contentResolver
        var name: String? = null
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0)
        }
        if (name == null && uri.scheme == "file") name = uri.lastPathSegment
        cr.openInputStream(uri)!!.use { input -> to.outputStream().use { input.copyTo(it) } }
        HeldFile(to.absolutePath, name, cr.getType(uri), to.length())
    }.getOrNull()

    /** Hand the job to the system: it runs now (as a short foreground job) and survives the app being closed */
    fun enqueue(id: String) {
        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(SendWorker.JOB to id))
            .addTag(SendWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, req)
    }

    /** Failed drops are kept for retrying; after a week they are only clutter */
    fun sweep(maxAgeMs: Long = 7L * 86_400_000) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }
}
