package xyz.smart_kits.dropit.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The proof-of-work cases below are copied from clients/test/vectors/pow.json (hash cases and leadingZeroBits);
 * keep them identical to that file.
 */
class PowTest {
    // id, salt, device_key, dkh, bits, nonce, digest_hex, leading_zero_bits, valid
    private class Case(
        val name: String, val id: String, val salt: String, val key: String?, val dkh: String,
        val bits: Int, val nonce: String, val digest: String, val zeros: Int, val valid: Boolean,
    )

    private val cases = listOf(
        Case("8 bits, with device_key", "AAAAAAAAAAAAAAAAAAAAAA", "BBBBBBBBBBBBBBBBBBBBBB", "extension-0123456789abcdef0123456789abcdef",
            "35e1fa2a75139456f2e9b3ebd2be47a8a9822d0cd18cf739b570124ae83f9adf", 8, "27",
            "0039eddba02d3bf8194b89197bc1a8c5ff953a16c3d39b3a13c093ca597d5090", 10, true),
        Case("12 bits, with device_key", "q1w2e3r4t5y6u7i8o9p0aQ", "zXcVbNmLkJhGfDsAqWeRtY", "cli-fedcba9876543210fedcba9876543210",
            "2c63a5e7a3bbe993a61b1c39055457fb1f534b741a3365095cd4d8dd57bc1712", 12, "7688",
            "0005987678492985234d6ab264f4d39876e4619da2e5871b7946b50f8d270b52", 13, true),
        Case("16 bits, no device_key", "0123456789abcdefghijkl", "mnopqrstuvwxyz0123456A", null, "-", 16, "72628",
            "0000ecf3c92fb893f7a4318dc49ba5b967a3c8007940b1b0b725417200eab634", 16, true),
        Case("insufficient bits", "AAAAAAAAAAAAAAAAAAAAAA", "BBBBBBBBBBBBBBBBBBBBBB", "extension-0123456789abcdef0123456789abcdef",
            "35e1fa2a75139456f2e9b3ebd2be47a8a9822d0cd18cf739b570124ae83f9adf", 20, "3",
            "15abbf18329336faa2740f8339dbc029bd9f7373c80fdf4e9f28df1e6b5b9453", 3, false),
    )

    private val zeroBits = listOf(
        "00ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to 8,
        "0000ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to 16,
        "01ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to 7,
        "80ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to 0,
        "0fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to 4,
        "0001ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to 15,
        "000000ffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to 24,
        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to 0,
        "00007fffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to 17,
    )

    @Test fun `the digest and its leading zero bits match the reference`() {
        for (c in cases) {
            assertEquals(c.dkh, Pow.keyDigest(c.key), c.name)
            val digest = "${c.id}|${c.salt}|${c.dkh}|${c.nonce}".encodeUtf8().sha256()
            assertEquals(c.digest, digest.hex(), c.name)
            assertEquals(c.zeros, Pow.leadingZeroBits(digest), c.name)
            assertEquals(c.valid, Pow.passes(c.id, c.salt, c.key, c.bits, c.nonce), c.name)
        }
    }

    @Test fun `leading zero bits of bare digests`() {
        for ((hex, expect) in zeroBits) assertEquals(expect, Pow.leadingZeroBits(hex.decodeHex()), hex)
    }

    private fun challenge(bits: Int = 8, v: Int = 1, purpose: String = "accounts.create") =
        """{"v":$v,"id":"AAAAAAAAAAAAAAAAAAAAAA","iat":1760000000000,"exp":1760000300000,"purpose":"$purpose","bits":$bits,"salt":"BBBBBBBBBBBBBBBBBBBBBB","used":3,"sig":"t5VtnCWtdNcQJmnhW5ulemMUP_sEh1ivXEg2hCesFFM"}"""

    private fun err(challenge: String?) =
        json.parseToJsonElement("""{"error":"TURNSTILE_REQUIRED"${if (challenge != null) ""","retry":true,"challenge":$challenge""" else ""}}""").jsonObject

    @Test fun `a challenge is read · and what does not fit is taken as none`() {
        assertNotNull(Pow.challengeIn(err(challenge())))
        assertNotNull(Pow.challengeIn(err(challenge(bits = 26))))
        assertNull(Pow.challengeIn(err(challenge(bits = 27))))
        assertNull(Pow.challengeIn(err(challenge(v = 2))))
        assertNull(Pow.challengeIn(err(challenge(purpose = "other"))))
        assertNull(Pow.challengeIn(err(challenge().replace(""""sig":"t5VtnCWtdNcQJmnhW5ulemMUP_sEh1ivXEg2hCesFFM"""", """"x":1"""))))
        assertNull(Pow.challengeIn(err(null)))
    }

    @Test fun `what is solved passes the reference check · and is sent back as it came`() = runTest {
        val key = "android-0123456789abcdef"
        for (bits in listOf(8, 12, 16)) {
            val ch = assertNotNull(Pow.challengeIn(err(challenge(bits))))
            val pow = assertNotNull(Pow.solve(ch, key))
            val nonce = pow.getValue("nonce").jsonPrimitive.content
            assertTrue(Pow.passes("AAAAAAAAAAAAAAAAAAAAAA", "BBBBBBBBBBBBBBBBBBBBBB", key, bits, nonce), "bits $bits")
            assertTrue(nonce == "0" || !nonce.startsWith("0"))
            // The challenge's own fields, untouched, and `nonce` as a string
            val sent = json.parseToJsonElement(challenge(bits)).jsonObject
            assertEquals(sent.keys + "nonce", pow.keys)
            for ((k, v) in sent) assertEquals(v, pow.getValue(k), k)
            assertTrue(pow.getValue("nonce").jsonPrimitive.isString)
        }
        // Without a device key the digest is mixed with "-"
        val pow = assertNotNull(Pow.solve(assertNotNull(Pow.challengeIn(err(challenge(10)))), null))
        assertTrue(Pow.passes("AAAAAAAAAAAAAAAAAAAAAA", "BBBBBBBBBBBBBBBBBBBBBB", null, 10, pow.getValue("nonce").jsonPrimitive.content))
    }

    @Test fun `solving gives up at its time limit`() = runTest {
        val hard = assertNotNull(Pow.challengeIn(err(challenge(bits = 26))))
        assertNull(Pow.solve(hard, "k", Duration.ZERO))
    }

    @Test fun `solving stops when cancelled`() = runTest {
        val hard = assertNotNull(Pow.challengeIn(err(challenge(bits = 26))))
        val job = launch { Pow.solve(hard, "k") }
        withContext(Dispatchers.Default) { delay(50.milliseconds) }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    // ---- creating an account ----

    private val jsonHeader = headersOf(HttpHeaders.ContentType, "application/json")
    private class Store : ClientStore {
        override suspend fun token(): String? = null
        override suspend fun endpoints(): List<String>? = null
        override suspend fun setEndpoints(list: List<String>) {}
    }

    private fun client(engine: MockEngine) = DropitClient(HttpClient(engine), Store(), listOf("https://a.example"), sleep = {})

    @Test fun `a challenge is answered once with the same request plus the answer`() = runTest {
        val bodies = mutableListOf<JsonObject>()
        val engine = MockEngine { req ->
            bodies += json.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
            if (bodies.size == 1) respond("""{"error":"TURNSTILE_REQUIRED","retry":true,"challenge":${challenge(8)}}""", HttpStatusCode.Forbidden, jsonHeader)
            else respond("""{"token":"dk_new","device_id":"d1"}""", HttpStatusCode.OK, jsonHeader)
        }
        val phases = mutableListOf<Boolean>()
        val answer = client(engine).createAccount("Redmi", "key-1", { phases += it })
        assertEquals("dk_new", answer.str("token"))
        assertEquals(2, bodies.size)
        assertFalse("pow" in bodies[0])
        val pow = bodies[1].getValue("pow").jsonObject
        assertTrue(Pow.passes("AAAAAAAAAAAAAAAAAAAAAA", "BBBBBBBBBBBBBBBBBBBBBB", "key-1", 8, pow.getValue("nonce").jsonPrimitive.content))
        // Everything else in the request is what it was
        assertEquals(bodies[0], JsonObject(bodies[1].filterKeys { it != "pow" }))
        assertEquals(listOf(true, false), phases)
    }

    @Test fun `without a challenge the plain refusal comes out · nothing is resent`() = runTest {
        var sent = 0
        val engine = MockEngine { sent++; respond("""{"error":"TURNSTILE_REQUIRED"}""", HttpStatusCode.Forbidden, jsonHeader) }
        val e = assertFailsWith<ApiError> { client(engine).createAccount("Redmi", null) }
        assertEquals("TURNSTILE_REQUIRED", e.code)
        assertEquals(1, sent)
        assertEquals("errors.TURNSTILE_REQUIRED", e.sentence().key)
    }

    @Test fun `a challenge asking too much or in a version not known is not solved`() = runTest {
        for (ch in listOf(challenge(27), challenge(8, v = 2))) {
            var sent = 0
            val engine = MockEngine { sent++; respond("""{"error":"TURNSTILE_REQUIRED","challenge":$ch}""", HttpStatusCode.Forbidden, jsonHeader) }
            assertFailsWith<ApiError> { client(engine).createAccount("Redmi", null) }
            assertEquals(1, sent)
        }
    }

    @Test fun `a second refusal does not start another round`() = runTest {
        var sent = 0
        val engine = MockEngine {
            sent++
            respond("""{"error":"TURNSTILE_REQUIRED","retry":true,"challenge":${challenge(8)}}""", HttpStatusCode.Forbidden, jsonHeader)
        }
        val e = assertFailsWith<ApiError> { client(engine).createAccount("Redmi", "k") }
        assertEquals("TURNSTILE_REQUIRED", e.code)
        assertEquals(2, sent)
    }

    @Test fun `running out of time shows the plain refusal and does not resend`() = runTest {
        var sent = 0
        val engine = MockEngine { sent++; respond("""{"error":"TURNSTILE_REQUIRED","challenge":${challenge(8)}}""", HttpStatusCode.Forbidden, jsonHeader) }
        val e = assertFailsWith<ApiError> { client(engine).createAccount("Redmi", "k", timeLimit = Duration.ZERO) }
        assertEquals("TURNSTILE_REQUIRED", e.code)
        assertEquals(1, sent)
    }

    @Test fun `other errors pass through untouched`() = runTest {
        var sent = 0
        val engine = MockEngine { sent++; respond("""{"error":"SIGNUP_LIMITED"}""", HttpStatusCode.TooManyRequests, jsonHeader) }
        assertEquals("SIGNUP_LIMITED", assertFailsWith<ApiError> { client(engine).createAccount("Redmi", null) }.code)
        assertEquals(1, sent)
    }
}
