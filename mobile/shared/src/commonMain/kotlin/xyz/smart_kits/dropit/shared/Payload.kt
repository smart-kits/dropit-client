package xyz.smart_kits.dropit.shared

import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.random.Random

// What gets sent: link or text, its metadata within budget, and batch markers.
// The browser extension (payload.js) is the reference; test/vectors/payload.json pins both.

const val META_BUDGET = 2000
const val TITLE_MAX = 200               // characters
const val DESCRIPTION_MAX = 300         // characters
const val FROM_URL_MAX = 1024           // bytes; a longer source address is left out, not cut
const val FILES_MAX = 10                // files sent together, the same in every client (text can go with them)
const val GROUP_MAX = 16                // the most one batch can hold (the service refuses more)

@Serializable
data class From(val url: String, val title: String? = null)

@Serializable
data class Group(val id: String, val i: Int, val n: Int)

@Serializable
data class Meta(
    val title: String? = null,
    val description: String? = null,
    val from: From? = null,
    val group: Group? = null,
) {
    val isEmpty get() = title == null && description == null && from == null && group == null

    /** Size of this metadata as JSON, in UTF-8 bytes — what the service counts */
    fun jsonBytes(): Int {
        val fields = buildList {
            title?.let { add(8 + jsonStringBytes(it)) }                     // "title":
            description?.let { add(14 + jsonStringBytes(it)) }              // "description":
            from?.let { f ->
                val inner = buildList {
                    add(6 + jsonStringBytes(f.url))                          // "url":
                    f.title?.let { add(8 + jsonStringBytes(it)) }
                }
                add(7 + 2 + inner.sum() + inner.size - 1)                   // "from":{…}
            }
            group?.let { g ->
                add(8 + 2 + 5 + jsonStringBytes(g.id) + 1 + 4 + g.i.toString().length + 1 + 4 + g.n.toString().length)
            }
        }
        return 2 + fields.sum() + maxOf(fields.size - 1, 0)
    }
}

internal fun isHttp(url: String?): Boolean {
    val u = url?.trim { it <= ' ' } ?: return false
    val scheme = u.substringBefore("://", "").lowercase()
    if (scheme != "http" && scheme != "https") return false
    val host = u.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
    return host.isNotEmpty() && host.none { it == ' ' }
}

/**
 * Whether [url] may be opened as a link: http or https only. What another device calls a link is only its own word for it: before
 * anything is opened the address itself is looked at (`javascript:`, `intent:`, `content:` and the like are not links here).
 * [httpsOnly]: for addresses the service hands back to be opened (a checkout), which are secure or nothing.
 */
fun canOpenLink(url: String?, httpsOnly: Boolean = false): Boolean {
    if (!isHttp(url)) return false
    return !httpsOnly || url!!.trim { it <= ' ' }.startsWith("https://", ignoreCase = true)
}

/** A lone http(s) address is a link; anything else is text */
fun kindOf(text: String?): String {
    val t = text.orEmpty().jsTrim()
    val rest = when {
        t.startsWith("https://", ignoreCase = true) -> t.substring(8)
        t.startsWith("http://", ignoreCase = true) -> t.substring(7)
        else -> return "text"
    }
    return if (rest.isNotEmpty() && rest.none(::isJsSpace)) "url" else "text"
}

/** What a link item sends as its body: the address without surrounding whitespace; text goes as typed */
fun rawFor(kind: String, text: String): String = if (kind == "url") text.jsTrim() else text

/** "This page": its title and the description its author wrote */
fun pageMeta(title: String?, description: String?): Meta {
    val t = clip(squash(title), TITLE_MAX)
    val d = clip(squash(description), DESCRIPTION_MAX)
    return Meta(title = t.ifEmpty { null }, description = d.ifEmpty { null })
}

/** Where a selection or a file came from: the page's address and title, nothing else */
fun fromMeta(url: String?, title: String?): From? {
    if (url == null || !isHttp(url) || utf8Bytes(url) > FROM_URL_MAX) return null
    val t = clip(squash(title), TITLE_MAX)
    return From(url, t.ifEmpty { null })
}

/**
 * Make metadata fit the budget. Metadata never makes a send fail: the description gives way first,
 * then the title, then the source's title, then the source; the batch marker is kept to the end.
 */
fun fitMeta(input: Meta?, limit: Int = META_BUDGET): Meta? {
    if (input == null) return null
    var meta = input.copy(
        title = input.title?.ifEmpty { null },
        description = input.description?.ifEmpty { null },
    )
    meta.from?.let { f ->
        meta = if (!isHttp(f.url) || utf8Bytes(f.url) > FROM_URL_MAX) meta.copy(from = null)
        else meta.copy(from = f.copy(title = f.title?.ifEmpty { null }))
    }
    fun fits() = meta.jsonBytes() <= limit
    // Longest prefix that fits, by binary search over characters; gone if none does
    fun shrink(get: (Meta) -> String?, set: (Meta, String?) -> Meta) {
        val full = get(meta) ?: return
        val chars = codePoints(full)
        var lo = 0
        var hi = chars.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            meta = set(meta, clip(full, mid))
            if (fits()) lo = mid else hi = mid - 1
        }
        meta = set(meta, if (lo > 0) clip(full, lo) else null)
    }
    if (!fits() && meta.description != null) shrink({ it.description }, { m, v -> m.copy(description = v) })
    if (!fits() && meta.title != null) shrink({ it.title }, { m, v -> m.copy(title = v) })
    if (!fits() && meta.from?.title != null) shrink({ it.from?.title }, { m, v -> m.copy(from = m.from?.copy(title = v)) })
    if (!fits()) meta = meta.copy(from = null)
    if (!fits()) meta = Meta(group = meta.group)
    return if (meta.isEmpty) null else meta
}

class BatchTooLarge(val max: Int) : IllegalArgumentException("batch too large")

/**
 * Batch markers for n things sent together, or nulls for one. `i` counts from 1 —
 * the file upload ignores a wrong marker without an error, so a mistake here would only show as split cards.
 */
fun groupsFor(n: Int, id: String = randomGroupId()): List<Group?> {
    if (n > GROUP_MAX) throw BatchTooLarge(GROUP_MAX)
    if (n < 2) return List(maxOf(n, 0)) { null }
    return List(n) { Group(id, it + 1, n) }
}

private const val ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
fun randomGroupId(): String = CharArray(12) { ID_ALPHABET[Random.nextInt(64)] }.concatToString()

/** "#71" or "#71–#73" */
fun seqRange(seqs: List<Long>): String {
    if (seqs.isEmpty()) return ""
    val lo = seqs.min()
    val hi = seqs.max()
    return if (lo == hi) "#$lo" else "#$lo–#$hi"
}

/** JavaScript's `toFixed(1)` for the positive numbers used here */
private fun fixed1(v: Double): String {
    val tenths = floor(v * 10 + 0.5).toLong()
    return "${tenths / 10}.${tenths % 10}"
}

/** "5 MB", "1.5 MB" */
fun mb(n: Long): String {
    val v = n / 1048576.0
    return if (v == floor(v)) "${v.toLong()} MB" else "${fixed1(v)} MB"
}

/** A file's size for people: "820 B", "14 KB", "2.3 MB" */
fun fileSize(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1048576 -> "${floor(n / 1024.0 + 0.5).toLong()} KB"
    else -> "${fixed1(n / 1048576.0)} MB"
}

/** The service addresses to try, in order: the ones that worked before, then the built-in ones */
fun endpointList(stored: List<String>?, defaults: List<String>): List<String> =
    (stored.orEmpty() + defaults).distinct()
