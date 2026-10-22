package xyz.smart_kits.dropit.receive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.ServiceNext
import xyz.smart_kits.dropit.shared.liveServiceNext
import xyz.smart_kits.dropit.ui.MainActivity

/**
 * Holds the connection that tells this phone something arrived (see [Live]) when system push does not get through. It exists only
 * while background receiving is on and has settled on a connection; switching receiving off, or push working after all, ends it.
 * Its notice is the quietest the system allows: no icon in the status bar, no sound, no content, no buttons.
 */
class ReceiveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The system may refuse to make it a foreground service (started again by itself while the app may not start one): then it does not
        // run, and does not crash; the app's next start or opening brings the connection back. Switched off, or signed out, since this was
        // asked for: nothing to keep either
        if (liveServiceNext(goForeground(), app.store.receiveOn, app.store.tokenNow() != null) == ServiceNext.STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        app.receiving.live.start()
        return START_STICKY
    }

    override fun onDestroy() {
        app.receiving.live.stop()
        super.onDestroy()
    }

    /** False when the system would not have it as a foreground service (or the notice could not be made) */
    private fun goForeground(): Boolean = try {
        becomeForeground()
        true
    } catch (e: Exception) {
        false
    }

    private fun becomeForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.bg_channel), NotificationManager.IMPORTANCE_MIN).apply {
                description = getString(R.string.bg_channel_desc)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val note: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.bg_receiving))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, note)
    }

    companion object {
        const val CHANNEL = "receiving"
        private const val ID = 7301

        /** Start it, if the system lets this app start one right now (not from the background on newer Android: then it waits for the app to be opened) */
        fun start(context: Context) {
            // Refused: only written down (after the phone starts, or the app is updated, this is not a moment the app may start one)
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ReceiveService::class.java)) }
                .onFailure { android.util.Log.w("dropit", "receiving service not started: ${it.javaClass.simpleName}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ReceiveService::class.java)) }
        }
    }
}

/** After the phone starts, and after dropit is updated: the connection comes back by itself when that is how this phone receives */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Starting a foreground service from here can be refused (the app may not start one at this moment): nothing to do about it then
        runCatching { context.app.receiving.resumeLive() }
            .onFailure { android.util.Log.w("dropit", "receiving not resumed: ${it.javaClass.simpleName}") }
    }
}
