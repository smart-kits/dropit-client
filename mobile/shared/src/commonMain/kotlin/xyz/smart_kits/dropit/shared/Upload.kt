package xyz.smart_kits.dropit.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// How a batch goes out: a few at a time, in order, and what to do when one fails. Only the arithmetic and the
// scheduling live here; the requests themselves are the app's.

/** How many requests of one batch are on their way at once (the text counts as one) */
const val UPLOAD_CONCURRENCY = 3

/** A connection kept warm is not warmed again within this long (the idle connection a client keeps goes after about this long) */
const val WARM_GAP_MS = 5L * 60_000

/** What came of sending a batch: what went through (by position in the batch), and the failure that stopped it, if any */
class UploadResult<T>(
    /** Position in the batch → the answer; ascending by position, whatever order they finished in */
    val done: Map<Int, T>,
    /** The failure of the earliest position that failed: the one to tell the person about */
    val failure: Throwable?,
    /** The positions that failed (each tried once; none was started after the first failure) */
    val failed: Set<Int>,
) {
    /** The answers in the batch's own order */
    val inOrder: List<T> get() = done.entries.sortedBy { it.key }.map { it.value }
}

/**
 * Run [work] for every position in `0 until count` that is not in [skip], at most [concurrency] at a time, starting them in order.
 *
 * - After the first failure nothing new is started; those already on their way finish (cancelling one could drop an answer the
 *   service already gave, and a retry would then send it twice).
 * - One failing does not stop the others from being recorded: [onDone] is called, one at a time, for each that went through.
 * - A cancellation from outside cancels all of it and is not a failure.
 */
suspend fun <T> sendConcurrently(
    count: Int,
    skip: Set<Int> = emptySet(),
    concurrency: Int = UPLOAD_CONCURRENCY,
    onDone: suspend (position: Int, answer: T) -> Unit = { _, _ -> },
    work: suspend (position: Int) -> T,
): UploadResult<T> {
    val todo = (0 until count).filter { it !in skip }
    val lock = Mutex()
    val done = mutableMapOf<Int, T>()
    val errors = mutableMapOf<Int, Throwable>()
    var next = 0
    supervisorScope {
        repeat(minOf(concurrency.coerceAtLeast(1), todo.size)) {
            launch {
                while (true) {
                    val position = lock.withLock { if (errors.isNotEmpty() || next >= todo.size) null else todo[next++] } ?: break
                    try {
                        val answer = work(position)
                        lock.withLock { done[position] = answer; onDone(position, answer) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        lock.withLock { errors[position] = e }
                    }
                }
            }
        }
    }
    val first = errors.keys.minOrNull()
    return UploadResult(done.entries.sortedBy { it.key }.associate { it.key to it.value }, first?.let { errors[it] }, errors.keys.toSet())
}

/** Whether a warm-up is due: never done, or the last one was long enough ago ([nowMs] and [lastMs] on the same monotonic clock) */
fun warmDue(lastMs: Long?, nowMs: Long, gapMs: Long = WARM_GAP_MS): Boolean = lastMs == null || nowMs - lastMs >= gapMs

/** The service's own time for a request, from a `Server-Timing` header (`total;dur=41, …`): null when it is not there */
fun serverMs(header: String?): Long? {
    if (header == null) return null
    for (part in header.split(',')) {
        val bits = part.trim().split(';').map { it.trim() }
        if (bits.firstOrNull() != "total") continue
        val dur = bits.drop(1).firstOrNull { it.startsWith("dur=") }?.removePrefix("dur=") ?: continue
        return dur.toDoubleOrNull()?.toLong()
    }
    return null
}
