package xyz.smart_kits.dropit.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.shared.intakeUriOk

/**
 * What the clipboard holds, offered at the top of the app when it is newer than anything dropit already
 * dealt with. Only the app on screen may read the clipboard, so this is the one moment dropit can notice a
 * copy by itself; nothing watches the clipboard in the background.
 */
data class ClipOffer(val text: String?, val uri: Uri?, val preview: String, val at: Long, val sensitive: Boolean = false)

/** Null when there is nothing new. Needs the window to have focus, or the clipboard reads empty. */
fun Context.newClip(seen: Long): ClipOffer? {
    val cm = getSystemService(ClipboardManager::class.java)
    // The description says when the clip was made without reading it: an old clip is not read at all
    val description = cm.primaryClipDescription ?: return null
    val at = description.timestamp
    if (at <= seen) return null
    // Marked sensitive by the app that put it there (a password, a code): not read, not shown and not sent from here; said so
    if (description.isSensitive()) return ClipOffer(null, null, getString(R.string.clip_sensitive_hidden), at, sensitive = true)
    val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
    // What kind it is comes from the clip itself, not from asking the app that put it there; and only an address served by another app is read
    val isText = (0 until description.mimeTypeCount).any { description.getMimeType(it).startsWith("text/") }
    val uri = item.uri?.takeIf { !isText && intakeUriOk(it.scheme, it.authority, packageName) }
    if (uri != null) {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return ClipOffer(null, uri, name ?: getString(R.string.clip_a_file), at)
    }
    // An address that is not served by another app is not words to send either (coerceToText would turn it into its own text)
    if (item.uri != null && !isText) return null
    val text = item.coerceToText(this)?.toString()?.takeIf { it.isNotBlank() } ?: return null
    return ClipOffer(text, null, text.trim().lines().take(3).joinToString("\n").take(160), at)
}
