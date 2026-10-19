package xyz.smart_kits.dropit

import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import xyz.smart_kits.dropit.shared.ApiError
import xyz.smart_kits.dropit.shared.Catalog
import xyz.smart_kits.dropit.shared.DropitClient
import xyz.smart_kits.dropit.shared.OfflineError
import xyz.smart_kits.dropit.shared.PlanLimits
import xyz.smart_kits.dropit.shared.Plans
import xyz.smart_kits.dropit.shared.Requests

/**
 * What is on sale and for how much comes from the service (GET /v1/plans): nothing about money is written in the app.
 * The answer is kept on the phone, drawn at once, and asked for again when it is more than an hour old. What was kept is
 * never thrown away until the service has given a new answer, however old and however many times it can't be reached (a stale
 * price is better than a blank one; checkout shows the real price). Only with nothing kept do the screens say prices aren't
 * available and leave nothing to press. Reading [catalog] in a composable redraws it when it changes.
 */
class PlansRepo(private val store: Store, private val client: DropitClient, private val now: () -> Long = System::currentTimeMillis) {
    /** null: not known (nothing has ever been kept) */
    val catalog = mutableStateOf(load())

    /** Worth offering an upgrade button: not when the service has said nothing can be bought right now (unknown: the plans screen says what it can) */
    val offering: Boolean get() = catalog.value?.canBuy != false

    /** The limits: the service's when known, else the ones the app starts from */
    val free: PlanLimits get() = catalog.value?.free ?: Plans.defaultFree
    val paid: PlanLimits get() = catalog.value?.paid ?: Plans.defaultPaid

    private fun load(): Catalog? {
        val kept = store.plansJson ?: return null
        return runCatching { Catalog.from(Json.parseToJsonElement(kept).jsonObject) }.getOrNull()
    }

    /** Ask the service unless what is kept is fresh (or [force]). Never throws: a service that can't be reached leaves what is drawn as it is */
    suspend fun refresh(force: Boolean = false) {
        if (!force && store.plansJson != null && now() - store.plansAt < Plans.FRESH_MS) return
        try {
            val data = client.send(Requests.plans()).data
            val parsed = Catalog.from(data) ?: return
            store.plansJson = data.toString()
            catalog.value = parsed
        } catch (e: ApiError) {
            // the service had no answer for us: what is kept stays
        } catch (e: OfflineError) {
        }
    }
}
