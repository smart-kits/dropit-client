package xyz.smart_kits.dropit.shared

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// Choosing who gets what is sent. Every rule is here, as plain functions: which devices can be chosen, what this phone sends to when
// nothing is chosen, what stays of a saved choice when devices come and go, the picking panel (at least one stays ticked), how a choice
// is said in a few words, and how far a send has got. The screens only draw what these return, and the service has the last word on
// who is valid.

/** A device on the account, as the service lists it */
data class Peer(
    val id: String,
    val name: String,
    /** `full`: it can receive. `ingest_only`: it can only send (a shortcut, a bookmark) and is never a target */
    val scope: String = "full",
    val lastSeenAt: Long? = null,
    /** The last item it has taken in: what an item's "delivered" is read from */
    val cursor: Long? = null,
) {
    val canReceive: Boolean get() = scope == "full"
}

/** The devices in the answer to [Requests.devices] */
fun peersFrom(answer: Answer): List<Peer> = peersFrom(answer.data)

fun peersFrom(data: JsonObject): List<Peer> = (data["devices"] as? JsonArray).orEmpty().mapNotNull { el ->
    val o = el as? JsonObject ?: return@mapNotNull null
    val id = o["device_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    Peer(
        id = id,
        name = o["name"]?.jsonPrimitive?.contentOrNull ?: "?",
        scope = o["scope"]?.jsonPrimitive?.contentOrNull ?: "full",
        lastSeenAt = o["last_seen_at"]?.jsonPrimitive?.longOrNull,
        cursor = o["cursor"]?.jsonPrimitive?.longOrNull,
    )
}

/** The devices something can be sent to: the ones that can receive, other than this one, in the order the service gives them */
fun receiversOf(peers: List<Peer>, selfId: String?): List<Peer> = peers.filter { it.canReceive && it.id != selfId }

// ───────────────────────────── what a send goes to by default ─────────────────────────────

/** What a saved default came to once checked against the devices that are there now */
data class Reconciled(
    /** The default to keep: empty means everyone */
    val ids: List<String>,
    /** How many of the saved devices are no longer on the account */
    val removed: Int,
    /** Every saved device was gone, so the default is everyone again */
    val fellBackToAll: Boolean,
)

/**
 * A saved default (device ids, empty = everyone) against the devices there are now. Devices that were removed drop out; if none
 * is left, or what is left is every device there is, it is everyone again (so a device that joins later is included).
 */
fun reconcileDefault(saved: List<String>, receivers: List<Peer>): Reconciled {
    val wanted = saved.distinct()
    if (wanted.isEmpty()) return Reconciled(emptyList(), 0, false)
    val there = receivers.map { it.id }.toSet()
    val kept = wanted.filter { it in there }
    val removed = wanted.size - kept.size
    return when {
        kept.isEmpty() -> Reconciled(emptyList(), removed, true)
        kept.size == there.size -> Reconciled(emptyList(), removed, false)
        else -> Reconciled(kept, removed, false)
    }
}

/** What a send is addressed to. Empty [ids] is everyone: the request carries no `to`. [names] are for saying it afterwards. */
data class Targets(val ids: List<String> = emptyList(), val names: List<String> = emptyList()) {
    val isAll: Boolean get() = ids.isEmpty()
    /** The `to` of a request: null for everyone */
    val to: List<String>? get() = ids.takeIf { it.isNotEmpty() }

    companion object {
        val ALL = Targets()

        /** The ids with their names, from the devices that are known (an id no longer there keeps no name) */
        fun of(ids: List<String>, peers: List<Peer>): Targets {
            val byId = peers.associateBy { it.id }
            val known = ids.filter { it in byId }
            return Targets(known, known.map { byId.getValue(it).name })
        }
    }
}

// ───────────────────────────── the panel ─────────────────────────────

/** Which devices are ticked in the panel: [all] are the choices, in order; a tick is only ever for one of them */
data class Pick(val all: List<String>, val ticked: Set<String>) {
    val count: Int get() = ticked.size
    val isAll: Boolean get() = all.isNotEmpty() && ticked.size == all.size

    /** Ticks or unticks one. With [keepOne] the last tick cannot be taken off (the result says it was refused): sending to nobody is not a choice */
    fun toggled(id: String, keepOne: Boolean): Toggled {
        if (id !in all) return Toggled(this, false)
        if (id !in ticked) return Toggled(copy(ticked = ticked + id), false)
        if (keepOne && ticked.size <= 1) return Toggled(this, true)
        return Toggled(copy(ticked = ticked - id), false)
    }

    fun everyone(): Pick = copy(ticked = all.toSet())

    /** The `to` of the request: null when everyone is ticked (then a device that joins later is included too) */
    fun to(): List<String>? = if (isAll) null else all.filter { it in ticked }

    companion object {
        /** The panel for sending: the default is ticked (everyone when there is none, or when none of it is there any more) */
        fun forSending(receivers: List<Peer>, default: List<String>): Pick {
            val all = receivers.map { it.id }
            val kept = default.filter { it in all }
            return Pick(all, if (kept.isEmpty()) all.toSet() else kept.toSet())
        }

        /** The panel for sending again: nothing is ticked */
        fun forResending(receivers: List<Peer>): Pick = Pick(receivers.map { it.id }, emptySet())
    }
}

data class Toggled(val pick: Pick, val refused: Boolean)

// ───────────────────────────── saying who ─────────────────────────────

/** A choice in a few words: everyone, one or two names, or how many. The screens put the right words around it. */
sealed interface TargetWords {
    data object All : TargetWords
    data class Names(val text: String) : TargetWords
    data class Count(val n: Int) : TargetWords
}

/** The longest a single name is written on a row, in wide characters (a Chinese character counts 1, a Latin letter half) */
const val NAME_MAX = 8

/** Two names are written out only when together they are no longer than this many characters */
const val TWO_NAMES_MAX = 14

private fun width(c: Char): Double = if (c.code >= 0x2E80) 1.0 else 0.5

/** [name] cut to [NAME_MAX] wide characters, with "…" when something was cut */
fun clipName(name: String, max: Int = NAME_MAX): String {
    var used = 0.0
    for ((i, c) in name.withIndex()) {
        used += width(c)
        if (used > max) return name.substring(0, i).trimEnd() + "…"
    }
    return name
}

/** Everyone → [TargetWords.All]; one device → its name; two → both names if they are short, else "2"; more → how many. Never a list of names. */
fun shortTarget(names: List<String>, separator: String = "、"): TargetWords = when (names.size) {
    0 -> TargetWords.All
    1 -> TargetWords.Names(clipName(names[0]))
    2 -> (names[0] + separator + names[1]).let { if (it.length <= TWO_NAMES_MAX) TargetWords.Names(it) else TargetWords.Count(2) }
    else -> TargetWords.Count(names.size)
}

/**
 * What a send was addressed to, in words, from its ids and the names kept with them. Only when there is a name for every id are names
 * said: with fewer names than ids (some of the devices were not known when it was held) it is the number that is said, never "everyone"
 * and never one name for what went to two devices. No ids is everyone.
 */
fun targetWordsOf(ids: List<String>, names: List<String>, separator: String = "、"): TargetWords = when {
    ids.isEmpty() -> TargetWords.All
    names.size != ids.size -> TargetWords.Count(ids.size)
    else -> shortTarget(names, separator)
}

/** How long ago a device was last on, in the steps the screens say */
sealed interface Seen {
    data object Unknown : Seen
    data object Now : Seen
    data class Minutes(val n: Int) : Seen
    data class Hours(val n: Int) : Seen
    data object Yesterday : Seen
    data class Days(val n: Int) : Seen
    /** More than a week: it may not come back */
    data object LongAgo : Seen
}

fun seenOf(lastSeenAt: Long?, nowMs: Long): Seen {
    if (lastSeenAt == null) return Seen.Unknown
    val min = ((nowMs - lastSeenAt) / 60_000).coerceAtLeast(0)
    return when {
        min < 2 -> Seen.Now
        min < 60 -> Seen.Minutes(min.toInt())
        min < 24 * 60 -> Seen.Hours((min / 60).toInt())
        min < 48 * 60 -> Seen.Yesterday
        min < 7 * 24 * 60 -> Seen.Days((min / (24 * 60)).toInt())
        else -> Seen.LongAgo
    }
}

// ───────────────────────────── how far it got, and sending again ─────────────────────────────

/** How many of the devices an item was meant for have taken it in */
data class Delivery(val got: Int, val total: Int) {
    val complete: Boolean get() = got >= total

    /** ●●○: one mark for each device; none when there are too many to draw */
    fun dots(): String? = if (total in 1..MAX_DOTS) "●".repeat(got.coerceIn(0, total)) + "○".repeat((total - got).coerceIn(0, total)) else null

    companion object { const val MAX_DOTS = 8 }
}

/**
 * How far a letter this phone sent has got, from each device's own place in the line: a device has it once that place is at or past the
 * letter's last number. Null when it cannot be said: not sent (or failed), the devices are not known, or nobody was meant to get it.
 */
fun deliveryOf(letter: Letter, peers: List<Peer>?, selfId: String?): Delivery? {
    if (!letter.mine || letter.failure != null || peers == null) return null
    val last = letter.seqs.maxOrNull() ?: return null
    val receivers = receiversOf(peers, selfId)
    val meant = if (letter.toIds.isEmpty()) receivers else receivers.filter { it.id in letter.toIds }
    if (meant.isEmpty()) return null
    return Delivery(meant.count { (it.cursor ?: 0L) >= last }, meant.size)
}

/** Where a device stands for a letter that is being sent again */
enum class RowState {
    /** It has taken it in */
    RECEIVED,
    /** It was meant to get it and has not yet: it will when it is next on */
    PENDING,
    /** The letter was not sent to it */
    NOT_SENT,
}

fun rowStateOf(letter: Letter, peer: Peer): RowState {
    if (letter.toIds.isNotEmpty() && peer.id !in letter.toIds) return RowState.NOT_SENT
    val last = letter.seqs.maxOrNull() ?: return RowState.PENDING
    return if ((peer.cursor ?: 0L) >= last) RowState.RECEIVED else RowState.PENDING
}

/** Whether a letter can be sent again now */
enum class ResendAvail {
    /** It was not sent, or it failed: there is nothing on the service to send again */
    NONE,
    OK,
    /** The service no longer keeps it */
    EXPIRED,
}

fun resendAvail(letter: Letter, nowMs: Long, otherReceivers: Int): ResendAvail = when {
    !letter.mine || letter.failure != null || letter.seqs.isEmpty() || otherReceivers < 1 -> ResendAvail.NONE
    letter.until != null && nowMs >= letter.until -> ResendAvail.EXPIRED
    else -> ResendAvail.OK
}

/** The letter a resend makes: the same words and files, a new place in the list, to the devices chosen, marked as sent again */
fun resentLetter(from: Letter, id: String, seqs: List<Long>, targets: Targets, nowMs: Long, until: Long?): Letter = from.copy(
    id = id, mine = true, at = nowMs, seqs = seqs, toIds = targets.ids, toNames = targets.names, again = true, until = until, failure = null,
)

/** Several small facts about a request's answer: the devices the service says it sent to, when it says */
fun targetsIn(answer: Answer): Targets? {
    val list = answer.data["to"] as? JsonArray ?: return null
    val pairs = list.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = o["device_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        id to (o["name"]?.jsonPrimitive?.contentOrNull ?: "?")
    }
    return if (pairs.isEmpty()) null else Targets(pairs.map { it.first }, pairs.map { it.second })
}
