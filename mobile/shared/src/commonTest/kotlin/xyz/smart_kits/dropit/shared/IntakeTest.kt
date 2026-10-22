package xyz.smart_kits.dropit.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the screens other apps can open will take in: only another app's content, and nothing that stalls */
class IntakeTest {
    private val own = "xyz.smart_kits.dropit"

    @Test fun `H-I1 content served by another app is taken in`() {
        assertTrue(intakeUriOk("content", "com.android.providers.media.documents", own))
        assertTrue(intakeUriOk("content", "media", own))
        assertTrue(intakeUriOk("CONTENT", "Com.Example.Files", own))
    }

    @Test fun `H-I2 a file address is refused - it can name this app's own private files`() {
        assertFalse(intakeUriOk("file", "", own))
        assertFalse(intakeUriOk("file", null, own))
        assertFalse(intakeUriOk("FILE", "localhost", own))
    }

    @Test fun `H-I3 any other scheme is refused`() {
        for (scheme in listOf("http", "https", "android.resource", "ftp", "intent", "javascript", "", null)) {
            assertFalse(intakeUriOk(scheme, "media", own), scheme.toString())
        }
    }

    @Test fun `H-I4 content of this app's own package is refused whatever the debug suffix`() {
        assertFalse(intakeUriOk("content", own, own))
        assertFalse(intakeUriOk("content", "$own.files", own))
        assertFalse(intakeUriOk("content", "$own.debug.provider", own))
        assertFalse(intakeUriOk("content", own.uppercase(), own))
        // A name that only starts the same is another app's
        assertTrue(intakeUriOk("content", "${own}x.files", own))
    }

    @Test fun `H-I5 user info or a port in the address cannot hide the package`() {
        assertFalse(intakeUriOk("content", "media@$own", own))
        assertFalse(intakeUriOk("content", "$own:80", own))
        assertFalse(intakeUriOk("content", "a:b@$own.files:1", own))
        assertTrue(intakeUriOk("content", "$own@media", own))     // the host is what picks the provider, and it is another app's
    }

    @Test fun `H-I6 no host - nothing to read`() {
        assertFalse(intakeUriOk("content", null, own))
        assertFalse(intakeUriOk("content", "", own))
        assertFalse(intakeUriOk("content", "  ", own))
        assertFalse(intakeUriOk("content", "user@", own))
    }

    @Test fun `H-I7 a copy that stopped moving is told from a slow one`() {
        assertFalse(copyStalled(lastBeatMs = 1_000, nowMs = 1_000))
        assertFalse(copyStalled(lastBeatMs = 1_000, nowMs = 1_000 + INTAKE_STALL_MS - 1))
        assertTrue(copyStalled(lastBeatMs = 1_000, nowMs = 1_000 + INTAKE_STALL_MS))
        assertTrue(copyStalled(0, 50, stallMs = 50))
        assertFalse(copyStalled(0, 49, stallMs = 50))
    }

    @Test fun `H-I8 a screen made again does not take in what it was given a second time`() {
        assertTrue(takesIn(restored = false))
        assertFalse(takesIn(restored = true))
    }
}

/** What is copied in of what was shared, and what is only listed */
class HoldStepTest {
    @Test fun `H-H1 a file the plan would refuse is listed and not copied`() {
        assertEquals(HoldStep.TOO_LARGE, holdStep(listed = 0, declared = 101, limit = 100))
        assertEquals(HoldStep.COPY, holdStep(listed = 0, declared = 100, limit = 100))
        assertEquals(HoldStep.COPY, holdStep(listed = 0, declared = 99, limit = 100))
    }

    @Test fun `H-H2 when the size is not said or the limit is not known it is copied`() {
        assertEquals(HoldStep.COPY, holdStep(listed = 0, declared = null, limit = 100))
        assertEquals(HoldStep.COPY, holdStep(listed = 0, declared = 10_000_000_000, limit = null))
    }

    @Test fun `H-H3 only the first files are copied and the rest are counted`() {
        assertEquals(HoldStep.COPY, holdStep(listed = FILES_MAX - 1, declared = 1, limit = 100))
        assertEquals(HoldStep.TOO_MANY, holdStep(listed = FILES_MAX, declared = 1, limit = 100))
        assertEquals(HoldStep.TOO_MANY, holdStep(listed = FILES_MAX + 5, declared = null, limit = null))
        // Counted as too many before its size is looked at
        assertEquals(HoldStep.TOO_MANY, holdStep(listed = FILES_MAX, declared = 1_000, limit = 100))
    }

    @Test fun `H-H4 a copy whose size was not said stops at the limit`() {
        assertEquals(false, copyOverLimit(100, 100))
        assertEquals(true, copyOverLimit(101, 100))
        assertEquals(false, copyOverLimit(Long.MAX_VALUE, null))
    }
}
