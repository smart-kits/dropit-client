package xyz.smart_kits.dropit.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Who a send goes to: the devices that can be chosen, the saved default, the panel, how it is said, how far it got */
class TargetsTest {
    private val me = "d_me"
    private val mac = Peer("d_mac", "My Mac", cursor = 10)
    private val web = Peer("d_web", "Office web", cursor = 4)
    private val linux = Peer("d_linux", "Home Linux", cursor = 10)
    private val shortcut = Peer("d_sc", "iPhone shortcut", scope = "ingest_only")
    private val phone = Peer(me, "My Pixel", cursor = 10)
    private val all = listOf(phone, mac, web, shortcut, linux)

    private fun sent(vararg seqs: Long, to: List<String> = emptyList(), failure: SendFailure? = null, until: Long? = null) =
        Letter(id = "s", mine = true, at = 1, seqs = seqs.toList(), toIds = to, failure = failure, until = until)

    // ---- the devices ----

    @Test fun `U-T1 the devices come out of the service's list`() {
        val data = Json.parseToJsonElement(
            """{"devices":[{"device_id":"d_a","name":"Mac","scope":"full","last_seen_at":5,"cursor":7},{"device_id":"d_b","name":"Shortcut","scope":"ingest_only"},{"name":"no id"},"junk"]}""",
        ).jsonObject
        assertEquals(listOf(Peer("d_a", "Mac", "full", 5, 7), Peer("d_b", "Shortcut", "ingest_only", null, null)), peersFrom(data))
        assertEquals(emptyList(), peersFrom(Json.parseToJsonElement("{}").jsonObject))
    }

    @Test fun `U-T2 only other devices that can receive can be chosen`() {
        assertEquals(listOf(mac, web, linux), receiversOf(all, me))
        assertEquals(listOf(phone, mac, web, linux), receiversOf(all, null))
        assertEquals(emptyList(), receiversOf(listOf(phone, shortcut), me))
    }

    // ---- the saved default ----

    @Test fun `U-T3 no saved default stays everyone`() {
        assertEquals(Reconciled(emptyList(), 0, false), reconcileDefault(emptyList(), listOf(mac, web)))
    }

    @Test fun `U-T4 a saved device that was removed drops out and is counted`() {
        val r = reconcileDefault(listOf("d_mac", "d_gone", "d_web"), listOf(mac, web, linux))
        assertEquals(Reconciled(listOf("d_mac", "d_web"), 1, false), r)
    }

    @Test fun `U-T5 every saved device removed is everyone again`() {
        val r = reconcileDefault(listOf("d_gone1", "d_gone2"), listOf(mac, web))
        assertEquals(Reconciled(emptyList(), 2, true), r)
        // and with no other devices at all
        assertEquals(Reconciled(emptyList(), 1, true), reconcileDefault(listOf("d_mac"), emptyList()))
    }

    @Test fun `U-T6 what is left being every device there is makes it everyone again so a new device is included`() {
        assertEquals(Reconciled(emptyList(), 1, false), reconcileDefault(listOf("d_mac", "d_gone"), listOf(mac)))
        assertEquals(Reconciled(emptyList(), 0, false), reconcileDefault(listOf("d_mac", "d_web"), listOf(mac, web)))
    }

    @Test fun `U-T7 targets say whether they are everyone and what the request carries`() {
        assertTrue(Targets.ALL.isAll)
        assertNull(Targets.ALL.to)
        val t = Targets.of(listOf("d_mac", "d_gone", "d_web"), listOf(mac, web))
        assertEquals(Targets(listOf("d_mac", "d_web"), listOf("My Mac", "Office web")), t)
        assertEquals(listOf("d_mac", "d_web"), t.to)
    }

    // ---- the panel ----

    private val receivers = listOf(mac, web, linux)

    @Test fun `U-T8 the sending panel has the default ticked or everyone when there is none`() {
        assertEquals(setOf("d_mac", "d_web", "d_linux"), Pick.forSending(receivers, emptyList()).ticked)
        assertEquals(setOf("d_linux"), Pick.forSending(receivers, listOf("d_linux")).ticked)
        // a default that has gone is not ticked; with nothing left of it, everyone
        assertEquals(setOf("d_web"), Pick.forSending(receivers, listOf("d_web", "d_gone")).ticked)
        assertEquals(setOf("d_mac", "d_web", "d_linux"), Pick.forSending(receivers, listOf("d_gone")).ticked)
    }

    @Test fun `U-T9 the last tick cannot be taken off when sending`() {
        val one = Pick.forSending(receivers, listOf("d_linux"))
        val refused = one.toggled("d_linux", keepOne = true)
        assertTrue(refused.refused)
        assertEquals(one, refused.pick)
        // another can be ticked first, then the first can go
        val two = one.toggled("d_web", keepOne = true)
        assertFalse(two.refused)
        assertEquals(setOf("d_linux", "d_web"), two.pick.ticked)
        assertEquals(setOf("d_web"), two.pick.toggled("d_linux", keepOne = true).pick.ticked)
    }

    @Test fun `U-T10 sending again can untick everything - the button then waits for a choice`() {
        val none = Pick.forResending(receivers)
        assertEquals(0, none.count)
        val one = none.toggled("d_mac", keepOne = false).pick
        assertEquals(1, one.count)
        assertEquals(0, one.toggled("d_mac", keepOne = false).pick.count)
    }

    @Test fun `U-T11 everyone ticked sends without naming anyone`() {
        val p = Pick.forSending(receivers, listOf("d_mac"))
        assertEquals(listOf("d_mac"), p.to())
        assertNull(p.everyone().to())
        assertTrue(p.everyone().isAll)
        // a choice that happens to be all of them is everyone too
        assertNull(Pick.forSending(receivers, emptyList()).to())
    }

    @Test fun `ticking something that is not a choice changes nothing`() {
        val p = Pick.forSending(receivers, emptyList())
        assertEquals(Toggled(p, false), p.toggled("d_nobody", keepOne = true))
    }

    // ---- saying who ----

    @Test fun `U-T12 everyone is all one device its name a few a count`() {
        assertEquals(TargetWords.All, shortTarget(emptyList()))
        assertEquals(TargetWords.Names("Linux"), shortTarget(listOf("Linux")))
        assertEquals(TargetWords.Count(3), shortTarget(listOf("a", "b", "c")))
        assertEquals(TargetWords.Count(9), shortTarget(List(9) { "d$it" }))
    }

    @Test fun `U-T13 two devices are named only when the names are short`() {
        assertEquals(TargetWords.Names("Mac、Linux"), shortTarget(listOf("Mac", "Linux")))
        assertEquals(TargetWords.Names("Mac, Linux"), shortTarget(listOf("Mac", "Linux"), ", "))
        assertEquals(TargetWords.Count(2), shortTarget(listOf("办公室的电脑一号", "家里的电脑二号机")))   // 8 + 1 + 8 > 14
        // the edge: 14 characters is still written out, 15 is not
        assertEquals(TargetWords.Names("12345、12345678"), shortTarget(listOf("12345", "12345678")))
        assertEquals(TargetWords.Count(2), shortTarget(listOf("12345", "123456789")))
    }

    @Test fun `U-T14 a long name is cut at eight wide characters`() {
        assertEquals("办公室的网页", clipName("办公室的网页"))
        assertEquals("我的办公室里的电…", clipName("我的办公室里的电脑一号"))
        assertEquals("我的办公室里的电脑", clipName("我的办公室里的电脑", 9))
        // Latin letters are half as wide: sixteen fit
        assertEquals("abcdefghijklmnop", clipName("abcdefghijklmnop"))
        assertEquals("abcdefghijklmnop…", clipName("abcdefghijklmnopq"))
        assertEquals(TargetWords.Names("我的办公室里的电…"), shortTarget(listOf("我的办公室里的电脑一号")))
    }

    @Test fun `U-T15 how long ago a device was on`() {
        val now = 100_000_000_000L
        fun ago(min: Long) = seenOf(now - min * 60_000, now)
        assertEquals(Seen.Unknown, seenOf(null, now))
        assertEquals(Seen.Now, ago(0))
        assertEquals(Seen.Now, ago(1))
        assertEquals(Seen.Minutes(5), ago(5))
        assertEquals(Seen.Hours(2), ago(125))
        assertEquals(Seen.Yesterday, ago(30 * 60))
        assertEquals(Seen.Days(3), ago(3 * 24 * 60 + 10))
        assertEquals(Seen.LongAgo, ago(8 * 24 * 60))
        assertEquals(Seen.Now, seenOf(now + 5_000, now))   // a clock a little ahead is still "now"
    }

    // ---- how far it got ----

    @Test fun `U-T16 delivery counts the devices that are at or past the last item`() {
        // everyone: Mac 10, Linux 10 have it, web 4 does not; the shortcut and this phone are not counted
        assertEquals(Delivery(2, 3), deliveryOf(sent(9, 10), all, me))
        assertEquals(Delivery(2, 3), deliveryOf(sent(10), all, me))
        assertEquals(Delivery(3, 3), deliveryOf(sent(4), all, me))
    }

    @Test fun `U-T17 a send to some devices counts only those`() {
        assertEquals(Delivery(1, 2), deliveryOf(sent(10, to = listOf("d_mac", "d_web")), all, me))
        assertEquals(Delivery(0, 1), deliveryOf(sent(10, to = listOf("d_web")), all, me))
        // a device that was removed since is not counted; with none left there is nothing to say
        assertEquals(Delivery(1, 1), deliveryOf(sent(10, to = listOf("d_mac", "d_gone")), all, me))
        assertNull(deliveryOf(sent(10, to = listOf("d_gone")), all, me))
    }

    @Test fun `U-T18 nothing is said before it is known or when it did not go`() {
        assertNull(deliveryOf(sent(10), null, me))
        assertNull(deliveryOf(sent(), all, me))
        assertNull(deliveryOf(sent(10, failure = SendFailure("x")), all, me))
        assertNull(deliveryOf(sent(10).copy(mine = false), all, me))
        assertNull(deliveryOf(sent(10), listOf(phone, shortcut), me))
    }

    @Test fun `U-T19 a device that has not yet said where it is has not got it`() {
        assertEquals(Delivery(0, 1), deliveryOf(sent(3), listOf(phone, Peer("d_new", "New", cursor = null)), me))
    }

    @Test fun `U-T20 dots - one for each device none when too many`() {
        assertEquals("●●○", Delivery(2, 3).dots())
        assertEquals("●●●●", Delivery(4, 4).dots())
        assertEquals("○", Delivery(0, 1).dots())
        assertNull(Delivery(1, Delivery.MAX_DOTS + 1).dots())
        assertTrue(Delivery(3, 3).complete)
        assertFalse(Delivery(2, 3).complete)
    }

    @Test fun `U-T21 each device's row when sending again`() {
        val l = sent(10, to = listOf("d_mac", "d_web"))
        assertEquals(RowState.RECEIVED, rowStateOf(l, mac))
        assertEquals(RowState.PENDING, rowStateOf(l, web))
        assertEquals(RowState.NOT_SENT, rowStateOf(l, linux))
        // to everyone: nobody is "not sent"
        assertEquals(RowState.PENDING, rowStateOf(sent(10), web))
        assertEquals(RowState.RECEIVED, rowStateOf(sent(10), linux))
    }

    @Test fun `U-T22 sending again is offered only for what was sent is still kept and has somewhere to go`() {
        assertEquals(ResendAvail.OK, resendAvail(sent(10), 5, 2))
        assertEquals(ResendAvail.OK, resendAvail(sent(10, until = 100), 99, 1))
        assertEquals(ResendAvail.EXPIRED, resendAvail(sent(10, until = 100), 100, 1))
        assertEquals(ResendAvail.NONE, resendAvail(sent(10), 5, 0))
        assertEquals(ResendAvail.NONE, resendAvail(sent(), 5, 2))
        assertEquals(ResendAvail.NONE, resendAvail(sent(10, failure = SendFailure("x")), 5, 2))
        assertEquals(ResendAvail.NONE, resendAvail(sent(10).copy(mine = false), 5, 2))
    }

    @Test fun `U-T23 a resend is a new letter marked as sent again with the new numbers and devices`() {
        val from = Letter(id = "a", mine = true, at = 1, seqs = listOf(3), kind = "text", text = "hi", toIds = listOf("d_mac"), toNames = listOf("My Mac"), until = 50)
        val r = resentLetter(from, "r1", listOf(8), Targets(listOf("d_web"), listOf("Office web")), 900, 1900)
        assertEquals("r1", r.id)
        assertEquals(listOf(8L), r.seqs)
        assertEquals(listOf("d_web"), r.toIds)
        assertEquals(listOf("Office web"), r.toNames)
        assertTrue(r.again)
        assertEquals(900L, r.at)
        assertEquals(1900L, r.until)
        assertEquals("hi", r.text)
        assertNull(r.failure)
    }

    @Test fun `U-T23b the items of a letter sent again share one new batch in the order they were sent`() {
        fun l(vararg q: Long) = Letter(id = "a", mine = true, at = 1, seqs = q.toList())
        assertEquals(listOf<Group?>(null), resendGroups(l(5), "N"))
        assertEquals(emptyList<Group?>(), resendGroups(l(), "N"))
        assertEquals(listOf<Group?>(Group("N", 1, 2), Group("N", 2, 2)), resendGroups(l(5, 6), "N"))
        // words then files: the places follow the seqs as they stand, from 1
        assertEquals(listOf(1, 2, 3, 4), resendGroups(l(10, 11, 12, 13), "N").map { it!!.i })
        assertEquals(setOf(4), resendGroups(l(10, 11, 12, 13), "N").map { it!!.n }.toSet())
        assertTrue(resendGroups(l(1, 2)).first()!!.id.isNotBlank())
        // more than a batch holds: each goes alone
        assertTrue(resendGroups(Letter(id = "a", mine = true, at = 1, seqs = (1L..17L).toList()), "N").all { it == null })
    }

    @Test fun `U-T24 the devices the service says it sent to are read from its answer`() {
        val a = Answer(Json.parseToJsonElement("""{"seq":5,"to":[{"device_id":"d_a","name":"Mac"},{"device_id":"d_b","name":"Linux"}]}""").jsonObject)
        assertEquals(Targets(listOf("d_a", "d_b"), listOf("Mac", "Linux")), targetsIn(a))
        assertNull(targetsIn(Answer(Json.parseToJsonElement("""{"seq":5,"to":null}""").jsonObject)))
        assertNull(targetsIn(Answer(Json.parseToJsonElement("""{"seq":5}""").jsonObject)))
    }
}
