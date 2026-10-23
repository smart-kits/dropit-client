package xyz.smart_kits.dropit.shared

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Requests described and answers read, without a network */
class ApiTest {
    @Test fun `U-E1 a file goes with its name · batch and source in the address`() {
        val spec = Requests.ingestFile("a b/图.png", "image/png", Group("abcdefgh", 2, 3), From("https://a.com/p?x=1", "Page"))
        assertEquals("POST", spec.method)
        assertEquals(
            "/v1/ingest/file?source=android&name=a%20b%2F%E5%9B%BE.png&from_url=https%3A%2F%2Fa.com%2Fp%3Fx%3D1" +
                "&from_title=Page&group=abcdefgh&i=2&n=3",
            spec.target,
        )
        assertEquals("image/png", spec.contentType)
        assertTrue(spec.retriable)
    }

    @Test fun `a checkout says where it was started from and which limit brought the person only when there is something to say`() {
        val plain = Requests.checkout("year")
        assertEquals(setOf("plan"), plain.body!!.keys)
        val wall = Requests.checkout("year", UpgradeFrom.WALL, "per_day").body!!
        assertEquals("year", wall.getValue("plan").jsonPrimitive.content)
        assertEquals("wall", wall.getValue("from").jsonPrimitive.content)
        assertEquals("per_day", wall.getValue("why").jsonPrimitive.content)
        assertEquals(setOf("plan", "from"), Requests.checkout("year_once", UpgradeFrom.NOTICE, null).body!!.keys)
        assertEquals(setOf("plan"), Requests.checkout("year", "", " ").body!!.keys)
        assertEquals("POST", Requests.billing().method)
        assertEquals("/v1/billing", Requests.billing().path)
    }

    @Test fun `only the limits a plan can lift are passed on as the reason`() {
        assertEquals("kept", UpgradeFrom.why("kept"))
        assertNull(UpgradeFrom.why("target"))     // not a limit of a plan
        assertNull(UpgradeFrom.why(null))
        assertNull(UpgradeFrom.why("hour"))       // the hourly pace is never sold
    }

    @Test fun `a file without a name or type`() {
        val spec = Requests.ingestFile(null, null, null, null)
        assertEquals("/v1/ingest/file?source=android", spec.target)
        assertEquals("application/octet-stream", spec.contentType)
    }

    @Test fun `text carries its batch in meta`() {
        val spec = Requests.ingestText("text", "hi", fitMeta(Meta(group = Group("abcdefgh", 1, 2))), 5)
        val body = spec.body!!
        assertEquals("text", body.getValue("kind").jsonPrimitive.content)
        assertEquals("android", body.getValue("source").jsonPrimitive.content)
        assertEquals("1", body.getValue("meta").jsonObject.getValue("group").jsonObject.getValue("i").jsonPrimitive.content)
        assertFalse("title" in body.getValue("meta").jsonObject)
    }

    @Test fun `U-T25 text to some devices names them to everyone says nothing`() {
        val some = Requests.ingestText("text", "hi", null, 5, to = listOf("d_a", "d_b")).body!!
        assertEquals(listOf("d_a", "d_b"), some.getValue("to").jsonArray.map { it.jsonPrimitive.content })
        assertFalse("to" in Requests.ingestText("text", "hi", null, 5).body!!)
        assertFalse("to" in Requests.ingestText("text", "hi", null, 5, to = emptyList()).body!!)
    }

    @Test fun `U-T26 a file to some devices names them in the address`() {
        val spec = Requests.ingestFile("a.png", "image/png", null, null, to = listOf("d_a", "d_b"))
        assertEquals("/v1/ingest/file?source=android&name=a.png&to=d_a%2Cd_b", spec.target)
        assertEquals("/v1/ingest/file?source=android&name=a.png", Requests.ingestFile("a.png", "image/png", null, null).target)
        assertEquals("/v1/ingest/file?source=android&name=a.png", Requests.ingestFile("a.png", "image/png", null, null, to = emptyList()).target)
    }

    @Test fun `U-T27 sending again names the item and the devices and is safe to repeat`() {
        val spec = Requests.resend(12, listOf("d_a"))
        assertEquals("POST", spec.method)
        assertEquals("/v1/items/12/resend", spec.path)
        assertEquals(listOf("d_a"), spec.body!!.getValue("to").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(spec.retriable)
        // everyone is said as "all": the service needs `to` here
        assertEquals("all", Requests.resend(12, null).body!!.getValue("to").jsonPrimitive.content)
    }

    @Test fun `U-T28 the new answers read as errors in words`() {
        val gone = errorMessage("TARGET_UNAVAILABLE", JsonObject(emptyMap()), 400)
        assertEquals("errors.TARGET_UNAVAILABLE", gone.key)
        assertEquals("errors.ITEM_EXPIRED", errorMessage("ITEM_EXPIRED", JsonObject(emptyMap()), 410).key)
        assertTrue(Strings.EN.render(gone).isNotBlank() && Strings.ZH.render(gone) != gone.key)
        assertEquals(Failure.OTHER, ApiError(400, "TARGET_UNAVAILABLE", JsonObject(emptyMap())).failure)
    }

    @Test fun `no empty meta is sent`() {
        assertFalse("meta" in Requests.ingestText("text", "hi", null, 5).body!!)
    }

    @Test fun `U-R4 joining and creating an account are never repeated`() {
        assertFalse(Requests.claim("ABC123", "Redmi", null, null).retriable)
        assertFalse(Requests.createAccount("Redmi", null).retriable)
        assertTrue(Requests.me().retriable)
    }

    @Test fun `joining asks for full rights and sends the fingerprint and the old key`() {
        val body = Requests.claim("ABC123", "Redmi K70", "k", "dk_old").body!!
        assertEquals("full", body.getValue("scope").jsonPrimitive.content)
        assertEquals("k", body.getValue("device_key").jsonPrimitive.content)
        assertEquals("dk_old", body.getValue("previous_token").jsonPrimitive.content)
        assertFalse("previous_token" in Requests.claim("ABC123", "Redmi", null, null).body!!)
    }

    @Test fun `U-E2 U-E3 a send and a repeat are both successes`() {
        assertEquals(71L, parse(200, """{"seq":71}""").seq)
        val again = parse(409, """{"error":"DEDUPED","seq":70}""")
        assertTrue(again.deduped)
        assertEquals(70L, again.seq)
    }

    @Test fun `U-E4 U-E5 the key stopped working`() {
        for ((status, body) in listOf(401 to """{"error":"INVALID_TOKEN"}""", 403 to """{"error":"DEVICE_REVOKED"}""")) {
            assertEquals(Failure.AUTH_LOST, assertFailsWith<ApiError> { parse(status, body) }.failure)
        }
        val replaced = assertFailsWith<ApiError> { parse(403, """{"error":"DEVICE_REVOKED","reason":"replaced"}""") }
        assertEquals("revokedReplaced", replaced.sentence().key)
    }

    @Test fun `U-E6 U-E7 limits`() {
        val day = assertFailsWith<ApiError> { parse(429, """{"error":"RATE_LIMITED","window":"day","retry_after":3600}""") }
        assertEquals(Failure.LIMIT, day.failure)
        assertEquals("rateDay", day.sentence().key)
        val full = assertFailsWith<ApiError> { parse(507, """{"error":"QUOTA_EXCEEDED","field":"bytes"}""") }
        assertEquals(Message("quotaBytes"), full.sentence())
    }

    @Test fun `limits that say which one and when room frees up`() {
        val up = assertFailsWith<ApiError> { parse(429, """{"error":"RATE_LIMITED","window":"day","field":"uploads","retry_after":3600}""") }
        assertEquals("rateDayUploads", up.sentence().key)
        // Accounts per network per day: says when it opens again, and how many a network may make
        val limited = ApiError(429, "SIGNUP_LIMITED", buildJsonObject { put("error", "SIGNUP_LIMITED"); put("retry_after", 3600); put("limit", 5) })
        assertEquals("signupLimitedAt", limited.sentence(0L).key)
        assertEquals(2, limited.sentence(0L).args.size)
        assertEquals("5", limited.sentence(0L).args[1])
        // Offline says why when it knows: no answer in time, no connection made, or just unavailable
        assertEquals("offlineSlow", OfflineError(AttemptTimeout()).sentence.key)
        assertEquals("offline", OfflineError(null).sentence.key)
        assertEquals("offline", OfflineError(Exception("reset")).sentence.key)
        // without a time it is still the plain sentence
        assertEquals("errors.SIGNUP_LIMITED", ApiError(429, "SIGNUP_LIMITED", buildJsonObject { put("error", "SIGNUP_LIMITED") }).sentence(0L).key)
        val down = assertFailsWith<ApiError> { parse(429, """{"error":"RATE_LIMITED","window":"day","field":"downloads","retry_after":3600}""") }
        assertEquals("rateDayDownloads", down.sentence().key)
        assertEquals("rateDay", assertFailsWith<ApiError> { parse(429, """{"error":"RATE_LIMITED","window":"day","retry_after":3600}""") }.sentence().key)
        val bytes = assertFailsWith<ApiError> { parse(507, """{"error":"QUOTA_EXCEEDED","field":"bytes","frees_in":7200}""") }
        assertEquals(Message("quotaBytesIn", listOf("7200")), bytes.sentence())
        assertEquals("Your space is full — the oldest item expires in about 2 hours and frees up room", Strings.EN.render(bytes.sentence()))
        assertEquals("空间已满，最早的一条约 2 小时后到期，到时自动腾出位置", Strings.ZH.render(bytes.sentence()))
        val items = assertFailsWith<ApiError> { parse(507, """{"error":"QUOTA_EXCEEDED","field":"items","frees_in":600}""") }
        assertEquals("Too many items are waiting — the oldest expires in under an hour and frees up room", Strings.EN.render(items.sentence()))
        assertEquals("排队等收的内容太多了，最早的一条不到 1 小时后到期，到时自动腾出位置", Strings.ZH.render(items.sentence()))
        assertEquals("about 3 days", Strings.EN.render(Message("quotaItemsIn", listOf("200000"))).substringAfter("expires in ").substringBefore(" and"))
    }

    @Test fun `U-E8 an unavailable service and an answer that is not JSON`() {
        assertEquals(Failure.UNAVAILABLE, assertFailsWith<ApiError> { parse(503, """{"error":"GLOBAL_CIRCUIT_OPEN"}""") }.failure)
        val odd = assertFailsWith<ApiError> { parse(500, "<html>oops</html>") }
        assertNull(odd.code)
        assertEquals(Message("httpFailed", listOf("500")), odd.sentence())
        assertEquals(JsonObject(emptyMap()), odd.data)
    }

    @Test fun `U-T1 U-T3 every sentence exists in both languages`() {
        assertEquals(Strings.EN.keys, Strings.ZH.keys)
        for (m in listOf(Message("tooLarge", listOf("5 MB")), Message("rateDay", listOf("08:00")), Message("rateDayUploads", listOf("08:00")), Message("rateDayDownloads", listOf("08:00")), Message("signupLimitedAt", listOf("08:00", "5")), Message("offlineSlow"), Message("offlineConnect"), Message("offlineDns"), Message("errors.PAIRING_LIMITED"), Message("offline"))) {
            for (s in listOf(Strings.EN, Strings.ZH)) {
                val text = s.render(m)
                assertFalse(text == m.key, "missing ${m.key}")
                m.args.forEach { assertTrue(it in text, "$text should say $it") }
            }
        }
        assertEquals(Strings.ZH, Strings.forLanguage("zh-Hans-CN"))
        assertEquals(Strings.EN, Strings.forLanguage("fr"))
    }

    @Test fun `U-S1 the fingerprint is a hash · never the facts`() {
        val k = deviceKey("android", listOf("a1b2c3d4e5f60718", "Xiaomi", "Redmi K70"))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(k))
        assertFalse(k == deviceKey("android", listOf("a1b2c3d4e5f60718", "Xiaomi", "Redmi K80")))
    }
}
