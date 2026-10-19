package xyz.smart_kits.dropit.shared

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// When many accounts were made from one network today, the service may ask for a little computing before it
// makes one more: it sends a small challenge, the phone finds a number that fits, and sends the request again
// with the answer attached. Nothing is run that the service sends: it is only data.

/** A challenge as the service sent it: [fields] are handed back as they came, only `nonce` is added */
class Challenge internal constructor(val id: String, val salt: String, val bits: Int, internal val fields: Map<String, JsonElement>)

object Pow {
    /** Longer than this and the phone gives up and shows the plain message */
    val TIME_LIMIT: Duration = 60.seconds

    /** A challenge asking for more than this is refused: it is more work than the service ever asks for */
    const val MAX_BITS = 26

    private const val PURPOSE = "accounts.create"
    private const val CHECK_EVERY = 1024
    private val FIELDS = listOf("v", "id", "iat", "exp", "purpose", "bits", "salt", "used", "sig")

    /** The challenge in an error answer, or null when there is none, it is one this version does not know, or it asks too much */
    fun challengeIn(data: JsonObject): Challenge? {
        val c = data["challenge"] as? JsonObject ?: return null
        val fields = FIELDS.associateWith { c[it] ?: return null }
        fun num(k: String) = (fields.getValue(k) as? JsonPrimitive)?.takeIf { !it.isString }
        fun str(k: String) = (fields.getValue(k) as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (num("v")?.intOrNull != 1 || str("purpose") != PURPOSE) return null
        val bits = num("bits")?.intOrNull ?: return null
        if (bits < 0 || bits > MAX_BITS) return null
        if (num("iat")?.longOrNull == null || num("exp")?.longOrNull == null || num("used")?.longOrNull == null) return null
        val id = str("id") ?: return null
        val salt = str("salt") ?: return null
        if (str("sig") == null) return null
        return Challenge(id, salt, bits, fields)
    }

    /** What the device key is mixed in as: the hex of its SHA-256, or `-` when the request carries none */
    fun keyDigest(deviceKey: String?): String = deviceKey?.encodeUtf8()?.sha256()?.hex() ?: "-"

    /** Leading zero bits of a digest: 8 for each zero byte, then the zeros at the front of the first other byte */
    fun leadingZeroBits(digest: ByteString): Int {
        var n = 0
        for (i in 0 until digest.size) {
            val b = digest[i].toInt() and 0xFF
            if (b == 0) { n += 8; continue }
            return n + b.countLeadingZeroBits() - 24
        }
        return n
    }

    /** Whether `nonce` is an answer: the check the service makes */
    fun passes(id: String, salt: String, deviceKey: String?, bits: Int, nonce: String): Boolean =
        leadingZeroBits("$id|$salt|${keyDigest(deviceKey)}|$nonce".encodeUtf8().sha256()) >= bits

    /**
     * Counts nonces up from 0 until the digest has enough zero bits, and returns the `pow` to send back.
     * Null when it takes longer than [deadline]. Runs off the caller's thread; stops at once when cancelled.
     */
    suspend fun solve(challenge: Challenge, deviceKey: String?, deadline: Duration = TIME_LIMIT): JsonObject? =
        withContext(Dispatchers.Default) {
            val ctx = currentCoroutineContext()
            // The part that never changes is encoded once; only the digits of the counter are written per try
            val prefix = "${challenge.id}|${challenge.salt}|${keyDigest(deviceKey)}|".encodeToByteArray()
            val buf = ByteArray(prefix.size + 20)
            prefix.copyInto(buf)
            val start = TimeSource.Monotonic.markNow()
            var nonce = 0L
            while (true) {
                if (nonce % CHECK_EVERY == 0L) {
                    ctx.ensureActive()
                    if (start.elapsedNow() >= deadline) return@withContext null
                }
                val len = prefix.size + writeDecimal(nonce, buf, prefix.size)
                if (leadingZeroBits(buf.toByteString(0, len).sha256()) >= challenge.bits) break
                nonce++
            }
            buildJsonObject {
                for ((k, v) in challenge.fields) put(k, v)
                put("nonce", JsonPrimitive(nonce.toString()))
            }
        }

    /** Writes `n` in decimal at `at` (no sign, no leading zeros) and says how many bytes it took */
    private fun writeDecimal(n: Long, buf: ByteArray, at: Int): Int {
        var digits = 1
        var t = n
        while (t >= 10) { t /= 10; digits++ }
        t = n
        for (i in digits - 1 downTo 0) { buf[at + i] = ('0'.code + (t % 10).toInt()).toByte(); t /= 10 }
        return digits
    }
}

/**
 * Create an account. When the service asks for a little computing first, work it out (`onPreparing` is told
 * when that starts and ends) and send the same request once more with the answer. Anything else, or a
 * second refusal, comes out as the error it is: the request is never sent a third time.
 */
suspend fun DropitClient.createAccount(
    deviceName: String,
    deviceKey: String?,
    onPreparing: (Boolean) -> Unit = {},
    timeLimit: Duration = Pow.TIME_LIMIT,
): Answer {
    try {
        return send(Requests.createAccount(deviceName, deviceKey))
    } catch (e: ApiError) {
        if (e.code != "TURNSTILE_REQUIRED") throw e
        val challenge = Pow.challengeIn(e.data) ?: throw e
        onPreparing(true)
        val pow = try { Pow.solve(challenge, deviceKey, timeLimit) } finally { onPreparing(false) } ?: throw e
        return send(Requests.createAccount(deviceName, deviceKey, pow))
    }
}
