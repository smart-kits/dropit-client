package xyz.smart_kits.dropit.shared

import kotlin.test.Test
import kotlin.test.assertEquals

// The prices themselves are checked against the website by the website's tests; this is how they're written
class PlansTest {
    @Test
    fun pricesAreWrittenWithCents() {
        assertEquals("1.90", Plans.price(1.9))
        assertEquals("19.90", Plans.price(19.9))
        assertEquals("49.90", Plans.price(49.9))
        assertEquals("3.00", Plans.price(3.0))
        assertEquals("1.66", Plans.price(19.9 / 12))
    }

    @Test
    fun spaceIsWrittenInMegabytesOrWholeGigabytes() {
        assertEquals("30 MB", Plans.space(30))
        assertEquals("500 MB", Plans.space(500))
        assertEquals("1 GB", Plans.space(1024))
    }

    @Test
    fun theStickerIsWorkedOutFromThePrices() {
        assertEquals(33, Plans.yearSaves)   // 1 - 19.9 / 29.9 = 33.4%
    }
}
