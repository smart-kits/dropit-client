package xyz.smart_kits.dropit.shared

// What each plan gets and what it costs, for the plan table and the upgrade page. The service enforces the
// limits whatever is written here; these are the same numbers the website shows, and a test fails if the
// two drift apart. Prices are in US dollars; checkout adds local tax.

data class PlanLimits(
    val days: Int,
    val spaceMB: Int,
    val fileMB: Int,
    val devices: Int,
    val perDay: Int,
    val queue: Int,
    /** Days of real-time delivery a free account starts with; null: always on */
    val realtimeTrialDays: Int?,
)

object Plans {
    val free = PlanLimits(days = 1, spaceMB = 30, fileMB = 5, devices = 3, perDay = 30, queue = 100, realtimeTrialDays = 10)
    val paid = PlanLimits(days = 10, spaceMB = 500, fileMB = 100, devices = 10, perDay = 300, queue = 1000, realtimeTrialDays = null)

    // One month and one year are paid once and don't renew; PRICE_YEAR is the yearly subscription
    const val PRICE_MONTH = 2.99
    const val PRICE_YEAR_ONCE = 24.9
    const val PRICE_YEAR = 14.9
    const val PRICE_LIFE = 39.9

    /** How much the subscription costs less than the same year paid once, in whole percent: the number on the sale sticker */
    val yearSaves: Int get() = ((1 - PRICE_YEAR / PRICE_YEAR_ONCE) * 100 + .5).toInt()

    /** A price as it is written: "9.9", "39.9" */
    fun price(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else ((v * 100 + .5).toLong() / 100.0).toString()
}
