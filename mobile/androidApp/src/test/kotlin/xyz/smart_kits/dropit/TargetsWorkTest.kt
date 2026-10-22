package xyz.smart_kits.dropit

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.smart_kits.dropit.work.HeldFile
import xyz.smart_kits.dropit.work.Job
import kotlin.test.Test
import kotlin.test.assertEquals

/** The app's side of choosing who a send goes to: what is held for a send carries it, and old held sends still read */
class TargetsWorkTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `U-T35 a held send written before devices could be chosen goes to everyone`() {
        val old = """{"id":"j1","text":"hi","files":[{"path":"/x/f0","name":"a.png","mime":"image/png","size":5}],"done":[0],"seqs":[4]}"""
        val job = json.decodeFromString<Job>(old)
        assertEquals(emptyList(), job.to)
        assertEquals(emptyList(), job.toNames)
        assertEquals(setOf(0), job.done)
    }

    @Test fun `U-T36 a held send keeps the devices it was addressed to across a retry`() {
        val job = Job(id = "j2", text = "hi", files = listOf(HeldFile("/x/f0", "a.png", "image/png", 5)), to = listOf("d_a"), toNames = listOf("Mac"))
        val back = json.decodeFromString<Job>(json.encodeToString(job))
        assertEquals(listOf("d_a"), back.to)
        assertEquals(listOf("Mac"), back.toNames)
        // sending it to everyone this time is just taking them off
        val all = back.copy(to = emptyList(), toNames = emptyList())
        assertEquals(emptyList(), all.to)
        assertEquals(back.done, all.done)     // what already went is still not sent again
    }
}
