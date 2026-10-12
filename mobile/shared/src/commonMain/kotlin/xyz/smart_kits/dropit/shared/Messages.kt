package xyz.smart_kits.dropit.shared

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock
import kotlin.time.Instant

/** A sentence to show, by key, with its arguments already worked out. Rendered by [Strings]. */
data class Message(val key: String, val args: List<String> = emptyList()) {
    override fun toString() = if (args.isEmpty()) key else "$key(${args.joinToString(",")})"
}

private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
private fun JsonObject.long(k: String) = this[k]?.jsonPrimitive?.longOrNull

/** A local "HH:MM" `seconds` from `nowMs` */
fun clock(seconds: Long, nowMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val t = Instant.fromEpochMilliseconds(nowMs + seconds * 1000).toLocalDateTime(zone)
    return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}

/**
 * The sentence for an error answer from the service. Numbers come from the answer, never from here.
 * Same choices as the extension's `errorText`; unknown codes fall back to the HTTP status.
 */
fun errorMessage(code: String?, data: JsonObject, status: Int, nowMs: Long = Clock.System.now().toEpochMilliseconds()): Message {
    val field = data.str("field")
    val limit = data.long("limit")
    val window = data.str("window")
    return when {
        code == "PAYLOAD_TOO_LARGE" && field == "size" && limit != null && limit > 0 -> Message("tooLarge", listOf(mb(limit)))
        code == "RATE_LIMITED" && window == "day" -> Message("rateDay", listOf(clock(data.long("retry_after") ?: 0, nowMs)))
        code == "RATE_LIMITED" && window == "hour" -> Message("rateHour")
        code == "QUOTA_EXCEEDED" && field == "items" -> Message("quotaItems")
        code == "QUOTA_EXCEEDED" && field == "bytes" -> Message("quotaBytes")
        code == "DEVICE_REVOKED" && data.str("reason") == "replaced" -> Message("revokedReplaced")
        code != null && code in Strings.EN.errors -> Message("errors.$code")
        else -> Message("httpFailed", listOf(status.toString()))
    }
}

/** Interface text, English and Simplified Chinese. Keys and wording follow the browser extension. */
class Strings private constructor(
    val errors: Map<String, String>,
    private val plain: Map<String, String>,
    private val formats: Map<String, (List<String>) -> String>,
) {
    fun render(m: Message): String = when {
        m.key.startsWith("errors.") -> errors[m.key.removePrefix("errors.")] ?: m.key
        m.key in formats -> formats.getValue(m.key)(m.args)
        else -> plain[m.key] ?: m.key
    }

    /** Every key this language has — the two languages must have the same set */
    val keys: Set<String> get() = errors.keys.map { "errors.$it" }.toSet() + plain.keys + formats.keys

    companion object {
        val EN = Strings(
            errors = mapOf(
                "INVALID_TOKEN" to "Invalid token — pair again",
                "DEVICE_REVOKED" to "This device was removed — pair again",
                "SCOPE_INSUFFICIENT" to "This token can only send",
                "DEVICE_LIMIT_REACHED" to "Device limit reached — remove a device first",
                "PAIRING_CODE_INVALID" to "Invalid pairing code",
                "PAIRING_CODE_EXPIRED" to "Pairing code expired — generate a new one",
                "RATE_LIMITED" to "Sending too fast",
                "SIGNUP_LIMITED" to "Too many accounts were created on this network today. Join an existing account with a pairing code, or try again tomorrow",
                "PAIRING_LIMITED" to "Too many wrong pairing codes from this network today. Check the code on the other device and try again tomorrow",
                "TURNSTILE_REQUIRED" to "Quite a few accounts were created on this network today. Create this one in the browser at dropit.smart-kits.xyz, then join it here with a pairing code",
                "QUOTA_EXCEEDED" to "Storage is full — wait for old items to expire",
                "PAYLOAD_TOO_LARGE" to "Too large to send",
                "GLOBAL_CIRCUIT_OPEN" to "dropit is not taking new items right now — try again later",
                "BLOB_UNAVAILABLE" to "Files can't be sent right now — try again later",
            ),
            plain = mapOf(
                "offline" to "Network unavailable",
                "rateHour" to "Sending too fast — try again within the hour",
                "quotaItems" to "Too many items waiting — wait for old ones to expire",
                "quotaBytes" to "Storage is full — wait for old items to expire",
                "revokedReplaced" to "dropit was joined again on this phone, so this copy was signed out — join again to use it",
            ),
            formats = mapOf(
                "httpFailed" to { a -> "Request failed: HTTP ${a[0]}" },
                "tooLarge" to { a -> "Larger than ${a[0]}, the most one item can be" },
                "rateDay" to { a -> "Today's sends are used up — back at ${a[0]}" },
                "batchTooLarge" to { a -> "At most ${a[0]} files at once" },
            ),
        )

        val ZH = Strings(
            errors = mapOf(
                "INVALID_TOKEN" to "token 无效，请重新配对",
                "DEVICE_REVOKED" to "设备已被移除，请重新配对",
                "SCOPE_INSUFFICIENT" to "这个 token 只能投递",
                "DEVICE_LIMIT_REACHED" to "设备数已达上限，先移除一台",
                "PAIRING_CODE_INVALID" to "配对码无效",
                "PAIRING_CODE_EXPIRED" to "配对码已过期，请重新生成",
                "RATE_LIMITED" to "投递过于频繁",
                "SIGNUP_LIMITED" to "这个网络今天建的账号太多了。可以用配对码加入已有账号，或者明天再试",
                "PAIRING_LIMITED" to "这个网络今天输错配对码的次数太多了。核对一下另一台设备上的码，明天再试",
                "TURNSTILE_REQUIRED" to "这个网络今天建的账号较多。请在浏览器里打开 dropit.smart-kits.xyz 建号，再在这里用配对码加入",
                "QUOTA_EXCEEDED" to "空间已满，等旧内容过期",
                "PAYLOAD_TOO_LARGE" to "内容太大，投不了",
                "GLOBAL_CIRCUIT_OPEN" to "dropit 暂时不收新内容，稍后再试",
                "BLOB_UNAVAILABLE" to "文件暂时投不了，稍后再试",
            ),
            plain = mapOf(
                "offline" to "网络不可用",
                "rateHour" to "投得太快了，这一小时内再试",
                "quotaItems" to "积压的内容太多了，等旧内容过期",
                "quotaBytes" to "空间已满，等旧内容过期",
                "revokedReplaced" to "这台手机上重新加入过 dropit，这一份已退出 —— 要用请重新加入",
            ),
            formats = mapOf(
                "httpFailed" to { a -> "请求失败 HTTP ${a[0]}" },
                "tooLarge" to { a -> "超过了单条上限 ${a[0]}" },
                "rateDay" to { a -> "今天的投递次数用完了，${a[0]} 恢复" },
                "batchTooLarge" to { a -> "一次最多 ${a[0]} 个文件" },
            ),
        )

        /** Simplified Chinese when the system language is Chinese, English otherwise */
        fun forLanguage(tag: String?): Strings = if (tag?.lowercase()?.startsWith("zh") == true) ZH else EN
    }
}
