package xyz.smart_kits.dropit.shared

// "Pull again": bring back what this phone already had, because it was cleared from the list or the list was lost. Three steps and no
// others, so there is nothing to type: everything the service keeps, the newest 50, or the last three days.

/** How far back to pull again */
enum class RepullSpan(val last: Int?, val days: Int?) {
    ALL(null, null),
    LAST_50(50, null),
    DAYS_3(null, 3),
}

/** The request that moves the place in the line back for a span. "Days" start from the beginning and are cut on this side. */
fun repullReset(span: RepullSpan): RequestSpec = when {
    span.last != null -> Requests.resetCursor(last = span.last)
    else -> Requests.resetCursor(toSeq = 0)
}

/** What of a span is kept once everything after the reset has been read: for "days", only what arrived in that time */
fun keepForSpan(items: List<Incoming>, span: RepullSpan, nowMs: Long): List<Incoming> {
    val days = span.days ?: return items
    val since = nowMs - days * DAY_MS
    return items.filter { it.at >= since }
}

/** One pull again may be asked for at most this often */
const val REPULL_GAP_MS = 60_000L

/** Milliseconds until the next pull again is allowed; 0 when it may be asked for now */
fun repullWaitMs(lastAt: Long, nowMs: Long): Long = if (lastAt <= 0L) 0L else (lastAt + REPULL_GAP_MS - nowMs).coerceIn(0L, REPULL_GAP_MS)
