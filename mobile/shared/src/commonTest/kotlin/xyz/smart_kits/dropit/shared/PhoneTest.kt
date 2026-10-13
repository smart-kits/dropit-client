package xyz.smart_kits.dropit.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneTest {
    @Test fun `each maker lands in its family by either name in any case`() {
        assertEquals(PhoneFamily.Samsung, PhoneFamily.of("samsung", "samsung"))
        assertEquals(PhoneFamily.Xiaomi, PhoneFamily.of("Xiaomi", "Redmi"))
        assertEquals(PhoneFamily.Xiaomi, PhoneFamily.of("Xiaomi", "POCO"))
        assertEquals(PhoneFamily.Xiaomi, PhoneFamily.of("unknown", "redmi"))
        assertEquals(PhoneFamily.Oppo, PhoneFamily.of("OPPO", "OPPO"))
        assertEquals(PhoneFamily.Oppo, PhoneFamily.of("OnePlus", "OnePlus"))
        assertEquals(PhoneFamily.Oppo, PhoneFamily.of("realme", "realme"))
        assertEquals(PhoneFamily.Vivo, PhoneFamily.of("vivo", "iQOO"))
    }

    @Test fun `anything else is plain Android`() {
        assertEquals(PhoneFamily.Stock, PhoneFamily.of("Google", "google"))
        assertEquals(PhoneFamily.Stock, PhoneFamily.of("HONOR", "HONOR"))
        assertEquals(PhoneFamily.Stock, PhoneFamily.of("TECNO", "TECNO"))
        assertEquals(PhoneFamily.Stock, PhoneFamily.of("motorola", "motorola"))
        assertEquals(PhoneFamily.Stock, PhoneFamily.of(null, null))
        // A name that merely starts the same is not that maker
        assertEquals(PhoneFamily.Stock, PhoneFamily.of("vivobook", ""))
    }
}
