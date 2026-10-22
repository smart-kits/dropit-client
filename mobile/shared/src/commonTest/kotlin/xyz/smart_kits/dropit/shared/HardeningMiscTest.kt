package xyz.smart_kits.dropit.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Small rules found by review: who a send is said to have gone to, when downloads start again, the clipboard marked sensitive, the service that holds the connection */
class HardeningMiscTest {
    // ── who it went to ──
    @Test fun `H-M1 no ids is everyone and a name for every id is said as names`() {
        assertEquals(TargetWords.All, targetWordsOf(emptyList(), emptyList()))
        assertEquals(TargetWords.All, targetWordsOf(emptyList(), listOf("stale name")))
        assertEquals(TargetWords.Names("Mac"), targetWordsOf(listOf("d_a"), listOf("Mac")))
        assertEquals(TargetWords.Names("Mac、Linux"), targetWordsOf(listOf("d_a", "d_b"), listOf("Mac", "Linux")))
        assertEquals(TargetWords.Count(3), targetWordsOf(listOf("d_a", "d_b", "d_c"), listOf("A", "B", "C")))
    }

    @Test fun `H-M2 fewer names than ids says how many and never everyone`() {
        // Aimed at two devices of which no name was known: not "everyone"
        assertEquals(TargetWords.Count(2), targetWordsOf(listOf("d_a", "d_b"), emptyList()))
        // Two devices and one name: not that one name
        assertEquals(TargetWords.Count(2), targetWordsOf(listOf("d_a", "d_b"), listOf("Mac")))
        assertEquals(TargetWords.Count(1), targetWordsOf(listOf("d_a"), emptyList()))
        // More names than ids is not to be believed either
        assertEquals(TargetWords.Count(1), targetWordsOf(listOf("d_a"), listOf("Mac", "Linux")))
    }

    @Test fun `H-M3 the words follow the separator given`() {
        assertEquals(TargetWords.Names("Mac, Linux"), targetWordsOf(listOf("a", "b"), listOf("Mac", "Linux"), ", "))
    }

    // ── what the plan keeps ──
    @Test fun `H-M4 how long things are kept is only said when it is known whether the account is paid`() {
        assertEquals(1 * DAY_MS, keepMs(paid = false, freeDays = 1, paidDays = 10))
        assertEquals(10 * DAY_MS, keepMs(paid = true, freeDays = 1, paidDays = 10))
        assertNull(keepMs(paid = null, freeDays = 1, paidDays = 10))
    }

    // ── when downloads start again ──
    @Test fun `H-M5 when downloads start again is worded by the phone's own day`() {
        val hour = 3_600_000L
        val midnightUtc = 20_000L * DAY_MS
        val now = midnightUtc + 10 * hour           // 10:00 UTC
        assertEquals(LimitWhen.UNKNOWN, limitWhen(null, now, 0))
        assertEquals(LimitWhen.UNKNOWN, limitWhen(now, now, 0))
        assertEquals(LimitWhen.UNKNOWN, limitWhen(now - 1, now, 0))
        assertEquals(LimitWhen.TODAY, limitWhen(now + 2 * hour, now, 0))
        assertEquals(LimitWhen.MORNING, limitWhen(midnightUtc + 24 * hour + 8 * hour, now, 0))
        assertEquals(LimitWhen.TOMORROW, limitWhen(midnightUtc + 24 * hour + 13 * hour, now, 0))
    }

    @Test fun `H-M6 the time zone moves where the day ends`() {
        val hour = 3_600_000L
        val midnightUtc = 20_000L * DAY_MS
        val now = midnightUtc + 20 * hour            // 20:00 UTC
        val until = midnightUtc + 24 * hour          // midnight UTC
        val plus8 = 8 * hour
        assertEquals(LimitWhen.MORNING, limitWhen(until, now, 0))                 // tomorrow 00:00 in UTC
        // At +8 it is 04:00 the next day for now and 08:00 for until: now is already tomorrow 04:00, until is the same day
        assertEquals(LimitWhen.TODAY, limitWhen(until, now, plus8))
        // At -5 it is 15:00 for now and 19:00 for until, the same day
        assertEquals(LimitWhen.TODAY, limitWhen(until, now, -5 * hour))
    }

    // ── the clipboard marked sensitive ──
    @Test fun `H-M7 the clipboard is sent in one step only when it has something and was not marked sensitive`() {
        assertEquals(ClipStep.SEND, clipStep(hasContent = true, sensitive = false))
        assertEquals(ClipStep.SENSITIVE, clipStep(hasContent = true, sensitive = true))
        assertEquals(ClipStep.EMPTY, clipStep(hasContent = false, sensitive = false))
        assertEquals(ClipStep.EMPTY, clipStep(hasContent = false, sensitive = true))
    }

    // ── the service that holds the connection ──
    @Test fun `H-M8 the connection service stops instead of crashing when the system would not make it a foreground service`() {
        assertEquals(ServiceNext.KEEP, liveServiceNext(foreground = true, receiveOn = true, signedIn = true))
        assertEquals(ServiceNext.STOP, liveServiceNext(foreground = false, receiveOn = true, signedIn = true))
        assertEquals(ServiceNext.STOP, liveServiceNext(foreground = true, receiveOn = false, signedIn = true))
        assertEquals(ServiceNext.STOP, liveServiceNext(foreground = true, receiveOn = true, signedIn = false))
        assertEquals(ServiceNext.STOP, liveServiceNext(foreground = false, receiveOn = false, signedIn = false))
    }
}
