package xyz.smart_kits.dropit.shared

// What the screens that other apps can open (the share sheet, "open with", the clipboard entry) may take in, and how long they wait for it.
// Another app decides what address it hands over, so nothing about it is trusted: only what a content provider of another app serves is
// read, and what stalls is given up on.

/**
 * Whether [scheme] and [authority] of an address another app handed over may be read and copied in: a `content` address served by a
 * provider of some other app. Not a `file` address (it can name anything this app can read, its own private files included), nor any
 * other scheme, nor a `content` address of this app's own package (what another app gives is never this app's own store).
 * [authority] may carry user info and a port; the host is what picks the provider.
 */
fun intakeUriOk(scheme: String?, authority: String?, ownPackage: String): Boolean {
    if (scheme?.lowercase() != "content") return false
    val host = authority?.substringAfterLast('@')?.substringBefore(':')?.trim()?.lowercase().orEmpty()
    if (host.isEmpty()) return false
    val own = ownPackage.lowercase()
    return host != own && !host.startsWith("$own.")
}

/** Upper bound on how long opening a handed-over file, or the next piece of it, may take before it is given up on */
const val INTAKE_STALL_MS = 20_000L

/**
 * Whether a copy has stopped moving rather than being slow: nothing has been heard of it ([lastBeatMs]: when the file opened, or when
 * the last piece of it arrived) for [stallMs].
 */
fun copyStalled(lastBeatMs: Long, nowMs: Long, stallMs: Long = INTAKE_STALL_MS): Boolean = nowMs - lastBeatMs >= stallMs

/**
 * Whether a screen being made again (turned, folded, resized) takes in what was handed to it: only the first time. The same intent comes
 * back with the new window, and taking it in again would send the same thing twice.
 */
fun takesIn(restored: Boolean): Boolean = !restored

/** What is done with one handed-over file before anything of it is copied */
enum class HoldStep {
    COPY,
    /** The tenth file is already listed: this one is only counted, never read ([FILES_MAX]) */
    TOO_MANY,
    /** The provider says it is bigger than the plan takes for one item: listed with the size it said, never copied */
    TOO_LARGE,
}

/**
 * [listed]: how many files are already listed for this drop (copied, or counted as too large). [declared]: the size the file's provider
 * says it has, when it says; [limit]: the plan's limit for one item, when it is known. A file whose size is not said is copied and
 * stopped when it passes the limit ([copyOverLimit]).
 */
fun holdStep(listed: Int, declared: Long?, limit: Long?): HoldStep = when {
    listed >= FILES_MAX -> HoldStep.TOO_MANY
    limit != null && declared != null && declared > limit -> HoldStep.TOO_LARGE
    else -> HoldStep.COPY
}

/** A copy under way has passed what the plan takes for one item: stop, the rest is not worth the room */
fun copyOverLimit(copied: Long, limit: Long?): Boolean = limit != null && copied > limit
