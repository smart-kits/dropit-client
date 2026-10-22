package xyz.smart_kits.dropit.work

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.HoldStep
import xyz.smart_kits.dropit.shared.Targets
import xyz.smart_kits.dropit.shared.copyOverLimit
import xyz.smart_kits.dropit.shared.copyStalled
import xyz.smart_kits.dropit.shared.holdStep
import xyz.smart_kits.dropit.shared.intakeUriOk
import xyz.smart_kits.dropit.shared.randomGroupId
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A copied-in file waiting to be sent */
@Serializable
data class HeldFile(
    val path: String,
    val name: String?,
    val mime: String?,
    val size: Long,
    /** Already made smaller (`path` and `size` are the smaller copy's): a retry sends it as it is, so its size stays the same */
    val shrunk: Boolean = false,
    /** Whether making it smaller was looked at already (it applied, or it did not): not looked at again, so a retry sends exactly what the first try sent */
    val shrinkChecked: Boolean = false,
) {
    /**
     * Whether its bytes are held. A file that was not copied (over the plan's limit for one item, or past the tenth) is still listed, with
     * the size it was said to have, so that the result can say it was left out; nothing is read from it
     */
    val copied: Boolean get() = path.isNotEmpty()
}

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
    /** The same numbers by place in the batch (the text first, then the files): with several going at once they finish in any order */
    val seqAt: Map<Int, Long> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    /** Files handed over that could not be read, so the result can say so instead of leaving them out quietly */
    val unreadable: Int = 0,
    /** The devices it goes to (ids); empty: everyone, and the requests carry no `to` */
    val to: List<String> = emptyList(),
    /** Their names when it was held, for saying who it went to */
    val toNames: List<String> = emptyList(),
)

/** What the service numbered the items, in the batch's own order (the text, then the files), whatever order they finished in */
val Job.seqsInOrder: List<Long>
    get() = if (seqAt.isNotEmpty() && seqAt.size == seqs.size) seqAt.toSortedMap().values.toList() else seqs

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

    /**
     * Copy what was handed over into the outbox. Runs while the handing-over screen is still open. [to] is who it goes to; without it,
     * this phone's default (everyone unless the person chose some devices): the share sheet, the tile and the clipboard all send this way.
     *
     * Not everything is copied: a file the plan would refuse (its size is read first, from the provider, and the copy stops at the limit
     * when the size was not said) and the files past the tenth are listed with their size but never read, so a big or long share does not
     * fill the phone only to be told no.
     */
    fun hold(inbound: Inbound, to: Targets? = null): Job {
        val targets = to ?: context.app.peers.defaultTargets()
        val limit = context.app.account.cachedItemBytesLimit()
        val id = UUID.randomUUID().toString()
        val d = dir(id).apply { mkdirs() }
        val files = mutableListOf<HeldFile>()
        var unreadable = 0
        inbound.streams.forEachIndexed { k, s ->
            if (holdStep(files.size, null, null) == HoldStep.TOO_MANY) { files += HeldFile("", null, null, 0); return@forEachIndexed }
            val held = copyIn(Uri.parse(s), File(d, "f$k"), limit)
            if (held == null) unreadable++ else files += held
        }
        return Job(id, text = inbound.text, title = inbound.title, files = files, unreadable = unreadable, to = targets.ids, toNames = targets.names)
            .also(::save)
    }

    /**
     * One handed-over file, copied in. Only what another app's content provider serves is read ([intakeUriOk]). The reading runs on its
     * own thread and is given up on when it stops moving (20 seconds without the file opening, or without its next piece), so a provider that never
     * answers cannot hold the thread that is taking the share in. Null when it cannot be had.
     */
    private fun copyIn(uri: Uri, to: File, limit: Long?): HeldFile? {
        if (!intakeUriOk(uri.scheme, uri.authority, context.packageName)) return null
        val beat = AtomicLong(SystemClock.elapsedRealtime())
        val stop = AtomicBoolean(false)
        val task = copier.submit<HeldFile?> { read(uri, to, limit, beat, stop) }
        while (true) {
            try {
                return task.get(500, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                if (copyStalled(beat.get(), SystemClock.elapsedRealtime())) {
                    stop.set(true)
                    task.cancel(true)
                    to.delete()
                    return null
                }
            } catch (e: Exception) {
                to.delete()
                return null
            }
        }
    }

    private fun read(uri: Uri, to: File, limit: Long?, beat: AtomicLong, stop: AtomicBoolean): HeldFile? = try {
        val cr = context.contentResolver
        val (name, declared) = peek(uri)
        beat.set(SystemClock.elapsedRealtime())
        val mime = cr.getType(uri)
        beat.set(SystemClock.elapsedRealtime())
        if (holdStep(0, declared, limit) == HoldStep.TOO_LARGE) HeldFile("", name, mime, declared!!)
        else {
            var total = 0L
            var over = false
            cr.openInputStream(uri)!!.use { input ->
                beat.set(SystemClock.elapsedRealtime())
                to.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (stop.get()) throw java.io.InterruptedIOException("given up")
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (copyOverLimit(total, limit)) { over = true; break }
                        out.write(buffer, 0, n)
                        beat.set(SystemClock.elapsedRealtime())
                    }
                }
            }
            if (over) { to.delete(); HeldFile("", name, mime, total) }
            else HeldFile(to.absolutePath, name, mime, to.length())
        }
    } catch (e: Throwable) {
        to.delete()
        null
    }

    /** The name and the size the provider says it has (either may be missing) */
    private fun peek(uri: Uri): Pair<String?, Long?> {
        val cr = context.contentResolver
        fun ask(columns: Array<String>): Pair<String?, Long?>? = runCatching {
            cr.query(uri, columns, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString)
                val sz = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)?.takeIf { it >= 0 }
                n to sz
            }
        }.getOrNull()
        return ask(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)) ?: ask(arrayOf(OpenableColumns.DISPLAY_NAME)) ?: (null to null)
    }

    /**
     * Hand the job to the system: it runs now (as a short foreground job) and survives the app being closed. Returns the new run's id,
     * or null when a run of this job is already going or waiting: a second tap on Retry (or a second notification button) while it is
     * must not take the job from the run that has it. With [toAll] the devices it was addressed to are dropped first (everyone this time).
     *
     * Waits for the system to have taken the job (a database write): not for the main thread.
     */
    fun start(id: String, toAll: Boolean = false): UUID? = synchronized(startLock) {
        val work = WorkManager.getInstance(context)
        if (inFlight(work.getWorkInfosForUniqueWork(id).get().map { it.state })) return null
        if (toAll) load(id)?.let { save(it.copy(to = emptyList(), toNames = emptyList())) }
        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(SendWorker.JOB to id))
            .addTag(SendWorker.TAG)
            .build()
        // Never REPLACE: that would cancel a run that is going, and a cancelled run is told apart from a stopped one only by who asked
        work.enqueueUniqueWork(id, ExistingWorkPolicy.KEEP, req).result.get()
        req.id
    }

    /** Failed drops are kept for retrying; after a week they are only clutter */
    fun sweep(maxAgeMs: Long = 7L * 86_400_000) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    companion object {
        private val startLock = Any()
        private val copier = Executors.newCachedThreadPool { r -> Thread(r, "dropit-copy").apply { isDaemon = true } }

        /** Whether a run of the job is going or waiting, from the states of the runs the system has under its name */
        fun inFlight(states: List<WorkInfo.State>): Boolean = states.any { !it.isFinished }

        /**
         * Whether a run that was stopped was stopped by the person (Cancel on the card or the notification, which is the app asking the
         * system to cancel it). Only then do the held copies go: any other stop (the system's time limit, its quota, a change of
         * conditions) leaves them for the run that follows.
         */
        fun cancelledByPerson(stopReason: Int): Boolean = stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP
    }
}
