package xyz.smart_kits.dropit.shared

// What a letter in the list offers, worked out in one place so the screen only draws it. The same inputs always give the same
// answer: what the letter is, which way it went (received or sent), and how saving its files stands right now.

/** Why the last try at saving a file did not work */
enum class SaveFailure { OFFLINE, FAILED, LIMIT, UNSUPPORTED }

/** One thing a letter's button or sheet block can do. [SAVING] is a button that is busy, not a thing to press. */
enum class Act {
    NONE, COPY, OPEN_LINK, SAVE, OPEN_FILE, SAVE_ALL, RETRY, SHARE, COPY_IMAGE, SAVING, CLEAR,
    /** Send what this phone sent to other devices once more (opens the panel that asks which) */
    RESEND,
    /** A send that stopped part-way: send what is left (what already went is not sent again) */
    RETRY_SEND,
    /** A send whose chosen devices are gone: send it to everyone this time */
    SEND_ALL,
}

data class LetterOffer(val act: Act, val enabled: Boolean = true)

/** How one file looks now: its state, with a save in progress or a failed try laid over a file that can still be saved */
data class FileView(val state: FileState, val saving: Boolean = false, val failure: SaveFailure? = null)

/** A file of a letter, as it stands. A file this phone sent is always [FileState.SENT]: it has nothing to fetch. */
fun Letter.viewOf(file: LetterFile, nowMs: Long, saving: Set<Long> = emptySet(), failures: Map<Long, SaveFailure> = emptyMap()): FileView {
    if (mine) return FileView(FileState.SENT)
    val state = file.state(nowMs)
    if (state != FileState.AVAILABLE) return FileView(state)
    return FileView(state, saving = file.seq in saving, failure = failures[file.seq])
}

/** More than one thing came in one drop: several files, or words with a file */
val Letter.isBatch: Boolean get() = files.size > 1 || (files.isNotEmpty() && text != null)

private fun FileView.pending() = state == FileState.AVAILABLE && failure != SaveFailure.UNSUPPORTED

/** What a send that stopped offers as its button: try again, or (when its devices are gone) everyone this time */
private fun SendFailure.offer(): LetterOffer = when {
    target -> LetterOffer(Act.SEND_ALL)
    retry -> LetterOffer(Act.RETRY_SEND)
    else -> LetterOffer(Act.RETRY_SEND, enabled = false)   // only a limit or a plan changes this: trying again changes nothing
}

/**
 * The one button on the row. Every letter has a single next step; nothing is offered that cannot be done.
 * [resend]: what sending it again comes to ([resendAvail]); only a letter this phone sent can be sent again.
 */
fun Letter.primary(
    nowMs: Long, saving: Set<Long> = emptySet(), failures: Map<Long, SaveFailure> = emptyMap(), resend: ResendAvail = ResendAvail.NONE,
): LetterOffer {
    if (mine) failure?.let { return it.offer() }
    val words = text
    if (files.isEmpty()) return when {
        words == null -> LetterOffer(Act.NONE)
        isLink && !mine -> LetterOffer(Act.OPEN_LINK)
        else -> LetterOffer(Act.COPY)
    }
    // What this phone sent keeps only a small picture of each file: words can be copied; files can only be sent again
    if (mine) return when {
        words != null -> LetterOffer(Act.COPY)
        resend == ResendAvail.OK -> LetterOffer(Act.RESEND)
        resend == ResendAvail.EXPIRED -> LetterOffer(Act.RESEND, enabled = false)
        else -> LetterOffer(Act.NONE)
    }

    val views = files.map { viewOf(it, nowMs, saving, failures) }
    if (views.any { it.saving }) return LetterOffer(Act.SAVING, enabled = false)
    if (!isBatch) {
        val v = views[0]
        return when (v.state) {
            FileState.SAVED -> LetterOffer(Act.OPEN_FILE)
            FileState.EXPIRED, FileState.SENT -> LetterOffer(Act.CLEAR)
            FileState.AVAILABLE -> when (v.failure) {
                null -> LetterOffer(Act.SAVE)
                SaveFailure.LIMIT -> LetterOffer(Act.RETRY, enabled = false)   // today's downloads are used up: trying again changes nothing
                SaveFailure.UNSUPPORTED -> LetterOffer(Act.NONE)
                else -> LetterOffer(Act.RETRY)
            }
        }
    }
    val pending = views.filter { it.pending() }
    return when {
        pending.isEmpty() -> when {
            words != null -> LetterOffer(Act.COPY)
            views.any { it.state == FileState.SAVED } -> LetterOffer(Act.SHARE)
            else -> if (views.all { it.state == FileState.EXPIRED }) LetterOffer(Act.CLEAR) else LetterOffer(Act.NONE)
        }
        pending.all { it.failure != null } -> LetterOffer(Act.RETRY, enabled = !pending.all { it.failure == SaveFailure.LIMIT })
        else -> LetterOffer(Act.SAVE_ALL)
    }
}

/** The blocks of the sheet, in order, at most four: the letter's own next step first, clearing last */
fun Letter.menu(
    nowMs: Long, saving: Set<Long> = emptySet(), failures: Map<Long, SaveFailure> = emptyMap(), resend: ResendAvail = ResendAvail.NONE,
): List<LetterOffer> {
    val words = text
    val out = mutableListOf<LetterOffer>()
    if (files.isEmpty() || mine) {
        if (mine) failure?.let { out += it.offer() }
        // Sending again comes first on what this phone sent: it is what the sheet is for there
        if (mine && failure == null) when (resend) {
            ResendAvail.OK -> out += LetterOffer(Act.RESEND)
            ResendAvail.EXPIRED -> out += LetterOffer(Act.RESEND, enabled = false)
            ResendAvail.NONE -> {}
        }
        if (words != null) {
            // Four blocks at most: a link this phone sent is not opened from its own sheet once it can be sent again
            if (isLink && !(mine && out.isNotEmpty())) out += LetterOffer(Act.OPEN_LINK)
            out += LetterOffer(Act.COPY)
            out += LetterOffer(Act.SHARE)
        }
        out += LetterOffer(Act.CLEAR)
        return out
    }
    val views = files.map { viewOf(it, nowMs, saving, failures) }
    val first = primary(nowMs, saving, failures)
    if (first.act in setOf(Act.SAVE, Act.OPEN_FILE, Act.SAVE_ALL, Act.RETRY, Act.SAVING)) out += first
    if (isBatch && words != null) out += LetterOffer(Act.COPY)
    // Sharing needs the file: one that can still be saved is saved first, so only an expired or unsupported one rules it out
    val usable = views.any { it.state == FileState.SAVED || it.pending() }
    if (usable) out += LetterOffer(Act.SHARE)
    val only = files.singleOrNull()
    if (!isBatch && only != null && only.mime.orEmpty().startsWith("image/") && usable) out += LetterOffer(Act.COPY_IMAGE)
    out += LetterOffer(Act.CLEAR)
    return out
}
