package xyz.smart_kits.dropit.ui

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.entry.SendClipboardTile

internal enum class TileAsk { ADDED, DECLINED, UNAVAILABLE }

/** A refusal is not the same as a system that cannot ask */
internal fun tileAskOf(result: Int): TileAsk = when (result) {
    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> TileAsk.ADDED
    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> TileAsk.DECLINED
    else -> TileAsk.UNAVAILABLE
}

/**
 * The system's one-tap "add this tile" (Android 13+). [done] gets DECLINED when the person said no, and
 * UNAVAILABLE when the system would not ask (older Android, or a system that leaves it out).
 */
@SuppressLint("NewApi")
internal fun Context.requestTile(done: (TileAsk) -> Unit) {
    if (Build.VERSION.SDK_INT < 33) return done(TileAsk.UNAVAILABLE)
    val sbm = getSystemService(StatusBarManager::class.java)
    runCatching {
        sbm.requestAddTileService(
            ComponentName(this, SendClipboardTile::class.java),
            getString(R.string.send_clipboard),
            Icon.createWithResource(this, R.drawable.ic_tile),
            mainExecutor,
        ) { result -> done(tileAskOf(result)) }
    }.onFailure { done(TileAsk.UNAVAILABLE) }
}
