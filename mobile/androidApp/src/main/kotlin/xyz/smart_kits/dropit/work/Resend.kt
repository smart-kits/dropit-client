package xyz.smart_kits.dropit.work

import android.content.Context
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.retentionMs
import xyz.smart_kits.dropit.shared.ApiError
import xyz.smart_kits.dropit.shared.Failure
import xyz.smart_kits.dropit.shared.Letter
import xyz.smart_kits.dropit.shared.OfflineError
import xyz.smart_kits.dropit.shared.Requests
import xyz.smart_kits.dropit.shared.Targets
import xyz.smart_kits.dropit.shared.resendGroups
import xyz.smart_kits.dropit.shared.resentLetter
import java.util.UUID

/**
 * Sending what this phone sent once more, to the devices chosen in the panel. Each item of the letter is sent again by the service
 * (a new item, a new send for the day's count); the letter that results goes to the top of the list, marked as sent again.
 * Nothing is kept for later: it is a request made now, and it says how it went.
 */
class Resend(private val context: Context) {
    sealed interface Result {
        /** All of it went. [already]: the same was sent again a moment ago, so nothing new was made */
        data class Done(val already: Boolean) : Result
        /** The words to show. [expired]: the service no longer has it; [upgrade]/[why]: a paid plan lifts the limit that was met */
        data class Failed(val text: String, val expired: Boolean = false, val upgrade: String? = null, val why: String? = null) : Result
    }

    suspend fun run(letter: Letter, targets: Targets): Result {
        val app = context.app
        val now = System.currentTimeMillis()
        val made = mutableListOf<Long>()
        var already = false
        try {
            // One new batch for the lot (not the old one: that is already complete on the other side)
            val groups = resendGroups(letter)
            for ((k, seq) in letter.seqs.withIndex()) {
                val answer = app.client.send(Requests.resend(seq, targets.to, groups[k]))
                already = already || answer.deduped
                answer.seq?.let(made::add)
            }
        } catch (e: ApiError) {
            if (e.failure == Failure.AUTH_LOST) app.store.forget(e.sentence().key)
            keep(letter, made, targets, now)
            return when (e.code) {
                "ITEM_EXPIRED" -> {
                    // From now on the list says so without asking
                    app.letters.add(letter.copy(until = now))
                    Result.Failed(app.strings.render(e.sentence()), expired = true)
                }
                "TARGET_UNAVAILABLE" -> {
                    app.peers.refresh()
                    Result.Failed(context.getString(R.string.resend_gone))
                }
                else -> Result.Failed(
                    app.strings.render(e.sentence()),
                    upgrade = e.data["upgrade_url"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
                    why = e.data["why"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
                )
            }
        } catch (e: OfflineError) {
            keep(letter, made, targets, now)
            return Result.Failed(app.strings.render(e.sentence))
        }
        // Said by the names that were ticked; a repeat of one sent a moment ago makes no new letter
        if (!already) keep(letter, made, targets, now)
        return Result.Done(already)
    }

    /** What did go (all of it, or the first of several) is in the list, so the person can see it */
    private suspend fun keep(from: Letter, seqs: List<Long>, targets: Targets, now: Long) {
        if (seqs.isEmpty()) return
        val app = context.app
        app.letters.add(resentLetter(from, UUID.randomUUID().toString(), seqs, targets, now, now + app.retentionMs()))
    }
}
