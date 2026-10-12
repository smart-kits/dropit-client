package xyz.smart_kits.dropit.work

import android.content.Intent

/**
 * What another app handed over, before anything is read: plain values, so the rules are tested on a
 * computer without Android. `streams` are content addresses still to be copied in.
 */
data class Inbound(
    val text: String? = null,
    val title: String? = null,
    val streams: List<String> = emptyList(),
) {
    val isEmpty get() = text.isNullOrBlank() && streams.isEmpty()

    companion object {
        /**
         * - SEND: text in EXTRA_TEXT (browsers put the page title in EXTRA_SUBJECT), or one file in EXTRA_STREAM
         * - SEND_MULTIPLE: files in EXTRA_STREAM (some apps add a caption in EXTRA_TEXT)
         * - PROCESS_TEXT: the selected text; nothing is written back
         * - VIEW: the file being opened
         */
        fun of(action: String?, text: CharSequence?, subject: CharSequence?, processText: CharSequence?, streams: List<String>, data: String?): Inbound =
            when (action) {
                Intent.ACTION_PROCESS_TEXT -> Inbound(text = processText?.toString())
                Intent.ACTION_VIEW -> Inbound(streams = listOfNotNull(data))
                Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE ->
                    Inbound(text = text?.toString(), title = subject?.toString()?.takeIf { it.isNotBlank() }, streams = streams)
                else -> Inbound()
            }
    }
}
