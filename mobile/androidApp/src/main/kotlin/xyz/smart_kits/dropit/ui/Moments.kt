package xyz.smart_kits.dropit.ui

import xyz.smart_kits.dropit.Me
import xyz.smart_kits.dropit.Store
import xyz.smart_kits.dropit.shared.Catalog
import xyz.smart_kits.dropit.shared.Plans

// When the app says something about the plan, and when it keeps quiet.
//
// Nothing here draws anything: it decides. The rules are the ones the notes follow —
//   · only under pressure (a limit close, the trial ending, the paid plan ending); never a reason of its own;
//   · each note once per period, and a note that was waved away does not come back in that period;
//   · one at a time: a limit that was just met, then a paid plan ending, then the trial, then a limit that is close;
//   · nothing about Free for an account that is Paid;
//   · what was said is remembered on this phone only, under `dropit.moment.<note>.<period>`; if that can't be read or written,
//     the note is not shown (never an error, never a note that would come back every time).
// Every number the notes use comes from the service's answers; what the service did not say is left out, not guessed.

const val DAY_MS = 86_400_000L

/** The trial's last days: a card from this many days before it ends, and another for the last day */
private const val TRIAL_SOON_MS = 3 * DAY_MS

/** A paid plan that does not renew says so this many days before it ends */
private const val PAID_SOON_MS = 7 * DAY_MS

/** The note for the day the trial ended is for those days only: an account whose trial ended long ago is not told "it ended" now */
private const val TRIAL_OVER_NOTE_MS = 7 * DAY_MS

/** Where the notes remember. Reading or writing may throw; [Moments] never lets that out */
interface MomentMemory {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/** This phone's kept preferences (see [Store.momentGet]) */
class StoreMemory(private val store: Store) : MomentMemory {
    override fun get(key: String) = store.momentGet(key)
    override fun put(key: String, value: String) = store.momentPut(key, value)
}

/** The cards at the top of the home screen. At most one shows at a time, in the order of this list's priority (see [Moments.homeCard]) */
enum class CardKind { T1, T2, T3, P1, P2, P4 }

/** [period] is what makes it a new card: the end of the trial, the end of the paid plan */
data class Card(val kind: CardKind, val period: String) {
    val isTrial: Boolean get() = kind == CardKind.T1 || kind == CardKind.T2 || kind == CardKind.T3
}

/** Where the free trial of the live connection stands */
enum class TrialStage { NONE, RUNNING, T1, T2, OVER }

/** The one quiet line under the plan in settings */
enum class LineKind { T0, P1, P1B, P3, P5 }
data class Line(val kind: LineKind, val days: Int? = null, val until: Long? = null)

/** How close today's items are to the limit */
enum class Tier { NONE, BASE, WARN }

/** A line beside what was just sent: items today close to the limit, or the space close to full */
enum class NearKind { ITEMS, ITEMS_WARN, SPACE }
data class Near(val kind: NearKind, val period: String)

/** A [Near] with the numbers it says, as they were when it was chosen (so the line does not change under the reader's eyes) */
data class NearView(val near: Near, val today: Int, val perDay: Int, val usedBytes: Long, val limitBytes: Long)

/** What the account has had so far, for "what you have already had". A part that is not known or is nothing is null and is left out of the sentence */
data class Enjoyed(val days: Int?, val items: Int?, val devices: Int?) {
    val any: Boolean get() = days != null || items != null || devices != null
}

/** Prices as the service lists them, written for people: null when the service did not say (or the account can't have that price) */
data class Prices(val trial: String?, val trialListed: String?, val year: String?, val once: String?)

fun utcDay(ms: Long): Long = ms / DAY_MS

fun nextUtcMidnight(now: Long): Long = (utcDay(now) + 1) * DAY_MS

/** Whole days left, counting a started day as a day (a trial with 30 hours left has 2 days) */
fun daysLeft(ms: Long): Int = ((ms + DAY_MS - 1) / DAY_MS).toInt()

fun trialStage(me: Me, now: Long): TrialStage {
    if (me.paid) return TrialStage.NONE
    val until = me.realtimeUntil ?: return TrialStage.NONE
    val left = until - now
    return when {
        left <= 0 -> TrialStage.OVER
        left <= DAY_MS -> TrialStage.T2
        left <= TRIAL_SOON_MS -> TrialStage.T1
        else -> TrialStage.RUNNING
    }
}

/**
 * How many days the trial lasts, from the first that says: the account's answer, the plan list's answer ([fromPlans]: null when the
 * list was never answered, so a number the app itself starts from is not passed off as the service's), the gap between the account's
 * start and the trial's end. Null: say no number.
 */
fun trialDays(me: Me, fromPlans: Int?): Int? {
    me.trialDays?.takeIf { it > 0 }?.let { return it }
    fromPlans?.takeIf { it > 0 }?.let { return it }
    val from = me.createdAt ?: return null
    val to = me.realtimeUntil ?: return null
    return ((to - from + DAY_MS / 2) / DAY_MS).toInt().takeIf { it > 0 }
}

/**
 * What the account has already had. [days]: the days since it was made, counting the first (never more than the trial's length once
 * that is over); when the start is not known, only an ended trial has a number (its length). Items: what the account has sent, when
 * that is more than nothing. Devices: when more than this one.
 */
fun enjoyed(me: Me, now: Long, trialDays: Int?, ended: Boolean): Enjoyed {
    val used = me.createdAt?.let { ((now - it) / DAY_MS + 1).toInt().coerceAtLeast(1) }
    val days = when {
        used != null -> if (trialDays != null) minOf(used, trialDays) else used
        ended -> trialDays
        else -> null
    }
    return Enjoyed(days, me.itemsTotal?.takeIf { it > 0 }, me.devicesUsed.takeIf { it > 1 })
}

/** How many of today's items were sent, as far as it can be told: what [me] said if that was today, plus what was sent since */
fun todayCount(me: Me, meAt: Long, now: Long, sentSince: Int = 0): Int? {
    val said = me.itemsToday ?: return null
    val base = if (meAt > 0 && utcDay(meAt) != utcDay(now)) 0 else said
    return base + sentSince.coerceAtLeast(0)
}

/** From 80 % of the day's items on the line is said once; from one before the last it says what Paid gives; when they are used up it is a limit met, not a preview */
fun itemsTier(me: Me, meAt: Long, now: Long, sentSince: Int = 0): Tier {
    if (me.paid) return Tier.NONE
    val per = me.itemsPerDay?.takeIf { it > 0 } ?: return Tier.NONE
    val today = todayCount(me, meAt, now, sentSince) ?: return Tier.NONE
    return when {
        today >= per -> Tier.NONE
        today >= per - 1 -> Tier.WARN
        today >= (per * 4 + 4) / 5 -> Tier.BASE
        else -> Tier.NONE
    }
}

/** The day's items are used up or one from it: the pill may move */
fun itemsPressing(me: Me, meAt: Long, now: Long, sentSince: Int = 0): Boolean {
    if (me.paid) return false
    val per = me.itemsPerDay?.takeIf { it > 0 } ?: return false
    val today = todayCount(me, meAt, now, sentSince) ?: return false
    return today >= per - 1
}

/** 80 % of the space used and not yet full ([extraBytes]: what was sent since [me] was asked) */
fun spaceNear(me: Me, extraBytes: Long = 0): Boolean {
    if (me.paid || me.bytesLimit <= 0) return false
    val used = me.bytesUsed + extraBytes.coerceAtLeast(0)
    return used * 5 >= me.bytesLimit * 4 && used < me.bytesLimit
}

/** The paid plan ends within a week and does not renew by itself */
fun paidEndingSoon(me: Me, now: Long): Boolean {
    if (!me.paid || me.renews == true || me.paidKind == "life") return false
    val until = me.paidUntil ?: return false
    return until - now in 1..PAID_SOON_MS
}

/** The prices the notes may name: what is listed (the first month's only for an account that can still have it). Not open for sale: nothing */
fun pricesOf(catalog: Catalog?, trialAvailable: Boolean, open: Boolean): Prices {
    if (!open || catalog == null) return Prices(null, null, null, null)
    val year = catalog.offer("year")
    val once = catalog.offer("year_once")
    val listed = if (year?.trialCents != null) Plans.money(year.trialCents!!, year.currency) else null
    return Prices(
        trial = listed.takeIf { trialAvailable },
        trialListed = listed,
        year = year?.let { Plans.money(it.cents, it.currency) },
        once = once?.let { Plans.money(it.cents, it.currency) },
    )
}

class Moments(private val memory: MomentMemory) {
    private fun key(id: String, period: String) = "dropit.moment.$id.$period"
    private fun stateKey(name: String) = "dropit.moment.state.$name"

    /** Said or waved away already — and also when that can't be told: then nothing is shown */
    fun seen(id: String, period: String): Boolean = try { memory.get(key(id, period)) != null } catch (e: Throwable) { true }

    fun mark(id: String, period: String) { try { memory.put(key(id, period), "1") } catch (e: Throwable) { } }

    private fun state(name: String): String? = try { memory.get(stateKey(name))?.takeIf { it.isNotEmpty() } } catch (e: Throwable) { null }
    private fun setState(name: String, value: String) { try { memory.put(stateKey(name), value) } catch (e: Throwable) { } }

    /**
     * Take note of what the account is now. Called with every answer about the account, before the cards are chosen: a plan that
     * changed from Free to Paid since the last answer is what makes the "you now have" card ([CardKind.P4]).
     */
    fun observe(me: Me, now: Long) {
        val last = state("plan")
        if (me.paid) {
            if (last == "free") {
                setState("p4", paidPeriod(me))
                setState("p4trial", state("trial") ?: "")
            }
            setState("everpaid", paidPeriod(me))
        } else {
            setState("trial", me.trialAvailable.toString())
        }
        setState("plan", if (me.paid) "paid" else "free")
    }

    /** A limit was met just now (a send that a paid plan would have let through): the pill may move for a day */
    fun markWall(now: Long) = setState("wall", now.toString())

    fun wallRecently(now: Long): Boolean = state("wall")?.toLongOrNull()?.let { now - it in 0..DAY_MS } == true

    /** Whether the account has ever been on a paid plan, as far as this phone knows: it saw one, or the service still gives an end date */
    fun everPaid(me: Me): Boolean = state("everpaid") != null || (me.paidUntil ?: 0) > 0

    /** The first month was on offer when the account last looked free: what the "you now have" card can say about the price */
    fun firstMonthWasOffered(): Boolean = state("p4trial") == "true"

    private fun paidPeriod(me: Me) = me.paidUntil?.toString() ?: "life"

    /** Paid ended a moment ago and the account is still inside the days its terms are kept */
    fun justEnded(me: Me, now: Long): Boolean {
        val grace = me.paidGraceUntil ?: return false
        return !me.paid && everPaid(me) && grace > now && (me.paidUntil ?: 0L) <= now
    }

    /**
     * The card for the top of the home screen, if any: a paid plan just started or is about to end, or just ended; else the trial
     * ending or ended. [wallShowing]: a limit was just met and says so on screen, which comes before everything.
     */
    fun homeCard(me: Me, now: Long, wallShowing: Boolean = false): Card? {
        if (wallShowing) return null
        val cards = buildList {
            if (me.paid) {
                state("p4")?.let { add(Card(CardKind.P4, it)) }
                if (paidEndingSoon(me, now)) add(Card(CardKind.P1, me.paidUntil.toString()))
            } else {
                if (justEnded(me, now)) add(Card(CardKind.P2, me.paidUntil?.toString() ?: state("everpaid") ?: "0"))
                val until = me.realtimeUntil
                if (until != null) when (trialStage(me, now)) {
                    TrialStage.OVER -> if (now - until <= TRIAL_OVER_NOTE_MS) add(Card(CardKind.T3, until.toString()))
                    TrialStage.T2 -> add(Card(CardKind.T2, "$until.t2"))
                    TrialStage.T1 -> add(Card(CardKind.T1, until.toString()))
                    else -> {}
                }
            }
        }
        return cards.firstOrNull { !seen(it.kind.name, it.period) }
    }

    /** Waved away: it does not come back in this period */
    fun dismiss(card: Card) {
        mark(card.kind.name, card.period)
        if (card.kind == CardKind.P4) setState("p4", "")
    }

    /** The line under the plan in settings: the trial's days, the paid plan's end or renewal, what is left of a paid plan that ended. Always there, never asks for anything */
    fun settingsLine(me: Me, now: Long): Line? {
        if (me.paid) {
            if (paidEndingSoon(me, now)) return Line(LineKind.P1, until = me.paidUntil)
            if (me.renews == true) return Line(LineKind.P1B)
            return null
        }
        val grace = me.paidGraceUntil
        if (grace != null && grace > now && everPaid(me)) return Line(LineKind.P3, until = grace)
        if (grace == null && everPaid(me)) return Line(LineKind.P5)
        val until = me.realtimeUntil
        return when (trialStage(me, now)) {
            TrialStage.RUNNING, TrialStage.T1, TrialStage.T2 -> Line(LineKind.T0, days = daysLeft(until!! - now))
            else -> null
        }
    }

    /**
     * The line to put beside the result of a send, if the day's items or the space are close. Each says itself once a UTC day (and the
     * one that says what Paid gives, once a day more); mark it with [markNear] when it is shown. [sentSince] / [sentBytes]: what was sent
     * since [me] was asked, so the line is right without asking again after every send.
     */
    fun nearLine(me: Me, meAt: Long, now: Long, sentSince: Int = 0, sentBytes: Long = 0): Near? {
        if (me.paid) return null
        val day = utcDay(now)
        when (itemsTier(me, meAt, now, sentSince)) {
            Tier.WARN -> if (!seen("N1", "$day.b")) return Near(NearKind.ITEMS_WARN, "$day.b")
            Tier.BASE -> if (!seen("N1", "$day.a")) return Near(NearKind.ITEMS, "$day.a")
            Tier.NONE -> {}
        }
        if (spaceNear(me, sentBytes) && !seen("N2", "$day.sp")) return Near(NearKind.SPACE, "$day.sp")
        return null
    }

    /** [nearLine], and marked as said: for the moment it is put on screen */
    fun takeNear(me: Me, meAt: Long, now: Long, sentSince: Int = 0, sentBytes: Long = 0): NearView? {
        val n = nearLine(me, meAt, now, sentSince, sentBytes) ?: return null
        markNear(n)
        return NearView(n, todayCount(me, meAt, now, sentSince) ?: 0, me.itemsPerDay ?: 0, me.bytesUsed + sentBytes.coerceAtLeast(0), me.bytesLimit)
    }

    fun markNear(n: Near) {
        val day = n.period.substringBefore('.')
        when (n.kind) {
            NearKind.ITEMS_WARN -> { mark("N1", "$day.b"); mark("N1", "$day.a") }
            NearKind.ITEMS -> mark("N1", "$day.a")
            NearKind.SPACE -> mark("N2", n.period)
        }
    }

    /**
     * Whether the pill at the top may move: only under pressure — the trial's card is up, the day's items are one from used up, or a
     * limit was met in the last day. [card]: what [homeCard] chose.
     */
    fun pressure(me: Me, meAt: Long, now: Long, card: Card?, sentSince: Int = 0): Boolean =
        !me.paid && ((card?.isTrial == true) || itemsPressing(me, meAt, now, sentSince) || wallRecently(now))

    /** The first expired item says once what a day or two of closed phone costs: asked for per account */
    fun keptNoteDue(period: String): Boolean = !seen("W6", period)
    fun markKeptNote(period: String) = mark("W6", period)
}
