package xyz.smart_kits.dropit.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Things on the main screens that went wrong on a real phone */
class JoinAndTileTest {
    // The field keeps the keyboard's text untouched; the code is read out of it
    @Test fun `the code is read out of whatever was typed`() {
        assertEquals("VCR46D", pairingCodeOf("vcr46d"))
        assertEquals("VCR46D", pairingCodeOf(" VCR-46D "))
        assertEquals("VCR46D", pairingCodeOf("vcr46dX"))
        assertEquals("VC", pairingCodeOf("vc"))
    }
}
