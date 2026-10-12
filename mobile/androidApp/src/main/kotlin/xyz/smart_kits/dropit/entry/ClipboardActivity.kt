package xyz.smart_kits.dropit.entry

import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.work.Inbound

/**
 * Send what is on the clipboard: a picture as the picture, anything else as text.
 * Only the focused app may read the clipboard (Android 10+), so this has a 1 px window and reads it once
 * the window has focus — reading in onResume comes back empty.
 */
class ClipboardActivity : ComponentActivity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.one_pixel)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        // Sent from here: opening dropit afterwards does not offer it again
        clip?.description?.timestamp?.let { app.store.clipSeen = maxOf(app.store.clipSeen, it) }
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val uri = item?.uri?.takeIf { u ->
            val type = contentResolver.getType(u).orEmpty()
            !type.startsWith("text/")
        }
        val inbound = when {
            uri != null -> Inbound(streams = listOf(uri.toString()))
            else -> Inbound(text = item?.coerceToText(this)?.toString())
        }
        takeIn(inbound, R.string.clipboard_empty)
    }
}
