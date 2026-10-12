package xyz.smart_kits.dropit.shared

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test fun `U-E8 an unavailable service and an answer that is not JSON`() {
        assertEquals(Failure.UNAVAILABLE, assertFailsWith<ApiError> { parse(503, """{"error":"GLOBAL_CIRCUIT_OPEN"}""") }.failure)
        val odd = assertFailsWith<ApiError> { parse(500, "<html>oops</html>") }
        assertNull(odd.code)
        assertEquals(Message("httpFailed", listOf("500")), odd.sentence())
        assertEquals(JsonObject(emptyMap()), odd.data)
    }

    @Test fun `U-T1 U-T3 every sentence exists in both languages`() {
        assertEquals(Strings.EN.keys, Strings.ZH.keys)
        for (m in listOf(Message("tooLarge", listOf("5 MB")), Message("rateDay", listOf("08:00")), Message("errors.PAIRING_LIMITED"), Message("offline"))) {
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
