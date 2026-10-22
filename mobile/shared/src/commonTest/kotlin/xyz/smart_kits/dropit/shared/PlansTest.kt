package xyz.smart_kits.dropit.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// What the service says about plans is read as it is; no price is written in the app, so these are checked against a sample answer.
// The limits the app starts from are checked against the service's by the website's tests.
class PlansTest {
    private val sample = """
        {"v":1,"rev":"abc","payments":{"open":true,"provider":"paddle","checkout":{"provider":"paddle","client_token":"x"}},
         "plans":[
           {"id":"year","kind":"subscription","interval":"year","price":{"cents":1990,"currency":"USD"},"trial":{"cents":190,"days":30}},
           {"id":"year_once","kind":"once","interval":null,"price":{"cents":2990,"currency":"USD"},"trial":null},
           {"id":"life","kind":"lifetime","interval":null,"price":{"cents":4990,"currency":"USD"},"trial":null}],
         "limits":{"free":{"days":2,"perDay":40,"downloads":250,"realtimeTrialDays":10},"paid":{"devices":12,"realtimeTrialDays":null}}}
    """.trimIndent()

    private fun catalog(text: String = sample) = Catalog.from(Json.parseToJsonElement(text).jsonObject)

    // The sample's own numbers (40 a day, 10 trial days, 2 days kept) are deliberately not the starting ones: what the service says wins
    @Test
    fun theStartingLimitsAreTheFreeNumbersOfNow() {
        assertEquals(20, Plans.defaultFree.perDay)
        assertEquals(14, Plans.defaultFree.realtimeTrialDays)
        assertEquals(1, Plans.defaultFree.days)
        assertEquals(2, Plans.defaultFree.devices)
        assertEquals(300, Plans.defaultPaid.perDay)
        assertEquals(10, Plans.defaultPaid.days)
        assertEquals(10, Plans.defaultPaid.devices)
        assertNull(Plans.defaultPaid.realtimeTrialDays)
        // an answer that does not say them leaves the starting ones in place
        val c = assertNotNull(catalog("""{"payments":{"open":true},"plans":[],"limits":{"free":{"days":1}}}"""))
        assertEquals(20, c.free.perDay)
        assertEquals(14, c.free.realtimeTrialDays)
    }

    @Test
    fun theThreePlansAndTheirPricesAreReadFromTheAnswer() {
        val c = assertNotNull(catalog())
        assertTrue(c.open)
        assertEquals("paddle", c.provider)
        assertEquals(1990L, c.offer("year")?.cents)
        assertEquals(190L, c.offer("year")?.trialCents)
        assertEquals(30, c.offer("year")?.trialDays)
        assertEquals(2990L, c.offer("year_once")?.cents)
        assertEquals(4990L, c.offer("life")?.cents)
        assertNull(c.offer("year_once")?.trialCents)
        assertNull(c.offer("month"))
        assertTrue(c.canBuy)
    }

    @Test
    fun theLimitsFromTheServiceReplaceTheStartingOnesOneByOne() {
        val c = assertNotNull(catalog())
        assertEquals(2, c.free.days)
        assertEquals(40, c.free.perDay)
        assertEquals(Plans.defaultFree.spaceMB, c.free.spaceMB)   // not said: the starting number stays
        assertEquals(12, c.paid.devices)
        assertNull(c.paid.realtimeTrialDays)                        // said null: always on
        assertEquals(10, c.free.realtimeTrialDays)
        assertEquals(250, c.free.downloads)                        // said: the service's number
        assertEquals(Plans.defaultPaid.downloads, c.paid.downloads) // not said: the starting number stays
    }

    @Test
    fun whatAPriceComesToIsWorkedOutNotWritten() {
        assertEquals(166L, Plans.perMonthCents(1990))   // $19.90 a year is about $1.66 a month
        assertEquals(100L, Plans.perMonthCents(1200))
        assertNull(Plans.perMonthCents(0))              // no price: nothing to say
        assertEquals(2.5, Plans.lifetimeInYears(4990, 1990))
        assertEquals(3.0, Plans.lifetimeInYears(5970, 1990))
        assertNull(Plans.lifetimeInYears(4990, 0))
        assertNull(Plans.lifetimeInYears(0, 1990))
    }

    @Test
    fun closedOrIncompleteMeansNothingCanBeBought() {
        assertFalse(assertNotNull(catalog("""{"v":1,"payments":{"open":false,"provider":null},"plans":[],"limits":{}}""")).canBuy)
        // open, but a plan has no price: not all three ways to pay can be shown
        val noLife = """{"payments":{"open":true,"provider":"paddle"},"plans":[
            {"id":"year","kind":"subscription","price":{"cents":1990,"currency":"USD"}},
            {"id":"year_once","kind":"once","price":{"cents":2990,"currency":"USD"}}]}"""
        assertTrue(assertNotNull(catalog(noLife)).open)
        assertFalse(assertNotNull(catalog(noLife)).canBuy)
    }

    @Test
    fun nonsenseIsNoAnswer() {
        assertNull(Catalog.from(Json.parseToJsonElement("""{"plans":"nope","payments":[]}""").jsonObject))
    }

    @Test
    fun aChangedPriceOrCurrencyIsWrittenAsTheServiceSaysIt() {
        assertEquals("$19.90", Plans.money(1990))
        assertEquals("$1.90", Plans.money(190, "USD"))
        assertEquals("$0.05", Plans.money(5))
        assertEquals("€12.90", Plans.money(1290, "EUR"))
        assertEquals("CHF 9.00", Plans.money(900, "CHF"))
    }

    @Test
    fun spaceIsWrittenInMegabytesOrWholeGigabytes() {
        assertEquals("30 MB", Plans.space(30))
        assertEquals("500 MB", Plans.space(500))
        assertEquals("1 GB", Plans.space(1024))
    }

    @Test
    fun theStickerIsWorkedOutFromThePrices() {
        assertEquals(33, assertNotNull(catalog()).yearSaves)   // 1 - 19.90 / 29.90 = 33.4%
        assertNull(assertNotNull(catalog("""{"payments":{"open":true},"plans":[{"id":"year","price":{"cents":1990}}]}""")).yearSaves)
    }

    @Test
    fun theKeptAnswerIsFreshForAnHourAndNeverExpires() {
        assertEquals(3_600_000L, Plans.FRESH_MS)
    }
}

// A new device turned away at joining: the removing half is always said, the paying half only with a secure link from the service
class DeviceFullHelpTest {
    private fun refusal(body: String, code: String = "DEVICE_LIMIT_REACHED") =
        ApiError(403, code, Json.parseToJsonElement(body).jsonObject)

    @Test
    fun aSecureLinkFromTheServiceIsOffered() {
        val h = assertNotNull(deviceFullHelp(refusal("""{"limit":2,"upgrade_url":"https://example.com/pay?u=1","why":"devices"}"""), offering = true))
        assertEquals("https://example.com/pay?u=1", h.upgradeUrl)
    }

    @Test
    fun aLinkThatIsNotSecureIsLeftOut() {
        assertNull(assertNotNull(deviceFullHelp(refusal("""{"limit":2,"upgrade_url":"http://example.com/pay"}"""), true)).upgradeUrl)
        assertNull(assertNotNull(deviceFullHelp(refusal("""{"limit":2,"upgrade_url":"javascript:alert(1)"}"""), true)).upgradeUrl)
        assertNull(assertNotNull(deviceFullHelp(refusal("""{"limit":2,"upgrade_url":"intent://x#Intent;end"}"""), true)).upgradeUrl)
    }

    @Test
    fun noLinkInTheAnswerMeansPaymentsAreClosedOrTheAccountIsPaid() {
        assertNull(assertNotNull(deviceFullHelp(refusal("""{"limit":2}"""), true)).upgradeUrl)
        assertNull(assertNotNull(deviceFullHelp(refusal("""{"limit":10}"""), true)).upgradeUrl)
        assertNull(assertNotNull(deviceFullHelp(refusal("""{"limit":2,"upgrade_url":""}"""), true)).upgradeUrl)
        assertNull(assertNotNull(deviceFullHelp(refusal("""{"limit":2,"upgrade_url":5}"""), true)).upgradeUrl)
    }

    @Test
    fun aBuildOrServiceNotSellingShowsNoLinkEvenWithOne() {
        assertNull(assertNotNull(deviceFullHelp(refusal("""{"limit":2,"upgrade_url":"https://example.com/pay"}"""), offering = false)).upgradeUrl)
    }

    @Test
    fun otherFailuresGetNoHelp() {
        assertNull(deviceFullHelp(refusal("""{"upgrade_url":"https://example.com/pay"}""", "PAIRING_CODE_INVALID"), true))
        assertNull(deviceFullHelp(IllegalStateException("x"), true))
        assertNull(deviceFullHelp(null, true))
    }
}
