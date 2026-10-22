package xyz.smart_kits.dropit.entry

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.launch
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.LatestAction
import xyz.smart_kits.dropit.shared.TileKind
import xyz.smart_kits.dropit.shared.latestAction
import xyz.smart_kits.dropit.ui.MainActivity
import xyz.smart_kits.dropit.ui.sensitiveClip

/**
 * Pull down → "Get latest": the newest text or link from another device goes on the clipboard. Files are not fetched by themselves
 * (each download counts against the day's downloads): dropit opens, where they are saved on a tap. It does not read the clipboard,
 * so it needs no window. One request each time, and only when the person taps.
 */
class ReceiveLatestTile : TileService() {
    private val main = Handler(Looper.getMainLooper())

    // Remembered, nothing more (the system's answer to "add this tile" is what the app goes by: see ui/Tile.kt)
    override fun onTileAdded() { app.store.setTileAdded(TileKind.RECEIVE_LATEST, true) }
    override fun onTileRemoved() { app.store.setTileAdded(TileKind.RECEIVE_LATEST, false) }
    override fun onStartListening() {
        app.store.setTileAdded(TileKind.RECEIVE_LATEST, true)
        show(getString(R.string.tile_latest), Tile.STATE_INACTIVE)
    }

    override fun onClick() {
        if (!app.account.signedIn) return openApp()
        app.warm()
        app.scope.launch {
            val pulled = app.receiving.pullNow("manual")
            // Not "latest" if it could not be asked: what the list already holds may be old
            if (pulled.failed) return@launch flash(R.string.tile_latest_offline)
            when (val action = latestAction(app.letters.all.value)) {
                is LatestAction.Copy -> {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(sensitiveClip(action.text))
                    // What dropit itself put there is not offered back as "new on your clipboard"
                    app.store.clipSeen = System.currentTimeMillis()
                    flash(R.string.tile_latest_copied)
                }
                LatestAction.OpenApp -> main.post { openApp() }
                LatestAction.None -> flash(R.string.tile_latest_none)
            }
        }
    }

    /** Say something on the tile for a moment, then go back to its name */
    private fun flash(text: Int) {
        main.post {
            show(getString(text), Tile.STATE_ACTIVE)
            main.postDelayed({ show(getString(R.string.tile_latest), Tile.STATE_INACTIVE) }, 2_000)
        }
    }

    private fun show(label: String, state: Int) {
        val tile = qsTile ?: return
        tile.label = label
        tile.state = state
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
