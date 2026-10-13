package xyz.smart_kits.dropit

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import xyz.smart_kits.dropit.entry.ClipboardActivity
import xyz.smart_kits.dropit.shared.DropitClient
import xyz.smart_kits.dropit.shared.Strings
import xyz.smart_kits.dropit.work.Outbox
import java.util.Locale

class App : Application() {
    lateinit var store: Store
        private set
    lateinit var client: DropitClient
        private set
    lateinit var account: Account
        private set
    lateinit var letters: LetterBox
        private set

    val strings: Strings get() = Strings.forLanguage(Locale.getDefault().toLanguageTag())

    /**
     * Whether the person let dropit run without battery limits. HyperOS's "no restrictions" puts the app on
     * this list (checked 10-11); without it, HyperOS pauses dropit half a second after it leaves the screen.
     */
    val runsInBackground: Boolean
        get() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /**
     * Ask for that, in as few taps as this build allows: the system's one-tap dialog where the build may
     * ask (it needs a permission store builds leave out), else this app's settings page.
     */
    fun askRunInBackground(from: android.content.Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        val page = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        val mayAsk = checkSelfPermission(android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) == PackageManager.PERMISSION_GRANTED
        runCatching { from.startActivity(if (mayAsk) direct else page) }.onFailure { runCatching { from.startActivity(page) } }
    }

    /** Send what was handed over before joining (see Intake) */
    fun sendWaiting() {
        val ids = store.waiting
        store.waiting = emptySet()
        ids.forEach(Outbox(this)::enqueue)
    }

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        val http = HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 60_000
            }
        }
        client = DropitClient(http, store, BuildConfig.ENDPOINTS.split(','))
        account = Account(this, store, client)
        letters = LetterBox(this)
        createChannels()
        addShortcuts()
        Outbox(this).sweep()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SEND, getString(R.string.channel_send), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.channel_send_desc)
                setSound(null, null)
            },
        )
    }

    /**
     * Long-press the home-screen icon → "Send clipboard"; and "My devices" for the share sheet's top row,
     * where the system has one: tapping it sends straight away. Dynamic shortcuts work for any build's package.
     */
    private fun addShortcuts() {
        val clipboard = ShortcutInfoCompat.Builder(this, "send_clipboard")
            .setShortLabel(getString(R.string.send_clipboard))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_logo))
            .setIntent(Intent(this, ClipboardActivity::class.java).setAction(Intent.ACTION_VIEW))
            .build()
        val share = ShortcutInfoCompat.Builder(this, "my_devices")
            .setShortLabel(getString(R.string.shortcut_devices))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_logo))
            .setIntent(Intent(this, xyz.smart_kits.dropit.ui.MainActivity::class.java).setAction(Intent.ACTION_VIEW))
            .setCategories(setOf("xyz.smart_kits.dropit.category.SHARE"))
            .setLongLived(true)
            .build()
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(this, listOf(clipboard, share)) }
    }

    companion object {
        const val CHANNEL_SEND = "send"
    }
}

val android.content.Context.app: App get() = applicationContext as App
