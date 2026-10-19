package xyz.smart_kits.dropit

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import xyz.smart_kits.dropit.shared.ClientStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What this phone keeps. The key (`dk_…`) is encrypted with a key that never leaves the Android Keystore;
 * everything else is plain preferences. Never backed up (allowBackup=false): a restored copy on another
 * phone would be the same device twice.
 */
class Store(context: Context) : ClientStore {
    private val prefs = context.getSharedPreferences("dropit", Context.MODE_PRIVATE)

    override suspend fun token(): String? = tokenNow()
    fun tokenNow(): String? = prefs.getString(TOKEN, null)?.let(Vault::open)

    var previousToken: String?
        get() = prefs.getString(PREVIOUS, null)?.let(Vault::open)
        set(v) = prefs.edit().putString(PREVIOUS, v?.let(Vault::seal)).apply()

    var deviceId: String?
        get() = prefs.getString(DEVICE_ID, null)
        set(v) = prefs.edit().putString(DEVICE_ID, v).apply()

    /** The last `/v1/me` answer and when it was fetched */
    var meJson: String?
        get() = prefs.getString(ME, null)
        set(v) = prefs.edit().putString(ME, v).putLong(ME_AT, System.currentTimeMillis()).apply()
    val meAt: Long get() = prefs.getLong(ME_AT, 0)

    /** The last `/v1/plans` answer (what is on sale and for how much) and when it was fetched */
    var plansJson: String?
        get() = prefs.getString(PLANS, null)
        set(v) = prefs.edit().putString(PLANS, v).putLong(PLANS_AT, System.currentTimeMillis()).apply()
    val plansAt: Long get() = prefs.getLong(PLANS_AT, 0)

    /**
     * When the clipboard's content was put there (its own timestamp) for the newest clip dropit already dealt
     * with: sent, from any way in, or waved away. Only a newer one is offered on opening the app.
     */
    var clipSeen: Long
        get() = prefs.getLong(CLIP_SEEN, 0)
        set(v) = prefs.edit().putLong(CLIP_SEEN, v).apply()

    /** Drops handed over before this phone joined: held, and sent once it has */
    var waiting: Set<String>
        get() = prefs.getStringSet(WAITING, null).orEmpty()
        set(v) = prefs.edit().putStringSet(WAITING, v).apply()

    /** Background receiving: on unless the person turned it off. What it came to is [receiveState] */
    var receiveOn: Boolean
        get() = prefs.getBoolean(RECEIVE_ON, true)
        set(v) = prefs.edit().putBoolean(RECEIVE_ON, v).apply()

    /** `push` when a test push got through; `none` when it did not; `refused:<message key>` when the service said no; null: not tried */
    var receiveState: String?
        get() = prefs.getString(RECEIVE_STATE, null)
        set(v) = prefs.edit().putString(RECEIVE_STATE, v).apply()

    /** Where the next pull starts. 0 until the first one: the service then only gives what came after this phone joined */
    var cursor: Long
        get() = prefs.getLong(CURSOR, 0)
        set(v) = prefs.edit().putLong(CURSOR, v).apply()

    /** The push address the service was last told about, so it is told again only when it changed */
    var pushToken: String?
        get() = prefs.getString(PUSH_TOKEN, null)
        set(v) = prefs.edit().putString(PUSH_TOKEN, v).apply()

    /** The website's version file as last read, and when */
    var updateJson: String?
        get() = prefs.getString(UPDATE_JSON, null)
        set(v) = prefs.edit().putString(UPDATE_JSON, v).apply()
    var updateAt: Long
        get() = prefs.getLong(UPDATE_AT, 0)
        set(v) = prefs.edit().putLong(UPDATE_AT, v).apply()

    /** Whether the "Send clipboard" tile is in quick settings (the tile says so when added or shown) */
    var tileAdded: Boolean
        get() = prefs.getBoolean(TILE_ADDED, false)
        set(v) = prefs.edit().putBoolean(TILE_ADDED, v).apply()

    /** A setup hint the person waved away: not shown again */
    fun tipHidden(name: String): Boolean = prefs.getBoolean("tip_hidden_$name", false)
    fun hideTip(name: String) = prefs.edit().putBoolean("tip_hidden_$name", true).apply()

    /** Whether the first-run steps (notifications, background, quick settings, gestures) were gone through */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()

    override suspend fun endpoints(): List<String>? = prefs.getString(ENDPOINTS, null)?.split('\n')?.filter { it.isNotBlank() }
    override suspend fun setEndpoints(list: List<String>) { prefs.edit().putString(ENDPOINTS, list.joinToString("\n")).apply() }

    fun signedIn(token: String, deviceId: String) {
        prefs.edit().putString(TOKEN, Vault.seal(token)).putString(DEVICE_ID, deviceId).remove(PREVIOUS).remove(ME).remove(CURSOR).remove(PUSH_TOKEN).remove(RECEIVE_STATE).apply()
    }

    /**
     * Stop using this phone's key. It is kept as `previous_token` and sent with the next join, so the service
     * knows exactly which device this was and lets the new join take its place instead of using another slot.
     */
    fun forget() {
        val old = tokenNow()
        prefs.edit().remove(TOKEN).remove(DEVICE_ID).remove(ME).remove(CURSOR).remove(PUSH_TOKEN).remove(RECEIVE_STATE).apply()
        if (old != null) previousToken = old
    }

    private companion object {
        const val TOKEN = "token"
        const val PREVIOUS = "previous_token"
        const val DEVICE_ID = "device_id"
        const val CLIP_SEEN = "clip_seen"
        const val WAITING = "waiting"
        const val TILE_ADDED = "tile_added"
        const val ME = "me"
        const val ME_AT = "me_at"
        const val PLANS = "plans"
        const val PLANS_AT = "plans_at"
        const val ENDPOINTS = "endpoints"
        const val RECEIVE_ON = "receive_on"
        const val RECEIVE_STATE = "receive_state"
        const val CURSOR = "cursor"
        const val UPDATE_JSON = "update_json"
        const val UPDATE_AT = "update_at"
        const val PUSH_TOKEN = "push_token"
    }
}

/** AES-GCM with a key kept in the Android Keystore */
private object Vault {
    private const val ALIAS = "dropit-token"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun open(sealed: String): String? = runCatching {
        val all = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12))
    }.getOrNull()
}
