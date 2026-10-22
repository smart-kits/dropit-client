package xyz.smart_kits.dropit

import androidx.work.WorkInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.smart_kits.dropit.receive.Registrations
import xyz.smart_kits.dropit.work.HeldFile
import xyz.smart_kits.dropit.work.Job
import xyz.smart_kits.dropit.work.Outbox
import xyz.smart_kits.dropit.work.WaitingSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What sending keeps and what it lets go of, and the small holders the review found racing. Nothing here needs a phone. */
class OutboxHardeningTest {
    private val json = Json { ignoreUnknownKeys = true }

    // ── who may take the copies away ──
    @Test fun `H-O1 only the person's own cancel takes the held copies away`() {
        assertTrue(Outbox.cancelledByPerson(WorkInfo.STOP_REASON_CANCELLED_BY_APP))
        for (reason in listOf(
            WorkInfo.STOP_REASON_NOT_STOPPED, WorkInfo.STOP_REASON_UNKNOWN, WorkInfo.STOP_REASON_TIMEOUT, WorkInfo.STOP_REASON_QUOTA,
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY, WorkInfo.STOP_REASON_PREEMPT, WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION,
            WorkInfo.STOP_REASON_DEVICE_STATE, WorkInfo.STOP_REASON_APP_STANDBY, WorkInfo.STOP_REASON_USER,
        )) {
            assertFalse(Outbox.cancelledByPerson(reason), reason.toString())
        }
    }

    // ── a run that is already going ──
    @Test fun `H-O2 a run that is going or waiting is not taken over by a second tap`() {
        assertTrue(Outbox.inFlight(listOf(WorkInfo.State.RUNNING)))
        assertTrue(Outbox.inFlight(listOf(WorkInfo.State.ENQUEUED)))
        assertTrue(Outbox.inFlight(listOf(WorkInfo.State.BLOCKED)))
        assertTrue(Outbox.inFlight(listOf(WorkInfo.State.FAILED, WorkInfo.State.RUNNING)))
    }

    @Test fun `H-O3 a run that ended - or none yet - leaves the way free for a retry`() {
        assertFalse(Outbox.inFlight(emptyList()))
        assertFalse(Outbox.inFlight(listOf(WorkInfo.State.FAILED)))
        assertFalse(Outbox.inFlight(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED)))
    }

    // ── what is held when it is copied in ──
    @Test fun `H-O4 a file that was listed but not copied says so and the rest of the job reads back as written`() {
        val job = Job(
            id = "j", files = listOf(
                HeldFile("/o/j/f0", "a.jpg", "image/jpeg", 10),
                HeldFile("", "big.mov", "video/quicktime", 900_000_000),
                HeldFile("", null, null, 0),
            ),
        )
        assertEquals(listOf(true, false, false), job.files.map { it.copied })
        assertEquals(job, json.decodeFromString<Job>(json.encodeToString(job)))
    }

    @Test fun `H-O5 a held file written by an earlier version is a copied one`() {
        val old = json.decodeFromString<HeldFile>("""{"path":"/o/j/f0","name":"a.jpg","mime":"image/jpeg","size":10}""")
        assertTrue(old.copied)
        assertFalse(old.shrunk)
    }

    // ── drops held before joining ──
    @Test fun `H-O6 two drops added at once are both kept`() {
        var stored: Set<String> = emptySet()
        val set = WaitingSet(read = { stored.also { Thread.sleep(1) } }, write = { stored = it })
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val done = (0 until 8).map { k -> pool.submit { start.await(); repeat(10) { n -> set.add("d$k-$n") } } }
        start.countDown()
        done.forEach { it.get() }
        pool.shutdown()
        assertEquals(80, stored.size)
    }

    @Test fun `H-O7 taking them all leaves none and one added meanwhile is not lost`() {
        var stored: Set<String> = emptySet()
        val set = WaitingSet(read = { stored }, write = { stored = it })
        set.add("a"); set.add("b"); set.add("a")
        assertEquals(setOf("a", "b"), set.take())
        assertEquals(emptySet(), stored)
        assertEquals(emptySet(), set.take())
        set.add("c")
        assertEquals(setOf("c"), set.take())
    }

    @Test fun `H-O8 taking and adding at the same time never loses or repeats a drop`() {
        var stored: Set<String> = emptySet()
        val set = WaitingSet(read = { stored.also { Thread.sleep(0, 200_000) } }, write = { stored = it })
        val taken = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val adders = (0 until 2).map { k -> pool.submit { start.await(); repeat(50) { n -> set.add("d$k-$n") } } }
        val takers = (0 until 2).map { pool.submit { start.await(); repeat(50) { taken.addAll(set.take()) } } }
        start.countDown()
        (adders + takers).forEach { it.get() }
        pool.shutdown()
        taken.addAll(set.take())
        assertEquals(100, taken.size)
        assertEquals(100, taken.toSet().size)
    }

    // ── what is registered with the system ──
    @Test fun `H-O9 a registration is taken off once however many times it is asked`() {
        val reg = Registrations<String>()
        val off = mutableListOf<String>()
        reg.hold("watch")
        assertEquals(1, reg.count)
        assertTrue(reg.release("watch") { off += it })
        assertFalse(reg.release("watch") { off += it })
        assertEquals(listOf("watch"), off)
        assertEquals(0, reg.count)
    }

    @Test fun `H-O10 starting again after the first run ended for good leaves one watch not two`() {
        val reg = Registrations<String>()
        val off = mutableListOf<String>()
        reg.hold("first")
        // The loop ended for good (its own finish takes its watch off) ...
        reg.release("first") { off += it }
        // ... and the next start registers its own; an earlier loop finishing late must not take the new one off
        reg.hold("second")
        assertFalse(reg.release("first") { off += it })
        assertEquals(1, reg.count)
        assertEquals(listOf("first"), off)
    }

    @Test fun `H-O11 a watch left over from a run that was never taken off is taken off by the next start`() {
        val reg = Registrations<String>()
        val off = mutableListOf<String>()
        reg.hold("old")
        // The next start finds one still held and takes it off before it registers its own
        assertTrue(reg.release("old") { off += it })
        reg.hold("new")
        assertEquals(listOf("old"), off)
        assertEquals(1, reg.count)
    }
}
