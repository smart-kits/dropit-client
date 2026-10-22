package xyz.smart_kits.dropit.receive

import xyz.smart_kits.dropit.App
import xyz.smart_kits.dropit.retentionMs
import xyz.smart_kits.dropit.shared.ApiError
import xyz.smart_kits.dropit.shared.Failure
import xyz.smart_kits.dropit.shared.OfflineError
import xyz.smart_kits.dropit.shared.RepullSpan
import xyz.smart_kits.dropit.shared.arrive
import xyz.smart_kits.dropit.shared.keepForSpan
import xyz.smart_kits.dropit.shared.pullAll
import xyz.smart_kits.dropit.shared.repullReset
import xyz.smart_kits.dropit.shared.repullWaitMs
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * "Pull again": the person asked for what this phone already had to come back (it was cleared from the list, or the list was lost).
 * The place in the line is moved back at the service, everything after it is read again, and what is not in the list yet goes in
 * without a notice (the person asked; the list shows it). Anything already in the list stays as it is. At most once a minute. It only
 * brings back what the service still keeps.
 */
class Repull(private val app: App) {
    sealed interface Result {
        /** [letters]: how many came back that were not in the list */
        data class Done(val letters: Int) : Result
        /** Too soon after the last one: how many seconds to wait */
        data class Wait(val seconds: Int) : Result
        data object Offline : Result
        data object Failed : Result
    }

    suspend fun run(span: RepullSpan): Result {
        val store = app.store
        if (store.tokenNow() == null) return Result.Failed
        val now = System.currentTimeMillis()
        val wait = repullWaitMs(store.repullAt, now)
        if (wait > 0) return Result.Wait(((wait + 999) / 1000).toInt())
        store.repullAt = now
        return try {
            val to = app.client.send(repullReset(span)).data["to_seq"]?.jsonPrimitive?.longOrNull ?: 0L
            val page = pullAll(to, "repull", app.client::send)
            val items = keepForSpan(page.items, span, now)
            // Nothing downloads by itself, and what the list already has is left as it is
            val arrival = arrive(items, store.deviceId, app.letters.all.value, store.cursor, unmetered = false, retentionMs = app.retentionMs(), cellularMax = -1)
            arrival.letters.forEach(app.letters::add)
            store.cursor = maxOf(store.cursor, arrival.cursor, page.next)
            Result.Done(arrival.letters.size)
        } catch (e: ApiError) {
            if (e.failure == Failure.AUTH_LOST) store.forget()
            store.repullAt = 0
            Result.Failed
        } catch (e: OfflineError) {
            store.repullAt = 0
            Result.Offline
        }
    }
}
