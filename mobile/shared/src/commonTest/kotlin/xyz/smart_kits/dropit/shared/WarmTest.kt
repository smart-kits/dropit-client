package xyz.smart_kits.dropit.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Opening a connection ahead of a send (A1) */
class WarmTest {
    private class Store(var endpointsSaved: List<String>? = null) : ClientStore {
        override suspend fun token() = "dk_secret"
        override suspend fun endpoints() = endpointsSaved
        override suspend fun setEndpoints(list: List<String>) { endpointsSaved = list }
    }

    @Test fun `A1-2 it asks for the small file with no key and then not again within the gap`() = runTest {
        val seen = mutableListOf<Triple<String, String, String?>>()
        val engine = MockEngine { req ->
            seen += Triple(req.method.value, req.url.encodedPath, req.headers[HttpHeaders.Authorization])
            respond("ok\n", HttpStatusCode.OK)
        }
        var now = 0L
        val client = DropitClient(HttpClient(engine), Store(), listOf("https://a.example"), clock = { now })
        assertTrue(client.warm())
        assertEquals(listOf(Triple<String, String, String?>("GET", "/ping.txt", null)), seen)       // no key goes with it
        now = WARM_GAP_MS - 1
        assertFalse(client.warm())
        assertEquals(1, seen.size)
        now = WARM_GAP_MS
        assertTrue(client.warm())
        assertEquals(2, seen.size)
    }

    @Test fun `A1-3 a failed warm-up is swallowed is not tried again by itself and the next chance may try`() = runTest {
        var n = 0
        val engine = MockEngine { _ -> n++; if (n > 0) throw Exception("connection reset"); respond("", HttpStatusCode.OK) }
        var now = 0L
        val client = DropitClient(HttpClient(engine), Store(), listOf("https://a.example", "https://b.example"), sleep = {}, clock = { now })
        assertFalse(client.warm())
        assertEquals(1, n)                // no second try, and the second address is not used
        now = 1_000                       // well within the gap: a failure is not a reason to wait
        assertFalse(client.warm())
        assertEquals(2, n)
    }

    @Test fun `A1-4 only the first address is used and it is not rewritten`() = runTest {
        val hosts = mutableListOf<String>()
        val engine = MockEngine { req -> hosts += req.url.host; respond("ok", HttpStatusCode.OK) }
        val store = Store(listOf("https://b.example", "https://a.example"))
        DropitClient(HttpClient(engine), store, listOf("https://a.example")).warm()
        assertEquals(listOf("b.example"), hosts)
        assertEquals(listOf("https://b.example", "https://a.example"), store.endpointsSaved)
    }

    @Test fun `A1-5 the request described is a plain read of a static path`() {
        val spec = Requests.warm()
        assertEquals("GET", spec.method)
        assertEquals("/ping.txt", spec.target)
        assertFalse(spec.auth)
        assertNull(spec.body)
    }

    @Test fun `B1-2 the service's time goes in the trace and a send still works without it`() = runTest {
        val lines = mutableListOf<String>()
        val engine = MockEngine { _ ->
            respond("""{"seq":1}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), "server-timing" to listOf("total;dur=41")))
        }
        DropitClient(HttpClient(engine), Store(), listOf("https://a.example"), trace = { lines += it }).send(Requests.me())
        assertTrue(lines.single().endsWith("(service 41 ms)"), lines.toString())
        lines.clear()
        val bare = MockEngine { _ -> respond("""{"seq":1}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        DropitClient(HttpClient(bare), Store(), listOf("https://a.example"), trace = { lines += it }).send(Requests.me())
        assertFalse(lines.single().contains("service"))
    }
}
