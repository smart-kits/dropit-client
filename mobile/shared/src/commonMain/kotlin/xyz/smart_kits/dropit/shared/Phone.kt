package xyz.smart_kits.dropit.shared

/**
 * Which maker's ways a phone follows, for the one setup screen that differs by brand: what the share sheet
 * does and whether a tap on the back can reach dropit. Anything not listed is treated as plain Android —
 * its wording is the most general, so it is the safe one to show when the maker is not recognized.
 */
enum class PhoneFamily {
    /** Plain Android and Pixel; also Honor, Transsion, Motorola and anything unrecognized */
    Stock,
    Samsung,
    Xiaomi,
    Oppo,
    Vivo;

    companion object {
        /** `manufacturer` and `brand` as the system reports them; either may name the family */
        fun of(manufacturer: String?, brand: String?): PhoneFamily {
            val names = listOf(manufacturer, brand).map { it.orEmpty().trim().lowercase() }
            fun any(vararg keys: String) = names.any { n -> keys.any { n == it || n.startsWith("$it ") } }
            return when {
                any("samsung") -> Samsung
                any("xiaomi", "redmi", "poco") -> Xiaomi
                any("oppo", "oneplus", "realme") -> Oppo
                any("vivo", "iqoo") -> Vivo
                else -> Stock
            }
        }
    }
}
