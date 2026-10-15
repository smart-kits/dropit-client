package xyz.smart_kits.dropit.shared

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// How the phone hears that something arrived while the app is closed: the system's push when it gets through,
// a connection the app keeps open when it doesn't, or not at all. The choice is worked out here; registering,
// connecting and waiting are the app's.

enum class Channel { PUSH, CONNECTION, OFF }

/** How long a test push may take and still count as "push works here". To be settled on real phones. */
const val PUSH_PROBE_WAIT_MS = 10_000L
const val HEARTBEAT_MS = 60_000L

/** What came of asking for a test push */
sealed interface Probe {
    data class Arrived(val afterMs: Long) : Probe
    /** Asked, and nothing came in time */
    data object Silent : Probe
    /** No push here at all: no address from the system, or the service couldn't send */
    data object Unavailable : Probe
    /** The service said no: real-time isn't this account's right now */
    data class Refused(val error: ApiError) : Probe
}

/** @param why shown in settings when receiving is off though the switch is on */
data class Choice(val channel: Channel, val why: Message? = null)

/** Why the service refused real-time, as a sentence; null when the error is about something else */
fun realtimeRefusal(error: ApiError): Message? {
    if (error.code != "WS_REQUIRES_PAID") return null
    return Message(if (error.data["reason"]?.jsonPrimitive?.contentOrNull == "trial_full") "realtimeFull" else "realtimeOver")
}

/** Which way to receive in the background. With the switch off nothing is asked and nothing is registered. */
fun chooseChannel(on: Boolean, probe: Probe?, waitMs: Long = PUSH_PROBE_WAIT_MS): Choice = when {
    !on -> Choice(Channel.OFF)
    probe is Probe.Arrived && probe.afterMs <= waitMs -> Choice(Channel.PUSH)
    probe is Probe.Refused -> realtimeRefusal(probe.error)?.let { Choice(Channel.OFF, it) } ?: Choice(Channel.CONNECTION)
    else -> Choice(Channel.CONNECTION)
}

/**
 * Whether to test push again. On push, a catch-up pull that found things no push announced means push is not
 * getting through any more; and a different network may let it through, or stop it.
 */
fun shouldReprobe(channel: Channel, missedOnCatchUp: Boolean, networkChanged: Boolean): Boolean = when (channel) {
    Channel.PUSH -> missedOnCatchUp || networkChanged
    Channel.CONNECTION -> networkChanged
    Channel.OFF -> false
}

// ── The connection ──────────────────────────────────────────────────────

/** What the service says on the connection */
sealed interface Frame {
    /** Something new: pull */
    data class New(val seq: Long?) : Frame
    /** The service is closing this connection and says why: `trial_full`, `too_many` */
    data class End(val reason: String) : Frame
    data object Pong : Frame
    data object Other : Frame
}

fun frameOf(text: String): Frame {
    if (text == "pong") return Frame.Pong
    val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return Frame.Other
    o["end"]?.jsonPrimitive?.contentOrNull?.let { return Frame.End(it) }
    return if (o["type"]?.jsonPrimitive?.contentOrNull == "new") Frame.New(o["seq"]?.jsonPrimitive?.longOrNull) else Frame.Other
}

private val RECONNECT_MS = longArrayOf(1_000, 2_000, 5_000, 15_000, 30_000, 60_000, 120_000, 300_000)

/** How long to wait before the `attempt`-th try in a row (1 = the first after a drop): longer each time, to a ceiling */
fun reconnectDelayMs(attempt: Int): Long = RECONNECT_MS[(attempt - 1).coerceIn(0, RECONNECT_MS.lastIndex)]

/** What to do when the connection is gone, or a ticket was refused */
sealed interface AfterDrop {
    /** Wait, take a new ticket (they work once), connect again, then pull what was missed */
    data class Reconnect(val delayMs: Long) : AfterDrop
    /** Real-time isn't this account's right now: stop trying, say why */
    data class Stop(val why: Message) : AfterDrop
    /** Too many connections on the account: this one stays away for a good while */
    data class Later(val delayMs: Long) : AfterDrop
}

/**
 * @param attempt how many tries in a row have failed, this one included
 * @param end     the reason the service gave on the connection, when it gave one
 * @param refused the error the ticket request came back with, when it did
 */
fun afterDrop(attempt: Int, end: String? = null, refused: ApiError? = null): AfterDrop {
    refused?.let { e -> realtimeRefusal(e)?.let { return AfterDrop.Stop(it) } }
    return when (end) {
        "trial_full" -> AfterDrop.Stop(Message("realtimeFull"))
        "too_many" -> AfterDrop.Later(RECONNECT_MS.last())
        else -> AfterDrop.Reconnect(reconnectDelayMs(attempt))
    }
}
