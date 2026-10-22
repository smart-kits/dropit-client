package xyz.smart_kits.dropit.entry

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import xyz.smart_kits.dropit.app

/** Pull down → "Send clipboard". A tile can't read the clipboard itself; it opens the 1 px screen that can. */
class SendClipboardTile : TileService() {
    // Remembered so the app stops offering to add it
    override fun onTileAdded() { app.store.tileAdded = true }
    override fun onTileRemoved() { app.store.tileAdded = false }
    override fun onStartListening() { app.store.tileAdded = true }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        app.warm()
        val intent = Intent(this, ClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
