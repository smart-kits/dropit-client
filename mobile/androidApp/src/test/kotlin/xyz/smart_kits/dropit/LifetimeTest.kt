package xyz.smart_kits.dropit

import kotlin.test.Test
import kotlin.test.assertEquals

/** A plan that never ends is written as "Lifetime" because the service says so, never because of what the end date looks like */
class LifetimeTest {
    private val jan2100 = 4_102_444_800_000L

    @Test fun lifetimeByKind() = assertEquals(true, isLifetime(true, "life", 1_800_000_000_000L))
    @Test fun noEndDateIsLifetime() = assertEquals(true, isLifetime(true, null, null))
    @Test fun aFarDateAloneIsNotLifetime() {
        assertEquals(false, isLifetime(true, null, jan2100))
        assertEquals(false, isLifetime(true, "year", jan2100))
    }
    @Test fun aYearIsNot() = assertEquals(false, isLifetime(true, "year", 1_800_000_000_000L))
    @Test fun freeIsNever() = assertEquals(false, isLifetime(false, "life", jan2100))
}
