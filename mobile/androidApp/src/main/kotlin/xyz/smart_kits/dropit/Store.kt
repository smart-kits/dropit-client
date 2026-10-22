package xyz.smart_kits.dropit

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import xyz.smart_kits.dropit.shared.ClientStore
import xyz.smart_kits.dropit.shared.IMAGE_ORIGINAL
import xyz.smart_kits.dropit.shared.IMAGE_SHRINK
import xyz.smart_kits.dropit.shared.TicketLedger
import xyz.smart_kits.dropit.shared.TileKind
import xyz.smart_kits.dropit.work.WaitingSet
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
    private val waitingSet = WaitingSet(
        read = { prefs.getStringSet(WAITING, null).orEmpty().toSet() },
        write = { prefs.edit().putStringSet(WAITING, it).apply() },
    )
    val waiting: Set<String> get() = prefs.getStringSet(WAITING, null).orEmpty()
    fun addWaiting(id: String) = waitingSet.add(id)
    /** All of them at once, leaving none: sent as soon as the phone has joined */
    fun takeWaiting(): Set<String> = waitingSet.take()

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

    /** When the list of what this phone sent was last looked at: anything sent after it puts a dot on the tab */
    var sentSeenAt: Long
        get() = prefs.getLong("sent_seen_at", 0)
        set(v) = prefs.edit().putLong("sent_seen_at", v).apply()

    /**
     * Whether big photos are made smaller before they are sent: [IMAGE_SHRINK] (the default) or [IMAGE_ORIGINAL]. [shrinkImages] is the
     * same thing as a yes / no, for a switch.
     */
    var imageMode: String
        get() = prefs.getString(IMAGE_MODE, IMAGE_SHRINK) ?: IMAGE_SHRINK
        set(v) = prefs.edit().putString(IMAGE_MODE, if (v == IMAGE_ORIGINAL) IMAGE_ORIGINAL else IMAGE_SHRINK).apply()
    var shrinkImages: Boolean
        get() = imageMode != IMAGE_ORIGINAL
        set(v) { imageMode = if (v) IMAGE_SHRINK else IMAGE_ORIGINAL }

    /** How many connection tickets this phone took on which UTC day (see [TicketLedger]): the day's limit survives the app being closed */
    var ticketLedger: TicketLedger
        get() = TicketLedger(prefs.getLong(TICKET_DAY, -1), prefs.getInt(TICKET_COUNT, 0))
        set(v) = prefs.edit().putLong(TICKET_DAY, v.day).putInt(TICKET_COUNT, v.count).apply()

    /** When today's downloads start again (epoch ms), once the service has said; 0 when not limited. Survives the app being closed */
    var downloadLimitUntil: Long
        get() = prefs.getLong(DOWNLOAD_LIMIT_UNTIL, 0)
        set(v) = prefs.edit().putLong(DOWNLOAD_LIMIT_UNTIL, v).apply()

    /** When the last test for push was made, and on what kind of network (`wifi`, `cellular`, `other`): so a switch of network is not a reason to test again every time */
    var probeAt: Long
        get() = prefs.getLong(PROBE_AT, 0)
        set(v) = prefs.edit().putLong(PROBE_AT, v).apply()
    var probeNet: String?
        get() = prefs.getString(PROBE_NET, null)
        set(v) = prefs.edit().putString(PROBE_NET, v).apply()

    /** Whether a quick-settings tile was last found to be in the pull-down panel: what the system said when it was asked to add it, or when it was shown / removed. Only a record */
    fun tileAdded(kind: TileKind): Boolean = when (kind) {
        TileKind.SEND_CLIPBOARD -> tileAdded
        TileKind.RECEIVE_LATEST -> prefs.getBoolean(TILE_LATEST_ADDED, false)
    }
    fun setTileAdded(kind: TileKind, added: Boolean) {
        when (kind) {
            TileKind.SEND_CLIPBOARD -> tileAdded = added
            TileKind.RECEIVE_LATEST -> prefs.edit().putBoolean(TILE_LATEST_ADDED, added).apply()
        }
    }

    /** The floating button (an experiment, only in builds that have it): off unless the person turns it on */
    var overlayBall: Boolean
        get() = prefs.getBoolean(OVERLAY_BALL, false)
        set(v) = prefs.edit().putBoolean(OVERLAY_BALL, v).apply()

    /**
     * Who a send goes to when nothing is chosen: device ids, empty = everyone (so a device that joins later is included). Kept on this
     * phone only; the service is not told. A send to everyone carries no `to`.
     */
    var defaultTo: List<String>
        get() = prefs.getString(DEFAULT_TO, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        set(v) = prefs.edit().putString(DEFAULT_TO, v.distinct().joinToString(",")).apply()

    /** The devices as last listed by the service, so the panel has something to show before (or without) an answer */
    var peersJson: String?
        get() = prefs.getString(PEERS, null)
        set(v) = prefs.edit().putString(PEERS, v).apply()

    /** Something about the default the person has not been told yet: `removed:<n>` (n devices dropped out) or `reset` (all of them were gone) */
    var targetsNote: String?
        get() = prefs.getString(TARGETS_NOTE, null)
        set(v) = prefs.edit().putString(TARGETS_NOTE, v).apply()

    /** Whether text that another device sent is put on the clipboard when it arrives (off unless turned on) */
    var autoClip: Boolean
        get() = prefs.getBoolean(AUTO_CLIP, false)
        set(v) = prefs.edit().putBoolean(AUTO_CLIP, v).apply()

    /** When "pull again" was last asked for (epoch ms): at most one a minute */
    var repullAt: Long
        get() = prefs.getLong(REPULL_AT, 0)
        set(v) = prefs.edit().putLong(REPULL_AT, v).apply()

    /** A setup hint the person waved away: not shown again */
    fun tipHidden(name: String): Boolean = prefs.getBoolean("tip_hidden_$name", false)
    fun hideTip(name: String) = prefs.edit().putBoolean("tip_hidden_$name", true).apply()

    /**
     * What the upgrade notes have already said or been waved away from (see Moments): one small text per key, `dropit.moment.…`.
     * Reading or writing may fail (the file can't be opened, the disk is full); the caller treats that as "nothing to show".
     */
    fun momentGet(key: String): String? = prefs.getString(key, null)
    fun momentPut(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    /** What the notes know about the account itself (its last plan, when a limit was last met) goes when the account on this phone changes */
    private fun forgetMomentState() {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("dropit.moment.state.") }.forEach { e.remove(it) }
        e.apply()
    }

    /** Whether the first-run steps (notifications, background, quick settings, gestures) were gone through */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()

    override suspend fun endpoints(): List<String>? = prefs.getString(ENDPOINTS, null)?.split('\n')?.filter { it.isNotBlank() }
    override suspend fun setEndpoints(list: List<String>) { prefs.edit().putString(ENDPOINTS, list.joinToString("\n")).apply() }

    fun signedIn(token: String, deviceId: String) {
        forgetMomentState()
        prefs.edit().putString(TOKEN, Vault.seal(token)).putString(DEVICE_ID, deviceId).remove(PREVIOUS).remove(ME).remove(CURSOR).remove(PUSH_TOKEN).remove(RECEIVE_STATE).remove(DOWNLOAD_LIMIT_UNTIL).remove(DEFAULT_TO).remove(PEERS).remove(TARGETS_NOTE).apply()
    }

    /**
     * Stop using this phone's key. It is kept as `previous_token` and sent with the next join, so the service
     * knows exactly which device this was and lets the new join take its place instead of using another slot.
     */
    fun forget() {
        val old = tokenNow()
        forgetMomentState()
        prefs.edit().remove(TOKEN).remove(DEVICE_ID).remove(ME).remove(CURSOR).remove(PUSH_TOKEN).remove(RECEIVE_STATE).remove(DOWNLOAD_LIMIT_UNTIL).remove(DEFAULT_TO).remove(PEERS).remove(TARGETS_NOTE).apply()
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
        const val IMAGE_MODE = "image_mode"
        const val TICKET_DAY = "ticket_day"
        const val TICKET_COUNT = "ticket_count"
        const val DOWNLOAD_LIMIT_UNTIL = "download_limit_until"
        const val PROBE_AT = "probe_at"
        const val PROBE_NET = "probe_net"
        const val TILE_LATEST_ADDED = "tile_latest_added"
        const val OVERLAY_BALL = "overlay_ball"
        const val DEFAULT_TO = "default_to"
        const val PEERS = "peers"
        const val TARGETS_NOTE = "targets_note"
        const val AUTO_CLIP = "auto_clip"
        const val REPULL_AT = "repull_at"
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
