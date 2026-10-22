package xyz.smart_kits.dropit.shared

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// Receiving with the app closed, the connection's side of it: how often a connection may be asked for, when to stop and when to try
// again, and a few small rules around it. Pure arithmetic: the clock and the dice come in as arguments, and the connecting, waiting
// and drawing are the app's (see Channel.kt for the choosing and the frames).

const val DAY_MS = 86_400_000L

/** How many connection tickets this phone takes in a day (UTC): a share of the account's, which its other devices use too. To be settled by use. */
const val TICKETS_PER_DAY = 60

/** From this many a day, settings says so in small print (for finding out how often a phone reconnects) */
const val TICKETS_NOTED_AT = 40

/** A connection that held this long was a good one: the waits start from the beginning again after it */
const val STABLE_MS = 60_000L

/** Nothing heard on the connection (not even an answer to the heartbeat) for this long: it is dead, close it and connect again */
const val STALE_MS = 150_000L

/** A new ticket is taken at some point in this long after midnight UTC (not all at once), so the account's day count and the trial's "today" move on */
const val RENEW_WINDOW_MS = 600_000L

/** After a connection fell back to no-push, the same kind of network is not tested for push again for this long */
const val REPROBE_GAP_MS = DAY_MS

/** The UTC day a time falls on */
fun utcDay(nowMs: Long): Long = nowMs / DAY_MS

/** How many tickets this phone has taken so far on a UTC day: [count] of [day] */
data class TicketLedger(val day: Long = -1, val count: Int = 0)

fun ticketsToday(ledger: TicketLedger, nowMs: Long): Int = if (ledger.day == utcDay(nowMs)) ledger.count else 0

fun canTakeTicket(ledger: TicketLedger, nowMs: Long, limit: Int = TICKETS_PER_DAY): Boolean = ticketsToday(ledger, nowMs) < limit

/** The count after one more is taken; a new UTC day starts from zero */
fun tookTicket(ledger: TicketLedger, nowMs: Long): TicketLedger = TicketLedger(utcDay(nowMs), ticketsToday(ledger, nowMs) + 1)

fun msUntilUtcMidnight(nowMs: Long): Long = DAY_MS - nowMs % DAY_MS

/** [ms] plus up to [fraction] more, by [dice] (0..1): many phones that lost the service together do not all come back in the same second */
fun withJitter(ms: Long, dice: Double, fraction: Double = 0.2): Long = ms + (ms * fraction * dice.coerceIn(0.0, 1.0)).toLong()

/**
 * How many tries in a row have failed once a connection ends: a connection that held for [STABLE_MS] or more was a good one, so
 * this is the first failure of a new run (the wait goes back to a second); a shorter one counts on from [previous].
 * Without this, "connected, then dropped at once" would reset the wait every time and hammer the service.
 */
fun failedInARow(previous: Int, heldMs: Long): Int = if (heldMs >= STABLE_MS) 1 else previous + 1

/** What to do after the connection is gone or a ticket was refused */
sealed interface Next {
    /** Wait [delayMs], take a new ticket, connect, then pull what was missed */
    data class Wait(val delayMs: Long) : Next
    /**
     * Stay away and say why. [resumeAfterMs]: when to try again (after UTC midnight, spread over [RENEW_WINDOW_MS]); null: not by
     * itself — only when the person switches it off and on, or the account changes (the trial is over: it asks again when the
     * account says real-time is available)
     */
    data class Stop(val why: Message, val resumeAfterMs: Long?) : Next
}

/** The refusal of a ticket by the day's count: 429 `window: day` */
fun isTicketsDay(e: ApiError): Boolean = e.status == 429 && e.code == "RATE_LIMITED" && e.data["window"]?.jsonPrimitive?.contentOrNull == "day"

/**
 * The step after a drop, with everything [afterDrop] decides plus the day's count and when "today is over" ends.
 * @param dice a number in 0..1 (a fresh random one each call) for spreading waits
 */
fun nextAfter(nowMs: Long, dice: Double, attempt: Int, end: String? = null, refused: ApiError? = null): Next {
    if (refused != null && isTicketsDay(refused)) {
        val sec = refused.data["retry_after"]?.jsonPrimitive?.longOrNull
        val base = if (sec != null && sec > 0) sec * 1000 else msUntilUtcMidnight(nowMs)
        return Next.Stop(Message("ticketsDay"), base + (RENEW_WINDOW_MS * dice.coerceIn(0.0, 1.0)).toLong())
    }
    return when (val a = afterDrop(attempt, end, refused)) {
        is AfterDrop.Reconnect -> Next.Wait(withJitter(a.delayMs, dice))
        is AfterDrop.Later -> Next.Wait(withJitter(a.delayMs, dice))
        is AfterDrop.Stop -> Next.Stop(
            a.why,
            if (a.why.key == "realtimeFull") msUntilUtcMidnight(nowMs) + (RENEW_WINDOW_MS * dice.coerceIn(0.0, 1.0)).toLong() else null,
        )
    }
}

/** When the next ticket is taken for the sake of the new UTC day: a moment after midnight, spread over [RENEW_WINDOW_MS] */
fun renewAt(nowMs: Long, dice: Double): Long = nowMs + msUntilUtcMidnight(nowMs) + (RENEW_WINDOW_MS * dice.coerceIn(0.0, 1.0)).toLong()

/** What to do when there is no push address (asked for, and none came) */
enum class NoAddress {
    /** No network that works right now: not a verdict on push; ask again when there is one */
    RETRY_LATER,
    /** The network works and there is still no address: push does not get through here; keep a connection instead */
    CONNECTION,
}

fun whenNoAddress(online: Boolean): NoAddress = if (online) NoAddress.CONNECTION else NoAddress.RETRY_LATER

/**
 * What asking the service for a test push came to, from its refusal. Null: the question says nothing about this phone (the key is
 * no good, or the service is down) — ask again later. Push the service cannot send at all counts as no push here.
 */
fun probeOfError(e: ApiError): Probe? = when {
    e.failure == Failure.AUTH_LOST -> null
    realtimeRefusal(e) != null -> Probe.Refused(e)
    e.code == "PUSH_UNAVAILABLE" || e.code == "PUSH_FAILED" -> Probe.Unavailable
    e.status >= 500 -> null
    else -> Probe.Unavailable
}

/**
 * Whether a change of network is a reason to test push again. A phone on a connection tests again at most once a day for the same
 * kind of network ([sameKind]: Wi-Fi to Wi-Fi), or each switch would cost a ten-second wait and a test push.
 */
fun reprobeOnNetworkChange(channel: Channel, networkChanged: Boolean, sameKind: Boolean, sinceLastProbeMs: Long?): Boolean {
    if (!shouldReprobe(channel, missedOnCatchUp = false, networkChanged = networkChanged)) return false
    if (channel != Channel.CONNECTION) return true
    return !sameKind || sinceLastProbeMs == null || sinceLastProbeMs >= REPROBE_GAP_MS
}

/**
 * When today's downloads start again, given what the service said ([retryAfterSec], seconds): that long from [nowMs], and never
 * more than a day. When it did not say, at the next UTC midnight (the downloads are counted by UTC day).
 */
fun downloadsResumeAt(nowMs: Long, retryAfterSec: Long?): Long =
    nowMs + (if (retryAfterSec != null) retryAfterSec.coerceIn(0, 86_400) * 1000 else msUntilUtcMidnight(nowMs))

/**
 * The notification for something that arrived, as the phone shows it: a letter with one file gets a Download button (the body still
 * opens the app); several files, or files with text, open the app; everything else is [noticeFor] as it is. Nothing downloads by itself.
 */
fun noticeOnPhone(letter: Letter, notice: Notice): Notice {
    if (letter.files.isEmpty()) return notice
    val single = letter.text == null && letter.files.size == 1 && letter.files[0].saved == null && letter.files[0].seq != null
    return if (single) notice.copy(actions = listOf(NoticeAction.DOWNLOAD), opensApp = true) else notice.copy(actions = emptyList(), opensApp = true)
}

/** Which of the two "tiles" in the pull-down panel a request is about */
enum class TileKind { SEND_CLIPBOARD, RECEIVE_LATEST }

/** What the "Get latest" tile does with the newest thing that came from another device */
sealed interface LatestAction {
    /** Text or a link: put it on the clipboard */
    data class Copy(val text: String) : LatestAction
    /** Files: nothing is downloaded by itself — the app opens, where they are saved on a tap */
    data object OpenApp : LatestAction
    /** Nothing from another device yet */
    data object None : LatestAction
}

/** The newest letter from another device (the list is newest first): what the tile does with it */
fun latestAction(letters: List<Letter>): LatestAction {
    val newest = letters.filter { !it.mine }.maxByOrNull { it.at } ?: return LatestAction.None
    val text = newest.text
    return when {
        newest.files.isNotEmpty() -> LatestAction.OpenApp
        !text.isNullOrEmpty() -> LatestAction.Copy(text)
        else -> LatestAction.None
    }
}
