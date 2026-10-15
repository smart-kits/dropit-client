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
    val free = PlanLimits(days = 1, spaceMB = 30, fileMB = 10, devices = 3, perDay = 30, queue = 100, realtimeTrialDays = 10)
    val paid = PlanLimits(days = 10, spaceMB = 500, fileMB = 100, devices = 10, perDay = 300, queue = 1000, realtimeTrialDays = null)

    // PRICE_YEAR is the yearly subscription; an account's first one starts with a month at PRICE_TRIAL.
    // One year is paid once and doesn't renew; lifetime is paid once, for good
    const val PRICE_TRIAL = 1.9
    const val PRICE_YEAR = 19.9
    const val PRICE_YEAR_ONCE = 29.9
    const val PRICE_LIFE = 49.9

    /** How much the subscription costs less than the same year paid once, in whole percent: the number on the sale sticker */
    val yearSaves: Int get() = ((1 - PRICE_YEAR / PRICE_YEAR_ONCE) * 100 + .5).toInt()

    /** Space as it is written, the way the website writes it: "30 MB", "1 GB" */
    fun space(mb: Int): String = if (mb % 1024 == 0) "${mb / 1024} GB" else "$mb MB"

    /** A price as it is written, always with cents, the way the website and checkout write it: "19.90" */
    fun price(v: Double): String {
        val cents = (v * 100 + .5).toLong()
        return "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
    }
}
