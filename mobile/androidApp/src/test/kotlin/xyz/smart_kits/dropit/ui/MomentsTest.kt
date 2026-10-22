package xyz.smart_kits.dropit.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import xyz.smart_kits.dropit.Me
import xyz.smart_kits.dropit.shared.Catalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When the notes about the plan show, and when they keep quiet: periods, priority, the edges of every trigger, and a memory that fails */
class MomentsTest {
    private val day = DAY_MS
    // Noon on a UTC day, so "a bit later today" never crosses midnight
    private val now = 20_000L * DAY_MS + 12 * 3_600_000L

    private class Mem : MomentMemory {
        val kept = HashMap<String, String>()
        override fun get(key: String): String? = kept[key]
        override fun put(key: String, value: String) { kept[key] = value }
    }

    /** Neither reads nor writes: the file can't be opened */
    private class Broken : MomentMemory {
        override fun get(key: String): String? = throw IllegalStateException("no storage")
        override fun put(key: String, value: String) = throw IllegalStateException("no storage")
    }

    private fun me(
        plan: String = "free", realtimeUntil: Long? = null, paidUntil: Long? = null, paidKind: String? = null, renews: Boolean? = null,
        graceUntil: Long? = null, itemsToday: Int? = null, perDay: Int? = 20, itemsTotal: Int? = null, devices: Int = 1,
        bytesUsed: Long = 0, bytesLimit: Long = 30L shl 20, createdAt: Long? = null, trialDays: Int? = null, trialAvailable: Boolean = true,
    ) = Me(
        plan = plan, devicesUsed = devices, devicesLimit = 3, itemBytesLimit = 10L shl 20, bytesUsed = bytesUsed, bytesLimit = bytesLimit,
        paidKind = paidKind, paidUntil = paidUntil, upgradeUrl = null, trialAvailable = trialAvailable, realtimeUntil = realtimeUntil,
        createdAt = createdAt, trialDays = trialDays, itemsTotal = itemsTotal, itemsToday = itemsToday, itemsPerDay = perDay,
        paidGraceUntil = graceUntil, renews = renews,
    )

    private fun moments(mem: MomentMemory = Mem()) = Moments(mem)

    // ── the trial ───────────────────────────────────────────────────────────

    @Test fun `the trial's stages change exactly at three days, one day and the end`() {
        val end = now + 10 * day
        fun stage(left: Long) = trialStage(me(realtimeUntil = now + left), now)
        assertEquals(TrialStage.RUNNING, stage(3 * day + 1))
        assertEquals(TrialStage.T1, stage(3 * day))
        assertEquals(TrialStage.T1, stage(day + 1))
        assertEquals(TrialStage.T2, stage(day))
        assertEquals(TrialStage.T2, stage(1))
        assertEquals(TrialStage.OVER, stage(0))
        assertEquals(TrialStage.OVER, stage(-day))
        assertEquals(TrialStage.NONE, trialStage(me(), now))                                    // not said: no stage
        assertEquals(TrialStage.NONE, trialStage(me(plan = "paid", realtimeUntil = end), now))   // paid: nothing about the trial
    }

    @Test fun `a started day counts as a day left`() {
        assertEquals(1, daysLeft(1))
        assertEquals(1, daysLeft(day))
        assertEquals(2, daysLeft(day + 1))
        assertEquals(3, daysLeft(3 * day))
    }

    @Test fun `the card for the last days comes once per end of trial and waving it away is remembered`() {
        val m = moments()
        val until = now + 2 * day
        val account = me(realtimeUntil = until)
        val card = assertNotNull(m.homeCard(account, now))
        assertEquals(Card(CardKind.T1, until.toString()), card)
        assertEquals(card, m.homeCard(account, now))                       // not dismissed: it stays
        m.dismiss(card)
        assertNull(m.homeCard(account, now))
        assertNull(m.homeCard(account, now + 1000))
        // a new end of trial is a new period
        assertNotNull(m.homeCard(me(realtimeUntil = until + 1), now))
    }

    @Test fun `the last day has its own card, and puts the first one's words in its place when that is still up`() {
        val m = moments()
        val until = now + 2 * day
        // the first card was shown and not dismissed; a day later the same card is there, now the last day's
        val first = assertNotNull(m.homeCard(me(realtimeUntil = until), now))
        assertEquals(CardKind.T1, first.kind)
        val later = assertNotNull(m.homeCard(me(realtimeUntil = until), until - day / 2))
        assertEquals(Card(CardKind.T2, "$until.t2"), later)
        // the first card was waved away: the last day still says itself once
        m.dismiss(first)
        assertEquals(CardKind.T2, m.homeCard(me(realtimeUntil = until), until - day / 2)?.kind)
        m.dismiss(later)
        assertNull(m.homeCard(me(realtimeUntil = until), until - day / 2))
    }

    @Test fun `the card for the day the trial ended shows once, and only in the days after`() {
        val m = moments()
        val until = now - 1
        val card = assertNotNull(m.homeCard(me(realtimeUntil = until), now))
        assertEquals(Card(CardKind.T3, until.toString()), card)
        m.dismiss(card)
        assertNull(m.homeCard(me(realtimeUntil = until), now))
        // an account whose trial ended long ago is not told it just did
        assertNull(moments().homeCard(me(realtimeUntil = now - 8 * day), now))
        assertNotNull(moments().homeCard(me(realtimeUntil = now - 7 * day), now))
    }

    @Test fun `a trial with more than three days left has no card, only the line in settings`() {
        val account = me(realtimeUntil = now + 4 * day + 1)
        assertNull(moments().homeCard(account, now))
        assertEquals(Line(LineKind.T0, days = 5), moments().settingsLine(account, now))
        assertEquals(Line(LineKind.T0, days = 1), moments().settingsLine(me(realtimeUntil = now + 100), now))
        assertNull(moments().settingsLine(me(realtimeUntil = now - 1), now))      // ended: the receive row says it, not this line
    }

    @Test fun `a limit that was just met comes before every card`() {
        val m = moments()
        assertNull(m.homeCard(me(realtimeUntil = now + day), now, wallShowing = true))
        assertNotNull(m.homeCard(me(realtimeUntil = now + day), now, wallShowing = false))
    }

    @Test fun `a paid account sees nothing about the free plan`() {
        val m = moments()
        val paid = me(plan = "paid", paidKind = "year", paidUntil = now + 100 * day, renews = true, realtimeUntil = now + day, itemsToday = 19)
        assertNull(m.homeCard(paid, now))
        assertNull(m.nearLine(paid, now, now))
        assertFalse(m.pressure(paid, now, now, null))
        assertEquals(TrialStage.NONE, trialStage(paid, now))
        assertEquals(Tier.NONE, itemsTier(paid, now, now))
        assertFalse(spaceNear(me(plan = "paid", bytesUsed = 29L shl 20)))
    }

    // ── the paid plan ───────────────────────────────────────────────────────

    @Test fun `a paid plan that does not renew says so in its last week, once per end date`() {
        val m = moments()
        fun soon(left: Long, renews: Boolean? = false, kind: String? = "year") =
            me(plan = "paid", paidKind = kind, paidUntil = now + left, renews = renews)
        assertNull(m.homeCard(soon(7 * day + 1), now))
        val card = assertNotNull(m.homeCard(soon(7 * day), now))
        assertEquals(CardKind.P1, card.kind)
        assertEquals((now + 7 * day).toString(), card.period)
        assertNotNull(m.homeCard(soon(1), now))
        assertNull(m.homeCard(soon(0), now))                    // already over
        assertNull(m.homeCard(soon(3 * day, renews = true), now))   // renews by itself: the payment service writes before it charges
        assertNull(m.homeCard(soon(3 * day, kind = "life"), now))
        m.dismiss(card)
        assertNull(m.homeCard(soon(7 * day), now))
        // a renewed plan has a new end date, so a new period
        assertNotNull(m.homeCard(soon(6 * day), now))
        // only the line stays
        assertEquals(LineKind.P1, m.settingsLine(soon(3 * day), now)?.kind)
    }

    @Test fun `a plan that renews by itself has a quiet line and no card`() {
        val m = moments()
        val account = me(plan = "paid", paidKind = "year", paidUntil = now + 2 * day, renews = true)
        assertNull(m.homeCard(account, now))
        assertEquals(LineKind.P1B, m.settingsLine(account, now)?.kind)
        assertNull(m.settingsLine(me(plan = "paid", paidKind = "life"), now))
    }

    @Test fun `going from free to paid makes the card for what is now there, once, and not for an account that was always paid`() {
        val m = moments()
        val paid = me(plan = "paid", paidKind = "year", paidUntil = now + 365 * day, renews = true)
        // the first answer this phone ever sees is paid: nothing to celebrate
        m.observe(paid, now)
        assertNull(m.homeCard(paid, now))

        val m2 = moments()
        m2.observe(me(), now)                                   // free
        assertNull(m2.homeCard(paid, now))                       // nothing until the answer says paid is observed
        m2.observe(paid, now)
        val card = assertNotNull(m2.homeCard(paid, now))
        assertEquals(Card(CardKind.P4, (now + 365 * day).toString()), card)
        // the card stays until it is put away; then not again, even after more answers
        m2.observe(paid, now + 1000)
        assertEquals(card, m2.homeCard(paid, now))
        m2.dismiss(card)
        assertNull(m2.homeCard(paid, now))
        m2.observe(paid, now + 2000)
        assertNull(m2.homeCard(paid, now))
    }

    @Test fun `whether the first month was on offer before paying is kept for the card's price words`() {
        val m = moments()
        m.observe(me(trialAvailable = true), now)
        m.observe(me(plan = "paid", paidKind = "year", paidUntil = now + day, renews = true, trialAvailable = false), now)
        assertTrue(m.firstMonthWasOffered())
        val n = moments()
        n.observe(me(trialAvailable = false), now)
        n.observe(me(plan = "paid", paidKind = "year", paidUntil = now + day, renews = true, trialAvailable = false), now)
        assertFalse(n.firstMonthWasOffered())
    }

    @Test fun `a lifetime plan that just began has its card with the period life`() {
        val m = moments()
        m.observe(me(), now)
        val life = me(plan = "paid", paidKind = "life", paidUntil = null)
        m.observe(life, now)
        assertEquals(Card(CardKind.P4, "life"), m.homeCard(life, now))
    }

    @Test fun `a paid plan that ended is told once, while its terms are still kept, and never to an account that was not paid`() {
        val ended = now - 1000
        val grace = now + 9 * day
        val m = moments()
        m.observe(me(plan = "paid", paidKind = "year", paidUntil = ended + 100, renews = false), now - day)
        val account = me(paidUntil = ended, graceUntil = grace, itemsTotal = 40)
        val card = assertNotNull(m.homeCard(account, now))
        assertEquals(Card(CardKind.P2, ended.toString()), card)
        m.dismiss(card)
        assertNull(m.homeCard(account, now))
        // after the days are over it is no longer told (the sentence about "until" would be wrong)
        assertNull(moments().also { it.observe(me(plan = "paid", paidUntil = 1), now - day) }.homeCard(me(paidUntil = ended, graceUntil = now - 1), now))
        // never paid: nothing
        assertNull(moments().homeCard(me(graceUntil = null), now))
        assertEquals(null, moments().settingsLine(me(), now))
    }

    @Test fun `what is left of a paid plan that ended is a line, and a refund ends it with one short sentence`() {
        val m = moments()
        val grace = now + 5 * day
        assertEquals(Line(LineKind.P3, until = grace), m.settingsLine(me(paidUntil = now - day, graceUntil = grace), now))
        // after the days: nothing more (the service stops giving the time)
        assertNull(m.settingsLine(me(paidUntil = now - 20 * day, graceUntil = now - 10 * day), now))
        // refunded: free, no days left, and the account had been paid
        assertEquals(LineKind.P5, m.settingsLine(me(paidUntil = now - day, graceUntil = null), now)?.kind)
        val seenPaid = moments().also { it.observe(me(plan = "paid", paidUntil = now + day), now - day) }
        assertEquals(LineKind.P5, seenPaid.settingsLine(me(graceUntil = null), now)?.kind)
        // and a refund is never a card
        assertNull(seenPaid.homeCard(me(graceUntil = null), now))
    }

    @Test fun `the card of a paid plan comes before the trial's, and a limit before both`() {
        val m = moments()
        m.observe(me(plan = "paid", paidUntil = now - 1000), now - day)
        val both = me(paidUntil = now - 1000, graceUntil = now + 5 * day, realtimeUntil = now - 1)
        assertEquals(CardKind.P2, m.homeCard(both, now)?.kind)
        assertNull(m.homeCard(both, now, wallShowing = true))
        m.dismiss(Card(CardKind.P2, (now - 1000).toString()))
        assertEquals(CardKind.T3, m.homeCard(both, now)?.kind)
    }

    // ── a limit that is close ───────────────────────────────────────────────

    @Test fun `the day's items are said from eighty percent, and from one before the last they say what Paid gives`() {
        fun tier(today: Int, per: Int = 20) = itemsTier(me(itemsToday = today, perDay = per), now, now)
        assertEquals(Tier.NONE, tier(15))
        assertEquals(Tier.BASE, tier(16))
        assertEquals(Tier.BASE, tier(18))
        assertEquals(Tier.WARN, tier(19))
        assertEquals(Tier.NONE, tier(20))        // used up: that is a limit met, not a preview
        assertEquals(Tier.NONE, tier(25))
        // the threshold is worked out from the service's number
        assertEquals(Tier.NONE, tier(23, per = 30))
        assertEquals(Tier.BASE, tier(24, per = 30))
        assertEquals(Tier.WARN, tier(29, per = 30))
        assertEquals(Tier.NONE, tier(7, per = 10))
        assertEquals(Tier.BASE, tier(8, per = 10))
        assertEquals(Tier.WARN, tier(9, per = 10))
        assertEquals(Tier.WARN, tier(5, per = 6))     // 80 % of 6 is 4.8, so from 5, which is also one before the last
        // not said by the service: no preview
        assertEquals(Tier.NONE, itemsTier(me(itemsToday = null), now, now))
        assertEquals(Tier.NONE, itemsTier(me(itemsToday = 19, perDay = null), now, now))
        assertEquals(Tier.NONE, itemsTier(me(itemsToday = 19, perDay = 0), now, now))
    }

    @Test fun `what was sent since the last answer counts, and yesterday's count does not`() {
        val account = me(itemsToday = 14)
        assertEquals(Tier.NONE, itemsTier(account, now, now, sentSince = 1))
        assertEquals(Tier.BASE, itemsTier(account, now, now, sentSince = 2))
        assertEquals(Tier.WARN, itemsTier(account, now, now, sentSince = 5))
        // asked yesterday evening, it is a new UTC day: only what was sent today counts
        val yesterday = now - day
        assertEquals(0, todayCount(account, yesterday, now))
        assertEquals(3, todayCount(account, yesterday, now, sentSince = 3))
        assertEquals(14, todayCount(account, now - 1000, now))
        assertEquals(14, todayCount(account, 0, now))    // when the time of the answer is not known, it is taken as today's
        assertNull(todayCount(me(itemsToday = null), now, now))
    }

    @Test fun `the line beside a send is said once per UTC day and tier`() {
        val m = moments()
        val account = me(itemsToday = 16)
        assertEquals(Near(NearKind.ITEMS, "${utcDay(now)}.a"), m.nearLine(account, now, now))
        assertNotNull(m.takeNear(account, now, now))                 // said (and marked)
        assertNull(m.nearLine(account, now, now))
        assertNull(m.nearLine(me(itemsToday = 18), now, now))        // same tier, same day
        // the tier that says what Paid gives is one more line that day
        val warn = assertNotNull(m.takeNear(me(itemsToday = 19), now, now))
        assertEquals(NearKind.ITEMS_WARN, warn.near.kind)
        assertEquals(19, warn.today)
        assertEquals(20, warn.perDay)
        assertNull(m.nearLine(me(itemsToday = 19), now, now))
        // next day: again
        val tomorrow = now + day
        assertEquals(NearKind.ITEMS, m.nearLine(me(itemsToday = 16), tomorrow, tomorrow)?.kind)
    }

    @Test fun `seeing the last tier first covers the first one too`() {
        val m = moments()
        m.takeNear(me(itemsToday = 19), now, now)
        assertNull(m.nearLine(me(itemsToday = 17), now, now))
    }

    @Test fun `the space is said from eighty percent and not when full`() {
        val limit = 30L shl 20
        assertFalse(spaceNear(me(bytesUsed = (24L shl 20) - 1, bytesLimit = limit)))
        assertTrue(spaceNear(me(bytesUsed = 24L shl 20, bytesLimit = limit)))
        assertTrue(spaceNear(me(bytesUsed = limit - 1, bytesLimit = limit)))
        assertFalse(spaceNear(me(bytesUsed = limit, bytesLimit = limit)))          // full: a limit met
        assertFalse(spaceNear(me(bytesUsed = 1, bytesLimit = 0)))                   // no limit said
        assertTrue(spaceNear(me(bytesUsed = 20L shl 20, bytesLimit = limit), extraBytes = 5L shl 20))
        val m = moments()
        val near = me(bytesUsed = 25L shl 20, bytesLimit = limit)
        val view = assertNotNull(m.takeNear(near, now, now))
        assertEquals(NearKind.SPACE, view.near.kind)
        assertEquals(25L shl 20, view.usedBytes)
        assertNull(m.nearLine(near, now, now))                                        // once that day
        assertNotNull(m.nearLine(near, now + day, now + day))
    }

    @Test fun `the items line comes before the space line, and the space line follows once the items have been said`() {
        val m = moments()
        val both = me(itemsToday = 16, bytesUsed = 25L shl 20)
        assertEquals(NearKind.ITEMS, m.takeNear(both, now, now)?.near?.kind)
        assertEquals(NearKind.SPACE, m.takeNear(both, now, now)?.near?.kind)
        assertNull(m.takeNear(both, now, now))
    }

    @Test fun `the pill moves only under pressure`() {
        val m = moments()
        val calm = me(itemsToday = 3, realtimeUntil = now + 20 * day)
        assertFalse(m.pressure(calm, now, now, null))
        // the trial's card is up (any of the three), but not a paid plan's
        for (k in listOf(CardKind.T1, CardKind.T2, CardKind.T3)) assertTrue(m.pressure(calm, now, now, Card(k, "x")))
        assertFalse(m.pressure(calm, now, now, Card(CardKind.P2, "x")))
        // one before the last item of the day, and used up
        assertFalse(m.pressure(me(itemsToday = 18), now, now, null))
        assertTrue(m.pressure(me(itemsToday = 19), now, now, null))
        assertTrue(m.pressure(me(itemsToday = 20), now, now, null))
        // a limit met in the last day, not after
        m.markWall(now - day + 1000)
        assertTrue(m.pressure(calm, now, now, null))
        assertFalse(m.pressure(calm, now + 2000, now + 2000, null))
        // the future is not "in the last day"
        val m2 = moments(); m2.markWall(now + 5000)
        assertFalse(m2.pressure(calm, now, now, null))
    }

    // ── what the cards say ──────────────────────────────────────────────────

    @Test fun `what the account has had leaves out what is nothing or not known`() {
        val created = now - 10 * day
        fun e(items: Int?, devices: Int, createdAt: Long? = created, trial: Int? = 14, ended: Boolean = false) =
            enjoyed(me(itemsTotal = items, devices = devices, createdAt = createdAt), now, trial, ended)
        assertEquals(Enjoyed(11, 42, 3), e(42, 3))                // the first day counts
        assertEquals(Enjoyed(11, null, 3), e(0, 3))               // nothing sent: that half is left out
        assertEquals(Enjoyed(11, 42, null), e(42, 1))             // one device: that half is left out
        assertEquals(Enjoyed(11, null, null), e(null, 1))
        assertEquals(Enjoyed(14, 5, 2), enjoyed(me(itemsTotal = 5, devices = 2, createdAt = now - 20 * day), now, 14, true))   // never more than the trial's length
        assertEquals(Enjoyed(null, 5, null), e(5, 1, createdAt = null))             // start not known, trial not over: no days
        assertEquals(Enjoyed(14, 5, null), e(5, 1, createdAt = null, ended = true))    // trial over: its length
        assertEquals(Enjoyed(null, 5, null), e(5, 1, createdAt = null, trial = null, ended = true))
        assertEquals(Enjoyed(11, 5, null), e(5, 1, trial = null))
        assertFalse(Enjoyed(null, null, null).any)
    }

    @Test fun `the trial's length is taken from the first source that says it, and the app's own starting number is not a source`() {
        val created = now - 3 * day
        assertEquals(21, trialDays(me(trialDays = 21, createdAt = created, realtimeUntil = created + 14 * day), 7))
        assertEquals(7, trialDays(me(createdAt = created, realtimeUntil = created + 14 * day), 7))
        assertEquals(14, trialDays(me(createdAt = created, realtimeUntil = created + 14 * day), null))
        assertNull(trialDays(me(realtimeUntil = created + 14 * day), null))
        assertNull(trialDays(me(createdAt = created), null))
        assertNull(trialDays(me(trialDays = 0), 0))
    }

    @Test fun `prices are named only when paying is open, and the first month only for an account that can still have it`() {
        val catalog = Catalog.from(Json.parseToJsonElement(
            """{"payments":{"open":true},"plans":[
                {"id":"year","kind":"subscription","price":{"cents":1990,"currency":"USD"},"trial":{"cents":190,"days":30}},
                {"id":"year_once","kind":"once","price":{"cents":2990,"currency":"USD"}},
                {"id":"life","kind":"lifetime","price":{"cents":4990,"currency":"USD"}}]}""",
        ).jsonObject)
        assertEquals(Prices("$1.90", "$1.90", "$19.90", "$29.90"), pricesOf(catalog, true, open = true))
        assertEquals(Prices(null, "$1.90", "$19.90", "$29.90"), pricesOf(catalog, false, open = true))
        assertEquals(Prices(null, null, null, null), pricesOf(catalog, true, open = false))
        assertEquals(Prices(null, null, null, null), pricesOf(null, true, open = true))
    }

    @Test fun `the next UTC midnight is the start of the next UTC day`() {
        assertEquals((utcDay(now) + 1) * DAY_MS, nextUtcMidnight(now))
        assertEquals(DAY_MS, nextUtcMidnight(0))
        assertEquals(2 * DAY_MS, nextUtcMidnight(DAY_MS))
    }

    // ── the first explanation of "expired" ──────────────────────────────────

    @Test fun `the explanation under the first expired file is said once per account`() {
        val m = moments()
        assertTrue(m.keptNoteDue("1700000000000"))
        m.markKeptNote("1700000000000")
        assertFalse(m.keptNoteDue("1700000000000"))
        assertTrue(m.keptNoteDue("another account"))
    }

    // ── keys, and a memory that fails ───────────────────────────────────────

    @Test fun `what was said is kept under dropit moment, the note and its period`() {
        val mem = Mem()
        val m = moments(mem)
        val until = now + day
        m.dismiss(assertNotNull(m.homeCard(me(realtimeUntil = until), now)))
        assertTrue(mem.kept.containsKey("dropit.moment.T2.$until.t2"), mem.kept.keys.toString())
        m.markNear(Near(NearKind.ITEMS_WARN, "${utcDay(now)}.b"))
        assertTrue(mem.kept.containsKey("dropit.moment.N1.${utcDay(now)}.a"))
        assertTrue(mem.kept.containsKey("dropit.moment.N1.${utcDay(now)}.b"))
        m.markNear(Near(NearKind.SPACE, "${utcDay(now)}.sp"))
        assertTrue(mem.kept.containsKey("dropit.moment.N2.${utcDay(now)}.sp"))
        // what the notes know about the account itself is apart from what they said, so it can be forgotten on its own
        m.observe(me(plan = "paid", paidUntil = now + day), now)
        assertTrue(mem.kept.keys.any { it.startsWith("dropit.moment.state.") })
    }

    @Test fun `when nothing can be read nothing is shown, and nothing throws`() {
        val m = moments(Broken())
        val account = me(realtimeUntil = now + day, itemsToday = 19, bytesUsed = 29L shl 20)
        assertNull(m.homeCard(account, now))
        assertNull(m.homeCard(me(plan = "paid", paidKind = "year", paidUntil = now + day, renews = false), now))
        assertNull(m.nearLine(account, now, now))
        assertNull(m.takeNear(account, now, now))
        assertTrue(m.seen("T1", "x"))
        assertFalse(m.keptNoteDue("a"))
        assertFalse(m.wallRecently(now))
        assertFalse(m.firstMonthWasOffered())
        assertFalse(m.pressure(me(), now, now, null))
        // writing fails quietly
        m.mark("T1", "x"); m.markWall(now); m.observe(account, now); m.dismiss(Card(CardKind.P4, "y")); m.markNear(Near(NearKind.ITEMS, "1.a")); m.markKeptNote("a")
        // the lines that only state facts still work
        assertEquals(LineKind.T0, m.settingsLine(me(realtimeUntil = now + 6 * day), now)?.kind)
    }

    @Test fun `a note that can be written but not read is not shown again after it was waved away`() {
        // reading fails after writing succeeded (storage went bad): treated as "said", never as "not said yet"
        val mem = object : MomentMemory {
            var writes = 0
            override fun get(key: String): String? = if (writes > 0) throw IllegalStateException("gone") else null
            override fun put(key: String, value: String) { writes++ }
        }
        val m = moments(mem)
        val card = assertNotNull(m.homeCard(me(realtimeUntil = now + 2 * day), now))
        m.dismiss(card)
        assertNull(m.homeCard(me(realtimeUntil = now + 2 * day), now))
    }

    // ── the new fields of the account's answer ──────────────────────────────

    @Test fun `the account's new fields are read, and a missing one is not a number`() {
        val full = Me.from(Json.parseToJsonElement(
            """{"plan":"free","devices_used":2,"devices_limit":3,"created_at":1700000000000,"trial_days":14,"items_total":42,"items_today":7,
                "items_per_day":20,"kept_days":1,"paid_grace_until":null,"renews":false,"can_manage":true,"realtime_until":1701000000000}""",
        ).jsonObject)
        assertEquals(1700000000000L, full.createdAt)
        assertEquals(14, full.trialDays)
        assertEquals(42, full.itemsTotal)
        assertEquals(7, full.itemsToday)
        assertEquals(20, full.itemsPerDay)
        assertEquals(1, full.keptDays)
        assertNull(full.paidGraceUntil)
        assertEquals(false, full.renews)
        assertTrue(full.canManage)
        val grace = Me.from(Json.parseToJsonElement("""{"plan":"free","paid_grace_until":1702000000000}""").jsonObject)
        assertEquals(1702000000000L, grace.paidGraceUntil)
        val old = Me.from(Json.parseToJsonElement("""{"plan":"free","devices_used":1,"devices_limit":3}""").jsonObject)
        assertNull(old.createdAt); assertNull(old.trialDays); assertNull(old.itemsTotal); assertNull(old.itemsToday)
        assertNull(old.itemsPerDay); assertNull(old.keptDays); assertNull(old.paidGraceUntil); assertNull(old.renews)
        assertFalse(old.canManage)
    }
}
