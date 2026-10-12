package xyz.smart_kits.dropit.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import xyz.smart_kits.dropit.R

/**
 * What the clipboard holds, offered at the top of the app when it is newer than anything dropit already
 * dealt with. Only the app on screen may read the clipboard, so this is the one moment dropit can notice a
 * copy by itself; nothing watches the clipboard in the background.
 */
data class ClipOffer(val text: String?, val uri: Uri?, val preview: String, val at: Long)

/** Null when there is nothing new. Needs the window to have focus, or the clipboard reads empty. */
fun Context.newClip(seen: Long): ClipOffer? {
    val cm = getSystemService(ClipboardManager::class.java)
    // The description says when the clip was made without reading it: an old clip is not read at all
    val at = cm.primaryClipDescription?.timestamp ?: return null
    if (at <= seen) return null
    val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
    val uri = item.uri?.takeIf { !contentResolver.getType(it).orEmpty().startsWith("text/") }
    if (uri != null) {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return ClipOffer(null, uri, name ?: getString(R.string.clip_a_file), at)
    }
    val text = item.coerceToText(this)?.toString()?.takeIf { it.isNotBlank() } ?: return null
    return ClipOffer(text, null, text.trim().lines().take(3).joinToString("\n").take(160), at)
}
