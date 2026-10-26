package xyz.smart_kits.dropit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The short account code and the device suffix: what tells accounts and same-named devices apart on screen */
class AccountTagTest {
    @Test fun givenWins() = assertEquals("0123abcd", accountTag("0123abcd", 1790126174108L))
    @Test fun workedOutFromCreatedAt() = assertEquals("fc985852", accountTag(null, 1790126174108L))
    @Test fun workedOutSecondVector() = assertEquals("a50974e9", accountTag(null, 1700000000000L))
    @Test fun badGivenFallsBackToCreatedAt() {
        for (bad in listOf("FC985852", "fc98585", "fc9858520", "zzzzzzzz", "", " fc985852"))
            assertEquals("fc985852", accountTag(bad, 1790126174108L), bad)
    }
    @Test fun badGivenAndNoTimeShowsNothing() {
        assertNull(accountTag("XYZ", null))
        assertNull(accountTag(null, null))
        assertNull(accountTag(null, 0L))
    }
    @Test fun meUsesServerFieldThenCreatedAt() {
        fun me(json: String) = Me.from(kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject)
        assertEquals("0123abcd", me("""{"account_tag":"0123abcd","created_at":1790126174108}""").accountTag)
        assertEquals("fc985852", me("""{"created_at":1790126174108}""").accountTag)
        assertNull(me("""{"account_tag":"nope"}""").accountTag)
    }

    @Test fun suffixOfAnId() = assertEquals("bT5Q", deviceSuffix("d_QMNWEuCelwDcAB7hmUbT5Q"))
    @Test fun suffixWithoutPrefix() = assertEquals("bT5Q", deviceSuffix("QMNWEuCelwDcAB7hmUbT5Q"))
    @Test fun suffixExactlyFour() = assertEquals("abcd", deviceSuffix("d_abcd"))
    @Test fun suffixTooShortIsWhatThereIs() = assertEquals("ab", deviceSuffix("d_ab"))
    @Test fun suffixOfNothing() {
        assertNull(deviceSuffix(""))
        assertNull(deviceSuffix("d_"))
    }
}
