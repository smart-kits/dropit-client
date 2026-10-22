package xyz.smart_kits.dropit.shared

import kotlinx.serialization.Serializable

// What the app's list shows: one letter per drop — a batch (text and files sent together) is one letter.
// The phone keeps a week of them and no more: dropit delivers, it is not an archive.

const val LETTER_DAYS = 7

@Serializable
data class LetterFile(
    val name: String,
    val size: Long,
    val mime: String? = null,
    val seq: Long? = null,
    /** Where the phone put it once received (a content address), when it has */
    val saved: String? = null,
    /** A small picture of it kept by the phone, for pictures */
    val thumb: String? = null,
    /** When the service stops having it (when it arrived plus how long the account's plan keeps things); null: not known */
    val expires: Long? = null,
    /** Its place in the batch it came in (from 1), for showing the files in the order they were sent; null: not known (a letter kept by an earlier version) */
    val i: Int? = null,
)

/** [SENT]: a file this phone sent: only its small picture is kept here, so there is nothing to save or open */
enum class FileState { SAVED, EXPIRED, AVAILABLE, SENT }

/**
 * What can be done with a received file now: it is on the phone (open it, share it, whether or not the service still has it),
 * it has gone from the service (nothing to fetch: dropit delivers, it does not store), or it can still be saved.
 * A file whose end is not known counts as available: the service has the last word when it is asked.
 */
fun LetterFile.state(nowMs: Long): FileState = when {
    saved != null -> FileState.SAVED
    expires != null && nowMs >= expires -> FileState.EXPIRED
    else -> FileState.AVAILABLE
}

@Serializable
data class Letter(
    /** The batch id, or `s<seq>` for a single item */
    val id: String,
    /** Sent from this phone: it has no arrival, so no postmark */
    val mine: Boolean,
    /** When it was sent (this phone) or reached the service */
    val at: Long,
    val seqs: List<Long> = emptyList(),
    /** The device or client it came from, for the postmark */
    val source: String? = null,
    /** `text` or `url`, when there is writing in it */
    val kind: String? = null,
    val text: String? = null,
    /** A link's page title */
    val title: String? = null,
    val fromTitle: String? = null,
    val fromUrl: String? = null,
    val files: List<LetterFile> = emptyList(),
    /** Sent from this phone to these devices (ids); empty: to everyone */
    val toIds: List<String> = emptyList(),
    /** Their names when it was sent, for saying who it went to */
    val toNames: List<String> = emptyList(),
    /** Made by sending an earlier one again */
    val again: Boolean = false,
    /** When the service stops having what this phone sent (so it can no longer be sent again); null: not known */
    val until: Long? = null,
    /** Set while a send of this letter did not go through: the card says why and offers to try again */
    val failure: SendFailure? = null,
) {
    /**
     * Words that are a link to open: the sender called it one *and* it is an http(s) address. Anything else that was called a link is
     * only text here (it can be copied, not opened).
     */
    val isLink: Boolean get() = kind == "url" && canOpenLink(text)

    /** What the letter is, for its heading: a link, text, one picture / video / file, or a count */
    val shape: LetterShape
        get() = when {
            files.isEmpty() -> if (isLink) LetterShape.Link else LetterShape.Text
            text != null -> LetterShape.TextAndFiles(files.size)
            files.size > 1 -> LetterShape.Files(files.size)
            files[0].mime.orEmpty().startsWith("image/") -> LetterShape.Image
            files[0].mime.orEmpty().startsWith("video/") -> LetterShape.Video
            else -> LetterShape.File
        }
}

/** A send that stopped part-way, for the card in the list of what this phone sent */
@Serializable
data class SendFailure(
    /** Why, as a sentence in the phone's language (made when it happened) */
    val reason: String,
    /** The files that did not go, by their place in the letter */
    val files: List<Int> = emptyList(),
    /** The words did not go */
    val words: Boolean = false,
    /** Trying again can work. False when only something else (a limit, a paid plan) does */
    val retry: Boolean = true,
    /** The devices it was addressed to are gone: the card offers sending it to everyone this time */
    val target: Boolean = false,
)

sealed interface LetterShape {
    data object Text : LetterShape
    data object Link : LetterShape
    data object Image : LetterShape
    data object Video : LetterShape
    data object File : LetterShape
    data class Files(val n: Int) : LetterShape
    data class TextAndFiles(val n: Int) : LetterShape
}

/** Newest first; a letter with the same id takes the old one's place; nothing older than a week stays */
fun withLetter(letters: List<Letter>, letter: Letter, nowMs: Long): List<Letter> =
    keepRecent((listOf(letter) + letters.filter { it.id != letter.id }).sortedByDescending { it.at }, nowMs)

/** The list without these letters (cleared from this phone's list; the service and saved files are not touched) */
fun withoutLetters(letters: List<Letter>, ids: Set<String>): List<Letter> = letters.filter { it.id !in ids }

fun keepRecent(letters: List<Letter>, nowMs: Long, days: Int = LETTER_DAYS): List<Letter> =
    letters.filter { nowMs - it.at < days * 86_400_000L }

/** A link without its scheme and trailing slash, as a person would say it */
fun shortUrl(url: String): String = url.substringAfter("://").removePrefix("www.").trimEnd('/')
