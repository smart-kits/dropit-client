package xyz.smart_kits.dropit

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.smart_kits.dropit.receive.ReceiveStatus
import xyz.smart_kits.dropit.receive.batteryLimited
import xyz.smart_kits.dropit.shared.Message
import xyz.smart_kits.dropit.ui.TileAsk
import xyz.smart_kits.dropit.ui.TileRow
import xyz.smart_kits.dropit.ui.tileRowState
import xyz.smart_kits.dropit.work.HeldFile
import xyz.smart_kits.dropit.work.Job
import xyz.smart_kits.dropit.work.seqsInOrder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The app's side of sending in parallel, shrinking, receiving in the background and the tiles. Nothing here needs a phone. */
class BackgroundWorkTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `U-S1 a tile row - the system's answer counts, the record is a hint, below Android 13 there is nothing to ask`() {
        assertEquals(TileRow.ADDED, tileRowState(added = true, sdk = 34, lastAsk = null))
        assertEquals(TileRow.CAN_ADD, tileRowState(added = false, sdk = 34, lastAsk = null))
        assertEquals(TileRow.ADDED, tileRowState(added = false, sdk = 34, lastAsk = TileAsk.ADDED))     // the system says it is there
        assertEquals(TileRow.CAN_ADD, tileRowState(added = true, sdk = 34, lastAsk = TileAsk.DECLINED))   // the system says it is not
        assertEquals(TileRow.MANUAL, tileRowState(added = false, sdk = 34, lastAsk = TileAsk.UNAVAILABLE))
        assertEquals(TileRow.ADDED, tileRowState(added = true, sdk = 34, lastAsk = TileAsk.UNAVAILABLE))
        assertEquals(TileRow.MANUAL, tileRowState(added = false, sdk = 32, lastAsk = null))
        assertEquals(TileRow.ADDED, tileRowState(added = true, sdk = 29, lastAsk = null))
    }

    @Test fun `A-7 a connection is warned about only when it is on a connection and the battery setting is not unrestricted`() {
        assertTrue(ReceiveStatus.Connection(connected = true).batteryLimited(unrestricted = false))
        assertTrue(ReceiveStatus.Connection(connected = false, retryAtMs = 5).batteryLimited(unrestricted = false))
        assertFalse(ReceiveStatus.Connection(connected = true).batteryLimited(unrestricted = true))
        assertFalse(ReceiveStatus.Push.batteryLimited(unrestricted = false))
        assertFalse(ReceiveStatus.Off.batteryLimited(unrestricted = false))
        assertFalse(ReceiveStatus.Refused(Message("realtimeOver")).batteryLimited(unrestricted = false))
    }

    @Test fun `A3-9 the numbers come back in the batch's order, not the order they finished in`() {
        val job = Job(id = "j", done = setOf(0, 1, 2), seqs = listOf(30L, 10L, 20L), seqAt = mapOf(2 to 30L, 0 to 10L, 1 to 20L))
        assertEquals(listOf(10L, 20L, 30L), job.seqsInOrder)
    }

    @Test fun `A3-10 a job written before this (no places) keeps the order it has`() {
        val old = json.decodeFromString<Job>("""{"id":"j","done":[0,1],"seqs":[7,8],"createdAt":1}""")
        assertEquals(emptyMap(), old.seqAt)
        assertEquals(listOf(7L, 8L), old.seqsInOrder)
        // half-and-half (one number has no place): the list as it is
        val half = Job(id = "j", seqs = listOf(5L, 4L), seqAt = mapOf(1 to 4L))
        assertEquals(listOf(5L, 4L), half.seqsInOrder)
    }

    @Test fun `A6-13 a held file written before this is read, and what was looked at is kept`() {
        val old = json.decodeFromString<HeldFile>("""{"path":"/x/f0","name":"a.jpg","mime":"image/jpeg","size":3000000}""")
        assertFalse(old.shrunk)
        assertFalse(old.shrinkChecked)
        val now = old.copy(path = "/x/f0.s", size = 900_000, shrunk = true, shrinkChecked = true)
        val back = json.decodeFromString<HeldFile>(json.encodeToString(now))
        assertEquals(now, back)
    }

    // The merged manifests are not built in a unit test: these read the files that feed them
    private fun manifest(path: String) = File(path).readText()

    @Test fun `A-5 the play build removes the floating-window permission and its window, the main one has the foreground service typed`() {
        val play = manifest("src/play/AndroidManifest.xml")
        assertTrue(Regex("""SYSTEM_ALERT_WINDOW"\s+tools:node="remove"""").containsMatchIn(play))
        assertTrue(play.contains("""android:name=".entry.OverlayBall" tools:node="remove""""))
        val main = manifest("src/main/AndroidManifest.xml")
        assertTrue(main.contains("FOREGROUND_SERVICE_SPECIAL_USE"))
        assertTrue(Regex("""android:name="\.receive\.ReceiveService"[^>]*android:foregroundServiceType="specialUse"""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(main))
        assertTrue(main.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
        // the site build must not take the permission out
        assertFalse(manifest("src/site/AndroidManifest.xml").contains("SYSTEM_ALERT_WINDOW"))
    }

    @Test fun `A-6 the build file gives the floating button to the site build only`() {
        val gradle = manifest("build.gradle.kts")
        val site = gradle.substringAfter("create(\"site\")").substringBefore("create(\"play\")")
        val play = gradle.substringAfter("create(\"play\")").substringBefore("signingConfigs")
        assertTrue(site.contains("\"OVERLAY\", \"true\""))
        assertTrue(play.contains("\"OVERLAY\", \"false\""))
    }
}
