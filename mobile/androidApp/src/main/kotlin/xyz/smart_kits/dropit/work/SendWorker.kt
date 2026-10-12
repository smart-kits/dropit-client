package xyz.smart_kits.dropit.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.CancellationException
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.ApiError
import xyz.smart_kits.dropit.shared.BatchTooLarge
import xyz.smart_kits.dropit.shared.Failure
import xyz.smart_kits.dropit.shared.FileBody
import xyz.smart_kits.dropit.shared.FileInfo
import xyz.smart_kits.dropit.shared.Item
import xyz.smart_kits.dropit.shared.Message
import xyz.smart_kits.dropit.shared.OfflineError
import xyz.smart_kits.dropit.shared.Requests
import xyz.smart_kits.dropit.shared.fileSize
import xyz.smart_kits.dropit.shared.mb
import xyz.smart_kits.dropit.shared.planBatch
import xyz.smart_kits.dropit.shared.seqRange
import java.io.File

/**
 * Sends one held drop. Whatever happens, it ends in one notification: sent (gone after 3 seconds), or not
 * sent with the reason and a Retry button. Never says "sent" without an answer from the service.
 */
class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val outbox = Outbox(context)
    private val notes = Notes(context)

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(inputData.getString(JOB) ?: "", 0, 0)

    private fun foreground(id: String, k: Int, n: Int): ForegroundInfo {
        val note = notes.progress(id, k, n, this.id)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(Notes.progressIdFor(id), note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(Notes.progressIdFor(id), note)
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(JOB) ?: return Result.failure()
        var job = outbox.load(id) ?: return Result.failure()
        val app = applicationContext.app
        if (!app.account.signedIn) {
            return fail(job.id, applicationContext.getString(R.string.join_again), retry = true, rejoin = true)
        }
        val me = app.account.me()
        val plan = try {
            planBatch(job.text, job.files.map { FileInfo(it.name, it.mime, it.size) }, job.title, null, me?.itemBytesLimit, job.groupId)
        } catch (e: BatchTooLarge) {
            outbox.drop(job.id)
            return fail(job.id, app.strings.render(Message("batchTooLarge", listOf(e.max.toString()))), retry = false)
        }
        val tooLarge = plan.tooLarge.map { i ->
            val f = job.files[i]
            applicationContext.getString(R.string.too_large, f.name ?: "?", fileSize(f.size), mb(me?.itemBytesLimit ?: 0))
        }
        val unreadable = if (job.unreadable > 0) {
            listOf(applicationContext.resources.getQuantityString(R.plurals.unreadable, job.unreadable, job.unreadable))
        } else emptyList()
        if (plan.isEmpty) {
            outbox.drop(job.id)
            return fail(job.id, (unreadable + tooLarge).joinToString("\n").ifEmpty { applicationContext.getString(R.string.nothing_to_send) }, retry = false)
        }

        var deduped = false
        var shownAt = 0L
        try {
            plan.items.forEachIndexed { k, item ->
                if (k in job.done) return@forEachIndexed
                setProgress(workDataOf(K to k + 1, N to plan.items.size))
                // The system drops a package's notifications past a few a second; a fast batch needs no more
                val now = SystemClock.elapsedRealtime()
                if (now - shownAt >= 500) {
                    shownAt = now
                    runCatching { setForeground(foreground(job.id, k + 1, plan.items.size)) }
                }
                val answer = when (item) {
                    is Item.Text -> app.client.send(Requests.ingestText(item.kind, item.raw, item.meta, job.createdAt))
                    is Item.File -> {
                        val f = File(job.files[item.index].path)
                        val held = job.files[item.index]
                        app.client.send(
                            Requests.ingestFile(held.name, held.mime, item.group, item.from),
                            FileBody(f.length()) { f.inputStream().toByteReadChannel() },
                        )
                    }
                }
                deduped = deduped || answer.deduped
                job = job.copy(done = job.done + k, seqs = job.seqs + listOfNotNull(answer.seq))
                outbox.save(job)
            }
        } catch (e: CancellationException) {
            outbox.drop(job.id)            // cancelled: nothing half-sent stays behind, and the copies go
            notes.cancel(job.id)
            throw e
        } catch (e: ApiError) {
            if (e.failure == Failure.AUTH_LOST) app.store.forget()
            return fail(job.id, app.strings.render(e.sentence()), retry = true, rejoin = e.failure == Failure.AUTH_LOST)
        } catch (e: OfflineError) {
            return fail(job.id, app.strings.render(e.sentence), retry = true)
        }

        val range = seqRange(job.seqs)
        val extra = buildList {
            if (plan.overCount.isNotEmpty()) add(applicationContext.getString(R.string.over_count, plan.overCount.size))
            addAll(unreadable)
            addAll(tooLarge)
        }
        val said = applicationContext.getString(if (deduped && job.seqs.size <= 1) R.string.sent_again else R.string.sent, range)
        notes.sent(job.id, said, extra, quiet = OnScreen.has(job.id) || InApp.has(job.id))
        outbox.drop(job.id)
        return Result.success(workDataOf(SEQS to job.seqs.toLongArray(), DONE to (listOf(said) + extra).joinToString("\n")))
    }

    private fun fail(jobId: String, reason: String, retry: Boolean, rejoin: Boolean = false): Result {
        if (InApp.has(jobId)) outbox.drop(jobId) else notes.failed(jobId, reason, retry, rejoin)
        return Result.failure(workDataOf(ERROR to reason, RETRY to retry))
    }

    companion object {
        const val JOB = "job"
        const val ERROR = "error"
        const val RETRY = "retry"
        const val SEQS = "seqs"
        const val DONE = "done"
        const val K = "k"
        const val N = "n"
        const val TAG = "send"
    }
}
