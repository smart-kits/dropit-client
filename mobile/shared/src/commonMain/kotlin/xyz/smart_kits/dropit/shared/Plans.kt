package xyz.smart_kits.dropit.shared

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// What each plan gets and what it costs, for the plan table and the upgrade page.
//
// No price is written here. The service answers GET /v1/plans — what is on sale, at what price, through which payment
// provider, and whether it can be bought at all — and the app draws from that: change a price, add a plan, switch
// provider or close payments on the service and the app follows, with no new release. The answer is kept on the phone
// (see PlansRepo) and never thrown away until the service has given a new one (a stale price is better than a blank one;
// checkout always shows the real price); with no answer and nothing kept, the app says so and nothing can be pressed.
//
// The limits start from the numbers below (which a test keeps equal to what the service enforces; the service enforces
// them whatever is written here) and are replaced by the service's own numbers when it answers.

data class PlanLimits(
    val days: Int,
    val spaceMB: Int,
    val fileMB: Int,
    val devices: Int,
    val perDay: Int,
    /** Files one account may download in a day */
    val downloads: Int,
    val queue: Int,
    /** Days of real-time delivery a free account starts with; null: always on */
    val realtimeTrialDays: Int?,
)

/** One plan on sale, as the service lists it. Money is in the smallest unit (cents) */
data class Offer(
    /** year (the subscription) · year_once (one year, paid once) · life (paid once, for good) */
    val id: String,
    val kind: String,
    val cents: Long,
    val currency: String,
    /** The first month's price and length, when the subscription starts with one */
    val trialCents: Long?,
    val trialDays: Int?,
)

/** The service's answer to GET /v1/plans */
data class Catalog(
    val open: Boolean,
    val provider: String?,
    val offers: List<Offer>,
    val free: PlanLimits,
    val paid: PlanLimits,
) {
    fun offer(id: String): Offer? = offers.firstOrNull { it.id == id }

    /** All three ways to pay can be pressed: the service says it is open and every plan has a price */
    val canBuy: Boolean get() = open && offer("year") != null && offer("year_once") != null && offer("life") != null

    /** How much the subscription costs less than the same year paid once, in whole percent: the number on the sale sticker */
    val yearSaves: Int?
        get() {
            val y = offer("year")?.cents ?: return null
            val o = offer("year_once")?.cents?.takeIf { it > 0 } ?: return null
            return ((1 - y.toDouble() / o) * 100 + .5).toInt()
        }

    companion object {
        fun from(o: JsonObject): Catalog? = runCatching {
            val pay = o["payments"]?.jsonObject
            val offers = o["plans"]?.jsonArray?.mapNotNull { e ->
                val p = e.jsonObject
                val price = p["price"]?.jsonObject ?: return@mapNotNull null
                val trial = p["trial"]?.takeIf { it is JsonObject }?.jsonObject
                Offer(
                    id = p["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    kind = p["kind"]?.jsonPrimitive?.contentOrNull ?: "",
                    cents = price["cents"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                    currency = price["currency"]?.jsonPrimitive?.contentOrNull ?: "USD",
                    trialCents = trial?.get("cents")?.jsonPrimitive?.longOrNull,
                    trialDays = trial?.get("days")?.jsonPrimitive?.intOrNull,
                )
            }.orEmpty()
            val limits = o["limits"]?.jsonObject
            Catalog(
                open = pay?.get("open")?.jsonPrimitive?.booleanOrNull == true,
                provider = pay?.get("provider")?.jsonPrimitive?.contentOrNull,
                offers = offers,
                free = Plans.limits(limits?.get("free")?.jsonObject, Plans.defaultFree),
                paid = Plans.limits(limits?.get("paid")?.jsonObject, Plans.defaultPaid),
            )
        }.getOrNull()
    }
}

object Plans {
    val defaultFree = PlanLimits(days = 1, spaceMB = 30, fileMB = 10, devices = 2, perDay = 20, downloads = 300, queue = 100, realtimeTrialDays = 14)
    val defaultPaid = PlanLimits(days = 10, spaceMB = 500, fileMB = 100, devices = 10, perDay = 300, downloads = 3000, queue = 1000, realtimeTrialDays = null)

    /** Within an hour of the last answer: no request. After that it is asked for again, and the kept one is drawn until the new one comes */
    const val FRESH_MS = 60 * 60_000L

    /** The service's limits over the starting ones, one by one */
    fun limits(o: JsonObject?, base: PlanLimits): PlanLimits {
        if (o == null) return base
        fun int(k: String, d: Int) = o[k]?.jsonPrimitive?.intOrNull ?: d
        return PlanLimits(
            days = int("days", base.days), spaceMB = int("spaceMB", base.spaceMB), fileMB = int("fileMB", base.fileMB),
            devices = int("devices", base.devices), perDay = int("perDay", base.perDay), downloads = int("downloads", base.downloads), queue = int("queue", base.queue),
            // an explicit null means "always on"
            realtimeTrialDays = if (o.containsKey("realtimeTrialDays")) o["realtimeTrialDays"]?.jsonPrimitive?.intOrNull else base.realtimeTrialDays,
        )
    }

    /** Space as it is written, the way the website writes it: "30 MB", "1 GB" */
    fun space(mb: Int): String = if (mb % 1024 == 0) "${mb / 1024} GB" else "$mb MB"

    /** What a year costs per month, in cents, rounded: $19.90 a year is about $1.66 a month. Null when it can't be said */
    fun perMonthCents(yearCents: Long): Long? = if (yearCents > 0) (yearCents + 6) / 12 else null

    /** How many years of the yearly plan the lifetime price is, to one decimal: $49.90 against $19.90 is 2.5. Null when it can't be said */
    fun lifetimeInYears(lifeCents: Long, yearCents: Long): Double? =
        if (lifeCents > 0 && yearCents > 0) kotlin.math.round(lifeCents * 10.0 / yearCents) / 10.0 else null

    /** A price as it is written, with its currency's sign and always with cents, the way the website and checkout write it: "$19.90" */
    fun money(cents: Long, currency: String = "USD"): String {
        val sign = when (currency) { "USD" -> "$"; "EUR" -> "€"; "GBP" -> "£"; "CNY" -> "¥"; "JPY" -> "¥"; else -> "$currency " }
        return "$sign${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
    }
}

/**
 * What the join screen adds under "all places are taken" when a new device is turned away. The way out that costs nothing (remove one
 * on a device already on the account) is always there; the way out that costs money is there only as a link the service gave: [upgradeUrl]
 * is the service's own address for paying on behalf of the account being joined (this phone has no sign-in yet, so the app's own
 * checkout cannot be used), and it is a secure address or nothing.
 */
data class DeviceFullHelp(val upgradeUrl: String?)

/**
 * Null for any other failure. [offering]: this build sells and the service has not said nothing can be bought. When the service left
 * no link out (nothing to buy, or the account is already Paid) or it is not a secure address, there is no [DeviceFullHelp.upgradeUrl]
 * and only the removing half is shown.
 */
fun deviceFullHelp(e: Throwable?, offering: Boolean): DeviceFullHelp? {
    if (e !is ApiError || e.code != "DEVICE_LIMIT_REACHED") return null
    val url = e.data["upgrade_url"]?.jsonPrimitive?.contentOrNull?.trim()
    return DeviceFullHelp(if (offering && canOpenLink(url, httpsOnly = true)) url else null)
}
