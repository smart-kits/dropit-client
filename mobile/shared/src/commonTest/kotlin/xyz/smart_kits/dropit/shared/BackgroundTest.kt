package xyz.smart_kits.dropit.shared

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Receiving with the app closed: the connection's rules, and the small decisions around it (F-7, F-8) */
class BackgroundTest {
    private fun err(status: Int, code: String, vararg data: Pair<String, Any>) =
        ApiError(status, code, JsonObject(data.associate { (k, v) -> k to (if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())) }))

    private val midnight = 20_000L * DAY_MS          // a UTC midnight

    @Test fun `U-C6 no address with a network - a connection`() {
        assertEquals(NoAddress.CONNECTION, whenNoAddress(online = true))
    }

    @Test fun `U-C7 no address and no network - not a verdict on push`() {
        assertEquals(NoAddress.RETRY_LATER, whenNoAddress(online = false))
    }

    @Test fun `U-C8 push the service cannot send counts as none a service that is down says nothing`() {
        assertEquals(Probe.Unavailable, probeOfError(err(503, "PUSH_UNAVAILABLE")))
        assertEquals(Probe.Unavailable, probeOfError(err(502, "PUSH_FAILED")))
        assertEquals(Choice(Channel.CONNECTION), chooseChannel(true, probeOfError(err(503, "PUSH_UNAVAILABLE"))))
        assertEquals(Choice(Channel.CONNECTION), chooseChannel(true, probeOfError(err(502, "PUSH_FAILED"))))
        assertNull(probeOfError(err(500, "INTERNAL")))
        assertNull(probeOfError(err(503, "GLOBAL_CIRCUIT_OPEN")))
        assertNull(probeOfError(err(401, "INVALID_TOKEN")))
        assertIs<Probe.Refused>(probeOfError(err(403, "WS_REQUIRES_PAID", "reason" to "trial_over")))
        assertEquals(Probe.Unavailable, probeOfError(err(404, "NOT_FOUND")))
    }

    @Test fun `U-C9 on a connection a switch to the same kind of network does not test push again within a day`() {
        assertFalse(reprobeOnNetworkChange(Channel.CONNECTION, networkChanged = true, sameKind = true, sinceLastProbeMs = 1_000))
        assertTrue(reprobeOnNetworkChange(Channel.CONNECTION, networkChanged = true, sameKind = true, sinceLastProbeMs = REPROBE_GAP_MS))
        assertTrue(reprobeOnNetworkChange(Channel.CONNECTION, networkChanged = true, sameKind = false, sinceLastProbeMs = 1_000))
        assertTrue(reprobeOnNetworkChange(Channel.CONNECTION, networkChanged = true, sameKind = true, sinceLastProbeMs = null))
        assertFalse(reprobeOnNetworkChange(Channel.CONNECTION, networkChanged = false, sameKind = false, sinceLastProbeMs = null))
        assertTrue(reprobeOnNetworkChange(Channel.PUSH, networkChanged = true, sameKind = true, sinceLastProbeMs = 1))   // push keeps the old rule
        assertFalse(reprobeOnNetworkChange(Channel.OFF, networkChanged = true, sameKind = false, sinceLastProbeMs = null))
    }

    @Test fun `U-W4 a connection that did not hold a minute does not start the waits over`() {
        assertEquals(3, failedInARow(2, heldMs = 5_000))
        assertEquals(2, failedInARow(1, heldMs = STABLE_MS - 1))
        assertEquals(1, failedInARow(7, heldMs = STABLE_MS))
        assertEquals(1, failedInARow(7, heldMs = 3_600_000))
        // so a connect-and-drop loop reaches the ceiling instead of staying at a second
        var n = 0
        repeat(10) { n = failedInARow(n, 100) }
        assertEquals(300_000L, reconnectDelayMs(n))
    }

    @Test fun `U-W5 the day's count used up - stop until after midnight spread over ten minutes`() {
        val e = err(429, "RATE_LIMITED", "window" to "day", "retry_after" to 3_600)
        val a = nextAfter(midnight + 20 * 3_600_000L, dice = 0.0, attempt = 1, refused = e)
        assertEquals(Next.Stop(Message("ticketsDay"), 3_600_000L), a)
        val b = nextAfter(midnight + 20 * 3_600_000L, dice = 1.0, attempt = 1, refused = e)
        assertEquals(Next.Stop(Message("ticketsDay"), 3_600_000L + RENEW_WINDOW_MS), b)
        // no number given: the next UTC midnight
        val c = nextAfter(midnight + 20 * 3_600_000L, dice = 0.0, attempt = 1, refused = err(429, "RATE_LIMITED", "window" to "day"))
        assertEquals(Next.Stop(Message("ticketsDay"), 4 * 3_600_000L), c)
        assertTrue(isTicketsDay(e))
        assertFalse(isTicketsDay(err(429, "RATE_LIMITED", "window" to "hour")))
        assertFalse(isTicketsDay(err(403, "WS_REQUIRES_PAID", "window" to "day")))
    }

    @Test fun `U-W5b the trial over stops for good - places taken stops until midnight - other refusals wait`() {
        val over = nextAfter(midnight, 0.5, 1, refused = err(403, "WS_REQUIRES_PAID", "reason" to "trial_over"))
        assertEquals(Next.Stop(Message("realtimeOver"), null), over)
        val full = nextAfter(midnight + 3_600_000L, 0.0, 1, end = "trial_full")
        assertEquals(Next.Stop(Message("realtimeFull"), DAY_MS - 3_600_000L), full)
        val busy = nextAfter(midnight, 0.0, 2, refused = err(503, "GLOBAL_CIRCUIT_OPEN"))
        assertEquals(Next.Wait(2_000), busy)
        val tooMany = nextAfter(midnight, 1.0, 1, end = "too_many")
        assertEquals(Next.Wait(withJitter(300_000, 1.0)), tooMany)
        assertEquals(360_000L, withJitter(300_000, 1.0))
    }

    @Test fun `U-W6 sixty tickets a day on this phone counted by UTC day`() {
        var ledger = TicketLedger()
        val t = midnight + 1_000
        repeat(TICKETS_PER_DAY) {
            assertTrue(canTakeTicket(ledger, t), "ticket $it")
            ledger = tookTicket(ledger, t)
        }
        assertFalse(canTakeTicket(ledger, t))
        assertFalse(canTakeTicket(ledger, midnight + DAY_MS - 1))
        assertEquals(TICKETS_PER_DAY, ticketsToday(ledger, t))
        assertTrue(canTakeTicket(ledger, midnight + DAY_MS))                  // the next UTC day: from zero
        assertEquals(0, ticketsToday(ledger, midnight + DAY_MS))
        assertEquals(1, tookTicket(ledger, midnight + DAY_MS).count)
        assertEquals(60, TICKETS_PER_DAY)
    }

    @Test fun `U-W6b midnight arithmetic`() {
        assertEquals(DAY_MS, msUntilUtcMidnight(midnight))
        assertEquals(1, msUntilUtcMidnight(midnight + DAY_MS - 1))
        assertEquals(midnight + DAY_MS, renewAt(midnight + DAY_MS - 5, 0.0))
        assertEquals(midnight + DAY_MS + RENEW_WINDOW_MS, renewAt(midnight + DAY_MS - 5, 1.0))
    }

    @Test fun `U-W7 what the connection says is read as before`() {
        assertEquals(AfterDrop.Stop(Message("realtimeFull")), afterDrop(1, end = "trial_full"))
        assertIs<AfterDrop.Later>(afterDrop(1, end = "too_many"))
        assertEquals(Frame.New(5), frameOf("""{"type":"new","seq":5}"""))
        assertEquals(150_000L, STALE_MS)
        assertEquals(60_000L, HEARTBEAT_MS)
    }

    @Test fun `U-D1 downloads start again when the service said at most a day away else at midnight`() {
        val now = midnight + 10 * 3_600_000L
        assertEquals(now + 3_600_000L, downloadsResumeAt(now, 3_600))
        assertEquals(now + DAY_MS, downloadsResumeAt(now, 10 * 86_400L))
        assertEquals(now, downloadsResumeAt(now, -5))
        assertEquals(midnight + DAY_MS, downloadsResumeAt(now, null))
    }

    private fun file(name: String = "a.pdf", saved: String? = null, seq: Long? = 5) = LetterFile(name, 1000, "application/pdf", seq, saved)
    private fun letter(text: String? = null, files: List<LetterFile> = emptyList(), mine: Boolean = false, at: Long = 1) =
        Letter(id = "s${at}", mine = mine, at = at, text = text, kind = text?.let { "text" }, files = files)

    @Test fun `U-N7 one file gets a Download button and the body opens the app - several or with a text open the app`() {
        val one = letter(files = listOf(file()))
        val n = noticeOnPhone(one, noticeFor(one, ready = false))
        assertEquals(listOf(NoticeAction.DOWNLOAD), n.actions)
        assertTrue(n.opensApp)
        val many = letter(files = listOf(file("a"), file("b", seq = 6)))
        assertEquals(emptyList(), noticeOnPhone(many, noticeFor(many, ready = false)).actions)
        assertTrue(noticeOnPhone(many, noticeFor(many, ready = false)).opensApp)
        val both = letter(text = "hi", files = listOf(file()))
        assertEquals(emptyList(), noticeOnPhone(both, noticeFor(both, ready = false)).actions)
        // already saved: no button (nothing to download)
        val saved = letter(files = listOf(file(saved = "content://x")))
        assertEquals(emptyList(), noticeOnPhone(saved, noticeFor(saved, ready = true)).actions)
        // text only: untouched
        val text = letter(text = "hello")
        assertEquals(noticeFor(text), noticeOnPhone(text, noticeFor(text)))
    }

    @Test fun `U-T1 the Get latest tile copies the newest text from another device opens the app for files says so when nothing`() {
        val old = letter(text = "old", at = 1)
        val newer = letter(text = "new", at = 5)
        val mine = letter(text = "mine", at = 9, mine = true)
        assertEquals(LatestAction.Copy("new"), latestAction(listOf(old, newer, mine)))
        assertEquals(LatestAction.Copy("new"), latestAction(listOf(mine, old, newer)))     // whatever order the list is in
        assertEquals(LatestAction.OpenApp, latestAction(listOf(old, letter(files = listOf(file()), at = 7))))
        assertEquals(LatestAction.None, latestAction(listOf(mine)))
        assertEquals(LatestAction.None, latestAction(emptyList()))
    }
}
