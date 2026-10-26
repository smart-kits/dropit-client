package xyz.smart_kits.dropit

import java.security.MessageDigest

private val TAG_SHAPE = Regex("^[0-9a-f]{8}$")

/**
 * The short code that tells accounts apart on screen: the service's own when it gives one, otherwise worked out from when the account
 * was made (the first 8 hex digits of SHA-256 of "dropit-account:" + that time in milliseconds). Anything that is not 8 lowercase hex
 * digits is treated as not given. Null when there is nothing to show.
 */
fun accountTag(given: String?, createdAt: Long?): String? {
    given?.takeIf { TAG_SHAPE.matches(it) }?.let { return it }
    if (createdAt == null || createdAt <= 0) return null
    val sum = MessageDigest.getInstance("SHA-256").digest("dropit-account:$createdAt".toByteArray(Charsets.UTF_8))
    return sum.take(4).joinToString("") { "%02x".format(it) }
}

/** The last 4 characters of a device's id (without the "d_" lead), to tell same-named devices apart; null for an empty id */
fun deviceSuffix(id: String): String? {
    val body = id.removePrefix("d_")
    return body.takeLast(4).ifEmpty { null }
}
