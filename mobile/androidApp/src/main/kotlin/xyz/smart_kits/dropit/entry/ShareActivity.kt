package xyz.smart_kits.dropit.entry

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import xyz.smart_kits.dropit.work.Inbound

/** Share → dropit · select text → dropit · open with → dropit */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val streams = buildList<Uri> {
            when (i.action) {
                Intent.ACTION_SEND -> stream(i)?.let(::add)
                Intent.ACTION_SEND_MULTIPLE -> addAll(streams(i))
            }
            // Some apps put files only in the clip data. Not when sharing text: then the clip data holds the
            // preview picture the share sheet shows (Chrome, the Xiaomi browser), not something to send
            val sharesText = i.type?.startsWith("text/") == true
            if (isEmpty() && !sharesText && i.action != Intent.ACTION_VIEW && i.action != Intent.ACTION_PROCESS_TEXT) {
                i.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let(::add) }
            }
        }
        takeIn(
            Inbound.of(
                i.action,
                i.getCharSequenceExtra(Intent.EXTRA_TEXT),
                i.getCharSequenceExtra(Intent.EXTRA_SUBJECT),
                i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
                streams.map(Uri::toString),
                i.data?.toString(),
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun stream(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streams(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM))
            .orEmpty()
}
