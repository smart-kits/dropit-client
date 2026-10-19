package xyz.smart_kits.dropit.shared

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// What arrives. Read the pages of a pull, leave out what this phone sent itself, put things sent together into
// one letter, and work out what to tell the person. Nothing here touches the network, the disk or the
// notification tray: the app does those with what these functions return.

const val PULL_LIMIT = 50
/** A pull never asks for more pages than this in one go: enough for everything that can be waiting */
const val PULL_PAGES_MAX = 40
/** More new letters than this at once become a single "N new items" */
const val BACKLOG_OVER = 3
/** On mobile data a file up to this size downloads by itself; a larger one waits for a tap */
const val AUTO_DOWNLOAD_CELLULAR_MAX = 20L shl 20
/** How much of a text a notice shows */
const val NOTICE_TEXT_MAX = 240

/** One item as the service lists it: the fields the phone uses */
data class Incoming(
    val seq: Long,
    val deviceId: String? = null,
    val kind: String = "text",
    val text: String? = null,
    /** Where a file downloads from, for a while; null for text and links */
    val url: String? = null,
    val bytes: Long = 0,
    val at: Long = 0,
    val source: String? = null,
    val filename: String? = null,
    val mime: String? = null,
    val group: Group? = null,
    val title: String? = null,
    val from: From? = null,
) {
    val isFile: Boolean get() = url != null
    /** The letter it belongs to: its batch, or itself */
    val letterId: String get() = group?.id ?: "s$seq"
}

data class PullPage(val items: List<Incoming>, val next: Long, val more: Boolean)

private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
private fun JsonObject.obj(k: String) = this[k] as? JsonObject

/** One page of the pull, read. Whatever doesn't look like an item is left out rather than failing the page. */
fun pullPage(answer: Answer, after: Long): PullPage {
    val items = (answer.data["items"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val seq = o["seq"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        val meta = o.obj("meta")
        val group = meta?.obj("group")?.let { g ->
            val id = g.str("id"); val i = g["i"]?.jsonPrimitive?.intOrNull; val n = g["n"]?.jsonPrimitive?.intOrNull
            if (id != null && i != null && n != null) Group(id, i, n) else null
        }
        val from = meta?.obj("from")?.let { f -> f.str("url")?.takeIf(::isHttp)?.let { From(it, f.str("title")) } }
        val url = o.str("url")
        Incoming(
            seq = seq,
            deviceId = o.str("device_id"),
            kind = o.str("kind") ?: "text",
            text = if (url == null) o.str("raw") else null,
            url = url,
            bytes = o["bytes"]?.jsonPrimitive?.longOrNull ?: 0,
            at = o["created_at"]?.jsonPrimitive?.longOrNull ?: 0,
            source = o.str("source"),
            filename = meta?.str("filename"),
            mime = meta?.str("mime"),
            group = group,
            title = meta?.str("title"),
            from = from,
        )
    }
    return PullPage(
        items,
        next = answer.data["next_after"]?.jsonPrimitive?.longOrNull ?: items.maxOfOrNull { it.seq } ?: after,
        more = answer.data["has_more"]?.jsonPrimitive?.booleanOrNull ?: false,
    )
}

/** Everything after `after`: keeps asking while the service says there is more. `fetch` is the app's client. */
suspend fun pullAll(after: Long, why: String?, fetch: suspend (RequestSpec) -> Answer): PullPage {
    val all = mutableListOf<Incoming>()
    var cursor = after
    repeat(PULL_PAGES_MAX) {
        val page = pullPage(fetch(Requests.pull(cursor, why = why)), cursor)
        all += page.items
        if (page.next <= cursor && page.more) return PullPage(all, cursor, false)   // not moving: stop rather than spin
        cursor = maxOf(cursor, page.next)
        if (!page.more) return PullPage(all, cursor, false)
    }
    return PullPage(all, cursor, true)
}

/** What a pull brought, for this phone */
data class Arrival(
    /** Where the next pull starts: past everything seen, this phone's own items included */
    val cursor: Long,
    /** Letters to keep: new ones, and ones already kept that grew (the rest of a batch came in) */
    val letters: List<Letter>,
    /** Files to fetch now, while their addresses work */
    val downloads: List<Download>,
    /** What to show. Nothing for this phone's own items, nor for a letter that only grew */
    val notices: List<Notice>,
)

data class Download(val seq: Long, val letterId: String, val url: String, val name: String, val mime: String?, val size: Long)

/** Whether a file of this size downloads by itself */
fun autoDownload(size: Long, unmetered: Boolean, cellularMax: Long = AUTO_DOWNLOAD_CELLULAR_MAX): Boolean = unmetered || size <= cellularMax

/**
 * Turn pulled items into letters and notices.
 * @param myDevice  this phone's device id: what it sent comes back in the pull and is skipped
 * @param known     the letters the phone already keeps
 * @param unmetered on Wi-Fi (or any network that isn't counted): every file downloads by itself
 * @param retentionMs how long the account's plan keeps things on the service: each file letter is told when its file goes
 * @param cellularMax the largest file that downloads by itself on a counted network (negative: none does)
 */
fun arrive(
    items: List<Incoming>, myDevice: String?, known: List<Letter>, cursor: Long, unmetered: Boolean,
    retentionMs: Long? = null, cellularMax: Long = AUTO_DOWNLOAD_CELLULAR_MAX,
): Arrival {
    val next = maxOf(cursor, items.maxOfOrNull { it.seq } ?: cursor)
    val theirs = items.filter { it.deviceId == null || it.deviceId != myDevice }
    val fresh = mutableListOf<Letter>()
    val grown = mutableListOf<Letter>()
    val downloads = mutableListOf<Download>()
    for ((id, pieces) in theirs.groupBy { it.letterId }) {
        val before = known.firstOrNull { it.id == id && !it.mine }
        val added = pieces.filter { before == null || it.seq !in before.seqs }
        if (added.isEmpty()) continue
        val letter = letterOf(id, added, before, retentionMs)
        (if (before == null) fresh else grown) += letter
        for (p in added) if (p.isFile && autoDownload(p.bytes, unmetered, cellularMax)) {
            downloads += Download(p.seq, id, p.url!!, p.filename ?: "file", p.mime, p.bytes)
        }
    }
    val notices = if (fresh.size > BACKLOG_OVER) {
        listOf(Notice(id = "backlog", summary = Message("noticeBacklog", listOf(fresh.sumOf { it.seqs.size }.toString())), opensApp = true))
    } else {
        fresh.map { l -> noticeFor(l, ready = l.files.all { autoDownload(it.size, unmetered, cellularMax) }) }
    }
    return Arrival(next, fresh + grown, downloads, notices)
}

private fun letterOf(id: String, pieces: List<Incoming>, before: Letter?, retentionMs: Long?): Letter {
    val ordered = pieces.sortedBy { it.group?.i ?: 0 }
    val words = ordered.firstOrNull { !it.isFile }
    val files = ordered.filter { it.isFile }.map { LetterFile(it.filename ?: "file", it.bytes, it.mime, it.seq, expires = retentionMs?.let { ms -> it.at + ms }) }
    val base = before ?: Letter(id = id, mine = false, at = pieces.minOf { it.at })
    return base.copy(
        seqs = (base.seqs + pieces.map { it.seq }).distinct().sorted(),
        source = base.source ?: pieces.first().source,
        kind = base.kind ?: words?.kind,
        text = base.text ?: words?.text,
        title = base.title ?: words?.title,
        fromTitle = base.fromTitle ?: pieces.firstNotNullOfOrNull { it.from?.title },
        fromUrl = base.fromUrl ?: pieces.firstNotNullOfOrNull { it.from?.url },
        files = (base.files + files).sortedBy { it.seq },
    )
}

enum class NoticeAction { COPY, OPEN, SHARE, DOWNLOAD }

/** One notification, described: the app only draws it */
data class Notice(
    /** The letter it is about (the same id takes the place of the one showing), or `backlog` */
    val id: String,
    /** Words from the content itself: a link's title, a file's name */
    val title: String? = null,
    /** More of the content: the text's first lines, a link's address */
    val body: String? = null,
    /** A sentence of ours in place of content: what a batch holds, how many came in */
    val summary: Message? = null,
    /** "From <page>" under something picked up on a web page */
    val from: Message? = null,
    /** The first is the one a tap does */
    val actions: List<NoticeAction> = emptyList(),
    /** A tap goes into the app: there is more than one thing to act on */
    val opensApp: Boolean = false,
)

/** All the lock screen shows, whatever arrived */
val LOCKED_NOTICE = Message("noticeLocked")

/**
 * What to show for a letter.
 * @param ready its files are on the phone, or on their way without asking: a file that waits for a tap gets Download
 */
fun noticeFor(letter: Letter, ready: Boolean = letter.files.all { it.saved != null }): Notice {
    val from = letter.fromTitle?.takeIf { it.isNotBlank() }?.let { Message("noticeFrom", listOf(it)) }
    return when (val shape = letter.shape) {
        LetterShape.Text -> Notice(letter.id, body = clip(letter.text, NOTICE_TEXT_MAX), from = from,
            actions = listOf(NoticeAction.COPY, NoticeAction.SHARE))
        LetterShape.Link -> Notice(letter.id, title = letter.title?.takeIf { it.isNotBlank() }, body = letter.text, from = from,
            actions = listOf(NoticeAction.OPEN, NoticeAction.COPY, NoticeAction.SHARE))
        LetterShape.Image, LetterShape.Video, LetterShape.File -> Notice(letter.id, title = letter.files[0].name, from = from,
            actions = listOf(if (ready) NoticeAction.OPEN else NoticeAction.DOWNLOAD, NoticeAction.SHARE))
        is LetterShape.Files -> Notice(letter.id, summary = Message("noticeFiles", listOf(shape.n.toString())), from = from, opensApp = true)
        is LetterShape.TextAndFiles -> Notice(letter.id, summary = Message("noticeTextAndFiles", listOf(shape.n.toString())), from = from, opensApp = true)
    }
}

/** The fresh download address of file `seq` in the answer to [Requests.itemAgain]; null when the service no longer has it */
fun freshAddress(answer: Answer, seq: Long): String? =
    pullPage(answer, seq - 1).items.firstOrNull { it.seq == seq && it.isFile }?.url
