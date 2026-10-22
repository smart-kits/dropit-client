package xyz.smart_kits.dropit.entry

import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.ClipStep
import xyz.smart_kits.dropit.shared.clipStep
import xyz.smart_kits.dropit.shared.intakeUriOk
import xyz.smart_kits.dropit.ui.isSensitive
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
        if (alreadyTaken(savedInstanceState)) { done = true; return }      // made again: the clipboard is not sent a second time
        app.warm()      // the connection opens while the window gets its focus and the clipboard is read
        setContentView(R.layout.one_pixel)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        // What kind it is comes from the clip itself, not from asking the app that put it there (which could take as long as it likes)
        val isText = clip?.description?.let { d -> (0 until d.mimeTypeCount).any { d.getMimeType(it).startsWith("text/") } } == true
        // Only an address served by another app is read ([Outbox] checks it again): a `file` address on the clipboard is not followed,
        // and is not sent as words either, whatever coerceToText would make of it
        val uri = item?.uri?.takeIf { !isText }
        val allowed = uri != null && intakeUriOk(uri.scheme, uri.authority, packageName)
        val sensitive = clip?.description?.isSensitive() == true
        val words = if (uri != null) null else item?.coerceToText(this)?.toString()
        // Sent from here: opening dropit afterwards does not offer it again (a sensitive one is not sent, so it still is offered, hidden)
        if (!sensitive) clip?.description?.timestamp?.let { app.store.clipSeen = maxOf(app.store.clipSeen, it) }
        when (clipStep(hasContent = allowed || !words.isNullOrBlank(), sensitive = sensitive)) {
            // Marked sensitive by the app that put it there (a password, a code): not sent without the person's own hand, and not shown
            ClipStep.SENSITIVE -> { Toast.makeText(this, R.string.clipboard_sensitive, Toast.LENGTH_LONG).show(); finish() }
            else -> takeIn(if (allowed) Inbound(streams = listOf(uri!!.toString())) else Inbound(text = words), R.string.clipboard_empty)
        }
    }
}
