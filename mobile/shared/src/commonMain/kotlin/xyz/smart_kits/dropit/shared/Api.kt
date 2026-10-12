package xyz.smart_kits.dropit.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// Requests are worked out here and only described: whoever sends them (Ktor in the app, a system upload
// session on iOS later) reads the answer back through [parse]. Nothing in this file touches the network.

const val DEFAULT_ENDPOINT = "https://dropit.smart-kits.xyz"
const val PRIVACY_URL = "$DEFAULT_ENDPOINT/privacy"
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
     * Safe to send twice: reads, and sends (the service recognizes a repeat within a minute).
     * Never joining or creating an account.
     */
    val retriable: Boolean get() = method == "GET" || path.startsWith("/v1/ingest")
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

    /** Text or a link. `meta` must already fit ([fitMeta]). */
    fun ingestText(kind: String, raw: String, meta: Meta?, clientTs: Long) = RequestSpec(
        "POST", "/v1/ingest",
        body = buildJsonObject {
            put("kind", kind)
            put("raw", raw)
            put("source", SOURCE)
            put("client_ts", clientTs)
            if (meta != null && !meta.isEmpty) put("meta", json.encodeToJsonElement(meta))
        },
    )

    /** One file in one request; its bytes are the body. The service names it by time and type when there is no `name`. */
    fun ingestFile(name: String?, mime: String?, group: Group?, from: From?) = RequestSpec(
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

    fun createAccount(deviceName: String, deviceKey: String?) = RequestSpec(
        "POST", "/v1/accounts", auth = false,
        body = buildJsonObject {
            put("device_name", deviceName)
            deviceKey?.let { put("device_key", it) }
        },
    )

    fun revoke(deviceId: String) = RequestSpec("DELETE", "/v1/devices/${encode(deviceId)}")

    /** A pairing code for another device; `replace` picks which device it takes the place of when slots are full */
    fun pair(replace: String? = null) = RequestSpec(
        "POST", "/v1/pair",
        body = buildJsonObject { replace?.let { put("replace", it) } },
    )

    fun pull(after: Long, limit: Int = 50) = RequestSpec(
        "GET", "/v1/pull", query = listOf("after" to after.toString(), "limit" to limit.toString()),
    )
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

class OfflineError(cause: Throwable?) : Exception("offline", cause) {
    val sentence: Message get() = Message("offline")
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
