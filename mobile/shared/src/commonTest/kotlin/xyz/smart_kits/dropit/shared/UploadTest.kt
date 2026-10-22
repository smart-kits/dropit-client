package xyz.smart_kits.dropit.shared

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A batch going out a few at a time (A3) */
class UploadTest {
    @Test fun `A3-1 at most three are on their way and they start in order`() = runTest {
        var now = 0
        var most = 0
        val started = mutableListOf<Int>()
        val r = sendConcurrently(count = 7) { i ->
            started += i
            now++; most = maxOf(most, now)
            delay(100)
            now--
            "a$i"
        }
        assertEquals(3, most)
        assertEquals((0..6).toList(), started)
        assertNull(r.failure)
        assertEquals((0..6).map { "a$it" }, r.inOrder)
    }

    @Test fun `A3-2 after the first failure nothing new starts and the ones on their way still finish and are recorded`() = runTest {
        val started = mutableListOf<Int>()
        val recorded = mutableListOf<Int>()
        val r = sendConcurrently<String>(count = 6, onDone = { i, _ -> recorded += i }) { i ->
            started += i
            when (i) {
                1 -> { delay(10); throw ApiError(503, "BUSY", kotlinx.serialization.json.JsonObject(emptyMap())) }
                else -> { delay(100); "a$i" }
            }
        }
        assertEquals(listOf(0, 1, 2), started)                 // 3, 4 and 5 were never started
        assertEquals(setOf(0, 2), recorded.toSet())            // the other two finished and are written down: a retry sends only 1, 3, 4, 5
        assertEquals(setOf(1), r.failed)
        assertTrue(r.failure is ApiError)
        assertEquals(listOf("a0", "a2"), r.inOrder)
    }

    @Test fun `A3-3 when two fail the earliest position is the one reported`() = runTest {
        val r = sendConcurrently<String>(count = 3) { i ->
            when (i) {
                0 -> { delay(50); throw IllegalStateException("zero") }
                2 -> { delay(10); throw IllegalStateException("two") }
                else -> "a$i"
            }
        }
        assertEquals("zero", r.failure?.message)
        assertEquals(setOf(0, 2), r.failed)
    }

    @Test fun `A3-4 what was sent before is skipped`() = runTest {
        val seen = mutableListOf<Int>()
        val r = sendConcurrently(count = 5, skip = setOf(0, 2)) { i -> seen += i; i }
        assertEquals(listOf(1, 3, 4), seen.sorted())
        assertEquals(listOf(1, 3, 4), r.done.keys.toList())
    }

    @Test fun `A3-5 answers come back in the batch's order whatever order they finished in`() = runTest {
        val r = sendConcurrently(count = 3) { i -> delay((3 - i) * 100L); i * 10 }
        assertEquals(listOf(0, 10, 20), r.inOrder)
    }

    @Test fun `A3-6 recording is one at a time`() = runTest {
        var inside = 0
        var overlap = false
        sendConcurrently(count = 6, onDone = { _, _: Int -> inside++; if (inside > 1) overlap = true; delay(5); inside-- }) { i -> delay(10); i }
        assertEquals(false, overlap)
    }

    @Test fun `A3-7 a cancellation from outside is not a failure`() = runTest {
        var result: UploadResult<Int>? = null
        val job = launch { result = sendConcurrently(count = 4) { i -> delay(1_000); i } }
        runCurrent()           // they are all under way, none finished
        job.cancel()
        assertNull(result)
    }

    @Test fun `A3-8 the concurrency is a number from the constant and never below one`() = runTest {
        assertEquals(3, UPLOAD_CONCURRENCY)
        var most = 0; var now = 0
        sendConcurrently(count = 3, concurrency = 0) { i -> now++; most = maxOf(most, now); delay(10); now--; i }
        assertEquals(1, most)
    }

    @Test fun `A1-1 a warm-up is due when never made or the gap has passed`() {
        assertTrue(warmDue(null, 0))
        assertEquals(false, warmDue(1_000, 1_000 + WARM_GAP_MS - 1))
        assertTrue(warmDue(1_000, 1_000 + WARM_GAP_MS))
        assertEquals(5L * 60_000, WARM_GAP_MS)
    }

    @Test fun `B1-1 the service's own time is read from the header and nothing else is`() {
        assertEquals(41L, serverMs("total;dur=41, auth;dur=12, data;dur=29"))
        assertEquals(7L, serverMs("auth;dur=3, total;dur=7.4"))
        assertNull(serverMs("auth;dur=3"))
        assertNull(serverMs("total"))
        assertNull(serverMs(null))
        assertNull(serverMs("total;dur=abc"))
    }
}
