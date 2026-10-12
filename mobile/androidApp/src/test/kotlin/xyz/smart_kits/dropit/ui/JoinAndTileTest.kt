package xyz.smart_kits.dropit.ui

import android.app.StatusBarManager
import kotlin.test.Test
import kotlin.test.assertEquals

/** Two things on the main screens that went wrong on a real phone */
class JoinAndTileTest {
    // The field keeps the keyboard's text untouched; the code is read out of it
    @Test fun `the code is read out of whatever was typed`() {
        assertEquals("VCR46D", pairingCodeOf("vcr46d"))
        assertEquals("VCR46D", pairingCodeOf(" VCR-46D "))
        assertEquals("VCR46D", pairingCodeOf("vcr46dX"))
        assertEquals("VC", pairingCodeOf("vc"))
    }

    // StatusBarManager's result codes are compile-time ints, so they are real here even without Android
    @Test fun `saying no keeps the button, a system that cannot ask hides it`() {
        assertEquals(TileAsk.ADDED, tileAskOf(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED))
        assertEquals(TileAsk.ADDED, tileAskOf(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED))
        assertEquals(TileAsk.DECLINED, tileAskOf(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED))
        assertEquals(TileAsk.UNAVAILABLE, tileAskOf(StatusBarManager.TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE))
        assertEquals(TileAsk.UNAVAILABLE, tileAskOf(StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND))
    }
}
