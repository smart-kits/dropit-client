package xyz.smart_kits.dropit.shared

// "Pull again": bring back what this phone already had, because it was cleared from the list or the list was lost. One plan, two sliders:
// the newest [RepullPlan.count] items (1 to 50, starting at 10) that are also not older than [RepullPlan.days] days (1 up to the days a paid
// plan keeps content, starting at 1; a free account stays at 1, and the track shows how far a paid one goes).

/** The fewest and the most items one pull again brings back (0 would bring back nothing, so the least is 1) */
const val REPULL_MIN_COUNT = 1
const val REPULL_MAX_COUNT = 50
const val REPULL_DEFAULT_COUNT = 10
const val REPULL_DEFAULT_DAYS = 1

/** What to bring back: the newest [count] items that arrived within the last [days] days */
data class RepullPlan(val count: Int = REPULL_DEFAULT_COUNT, val days: Int = REPULL_DEFAULT_DAYS)

/** How long the days slider's track is: up to the days a paid plan keeps content (the service's number); 1 when that is not known */
fun repullTrackDays(paidDays: Int?): Int = (paidDays ?: 1).coerceAtLeast(1)

/**
 * The most days this account may choose: a free account has one; a paid one, the days its content is kept (the service's number for the
 * account, else the paid plan's), never more than the track
 */
fun repullDaysLimit(paid: Boolean, keptDays: Int?, paidDays: Int?): Int {
    val track = repullTrackDays(paidDays)
    if (!paid) return 1
    return (keptDays ?: track).coerceIn(1, track)
}

/** A choice made to fit the limits: the count 1 to 50, the days 1 up to [daysLimit] */
fun repullPlanOf(count: Int, days: Int, daysLimit: Int): RepullPlan =
    RepullPlan(count.coerceIn(REPULL_MIN_COUNT, REPULL_MAX_COUNT), days.coerceIn(1, daysLimit.coerceAtLeast(1)))

/** Whether the days slider can be moved: only when the account may choose more than one day */
fun repullDaysMovable(daysLimit: Int): Boolean = daysLimit > 1

/** Whether the days slider is drawn at all: when there is a track longer than one day to show */
fun repullDaysShown(paidDays: Int?): Boolean = repullTrackDays(paidDays) > 1

/** Whether touching the stopped days slider says why: for an account that cannot move it, while a longer track is there to point at */
fun repullKeptHintFor(daysLimit: Int, paidDays: Int?): Boolean = !repullDaysMovable(daysLimit) && repullDaysShown(paidDays)

/** Whether the hint has a "see plans" button: only where paying is on offer in this build and the service has not said it is closed */
fun repullHintHasButton(paymentsBuild: Boolean, offering: Boolean): Boolean = paymentsBuild && offering

/** The request that moves the place in the line back to just before the newest [RepullPlan.count] items (only the service can count them) */
fun repullReset(plan: RepullPlan): RequestSpec = Requests.resetCursor(last = plan.count)

/** What of a plan is kept once everything after the reset has been read: the newest [RepullPlan.count] of what arrived within the days */
fun keepForPlan(items: List<Incoming>, plan: RepullPlan, nowMs: Long): List<Incoming> {
    val since = nowMs - plan.days * DAY_MS
    return items.filter { it.at >= since }.sortedBy { it.seq }.takeLast(plan.count)
}

/** One pull again may be asked for at most this often */
const val REPULL_GAP_MS = 60_000L

/** Milliseconds until the next pull again is allowed; 0 when it may be asked for now */
fun repullWaitMs(lastAt: Long, nowMs: Long): Long = if (lastAt <= 0L) 0L else (lastAt + REPULL_GAP_MS - nowMs).coerceIn(0L, REPULL_GAP_MS)
