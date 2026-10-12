package xyz.smart_kits.dropit.shared

// String helpers that behave exactly like the browser extension's (JavaScript) versions,
// so every client cuts, squashes and measures text the same way.

/** JavaScript's `\s`: what `trim()` and the `\s` class treat as whitespace */
internal fun isJsSpace(c: Char): Boolean = when (c) {
    ' ', '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ',
    ' ', ' ', '　', '﻿' -> true
    else -> c in ' '..' '
}

internal fun String.jsTrim(): String = trim(::isJsSpace)
internal fun String.jsTrimEnd(): String = trimEnd(::isJsSpace)

/** Characters as people count them (code points), never half a surrogate pair */
internal fun codePoints(s: String): List<String> {
    val out = ArrayList<String>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            out += s.substring(i, i + 2); i += 2
        } else {
            out += c.toString(); i++
        }
    }
    return out
}

internal fun utf8Bytes(s: String): Int = s.encodeToByteArray().size

/** Control characters and runs of whitespace (newlines included) become one space — as the service does */
fun squash(s: String?): String {
    val sb = StringBuilder()
    var inRun = false
    for (c in s.orEmpty()) {
        if (c <= '\u001F' || c == '\u007F' || isJsSpace(c)) {
            if (!inRun) sb.append(' ')
            inRun = true
        } else {
            sb.append(c); inRun = false
        }
    }
    return sb.toString().trim(' ')
}

/** Cut to `n` characters without splitting a surrogate pair; mark the cut with … */
fun clip(s: String?, n: Int): String {
    val chars = codePoints(s.orEmpty())
    if (chars.size <= n) return chars.joinToString("")
    return if (n <= 0) "" else chars.subList(0, n - 1).joinToString("").jsTrimEnd() + "…"
}

/** The length in UTF-8 bytes of `s` written as JSON.stringify would write it */
internal fun jsonStringBytes(s: String): Int {
    var n = 2
    for (c in s) n += when {
        c == '"' || c == '\\' || c == '\b' || c == '\u000C' || c == '\n' || c == '\r' || c == '\t' -> 2
        c < ' ' -> 6
        c.isSurrogate() -> 2       // each half of a pair: 4 bytes per pair in UTF-8
        else -> utf8Bytes(c.toString())
    }
    return n
}
