package xyz.smart_kits.dropit.shared

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** "Pull again": three steps, the request each one makes, and what of a span is kept */
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

    @Test fun `U-R1b each span asks for what it needs`() {
        assertEquals("0", repullReset(RepullSpan.ALL).body!!.getValue("to_seq").jsonPrimitive.content)
        assertEquals("50", repullReset(RepullSpan.LAST_50).body!!.getValue("last").jsonPrimitive.content)
        // days start from the beginning too: the service has no "days"; they are cut on this side
        assertEquals("0", repullReset(RepullSpan.DAYS_3).body!!.getValue("to_seq").jsonPrimitive.content)
    }

    @Test fun `U-R2 three days keeps only what arrived in the last three days`() {
        val now = 10 * DAY_MS
        val items = listOf(item(1, now - 4 * DAY_MS), item(2, now - 3 * DAY_MS), item(3, now - DAY_MS), item(4, now))
        assertEquals(listOf(2L, 3L, 4L), keepForSpan(items, RepullSpan.DAYS_3, now).map { it.seq })
        // the others keep everything that came back
        assertEquals(items, keepForSpan(items, RepullSpan.ALL, now))
        assertEquals(items, keepForSpan(items, RepullSpan.LAST_50, now))
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

    @Test fun `the spans are the ones the settings row names`() {
        assertEquals(listOf(RepullSpan.ALL, RepullSpan.LAST_50, RepullSpan.DAYS_3), RepullSpan.entries)
        assertNull(RepullSpan.ALL.last)
        assertEquals(50, RepullSpan.LAST_50.last)
        assertEquals(3, RepullSpan.DAYS_3.days)
    }
}
