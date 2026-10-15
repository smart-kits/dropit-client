package xyz.smart_kits.dropit.shared

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How the phone hears about arrivals in the background */
class ChannelTest {
    private fun refused(reason: String) = ApiError(403, "WS_REQUIRES_PAID", JsonObject(mapOf("reason" to JsonPrimitive(reason))))

    @Test fun `U-C1 the test push arrived in time - push`() {
        assertEquals(Choice(Channel.PUSH), chooseChannel(true, Probe.Arrived(2_000)))
        assertEquals(Choice(Channel.PUSH), chooseChannel(true, Probe.Arrived(PUSH_PROBE_WAIT_MS)))
    }

    @Test fun `U-C2 no push - keep a connection`() {
        assertEquals(Choice(Channel.CONNECTION), chooseChannel(true, Probe.Silent))
        assertEquals(Choice(Channel.CONNECTION), chooseChannel(true, Probe.Unavailable))
        assertEquals(Choice(Channel.CONNECTION), chooseChannel(true, Probe.Arrived(PUSH_PROBE_WAIT_MS + 1)))   // too late to count on
        assertEquals(Choice(Channel.CONNECTION), chooseChannel(true, null))
    }

    @Test fun `U-C3 on push - missing something on catch-up asks for another test`() {
        assertTrue(shouldReprobe(Channel.PUSH, missedOnCatchUp = true, networkChanged = false))
        assertTrue(shouldReprobe(Channel.PUSH, missedOnCatchUp = false, networkChanged = true))
        assertEquals(false, shouldReprobe(Channel.PUSH, missedOnCatchUp = false, networkChanged = false))
        assertTrue(shouldReprobe(Channel.CONNECTION, missedOnCatchUp = false, networkChanged = true))
        assertEquals(false, shouldReprobe(Channel.CONNECTION, missedOnCatchUp = true, networkChanged = false))   // a connection catches up by itself
        assertEquals(false, shouldReprobe(Channel.OFF, missedOnCatchUp = true, networkChanged = true))
    }

    @Test fun `U-C4 refused - off - and it says which`() {
        val over = chooseChannel(true, Probe.Refused(refused("trial_over")))
        val full = chooseChannel(true, Probe.Refused(refused("trial_full")))
        assertEquals(Choice(Channel.OFF, Message("realtimeOver")), over)
        assertEquals(Choice(Channel.OFF, Message("realtimeFull")), full)
        assertEquals("今天的实时名额满了，明天自动恢复", Strings.ZH.render(full.why!!))
        assertEquals("后台接收的体验期结束了，付费版一直有", Strings.ZH.render(over.why!!))
        // any other refusal is about push, not about the account: a connection may still work
        assertEquals(Choice(Channel.CONNECTION), chooseChannel(true, Probe.Refused(ApiError(502, "PUSH_FAILED", JsonObject(emptyMap())))))
        assertNull(realtimeRefusal(ApiError(429, "RATE_LIMITED", JsonObject(emptyMap()))))
    }

    @Test fun `U-C5 switch off - off - whatever push would do`() {
        assertEquals(Choice(Channel.OFF), chooseChannel(false, Probe.Arrived(100)))
        assertEquals(Choice(Channel.OFF), chooseChannel(false, null))
    }

    @Test fun `U-W1 what the connection says`() {
        assertEquals(60_000L, HEARTBEAT_MS)
        assertEquals(Frame.New(12), frameOf("""{"type":"new","seq":12}"""))
        assertEquals(Frame.Pong, frameOf("pong"))
        assertEquals(Frame.End("trial_full"), frameOf("""{"type":"new","seq":12,"end":"trial_full"}"""))
        assertEquals(Frame.End("too_many"), frameOf("""{"type":"end","end":"too_many","limit":12}"""))
        assertEquals(Frame.Other, frameOf("hello"))
        assertEquals(Frame.Other, frameOf("""{"type":"later"}"""))
    }

    @Test fun `U-W2 a dropped connection comes back - later each time - to a ceiling`() {
        val waits = (1..12).map { reconnectDelayMs(it) }
        assertEquals(1_000L, waits.first())
        assertTrue(waits.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(300_000L, waits.last())
        assertEquals(AfterDrop.Reconnect(1_000), afterDrop(1))
        assertEquals(AfterDrop.Reconnect(5_000), afterDrop(3))
        assertEquals(1_000L, reconnectDelayMs(0))            // never out of range
    }

    @Test fun `U-W3 refused a ticket - stop - not retry`() {
        assertEquals(AfterDrop.Stop(Message("realtimeOver")), afterDrop(1, refused = refused("trial_over")))
        assertEquals(AfterDrop.Stop(Message("realtimeFull")), afterDrop(4, refused = refused("trial_full")))
        assertEquals(AfterDrop.Stop(Message("realtimeFull")), afterDrop(1, end = "trial_full"))
        assertIs<AfterDrop.Later>(afterDrop(1, end = "too_many"))
        // a ticket refused for another reason (offline, busy) is just another failed try
        assertEquals(AfterDrop.Reconnect(2_000), afterDrop(2, refused = ApiError(503, "GLOBAL_CIRCUIT_OPEN", JsonObject(emptyMap()))))
    }
}
