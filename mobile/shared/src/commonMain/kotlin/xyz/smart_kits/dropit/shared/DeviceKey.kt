package xyz.smart_kits.dropit.shared

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okio.ByteString.Companion.encodeUtf8

/**
 * A fingerprint of this app on this device: SHA-256 of `[kind, ...facts]` as JSON, the same rule as the
 * browser extension. Joining again after a reinstall then takes the place of the earlier join instead of
 * using up another device slot. Only the hash is sent, never the facts.
 *
 * `facts` are strings or numbers. On Android: the app's device id (stable per signing key) and the model.
 */
fun deviceKey(kind: String, facts: List<Any>): String {
    val arr = JsonArray(listOf(JsonPrimitive(kind)) + facts.map {
        when (it) {
            is Number -> JsonPrimitive(it)
            else -> JsonPrimitive(it.toString())
        }
    })
    return arr.toString().encodeUtf8().sha256().hex()
}
