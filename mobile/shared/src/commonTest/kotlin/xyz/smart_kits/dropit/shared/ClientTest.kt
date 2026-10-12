package xyz.smart_kits.dropit.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Addresses, retries and what is sent on the wire */
class ClientTest {
    private class Store(var endpointsSaved: List<String>? = null, val token: String? = "dk_t") : ClientStore {
        override suspend fun token() = token
        override suspend fun endpoints() = endpointsSaved
        override suspend fun setEndpoints(list: List<String>) { endpointsSaved = list }
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun dropped(): Nothing = throw Exception("connection reset")

    @Test fun `U-R1 the second address works and is remembered first`() = runTest {
        val seen = mutableListOf<String>()
        val engine = MockEngine { req ->
            seen += req.url.host
            if (req.url.host == "a.example") dropped()
            respond("""{"plan":"free"}""", HttpStatusCode.OK, json)
        }
        val store = Store(listOf("https://a.example", "https://b.example"))
        val waits = mutableListOf<Long>()
        val answer = DropitClient(HttpClient(engine), store, listOf("https://b.example"), sleep = { waits += it }).send(Requests.me())
        assertEquals("free", answer.str("plan"))
        assertEquals(listOf("a.example", "a.example", "a.example", "b.example"), seen)
        assertEquals(RETRY_MS, waits)
        assertEquals(listOf("https://b.example", "https://a.example"), store.endpointsSaved)
    }

    @Test fun `U-R2 an error answer does not try another address`() = runTest {
        val seen = mutableListOf<String>()
        val engine = MockEngine { req ->
            seen += req.url.host
            respond("""{"error":"RATE_LIMITED","window":"hour"}""", HttpStatusCode.TooManyRequests, json)
        }
        val client = DropitClient(HttpClient(engine), Store(listOf("https://a.example", "https://b.example")), sleep = {})
        val err = assertFailsWith<ApiError> { client.send(Requests.ingestText("text", "x", null, 1)) }
        assertEquals("RATE_LIMITED", err.code)
        assertEquals(listOf("a.example"), seen)
    }

    @Test fun `U-R3 a send gets through after two dropped connections`() = runTest {
        var drops = 2
        val engine = MockEngine { if (drops-- > 0) dropped() else respond("""{"seq":9}""", HttpStatusCode.OK, json) }
        val answer = DropitClient(HttpClient(engine), Store(listOf("https://a.example")), listOf(), sleep = {})
            .send(Requests.ingestText("text", "x", null, 1))
        assertEquals(9L, answer.seq)
    }

    @Test fun `U-R4 U-R5 joining is sent once per address · and no answer anywhere is offline`() = runTest {
        val seen = mutableListOf<String>()
        val engine = MockEngine { req -> seen += req.url.host; dropped() }
        val client = DropitClient(HttpClient(engine), Store(listOf("https://a.example")), listOf("https://b.example"), sleep = {})
        assertFailsWith<OfflineError> { client.send(Requests.claim("ABC123", "Redmi", null, null)) }
        assertEquals(listOf("a.example", "b.example"), seen)
    }

    @Test fun `the key goes only where it is needed`() = runTest {
        val auths = mutableListOf<String?>()
        val engine = MockEngine { req -> auths += req.headers["authorization"]; respond("{}", HttpStatusCode.OK, json) }
        val client = DropitClient(HttpClient(engine), Store(), sleep = {})
        client.send(Requests.me())
        client.send(Requests.claim("ABC123", "Redmi", null, null))
        assertEquals(listOf("Bearer dk_t", null), auths)
    }

    @Test fun `a file is sent as its own bytes · with its length and type`() = runTest {
        val bytes = "hello file".encodeToByteArray()
        var got: ByteArray? = null
        var type: String? = null
        var length: Long? = null
        val engine = MockEngine { req ->
            got = req.body.toByteArray()
            type = req.body.contentType?.toString()
            length = req.body.contentLength
            respond("""{"seq":3}""", HttpStatusCode.OK, json)
        }
        val client = DropitClient(HttpClient(engine), Store(), sleep = {})
        val answer = client.send(Requests.ingestFile("a.txt", "text/plain", null, null), FileBody(bytes.size.toLong()) { ByteReadChannel(bytes) })
        assertEquals(3L, answer.seq)
        assertTrue(got.contentEquals(bytes))
        assertEquals("text/plain", type)
        assertEquals(bytes.size.toLong(), length)
    }
}
