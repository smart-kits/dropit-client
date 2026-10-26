package xyz.smart_kits.dropit.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// Requests are worked out here and only described: whoever sends them (Ktor in the app, a system upload
// session on iOS later) reads the answer back through [parse]. Nothing in this file touches the network.

/** Where in the app an upgrade was started from, said with the checkout request (only counted) */
object UpgradeFrom {
    const val PILL = "pill"
    const val SETTINGS = "settings"
    const val PLANS = "plans"
    const val WALL = "wall"
    const val SHARE = "share"
    const val NOTICE = "notice"
    const val TRIAL_CARD = "trial_card"
    const val DEVICES = "devices"

    /** The limits a plan can lift; anything else is left out of the request */
    val WHYS = setOf("per_day", "queue", "space", "file_size", "devices", "realtime", "uploads", "downloads", "kept", "account")

    fun why(w: String?): String? = w?.takeIf { it in WHYS }
}

const val DEFAULT_ENDPOINT = "https://dropit.smart-kits.xyz"
const val PRIVACY_URL = "$DEFAULT_ENDPOINT/privacy"

/** The page that lists every client, the one for this device recommended: where a new device gets dropit */
const val INSTALL_URL = "$DEFAULT_ENDPOINT/#install"
const val SOURCE_URL = "https://github.com/smart-kits/dropit-client"

/** What a pairing QR code holds: the page that opens the right client with the code filled in */
fun pairingLink(code: String, base: String = DEFAULT_ENDPOINT): String = "$base/?pair=$code"

/**
 * The pairing code in what a camera read, or null when it is not dropit's: only a 6-character code by itself,
 * or a link to dropit's own address with `pair=`. Nothing else is ever opened or acted on.
 */
fun pairingCodeIn(scanned: String, hosts: List<String> = listOf(DEFAULT_ENDPOINT)): String? {
    val s = scanned.trim()
    fun code(c: String) = c.uppercase().takeIf { it.length == 6 && it.all { ch -> ch in '0'..'9' || ch in 'A'..'Z' } }
    code(s)?.let { return it }
    val host = hosts.firstOrNull { h -> s.startsWith("$h/?") || s.startsWith("$h?") } ?: return null
    val query = s.removePrefix(host).substringAfter('?').substringBefore('#')
    return query.split('&').firstOrNull { it.startsWith("pair=") }?.let { code(it.removePrefix("pair=")) }
}
const val SOURCE = "android"

internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

/** One request, described. `body` is JSON; a file's bytes are attached by whoever sends it. */
data class RequestSpec(
    val method: String,
    val path: String,
    val query: List<Pair<String, String>> = emptyList(),
    val body: JsonObject? = null,
    val auth: Boolean = true,
    val contentType: String? = if (body != null) "application/json" else null,
) {
    /** Path with the query, percent-encoded */
    val target: String
        get() = if (query.isEmpty()) path else path + "?" + query.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

    /**
     * Safe to send twice: reads, and sends and sends-again (the service recognizes a repeat within a minute), and asking for a checkout (a second
     * one is only another unpaid draft that nobody opens).
     * Never joining or creating an account — except when no connection was ever made (see DropitClient.attempt).
     */
    val retriable: Boolean get() = method == "GET" || path.startsWith("/v1/ingest") || path == "/v1/checkout" || path.endsWith("/resend")
}

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
internal fun encode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = (b.toInt() and 0xFF)
        if (c < 128 && UNRESERVED.indexOf(c.toChar()) >= 0) append(c.toChar())
        else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
    }
}

object Requests {
    fun me() = RequestSpec("GET", "/v1/me")
    fun endpoints() = RequestSpec("GET", "/v1/endpoints", auth = false)

    /** A tiny static file on the service's own address: asked for only to have a connection open by the time something is sent */
    fun warm() = RequestSpec("GET", "/ping.txt", auth = false)

    /** Text or a link. `meta` must already fit ([fitMeta]). */
    fun ingestText(kind: String, raw: String, meta: Meta?, clientTs: Long, to: List<String>? = null) = RequestSpec(
        "POST", "/v1/ingest",
        body = buildJsonObject {
            put("kind", kind)
            put("raw", raw)
            put("source", SOURCE)
            put("client_ts", clientTs)
            if (meta != null && !meta.isEmpty) put("meta", json.encodeToJsonElement(meta))
            // Only some of the devices: named. Everyone is the absence of `to`, so a device that joins later is included
            if (!to.isNullOrEmpty()) put("to", JsonArray(to.map(::JsonPrimitive)))
        },
    )

    /** One file in one request; its bytes are the body. The service names it by time and type when there is no `name`. */
    fun ingestFile(name: String?, mime: String?, group: Group?, from: From?, to: List<String>? = null) = RequestSpec(
        "POST", "/v1/ingest/file",
        query = buildList {
            add("source" to SOURCE)
            if (!name.isNullOrBlank()) add("name" to name)
            if (from != null) {
                add("from_url" to from.url)
                from.title?.let { add("from_title" to it) }
            }
            if (group != null) {
                add("group" to group.id); add("i" to group.i.toString()); add("n" to group.n.toString())
            }
            if (!to.isNullOrEmpty()) add("to" to to.joinToString(","))
        },
        contentType = mime?.ifBlank { null } ?: "application/octet-stream",
    )

    fun claim(code: String, deviceName: String, deviceKey: String?, previousToken: String?) = RequestSpec(
        "POST", "/v1/pair/claim", auth = false,
        body = buildJsonObject {
            put("code", code)
            put("scope", "full")
            put("device_name", deviceName)
            deviceKey?.let { put("device_key", it) }
            previousToken?.let { put("previous_token", it) }
        },
    )

    /** `pow`: the answer to a challenge the service sent back the first time (see [Pow]) */
    fun createAccount(deviceName: String, deviceKey: String?, pow: JsonObject? = null) = RequestSpec(
        "POST", "/v1/accounts", auth = false,
        body = buildJsonObject {
            put("device_name", deviceName)
            deviceKey?.let { put("device_key", it) }
            pow?.let { put("pow", it) }
        },
    )

    fun revoke(deviceId: String) = RequestSpec("DELETE", "/v1/devices/${encode(deviceId)}")

    /** A pairing code for another device; `replace` picks which device it takes the place of when slots are full */
    fun pair(replace: String? = null) = RequestSpec(
        "POST", "/v1/pair",
        body = buildJsonObject { replace?.let { put("replace", it) } },
    )

    fun devices() = RequestSpec("GET", "/v1/devices")

    /**
     * Send what is already on the service once more, to the named devices (`to` is required here: sending again to everyone is
     * [to] = null → "all"). It is a new item, a new send for the day's count. 410 when the service no longer has it.
     * [group]: the place this item takes in a batch sent again together (the same shape as when sending); without it the
     * new item stands alone.
     */
    fun resend(seq: Long, to: List<String>?, group: Group? = null) = RequestSpec(
        "POST", "/v1/items/$seq/resend",
        body = buildJsonObject {
            if (to.isNullOrEmpty()) put("to", "all") else put("to", JsonArray(to.map(::JsonPrimitive)))
            group?.let {
                put("group", buildJsonObject { put("id", it.id); put("i", it.i); put("n", it.n) })
            }
        },
    )

    /**
     * Move this phone's place in the line back, so the next pull brings things again: to item [toSeq] (0 = the beginning of what the
     * service keeps), or to just before the newest [last] items this device can see — only the service can count those.
     */
    fun resetCursor(toSeq: Long? = null, last: Int? = null) = RequestSpec(
        "POST", "/v1/cursor/reset",
        body = buildJsonObject {
            if (last != null) put("last", last) else put("to_seq", toSeq ?: 0L)
        },
    )

    /** What is on sale and for how much, and whether it can be bought at all: no sign-in needed. Nothing about money is written in the app */
    fun plans() = RequestSpec("GET", "/v1/plans", auth = false)

    /**
     * Where to pay for `plan` (year, year_once or life): the answer holds `checkout_url`, opened in the browser. [from] is where in the
     * app the person pressed (one of [UpgradeFrom]) and [why] the limit that was just met, if that is what brought them: both are only
     * counted, they change nothing about what is offered
     */
    fun checkout(plan: String, from: String? = null, why: String? = null) = RequestSpec(
        "POST", "/v1/checkout",
        body = buildJsonObject {
            put("plan", plan)
            if (!from.isNullOrBlank()) put("from", from)
            if (!why.isNullOrBlank()) put("why", why)
        },
    )

    /** The page where a paid account manages its subscription (payments, invoices, cancelling): the answer holds `url` */
    fun billing() = RequestSpec("POST", "/v1/billing")

    /** `why`: what set the pull off — `open`, `push`, `reconnect`, `manual`, `pair` (the service counts pulls by it) */
    fun pull(after: Long, limit: Int = PULL_LIMIT, why: String? = null) = RequestSpec(
        "GET", "/v1/pull",
        query = buildList {
            add("after" to after.toString()); add("limit" to limit.toString())
            why?.let { add("why" to it) }
        },
    )

    /**
     * One item again, for a fresh download address (the one it came with works for five minutes): the pull after the one before it.
     * It never moves the phone's place in the list, and the item is simply missing when the service no longer has it (expired).
     */
    fun itemAgain(seq: Long) = pull(seq - 1, limit = 1, why = "manual")

    /** This phone's address at the system push service; a blank `token` takes it off again */
    fun registerPush(token: String?) = RequestSpec(
        "PUT", "/v1/devices/self/push",
        body = buildJsonObject {
            if (!token.isNullOrBlank()) put("kind", "fcm")
            put("token", token.orEmpty())
        },
    )

    /** Ask the service to push one test signal to this phone: whether it arrives decides how the phone receives */
    fun testPush() = RequestSpec("POST", "/v1/devices/self/push/test")

    /** A one-minute, single-use ticket for the live connection */
    fun wsTicket() = RequestSpec("POST", "/v1/ws/ticket")

    /** Where the live connection opens, given a ticket. The connection says why it is closing (`can=end`). */
    fun wsTarget(ticket: String): String = "/v1/ws?ticket=${encode(ticket)}&can=end"
}

/** What went wrong, in the terms a person acts on */
enum class Failure {
    /** The key no longer works: forget it, keep it as `previous_token`, ask to join again */
    AUTH_LOST,
    /** Too much: a file over the limit, sending too fast, storage full */
    LIMIT,
    /** The service is busy or down: keep the item, offer to retry */
    UNAVAILABLE,
    /** No answer at all */
    OFFLINE,
    OTHER,
}

class ApiError(val status: Int, val code: String?, val data: JsonObject) : Exception("HTTP $status ${code.orEmpty()}") {
    val failure: Failure
        get() = when {
            code == "INVALID_TOKEN" || code == "DEVICE_REVOKED" -> Failure.AUTH_LOST
            status == 413 || status == 429 || status == 507 -> Failure.LIMIT
            status == 503 || status >= 500 -> Failure.UNAVAILABLE
            else -> Failure.OTHER
        }

    fun sentence(nowMs: Long? = null): Message =
        if (nowMs == null) errorMessage(code, data, status) else errorMessage(code, data, status, nowMs)
}

class OfflineError(private val why: Throwable?) : Exception("offline", why) {
    /** Why, as far as it is known: no answer in time, no connection made, a name that would not resolve — or just "unavailable" */
    val sentence: Message get() = Message(
        when {
            why is AttemptTimeout -> "offlineSlow"
            why is io.ktor.client.network.sockets.ConnectTimeoutException -> "offlineConnect"
            why != null && why::class.simpleName == "UnknownHostException" -> "offlineDns"
            else -> "offline"
        },
    )
}

/** An answer that worked. `deduped`: the same thing was sent a moment ago — still a success. */
data class Answer(val data: JsonObject, val deduped: Boolean = false) {
    val seq: Long? get() = data["seq"]?.jsonPrimitive?.longOrNull
    fun str(k: String): String? = data[k]?.jsonPrimitive?.contentOrNull
}

/** Read an answer: 2xx and 409 DEDUPED are successes; everything else is an [ApiError]. */
fun parse(status: Int, body: String): Answer {
    val data = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { JsonObject(emptyMap()) }
    val code = data["error"]?.jsonPrimitive?.contentOrNull
    if (status in 200..299) return Answer(data)
    if (code == "DEDUPED") return Answer(data, deduped = true)
    throw ApiError(status, code, data)
}
