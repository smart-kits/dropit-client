package xyz.smart_kits.dropit.shared

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Pull again": the plan (newest N within D days), what the account may choose, the request, and what is kept */
class RepullTest {
    private fun item(seq: Long, at: Long) = Incoming(seq = seq, at = at)

    @Test fun `U-R1 the requests - from the beginning or the newest N`() {
        val all = Requests.resetCursor(toSeq = 0)
        assertEquals("POST", all.method)
        assertEquals("/v1/cursor/reset", all.path)
        assertEquals("0", all.body!!.getValue("to_seq").jsonPrimitive.content)
        assertFalse("last" in all.body!!)

        val last = Requests.resetCursor(last = 50)
        assertEquals("50", last.body!!.getValue("last").jsonPrimitive.content)
        assertFalse("to_seq" in last.body!!)
    }

    @Test fun `U-R1b a plan asks the service for the newest N only`() {
        assertEquals("10", repullReset(RepullPlan()).body!!.getValue("last").jsonPrimitive.content)
        assertEquals("50", repullReset(RepullPlan(50, 3)).body!!.getValue("last").jsonPrimitive.content)
    }

    @Test fun `U-R2 the default is the newest 10 within one day`() {
        assertEquals(RepullPlan(10, 1), RepullPlan())
        val now = 10 * DAY_MS
        // 12 items, the newest 11 within a day: the newest 10 of them
        val items = (1L..12L).map { item(it, now - DAY_MS + it * 1000) } + item(0, now - 2 * DAY_MS)
        assertEquals((3L..12L).toList(), keepForPlan(items, RepullPlan(), now).map { it.seq })
    }

    @Test fun `U-R2b both limits at once - the days cut and the count cut`() {
        val now = 10 * DAY_MS
        val items = listOf(item(1, now - 4 * DAY_MS), item(2, now - 3 * DAY_MS), item(3, now - DAY_MS), item(4, now))
        assertEquals(listOf(2L, 3L, 4L), keepForPlan(items, RepullPlan(10, 3), now).map { it.seq })   // three days keeps the newer three
        assertEquals(listOf(3L, 4L), keepForPlan(items, RepullPlan(2, 3), now).map { it.seq })        // and then only the newest two
    }

    @Test fun `U-R2c the count slider runs 1 to 50 and starts at 10`() {
        assertEquals(1, REPULL_MIN_COUNT)
        assertEquals(50, REPULL_MAX_COUNT)
        assertEquals(10, REPULL_DEFAULT_COUNT)
        val now = 10 * DAY_MS
        val items = (1L..80L).map { item(it, now) }
        assertEquals(50, keepForPlan(items, RepullPlan(50, 3), now).size)
        assertEquals(1, keepForPlan(items, RepullPlan(1, 3), now).size)
        assertEquals(50, repullPlanOf(500, 3, 10).count)    // past the end: 50
        assertEquals(1, repullPlanOf(0, 1, 10).count)       // 0 would bring back nothing: 1
        assertEquals(37, repullPlanOf(37, 1, 10).count)     // any step between
    }

    @Test fun `U-R2d the days slider - a free account stays at one day a paid one goes as far as it keeps`() {
        assertEquals(1, REPULL_DEFAULT_DAYS)
        assertEquals(10, repullTrackDays(10))               // the track is as long as a paid plan keeps (the service's number)
        assertEquals(1, repullTrackDays(null))              // not known: just one day
        assertEquals(1, repullDaysLimit(paid = false, keptDays = 1, paidDays = 10))    // free: one, though the track shows ten
        assertEquals(1, repullDaysLimit(paid = false, keptDays = 10, paidDays = 10))
        assertEquals(10, repullDaysLimit(paid = true, keptDays = 10, paidDays = 10))
        assertEquals(7, repullDaysLimit(paid = true, keptDays = 7, paidDays = 10))     // kept less than the plan: the account's number
        assertEquals(10, repullDaysLimit(paid = true, keptDays = 30, paidDays = 10))   // never past the track
        assertEquals(10, repullDaysLimit(paid = true, keptDays = null, paidDays = 10))
        assertEquals(1, repullPlanOf(10, 5, 1).days)         // a free account cannot ask for more
        assertEquals(10, repullPlanOf(10, 99, 10).days)
        assertEquals(1, repullPlanOf(10, 0, 10).days)
    }

    @Test fun `U-R2e the days slider can be moved only by an account that may choose and says why when it cannot`() {
        assertFalse(repullDaysMovable(1))
        assertTrue(repullDaysMovable(2))
        assertTrue(repullDaysShown(10))
        assertFalse(repullDaysShown(1))                      // no longer track to show
        assertFalse(repullDaysShown(null))
        assertTrue(repullKeptHintFor(daysLimit = 1, paidDays = 10))    // free, with a track to point at: touching it explains
        assertFalse(repullKeptHintFor(daysLimit = 10, paidDays = 10))  // paid: it moves, nothing to explain
        assertFalse(repullKeptHintFor(daysLimit = 1, paidDays = null)) // no slider drawn
    }

    @Test fun `U-R2f the hint has a plans button only where paying is on offer`() {
        assertTrue(repullHintHasButton(paymentsBuild = true, offering = true))
        assertFalse(repullHintHasButton(paymentsBuild = false, offering = true))   // the store build
        assertFalse(repullHintHasButton(paymentsBuild = true, offering = false))   // paying is closed
    }

    @Test fun `U-R2g the plans button comes from a place the service already counts`() {
        assertEquals("settings", UpgradeFrom.SETTINGS)
        assertTrue("kept" in UpgradeFrom.WHYS)
    }

    @Test fun `U-R3 one pull again a minute`() {
        assertEquals(0L, repullWaitMs(0L, 1_000_000))                         // never done
        assertEquals(60_000L, repullWaitMs(1_000_000, 1_000_000))              // just now
        assertEquals(20_000L, repullWaitMs(1_000_000, 1_040_000))
        assertEquals(0L, repullWaitMs(1_000_000, 1_060_000))
        assertEquals(0L, repullWaitMs(1_000_000, 2_000_000))
        // a clock that went back does not lock it for longer than the minute
        assertEquals(60_000L, repullWaitMs(1_000_000, 500_000))
    }
}
