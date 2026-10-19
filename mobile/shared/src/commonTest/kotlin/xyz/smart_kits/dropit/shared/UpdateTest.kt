package xyz.smart_kits.dropit.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The website's version file: what it may say, and what a build older than it does about it */
class UpdateTest {
    private val hash = "a".repeat(64)
    private fun file(vararg extra: String, url: String = "$DEFAULT_ENDPOINT/downloads/dropit-android.apk", sha: String = hash) =
        """{"versionCode":3,"versionName":"0.3.0","url":"$url","sha256":"$sha"${extra.joinToString("") { ",$it" }}}"""

    @Test fun `U-U1 the file is read - what is not said gets a gentle default`() {
        val u = parseUpdate(file())!!
        assertEquals(3, u.versionCode)
        assertEquals("0.3.0", u.versionName)
        assertEquals(0, u.minVersionCode)
        assertFalse(u.force)
        assertTrue(u.notes.isEmpty())
        assertNull(u.releaseUrl)
    }

    @Test fun `U-U2 all the fields`() {
        val u = parseUpdate(file(""""minVersionCode":2""", """"force":true""", """"size":2900000""",
            """"notes":{"en":"Faster","zh":"更快"}""", """"releaseUrl":"https://github.com/x/y/releases/tag/android-0.3.0""""))!!
        assertEquals(2, u.minVersionCode)
        assertTrue(u.force)
        assertEquals(2_900_000L, u.size)
        assertEquals("https://github.com/x/y/releases/tag/android-0.3.0", u.releaseUrl)
    }

    @Test fun `U-U3 not a version file - nothing`() {
        assertNull(parseUpdate(""))
        assertNull(parseUpdate("<html>captive portal</html>"))
        assertNull(parseUpdate("""{"versionName":"1"}"""))
        assertNull(parseUpdate(file(sha = "abc")))                               // not a hash
        assertNull(parseUpdate(file(sha = "g".repeat(64))))
    }

    @Test fun `U-U4 the download has to be on the service itself`() {
        assertNull(parseUpdate(file(url = "https://evil.example/dropit.apk")))
        assertNull(parseUpdate(file(url = "$DEFAULT_ENDPOINT.evil.example/dropit.apk")))   // a longer host that starts the same
        assertNull(parseUpdate(file(url = "http://dropit.smart-kits.xyz/dropit.apk")))
        assertTrue(parseUpdate(file(url = "http://127.0.0.1:8799/downloads/x.apk"), base = "http://127.0.0.1:8799") != null)
    }

    @Test fun `U-U5 newer is offered - same or older is not`() {
        val u = parseUpdate(file())!!
        assertEquals(UpdateVerdict.UpToDate, updateFor(u, 3))
        assertEquals(UpdateVerdict.UpToDate, updateFor(u, 4))
        assertEquals(UpdateVerdict.Available(u, must = false), updateFor(u, 2))
    }

    @Test fun `U-U6 a build below the minimum or any build when forced must update`() {
        val low = parseUpdate(file(""""minVersionCode":2"""))!!
        assertEquals(UpdateVerdict.Available(low, must = true), updateFor(low, 1))
        assertEquals(UpdateVerdict.Available(low, must = false), updateFor(low, 2))      // at the minimum: may go on
        val forced = parseUpdate(file(""""force":true"""))!!
        assertEquals(true, (updateFor(forced, 2) as UpdateVerdict.Available).must)
        assertEquals(UpdateVerdict.UpToDate, updateFor(forced, 3))                       // already there: nothing to force
    }

    @Test fun `U-U7 notes follow the language`() {
        val u = parseUpdate(file(""""notes":{"en":"Faster","zh":"更快"}"""))!!
        assertEquals("更快", u.notesFor("zh-CN"))
        assertEquals("更快", u.notesFor("zh-Hant-TW"))
        assertEquals("Faster", u.notesFor("en-US"))
        assertEquals("Faster", u.notesFor("fr"))
        assertEquals("Faster", parseUpdate(file(""""notes":{"en":"Faster"}"""))!!.notesFor("zh-CN"))   // no Chinese: English
        assertNull(parseUpdate(file())!!.notesFor("en"))
    }

    @Test fun `U-U8 how often the file is read`() {
        val hour = 3_600_000L
        assertTrue(dueForCheck(0, 1_000))
        assertFalse(dueForCheck(1_000, 1_000 + hour))
        assertTrue(dueForCheck(1_000, 1_000 + UPDATE_CHECK_MS))
        assertTrue(dueForCheck(1_000, 1_000 + hour, asked = true))                         // "Check now"
        assertTrue(dueForCheck(10_000_000, 5_000_000))                                     // the clock went backwards
    }

    @Test fun `U-U9 the hash compares without case`() {
        assertTrue(sameHash("ABCDEF", "abcdef"))
        assertFalse(sameHash("abcdef", "abcdee"))
    }

    @Test
    fun aVersionFileWithValuesOfTheWrongKindIsNotOneAndNeverAnException() {
        val ok = """{"versionCode":2,"url":"$DEFAULT_ENDPOINT/a.apk","sha256":"${"a".repeat(64)}"}"""
        assertNotNull(parseUpdate(ok))
        for (bad in listOf(
            """{"versionCode":{},"url":"$DEFAULT_ENDPOINT/a.apk","sha256":"${"a".repeat(64)}"}""",
            """{"versionCode":2,"url":[1],"sha256":"${"a".repeat(64)}"}""",
            """{"versionCode":2,"url":"$DEFAULT_ENDPOINT/a.apk","sha256":{"x":1}}""",
            """{"versionCode":2,"url":"$DEFAULT_ENDPOINT/a.apk","sha256":"${"a".repeat(64)}","notes":{"en":["x"]}}""",
            """{"versionCode":2,"url":"$DEFAULT_ENDPOINT/a.apk","sha256":"${"a".repeat(64)}","force":{}}""",
            """[1,2]""", """null""", "",
        )) assertNull(parseUpdate(bad), bad)
    }
}
