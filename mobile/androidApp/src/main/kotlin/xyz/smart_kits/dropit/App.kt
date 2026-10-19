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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xyz.smart_kits.dropit.receive.Arrivals
import xyz.smart_kits.dropit.receive.Downloader
import xyz.smart_kits.dropit.receive.Receiving
import xyz.smart_kits.dropit.shared.DropitClient
import xyz.smart_kits.dropit.shared.Strings
import xyz.smart_kits.dropit.update.Updater
import xyz.smart_kits.dropit.work.Outbox
import java.util.Locale

class App : Application() {
    lateinit var store: Store
        private set
    lateinit var client: DropitClient
        private set
    lateinit var account: Account
        private set
    lateinit var plans: PlansRepo
        private set
    lateinit var downloader: Downloader
        private set
    lateinit var letters: LetterBox
        private set
    lateinit var receiving: Receiving
        private set
    lateinit var arrivals: Arrivals
        private set
    lateinit var updater: Updater
        private set

    /** For what has to go on after the screen is gone: telling the service this phone's push address, catching up */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            // A connection is the expensive part on a bad phone network (measured here: ~0.9 s round trip, a third of packets lost, so
            // opening one takes 5–8 s): it is kept and used again. A small heartbeat keeps it alive and notices a dead one in seconds
            // instead of at the read timeout. Ordinary requests have their own deadline in the client; the socket limit is for files
            engine {
                config {
                    pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 12_000
                socketTimeoutMillis = 60_000
            }
        }
        client = DropitClient(http, store, BuildConfig.ENDPOINTS.split(','), trace = { android.util.Log.i("dropit-net", it) })
        account = Account(this, store, client)
        plans = PlansRepo(store, client)
        letters = LetterBox(this)
        arrivals = Arrivals(this)
        downloader = Downloader(this, client, letters)
        receiving = Receiving(this, store, client, letters, arrivals) {
            // The service keeps things 1 day on the free plan and 10 on the paid one (the service's numbers when it has said them)
            val days = if (account.me()?.paid == true) plans.paid.days else plans.free.days
            days * 86_400_000L
        }
        receiving.init()
        updater = Updater(this, store)
        createChannels()
        addShortcuts()
        Outbox(this).sweep()
        if (updater.enabled) scope.launch { updater.check() }
        // What is on sale and for how much: asked for again when what is kept is more than an hour old
        scope.launch { plans.refresh() }
        // Each start: the service is told the push address if it changed; if the last check could not be made (no network,
        // the service could not send), it is made again now. What is waiting is NOT fetched here: this runs for every process
        // start, including the one a push wakes — and a quiet pull here would take what that push is about to announce.
        // The screen fetches it when it opens (MainActivity.onResume), whatever became of the push address.
        if (account.signedIn) {
            scope.launch {
                if (store.receiveOn && store.receiveState == null) receiving.enable()
                else receiving.register()
            }
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SEND, getString(R.string.channel_send), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.channel_send_desc)
                setSound(null, null)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECEIVE, getString(R.string.channel_receive), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.channel_receive_desc)
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
        const val CHANNEL_RECEIVE = "receive"
    }
}

val android.content.Context.app: App get() = applicationContext as App
