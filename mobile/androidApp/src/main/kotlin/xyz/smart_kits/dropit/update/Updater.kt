package xyz.smart_kits.dropit.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.smart_kits.dropit.BuildConfig
import xyz.smart_kits.dropit.Store
import xyz.smart_kits.dropit.shared.UPDATE_PATH
import xyz.smart_kits.dropit.shared.UpdateInfo
import xyz.smart_kits.dropit.shared.UpdateVerdict
import xyz.smart_kits.dropit.shared.dueForCheck
import xyz.smart_kits.dropit.shared.parseUpdate
import xyz.smart_kits.dropit.shared.sameHash
import xyz.smart_kits.dropit.shared.updateFor
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Where the update is, for the screen */
sealed interface UpdateUi {
    data object None : UpdateUi
    /** [must]: this build may not be used until it is updated */
    data class Available(val info: UpdateInfo, val must: Boolean) : UpdateUi
    data class Downloading(val info: UpdateInfo, val must: Boolean, val done: Long) : UpdateUi
    /** Downloaded and checked: one tap installs it */
    data class Ready(val info: UpdateInfo, val must: Boolean, val file: File) : UpdateUi
    data class Failed(val info: UpdateInfo, val must: Boolean, val reason: Why) : UpdateUi
}

/** This build may not be used until it is updated */
val UpdateUi.mustUpdate: Boolean
    get() = when (this) {
        UpdateUi.None -> false
        is UpdateUi.Available -> must
        is UpdateUi.Downloading -> must
        is UpdateUi.Ready -> must
        is UpdateUi.Failed -> must
    }

enum class Why { NETWORK, MISMATCH }

/**
 * The website's build updates itself: the site's `android.json` says what the newest version is; the file
 * is fetched from the same service, checked against the hash that file gives, and only then handed to the
 * system, which installs it only over a build signed with the same key.
 */
class Updater(private val context: Context, private val store: Store) {
    private val _state = MutableStateFlow<UpdateUi>(UpdateUi.None)
    val state: StateFlow<UpdateUi> get() = _state
    private val lock = Mutex()

    /** The version whose dialog was closed with "Later": not shown again until the app starts anew. A required update cannot be closed. */
    private val _dismissed = MutableStateFlow(0)
    val dismissed: StateFlow<Int> get() = _dismissed
    fun dismiss(versionCode: Int) { _dismissed.value = versionCode }

    private val bases = BuildConfig.ENDPOINTS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    private val dir get() = File(context.cacheDir, "updates")

    /** Whether this build updates itself at all (the one from the store does not: the store does it) */
    val enabled: Boolean get() = BuildConfig.SELF_UPDATE

    init {
        // What was downloaded and installed is no longer needed; what the file said last time still counts, offline too
        if (enabled) {
            // Only what is already installed (or older) goes: a newer one that was downloaded may be waiting for the person to allow installing
            runCatching { dir.listFiles()?.filter { (Regex("dropit-(\\d+)\\.apk").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0) <= BuildConfig.VERSION_CODE }?.forEach { it.delete() } }
            store.updateJson?.let { remember(it, BuildConfig.VERSION_CODE) }
        }
    }

    private fun remember(text: String, installed: Int) {
        val info = bases.firstNotNullOfOrNull { parseUpdate(text, it) } ?: return
        (updateFor(info, installed) as? UpdateVerdict.Available)?.let { _state.value = UpdateUi.Available(it.info, it.must) }
    }

    /**
     * Read the version file, at most once a day unless [asked]. A build that must update shows that straight away
     * from what was read last, even offline. Returns false when the file could not be read.
     */
    suspend fun check(asked: Boolean = false): Boolean {
        if (!enabled) return true
        if (!dueForCheck(store.updateAt, System.currentTimeMillis(), asked)) return true
        return lock.withLock {
            val text = withContext(Dispatchers.IO) { bases.firstNotNullOfOrNull { fetch(it + UPDATE_PATH) } } ?: return@withLock false
            val info = bases.firstNotNullOfOrNull { parseUpdate(text, it) } ?: return@withLock false
            store.updateJson = text
            store.updateAt = System.currentTimeMillis()
            when (val v = updateFor(info, BuildConfig.VERSION_CODE)) {
                UpdateVerdict.UpToDate -> if (_state.value is UpdateUi.Available) _state.value = UpdateUi.None
                is UpdateVerdict.Available -> if (_state.value !is UpdateUi.Downloading && _state.value !is UpdateUi.Ready) _state.value = UpdateUi.Available(v.info, v.must)
            }
            true
        }
    }

    private fun fetch(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText().take(64 * 1024) } else null } finally { c.disconnect() }
    }.getOrNull()

    /** Download the file, check its hash, and keep it ready. Nothing is installed here. */
    suspend fun download() {
        val now = _state.value
        val (info, must) = when (now) {
            is UpdateUi.Available -> now.info to now.must
            is UpdateUi.Failed -> now.info to now.must
            else -> return
        }
        _state.value = UpdateUi.Downloading(info, must, 0)
        // Whatever happens to this coroutine, the state must not be left saying "downloading": the screen's own scope is gone on a rotation
        val result = try { fetchChecked(info, must) } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = UpdateUi.Failed(info, must, Why.NETWORK)
            throw e
        }
        _state.value = if (result is File) UpdateUi.Ready(info, must, result) else UpdateUi.Failed(info, must, result as Why)
    }

    /** The file, checked against its hash, or why not. A file kept from before is used when it still matches. */
    private suspend fun fetchChecked(info: UpdateInfo, must: Boolean): Any {
        // Never more than was announced (plus a little), and never longer than ten minutes: the hash is only checked at the end
        val limit = if (info.size > 0) info.size + (1L shl 20) else 100L shl 20
        val deadline = System.nanoTime() + 10 * 60_000_000_000L
        return withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val out = File(dir, "dropit-${info.versionCode}.apk")
                if (out.exists() && sameHash(info.sha256, sha256Of(out))) return@runCatching out
                val md = MessageDigest.getInstance("SHA-256")
                var total = 0L
                val c = URL(info.url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                try {
                    if (c.responseCode != 200) return@runCatching null
                    c.inputStream.use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n); md.update(buf, 0, n); total += n
                                if (total > limit) { out.delete(); return@runCatching Why.MISMATCH }
                                if (System.nanoTime() > deadline) { out.delete(); return@runCatching Why.NETWORK }
                                if (total % (256 * 1024) < buf.size) _state.value = UpdateUi.Downloading(info, must, total)
                            }
                        }
                    }
                } finally { c.disconnect() }
                val hash = md.digest().joinToString("") { "%02x".format(it) }
                if (sameHash(info.sha256, hash)) out else { out.delete(); Why.MISMATCH }
            }.getOrNull() ?: Why.NETWORK
        }
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buf = ByteArray(64 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Hand the checked file to the system's installer. False when this app is not yet allowed to install:
     * the system's page for that opens, and the person taps Install again when back.
     */
    fun install(): Boolean {
        val ready = _state.value as? UpdateUi.Ready ?: return false
        // The file may be gone (the system cleared the cache): say so and let it be fetched again
        if (!ready.file.exists()) { _state.value = UpdateUi.Failed(ready.info, ready.must, Why.NETWORK); return false }
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", ready.file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}
