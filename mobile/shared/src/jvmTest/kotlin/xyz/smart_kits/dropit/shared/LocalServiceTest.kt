package xyz.smart_kits.dropit.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Against a real local service (a local dropit service on port 8799), only when DROPIT_DEV_URL is set:
 *   DROPIT_DEV_URL=http://127.0.0.1:8799 ./gradlew :shared:jvmTest --tests '*LocalServiceTest*'
 * Checks what unit tests can't: that the service reads these requests the way this code means them.
 */
class LocalServiceTest {
    private val base = System.getenv("DROPIT_DEV_URL")

    private class Mem(var token: String? = null) : ClientStore {
        override suspend fun token() = token
        override suspend fun endpoints(): List<String>? = null
        override suspend fun setEndpoints(list: List<String>) {}
    }

    private fun client(store: Mem) = DropitClient(HttpClient(OkHttp), store, listOf(base!!))
    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull

    @Test fun `a phone joins, sends a batch, and the service keeps it as one`() = runBlocking {
        if (base == null) return@runBlocking println("skipped: DROPIT_DEV_URL not set")
        val owner = Mem()
        val c = client(owner)
        owner.token = c.send(Requests.createAccount("test-owner", null)).str("token")
        val itemLimit = c.send(Requests.me()).data["item_bytes_limit"]!!.jsonPrimitive.long

        // Join as a phone with a fingerprint, as the app does
        val phone = Mem()
        val pc = client(phone)
        val key = deviceKey("android", listOf("test-${System.nanoTime()}", "Xiaomi", "Redmi K70"))
        val code = c.send(Requests.pair()).str("code")!!
        val joined = pc.send(Requests.claim(code, "Redmi K70", key, null))
        phone.token = joined.str("token")
        val phoneId = joined.str("device_id")!!

        // Text + two files: one batch, text first, i from 1
        val files = listOf("hello".encodeToByteArray(), ByteArray(3000) { (it % 251).toByte() })
        val plan = planBatch("a note", files.mapIndexed { k, b -> FileInfo("f$k.bin", "application/octet-stream", b.size.toLong()) }, itemLimit = itemLimit)
        val seqs = plan.items.map { item ->
            when (item) {
                is Item.Text -> pc.send(Requests.ingestText(item.kind, item.raw, item.meta, 1)).seq!!
                is Item.File -> pc.send(
                    Requests.ingestFile(item.info.name, item.info.mime, item.group, item.from),
                    FileBody(files[item.index].size.toLong()) { ByteReadChannel(files[item.index]) },
                ).seq!!
            }
        }
        val items = c.send(Requests.pull(0)).data["items"]!!.jsonArray.map { it.jsonObject }.filter { it["seq"]!!.jsonPrimitive.long in seqs }
        assertEquals(3, items.size)
        val groups = items.map { (it["meta"]!!.jsonObject["group"]!!.jsonObject) }
        assertEquals(1, groups.map { it.s("id") }.toSet().size, "one batch")
        assertEquals(listOf(1, 2, 3), groups.map { it["i"]!!.jsonPrimitive.int })
        assertTrue(items.all { it.s("source") == "android" && it.s("device_id") == phoneId }, items.toString())
        assertEquals(listOf(5L, 3000L), items.drop(1).map { it["bytes"]!!.jsonPrimitive.long })
        assertEquals(listOf("f0.bin", "f1.bin"), items.drop(1).map { it["meta"]!!.jsonObject.s("filename") })

        // The same clipboard sent twice in a row within a minute: the second is a success, marked as a repeat
        // (the service only counts it as a repeat when it comes right after the same thing)
        val first = pc.send(Requests.ingestText("text", "copied twice", null, 1))
        val again = pc.send(Requests.ingestText("text", "copied twice", null, 1))
        assertTrue(again.deduped, again.toString())
        assertEquals(first.seq, again.seq)

        // A link with its page title
        val link = planBatch("https://example.com/p", emptyList(), title = "A page").items.single() as Item.Text
        val linkSeq = pc.send(Requests.ingestText(link.kind, link.raw, link.meta, 1)).seq!!
        val pulled = c.send(Requests.pull(linkSeq - 1, 1)).data["items"]!!.jsonArray.single().jsonObject
        assertEquals("url", pulled.s("kind"))
        assertEquals("A page", pulled["meta"]!!.jsonObject.s("title"))

        // Over the single-item limit: refused, and said with the limit from the answer
        val big = ByteArray((itemLimit + 1).toInt())
        val tooBig = assertFailsWith<ApiError> {
            pc.send(Requests.ingestFile("big.bin", null, null, null), FileBody(big.size.toLong()) { ByteReadChannel(big) })
        }
        assertEquals(Failure.LIMIT, tooBig.failure)
        assertEquals(Message("tooLarge", listOf(mb(itemLimit))), tooBig.sentence())

        // Reinstalled: the same fingerprint joins again and takes its own place, not another slot
        val code2 = c.send(Requests.pair()).str("code")!!
        val rejoined = client(Mem()).send(Requests.claim(code2, "Redmi K70", key, null))
        assertEquals("key", rejoined.data["replaced"]!!.jsonObject.s("how"))
        assertEquals(phoneId, rejoined.data["replaced"]!!.jsonObject.s("device_id"))

        // The old copy's key now fails as "lost": the app forgets it and asks to join again
        val lost = assertFailsWith<ApiError> { pc.send(Requests.me()) }
        assertEquals(Failure.AUTH_LOST, lost.failure)
        assertEquals("revokedReplaced", lost.sentence().key)
    }
}
