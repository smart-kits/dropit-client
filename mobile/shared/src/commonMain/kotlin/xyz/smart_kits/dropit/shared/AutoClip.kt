package xyz.smart_kits.dropit.shared

/**
 * When the person has asked for text from another device to go on the clipboard as it arrives: which of what just came it is. The newest
 * one that is only words (text or a link): a file is never fetched by itself, and several that came together are not all copied, since
 * each would replace the last and only the newest is wanted. A "link" that is not an http(s) address is not written on its own either
 * (the newest is the one looked at: an older text is not put there in its place). Null when there is nothing like that.
 */
fun autoClipLetter(arrived: List<Letter>): Letter? =
    arrived.filter { !it.mine && it.files.isEmpty() && !it.text.isNullOrEmpty() }.maxByOrNull { it.at }?.takeIf { it.kind != "url" || it.isLink }
