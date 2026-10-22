package xyz.smart_kits.dropit.ui

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.entry.ReceiveLatestTile
import xyz.smart_kits.dropit.entry.SendClipboardTile
import xyz.smart_kits.dropit.shared.TileKind

internal enum class TileAsk { ADDED, DECLINED, UNAVAILABLE }

/** A refusal is not the same as a system that cannot ask */
internal fun tileAskOf(result: Int): TileAsk = when (result) {
    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> TileAsk.ADDED
    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> TileAsk.DECLINED
    else -> TileAsk.UNAVAILABLE
}

/** How a tile's row in settings reads: already in the pull-down panel, can be added with one tap, or only by hand (the system will not ask) */
internal enum class TileRow { ADDED, CAN_ADD, MANUAL }

/**
 * What a tile's row shows. [added] is the app's own record (see `Store.tileAdded`): it is right most of the time but not always (a tile
 * taken out while the app was not running leaves no word), so the system's answer to a request ([lastAsk]) is the one that counts:
 * asking about a tile that is already there never shows a dialog and comes back as ADDED. Below Android 13 there is nothing to ask.
 */
internal fun tileRowState(added: Boolean, sdk: Int, lastAsk: TileAsk?): TileRow = when {
    lastAsk == TileAsk.ADDED -> TileRow.ADDED
    lastAsk == TileAsk.DECLINED -> TileRow.CAN_ADD
    lastAsk == TileAsk.UNAVAILABLE || sdk < 33 -> if (added) TileRow.ADDED else TileRow.MANUAL
    added -> TileRow.ADDED
    else -> TileRow.CAN_ADD
}

/** The row for [kind] now; [lastAsk]: the answer of the last request in this session, if one was made */
internal fun Context.tileRow(kind: TileKind, lastAsk: TileAsk? = null): TileRow =
    tileRowState(app.store.tileAdded(kind), Build.VERSION.SDK_INT, lastAsk)

private fun Context.tileParts(kind: TileKind): Triple<Class<*>, Int, Int> = when (kind) {
    TileKind.SEND_CLIPBOARD -> Triple(SendClipboardTile::class.java, R.string.send_clipboard, R.drawable.ic_tile)
    TileKind.RECEIVE_LATEST -> Triple(ReceiveLatestTile::class.java, R.string.tile_latest, R.drawable.ic_tile_latest)
}

/**
 * The system's one-tap "add this tile" (Android 13+), for [kind]. [done] gets DECLINED when the person said no, and
 * UNAVAILABLE when the system would not ask (older Android, or a system that leaves it out). What the system answers is what the
 * app writes down as the tile's state (ADDED: it is there; DECLINED: it is not); the system asks one tile at a time.
 */
@SuppressLint("NewApi")
internal fun Context.requestTile(kind: TileKind = TileKind.SEND_CLIPBOARD, done: (TileAsk) -> Unit) {
    if (Build.VERSION.SDK_INT < 33) return done(TileAsk.UNAVAILABLE)
    val (service, label, icon) = tileParts(kind)
    val sbm = getSystemService(StatusBarManager::class.java)
    fun answered(ask: TileAsk) {
        when (ask) {
            TileAsk.ADDED -> app.store.setTileAdded(kind, true)
            TileAsk.DECLINED -> app.store.setTileAdded(kind, false)
            TileAsk.UNAVAILABLE -> {}
        }
        done(ask)
    }
    runCatching {
        sbm.requestAddTileService(
            ComponentName(this, service),
            getString(label),
            Icon.createWithResource(this, icon),
            mainExecutor,
        ) { result -> answered(tileAskOf(result)) }
    }.onFailure { done(TileAsk.UNAVAILABLE) }
}
