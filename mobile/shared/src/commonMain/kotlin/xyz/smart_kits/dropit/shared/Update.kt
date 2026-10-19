package xyz.smart_kits.dropit.shared

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// The build from the website updates itself. The site holds one small file that says what the newest version
// is, where to get it and what its bytes should hash to; the app compares and offers. Nothing here touches
// the network or the disk.

/** Where the version file is, below the service address: a plain static file */
const val UPDATE_PATH = "/downloads/android.json"
/** The version file is read at most this often */
const val UPDATE_CHECK_MS = 20L * 3_600_000

/**
 * What the version file says.
 * @property minVersionCode a build older than this must update before it is used again
 * @property force every older build must update, whatever [minVersionCode] says
 * @property notes what changed, by language (`en`, `zh`)
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val minVersionCode: Int,
    val force: Boolean,
    val url: String,
    val sha256: String,
    val size: Long,
    val notes: Map<String, String>,
    val releaseUrl: String?,
) {
    /** The notes in the person's language: Chinese for any `zh`, else English, else whatever there is */
    fun notesFor(languageTag: String): String? {
        val key = if (languageTag.lowercase().startsWith("zh")) "zh" else "en"
        return (notes[key] ?: notes["en"] ?: notes.values.firstOrNull())?.takeIf { it.isNotBlank() }
    }
}

/**
 * Read the version file. Null for anything that is not one: a file with no usable address or hash is
 * worth nothing. The address must be on the same service as the file itself, so a version file can only
 * send people to the product's own site.
 */
/** A version file with a value of the wrong kind in it (an object where a number should be) is not one: never an exception, which would stop the app on every start */
fun parseUpdate(text: String, base: String = DEFAULT_ENDPOINT): UpdateInfo? = runCatching { parseUpdateUnsafe(text, base) }.getOrNull()

private fun parseUpdateUnsafe(text: String, base: String): UpdateInfo? {
    val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
    val code = o["versionCode"]?.jsonPrimitive?.intOrNull ?: return null
    val url = s("url")?.takeIf { it.startsWith(base.trimEnd('/') + "/") } ?: return null
    val hash = s("sha256")?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } ?: return null
    val notes = (o["notes"] as? JsonObject)?.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }?.toMap().orEmpty()
    return UpdateInfo(
        versionCode = code,
        versionName = s("versionName") ?: code.toString(),
        minVersionCode = o["minVersionCode"]?.jsonPrimitive?.intOrNull ?: 0,
        force = o["force"]?.jsonPrimitive?.booleanOrNull ?: false,
        url = url,
        sha256 = hash,
        size = o["size"]?.jsonPrimitive?.longOrNull ?: 0,
        notes = notes,
        releaseUrl = s("releaseUrl")?.takeIf(::isHttp),
    )
}

sealed interface UpdateVerdict {
    data object UpToDate : UpdateVerdict
    /** A newer build exists; [must]: this one may not be used until it is installed */
    data class Available(val info: UpdateInfo, val must: Boolean) : UpdateVerdict
}

fun updateFor(info: UpdateInfo, installedCode: Int): UpdateVerdict =
    if (info.versionCode <= installedCode) UpdateVerdict.UpToDate
    else UpdateVerdict.Available(info, must = info.force || installedCode < info.minVersionCode)

/** Whether the version file should be read again: not within [UPDATE_CHECK_MS] of the last time, unless asked */
fun dueForCheck(lastMs: Long, nowMs: Long, asked: Boolean = false): Boolean =
    asked || lastMs <= 0 || nowMs - lastMs >= UPDATE_CHECK_MS || nowMs < lastMs

/** The bytes the version file promised? Case does not matter */
fun sameHash(expected: String, actual: String): Boolean = expected.equals(actual, ignoreCase = true)
