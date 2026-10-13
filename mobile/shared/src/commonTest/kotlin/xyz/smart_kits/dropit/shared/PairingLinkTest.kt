package xyz.smart_kits.dropit.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingLinkTest {
    @Test fun `a code by itself or dropit's own link gives the code`() {
        assertEquals("7KQ2MD", pairingCodeIn("7KQ2MD"))
        assertEquals("7KQ2MD", pairingCodeIn(" 7kq2md\n"))
        assertEquals("7KQ2MD", pairingCodeIn("https://dropit.smart-kits.xyz/?pair=7KQ2MD"))
        assertEquals("7KQ2MD", pairingCodeIn("https://dropit.smart-kits.xyz/?lang=zh&pair=7kq2md#install-android"))
        assertEquals("7KQ2MD", pairingCodeIn(pairingLink("7KQ2MD")))
    }

    @Test fun `anything else is not ours and gives nothing`() {
        assertNull(pairingCodeIn("https://example.com/?pair=7KQ2MD"))
        // A look-alike address must not pass for ours
        assertNull(pairingCodeIn("https://dropit.smart-kits.xyz.evil.example/?pair=7KQ2MD"))
        assertNull(pairingCodeIn("https://dropit.smart-kits.xyz/upgrade?u=abc"))
        assertNull(pairingCodeIn("https://dropit.smart-kits.xyz/?pair=7KQ2M"))
        assertNull(pairingCodeIn("https://dropit.smart-kits.xyz/?pair=7KQ2MD99"))
        assertNull(pairingCodeIn("wxp://f2f0abcdefg"))
        assertNull(pairingCodeIn("hello world"))
        assertNull(pairingCodeIn(""))
    }
}
