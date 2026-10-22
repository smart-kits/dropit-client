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
        // Today's uploads and downloads have their own daily limits and say so (today's sends have no field)
        code == "RATE_LIMITED" && window == "day" -> {
            val back = clock(data.long("retry_after") ?: 0, nowMs)
            Message(when (field) { "uploads" -> "rateDayUploads"; "downloads" -> "rateDayDownloads"; else -> "rateDay" }, listOf(back))
        }
        code == "RATE_LIMITED" && window == "hour" -> Message("rateHour")
        // Accounts per network per day: say when it opens again (the service counts by UTC day, shown in the phone's own clock)
        code == "SIGNUP_LIMITED" && data.long("retry_after") != null -> Message("signupLimitedAt", listOf(clock(data.long("retry_after") ?: 0, nowMs), (data.long("limit") ?: 0).toString()))
        // When the service says when the oldest item expires, so does this: waiting has an end
        code == "QUOTA_EXCEEDED" && field == "items" -> data.long("frees_in")?.let { Message("quotaItemsIn", listOf(it.toString())) } ?: Message("quotaItems")
        code == "QUOTA_EXCEEDED" && field == "bytes" -> data.long("frees_in")?.let { Message("quotaBytesIn", listOf(it.toString())) } ?: Message("quotaBytes")
        code == "DEVICE_REVOKED" && data.str("reason") == "replaced" -> Message("revokedReplaced")
        code != null && code in Strings.EN.errors -> Message("errors.$code")
        else -> Message("httpFailed", listOf(status.toString()))
    }
}

/** How long until the oldest item expires and frees room, in words: "under an hour", "about 3 hours", "about 2 days" */
private fun spanEn(sec: Long): String {
    val h = (sec + 3599) / 3600
    return if (sec < 3600) "under an hour" else if (h < 48) "about $h hour${if (h == 1L) "" else "s"}" else "about ${(sec + 86399) / 86400} days"
}

private fun spanZh(sec: Long): String {
    val h = (sec + 3599) / 3600
    return if (sec < 3600) "不到 1 小时" else if (h < 48) "约 $h 小时" else "约 ${(sec + 86399) / 86400} 天"
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
                "TARGET_UNAVAILABLE" to "Those devices are no longer on the account",
                "ITEM_EXPIRED" to "It has expired — dropit no longer keeps it",
            ),
            plain = mapOf(
                "offline" to "Network unavailable",
                "offlineSlow" to "The connection is too slow — no answer came in time. Try again",
                "offlineConnect" to "Could not connect to the service. Check the network and try again",
                "offlineDns" to "Could not find the service. Check the network and try again",
                "rateHour" to "Sending too fast this hour — try again in a little while",
                "quotaItems" to "Too many items waiting — wait for old ones to expire",
                "quotaBytes" to "Storage is full — wait for old items to expire",
                "revokedReplaced" to "dropit was joined again on this phone, so this copy was signed out — join again to use it",
                "noticeLocked" to "dropit · something new",
                "realtimeOver" to "Background-receiving trial ended · Paid keeps it on",
                "realtimeFull" to "Today's real-time trial places are taken — it comes back tomorrow by itself; opening the app still fetches new items",
                "ticketsDay" to "Today's real-time connections are used up — it comes back tomorrow by itself",
            ),
            formats = mapOf(
                "httpFailed" to { a -> "Request failed: HTTP ${a[0]}" },
                "tooLarge" to { a -> "Larger than ${a[0]}, the most one item can be" },
                "rateDay" to { a -> "Today's sends are used up — back at ${a[0]}" },
                "signupLimitedAt" to { a -> "This network has made its ${a[1]} accounts for today. Join an existing account with a pairing code, or try again after ${a[0]}" },
                "rateDayUploads" to { a -> "Today's file uploads are used up — back at ${a[0]}" },
                "rateDayDownloads" to { a -> "Today's downloads are used up — back at ${a[0]}" },
                "quotaItemsIn" to { a -> "Too many items waiting — the oldest expires in ${spanEn(a[0].toLong())} and frees up room" },
                "quotaBytesIn" to { a -> "Storage is full — the oldest item expires in ${spanEn(a[0].toLong())} and frees up room" },
                "batchTooLarge" to { a -> "At most ${a[0]} files at once" },
                "noticeFrom" to { a -> "From ${a[0]}" },
                "noticeFiles" to { a -> "${a[0]} files" },
                "noticeTextAndFiles" to { a -> if (a[0] == "1") "1 file and a text" else "${a[0]} files and a text" },
                "noticeBacklog" to { a -> "${a[0]} new items" },
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
                "TARGET_UNAVAILABLE" to "选的设备已不在这个账号里",
                "ITEM_EXPIRED" to "已过期，dropit 不再保留",
            ),
            plain = mapOf(
                "offline" to "网络不可用",
                "offlineSlow" to "网络太慢，没等到回应。再试一次",
                "offlineConnect" to "连不上服务，请检查网络后再试",
                "offlineDns" to "找不到服务，请检查网络后再试",
                "rateHour" to "这一小时投得太快了，稍后再试",
                "quotaItems" to "积压的内容太多了，等旧内容过期",
                "quotaBytes" to "空间已满，等旧内容过期",
                "revokedReplaced" to "这台手机上重新加入过 dropit，这一份已退出 —— 要用请重新加入",
                "noticeLocked" to "dropit · 新内容",
                "realtimeOver" to "后台接收体验已结束 · 付费版一直有",
                "realtimeFull" to "今天的实时体验名额满了，明天自动恢复；打开 App 仍可收取",
                "ticketsDay" to "今天的实时连接次数用完了，明天自动恢复",
            ),
            formats = mapOf(
                "httpFailed" to { a -> "请求失败 HTTP ${a[0]}" },
                "tooLarge" to { a -> "超过了单条上限 ${a[0]}" },
                "rateDay" to { a -> "今天的投递次数用完了，${a[0]} 恢复" },
                "signupLimitedAt" to { a -> "这个网络今天已经建了 ${a[1]} 个账号，到上限了。可以用配对码加入已有账号，或者 ${a[0]} 以后再试" },
                "rateDayUploads" to { a -> "今天的文件上传次数用完了，${a[0]} 恢复" },
                "rateDayDownloads" to { a -> "今天的下载次数用完了，${a[0]} 恢复" },
                "quotaItemsIn" to { a -> "积压的内容太多了，最早的一条${spanZh(a[0].toLong())}后到期，到时自动腾出位置" },
                "quotaBytesIn" to { a -> "空间已满，最早的一条${spanZh(a[0].toLong())}后到期，到时自动腾出位置" },
                "batchTooLarge" to { a -> "一次最多 ${a[0]} 个文件" },
                "noticeFrom" to { a -> "来自 ${a[0]}" },
                "noticeFiles" to { a -> "${a[0]} 个文件" },
                "noticeTextAndFiles" to { a -> "${a[0]} 个文件和一段文字" },
                "noticeBacklog" to { a -> "收到 ${a[0]} 条" },
            ),
        )

        /** Simplified Chinese when the system language is Chinese, English otherwise */
        fun forLanguage(tag: String?): Strings = if (tag?.lowercase()?.startsWith("zh") == true) ZH else EN
    }
}
