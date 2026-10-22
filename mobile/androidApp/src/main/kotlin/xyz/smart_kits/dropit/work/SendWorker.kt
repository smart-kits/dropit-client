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
import kotlinx.coroutines.launch
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.App
import xyz.smart_kits.dropit.shared.Answer
import xyz.smart_kits.dropit.shared.BatchPlan
import xyz.smart_kits.dropit.shared.SendFailure
import xyz.smart_kits.dropit.shared.Targets
import xyz.smart_kits.dropit.shared.targetsIn
import xyz.smart_kits.dropit.retentionMs
import xyz.smart_kits.dropit.shared.ApiError
import xyz.smart_kits.dropit.shared.BatchTooLarge
import xyz.smart_kits.dropit.shared.Failure
import xyz.smart_kits.dropit.shared.FileBody
import xyz.smart_kits.dropit.shared.FileInfo
import xyz.smart_kits.dropit.shared.Item
import xyz.smart_kits.dropit.shared.Letter
import xyz.smart_kits.dropit.shared.LetterFile
import xyz.smart_kits.dropit.shared.Message
import xyz.smart_kits.dropit.shared.OfflineError
import xyz.smart_kits.dropit.shared.Requests
import xyz.smart_kits.dropit.shared.fileSize
import xyz.smart_kits.dropit.shared.mb
import xyz.smart_kits.dropit.shared.planBatch
import xyz.smart_kits.dropit.shared.sendConcurrently
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
        // Big photos are made smaller first (a setting; on unless turned off), once: a retry sends the same file, so it is the same size
        job = Shrink(outbox).apply(job, app.store.imageMode)
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
            // Only files over the plan's limit: a free account is shown the way to send them
            val upgrade = if (tooLarge.isNotEmpty()) me?.upgradeUrl else null
            return fail(
                job.id, (unreadable + tooLarge).joinToString("\n").ifEmpty { applicationContext.getString(R.string.nothing_to_send) },
                retry = false, upgrade = upgrade, why = upgrade?.let { "file_size" },
            )
        }

        var deduped = false
        var sentTo: Targets? = null      // who the service says the first item went to
        var shownAt = 0L
        try {
            // A few at a time, started in order; the first failure stops new ones from starting (the ones on their way finish, so
            // what the service already took is written down). Each is recorded as it finishes, so a retry sends only what is left
            val sent = sendConcurrently<Answer>(
                count = plan.items.size, skip = job.done,
                onDone = { k, answer ->
                    deduped = deduped || answer.deduped
                    if (sentTo == null) sentTo = targetsIn(answer)
                    job = job.copy(
                        done = job.done + k, seqs = job.seqs + listOfNotNull(answer.seq),
                        seqAt = job.seqAt + listOfNotNull(answer.seq?.let { k to it }),
                    )
                    runCatching { outbox.save(job) }
                },
            ) { k ->
                val shown = minOf(job.done.size + 1, plan.items.size)
                setProgress(workDataOf(K to shown, N to plan.items.size))
                // The system drops a package's notifications past a few a second; a fast batch needs no more
                val now = SystemClock.elapsedRealtime()
                if (now - shownAt >= 500) {
                    shownAt = now
                    runCatching { setForeground(foreground(job.id, shown, plan.items.size)) }
                }
                when (val item = plan.items[k]) {
                    is Item.Text -> app.client.send(Requests.ingestText(item.kind, item.raw, item.meta, job.createdAt, job.to))
                    is Item.File -> {
                        val held = job.files[item.index]
                        val f = File(held.path)
                        app.client.send(
                            Requests.ingestFile(held.name, held.mime, item.group, item.from, job.to),
                            FileBody(f.length()) { f.inputStream().toByteReadChannel() },
                        )
                    }
                }
            }
            sent.failure?.let { throw it }
        } catch (e: CancellationException) {
            outbox.drop(job.id)            // cancelled: nothing half-sent stays behind, and the copies go
            notes.cancel(job.id)
            runCatching { app.letters.remove(setOf(job.id)); app.letters.release() }    // and so does the card of an earlier try
            throw e
        } catch (e: ApiError) {
            if (e.failure == Failure.AUTH_LOST) app.store.forget()
            // The devices it was addressed to are not on the account any more: the default is everyone again (the person is told), and
            // what was held stays, so one tap sends it to everyone this time
            if (e.code == "TARGET_UNAVAILABLE") {
                app.peers.defaultGone()
                app.scope.launch { app.peers.refresh() }
                val reason = applicationContext.getString(R.string.target_gone)
                recordFailure(job, plan, reason, retry = false, target = true)
                return fail(job.id, reason, retry = false, target = true)
            }
            // The service says so itself when a paid plan would have let this through
            val upgrade = e.data["upgrade_url"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            val why = e.data["why"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            val reason = app.strings.render(e.sentence())
            recordFailure(job, plan, reason, retry = upgrade == null)
            return fail(
                job.id, reason, retry = upgrade == null, rejoin = e.failure == Failure.AUTH_LOST,
                upgrade = upgrade, why = why,
            )
        } catch (e: OfflineError) {
            val reason = app.strings.render(e.sentence)
            recordFailure(job, plan, reason, retry = true)
            return fail(job.id, reason, retry = true)
        }

        val seqs = job.seqsInOrder
        val range = seqRange(seqs)
        val extra = buildList {
            if (plan.overCount.isNotEmpty()) add(applicationContext.getString(R.string.over_count, plan.overCount.size))
            addAll(unreadable)
            addAll(tooLarge)
        }
        val said = applicationContext.getString(if (deduped && seqs.size <= 1) R.string.sent_again else R.string.sent, range)
        notes.sent(job.id, said, extra, quiet = OnScreen.has(job.id) || InApp.has(job.id))
        // It joins the app's list as a letter from this phone, before the held copies go
        runCatching {
            val text = plan.items.filterIsInstance<Item.Text>().firstOrNull()
            val sentAt = System.currentTimeMillis()
            // Who it went to: as the service says it (it leaves out devices that were not valid), else as asked
            val to = sentTo ?: Targets(job.to, job.toNames)
            app.letters.add(
                Letter(
                    id = job.id, mine = true, at = sentAt, seqs = seqs,
                    toIds = to.ids, toNames = to.names, until = keptUntil(app, sentAt),
                    kind = text?.kind, text = text?.raw, title = text?.meta?.title,
                    files = plan.items.filterIsInstance<Item.File>().map { f ->
                        val held = job.files[f.index]
                        LetterFile(held.name ?: "?", held.size, held.mime, thumb = app.letters.thumb(File(held.path), "${job.id}-${f.index}", held.mime))
                    },
                ),
            )
        }
        outbox.drop(job.id)
        return Result.success(workDataOf(SEQS to seqs.toLongArray(), DONE to (listOf(said) + extra).joinToString("\n")))
    }

    private fun fail(jobId: String, reason: String, retry: Boolean, rejoin: Boolean = false, upgrade: String? = null, why: String? = null, target: Boolean = false): Result {
        // A limit that a paid plan lifts was just met: the pill at the top of the home screen may move for a day
        if (upgrade != null) runCatching { applicationContext.app.moments.markWall(System.currentTimeMillis()) }
        if (InApp.has(jobId)) outbox.drop(jobId) else notes.failed(jobId, reason, retry, rejoin, upgrade, toAll = target, why = why)
        return Result.failure(workDataOf(ERROR to reason, RETRY to retry, UPGRADE to upgrade, WHY to (if (target) "target" else why), REJOIN to rejoin))
    }

    /**
     * A drop that stopped part-way becomes a card in the list of what this phone sent, saying which files did not go and why. What was
     * held stays for the retry, which sends only what is left; when it is sent the card is replaced by the sent letter (same id).
     * A drop sent from dropit's own screen is not kept: the screen keeps what was written and says why there.
     */
    private fun recordFailure(job: Job, plan: BatchPlan, reason: String, retry: Boolean, target: Boolean = false) {
        if (InApp.has(job.id)) return
        runCatching {
            val app = applicationContext.app
            val text = plan.items.withIndex().firstOrNull { it.value is Item.Text }
            val fileItems = plan.items.withIndex().filter { it.value is Item.File }
            app.letters.add(
                Letter(
                    id = job.id, mine = true, at = job.createdAt, seqs = job.seqsInOrder,
                    toIds = job.to, toNames = job.toNames,
                    kind = (text?.value as? Item.Text)?.kind, text = (text?.value as? Item.Text)?.raw, title = (text?.value as? Item.Text)?.meta?.title,
                    files = fileItems.map { (_, item) ->
                        val held = job.files[(item as Item.File).index]
                        LetterFile(held.name ?: "?", held.size, held.mime, thumb = app.letters.thumb(File(held.path), "${job.id}-${item.index}", held.mime))
                    },
                    failure = SendFailure(
                        reason = reason,
                        files = fileItems.mapIndexedNotNull { pos, (k, _) -> pos.takeIf { k !in job.done } },
                        words = text != null && text.index !in job.done,
                        retry = retry, target = target,
                    ),
                ),
            )
        }
    }

    /** When the service stops keeping what was just sent */
    private suspend fun keptUntil(app: App, from: Long): Long = from + app.retentionMs()

    companion object {
        const val JOB = "job"
        const val ERROR = "error"
        const val RETRY = "retry"
        const val UPGRADE = "upgrade"
        const val WHY = "why"
        const val REJOIN = "rejoin"
        const val SEQS = "seqs"
        const val DONE = "done"
        const val K = "k"
        const val N = "n"
        const val TAG = "send"
    }
}
