package xyz.smart_kits.dropit

import android.annotation.SuppressLint
import kotlinx.coroutines.withTimeoutOrNull
import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import xyz.smart_kits.dropit.shared.Answer
import xyz.smart_kits.dropit.shared.ApiError
import xyz.smart_kits.dropit.shared.DropitClient
import xyz.smart_kits.dropit.shared.Failure
import xyz.smart_kits.dropit.shared.OfflineError
import xyz.smart_kits.dropit.shared.Requests
import xyz.smart_kits.dropit.shared.createAccount
import xyz.smart_kits.dropit.shared.deviceKey

/** What `/v1/me` says, as far as this app uses it */
data class Me(
    val plan: String,
    val devicesUsed: Int,
    val devicesLimit: Int,
    val itemBytesLimit: Long?,
    val bytesUsed: Long,
    val bytesLimit: Long,
    /** Lifetime or a year — or null on the free plan */
    val paidKind: String?,
    val paidUntil: Long?,
    /** Where a free account upgrades; the service gives it to free accounts only */
    val upgradeUrl: String?,
    /** Whether subscribing starts with the first month at its own price: once per account; unless the service says no, yes */
    val trialAvailable: Boolean,
) {
    val paid: Boolean get() = plan == "paid"

    companion object {
        fun from(o: JsonObject) = Me(
            plan = o["plan"]?.jsonPrimitive?.contentOrNull ?: "free",
            devicesUsed = o["devices_used"]?.jsonPrimitive?.intOrNull ?: 0,
            devicesLimit = o["devices_limit"]?.jsonPrimitive?.intOrNull ?: 0,
            itemBytesLimit = o["item_bytes_limit"]?.jsonPrimitive?.longOrNull,
            bytesUsed = o["bytes_used"]?.jsonPrimitive?.longOrNull ?: 0,
            bytesLimit = o["bytes_limit"]?.jsonPrimitive?.longOrNull ?: 0,
            paidKind = o["paid_kind"]?.jsonPrimitive?.contentOrNull,
            paidUntil = o["paid_until"]?.jsonPrimitive?.longOrNull,
            upgradeUrl = o["upgrade_url"]?.jsonPrimitive?.contentOrNull,
            trialAvailable = o["trial_available"]?.jsonPrimitive?.booleanOrNull ?: true,
        )
    }
}

/** How long leaving waits for the service to take this device off before leaving anyway */
private const val SIGN_OUT_WAIT_MS = 5_000L

class Account(private val context: Context, private val store: Store, private val client: DropitClient) {
    val signedIn: Boolean get() = store.tokenNow() != null

    /** The phone's own name ("Redmi K70"), or maker and model */
    fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    /** This app on this phone. ANDROID_ID is per app signing key, so a reinstall gives the same value. */
    @SuppressLint("HardwareIds")
    fun fingerprint(): String? = runCatching {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        deviceKey("android", listOf(id, Build.MANUFACTURER, Build.MODEL))
    }.getOrNull()

    suspend fun join(code: String) = signIn(
        client.send(Requests.claim(code.trim().uppercase(), deviceName(), fingerprint(), store.previousToken)),
    )

    /** `onPreparing`: told when the service has asked for a little computing first, and when that is done */
    suspend fun createAccount(onPreparing: (Boolean) -> Unit = {}) =
        signIn(client.createAccount(deviceName(), fingerprint(), onPreparing))

    /**
     * An account just created is a free one: written down at once as what `/v1/me` would say, so the home screen can show its plan
     * without waiting for the first answer (on a slow connection that is several seconds). The numbers are the ones the app already
     * has from the service's plan list; the next real answer replaces all of it.
     */
    fun assumeFree(devices: Int, spaceBytes: Long, fileBytes: Long) {
        store.meJson = JsonObject(mapOf(
            "plan" to kotlinx.serialization.json.JsonPrimitive("free"),
            "devices_used" to kotlinx.serialization.json.JsonPrimitive(1),
            "devices_limit" to kotlinx.serialization.json.JsonPrimitive(devices),
            "bytes_used" to kotlinx.serialization.json.JsonPrimitive(0),
            "bytes_limit" to kotlinx.serialization.json.JsonPrimitive(spaceBytes),
            "item_bytes_limit" to kotlinx.serialization.json.JsonPrimitive(fileBytes),
        )).toString()
    }

    private fun signIn(a: Answer) {
        store.signedIn(a.str("token") ?: error("no token in answer"), a.str("device_id") ?: "")
    }

    /**
     * Leave the account on this phone: remove this device first, so its slot is free at once.
     * Offline, it can't be removed now; the key is kept as previous_token either way, so joining again
     * on this phone takes this device's place.
     */
    suspend fun signOut() {
        // Bounded: on a slow or half-dead connection the request could otherwise hold the screen for a minute. Not removed in time
        // is the same as offline: the key is kept as previous_token below, and joining again takes this device's place
        store.deviceId?.let { id -> withTimeoutOrNull(SIGN_OUT_WAIT_MS) { runCatching { client.send(Requests.revoke(id)) } } }
        store.forget()
    }

    /**
     * The account line: cached for an hour, refreshed when asked. Null when it can't be had. [stale]: whatever was kept, however old, is
     * fine — for drawing a screen at once while the real answer is on its way (the caller asks again with [fresh]).
     */
    suspend fun me(fresh: Boolean = false, stale: Boolean = false): Me? {
        val cached = store.meJson?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        if (!fresh && cached != null && (stale || System.currentTimeMillis() - store.meAt < 3_600_000)) return Me.from(cached)
        return try {
            val data = client.send(Requests.me()).data
            store.meJson = data.toString()
            Me.from(data)
        } catch (e: ApiError) {
            if (e.failure == Failure.AUTH_LOST) store.forget()
            cached?.let(Me::from)
        } catch (e: OfflineError) {
            cached?.let(Me::from)
        }
    }
}
