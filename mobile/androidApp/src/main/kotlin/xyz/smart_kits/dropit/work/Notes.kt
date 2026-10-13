package xyz.smart_kits.dropit.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import xyz.smart_kits.dropit.App
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.ui.MainActivity
import java.util.UUID

/** Drops whose card is on screen: their result shows there, not in a notification as well */
object OnScreen {
    private val ids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun add(id: String) = ids.add(id)
    fun remove(id: String) = ids.remove(id)
    fun has(id: String) = id in ids
}

/**
 * Drops sent from dropit's own screen while it is open: the screen says how they went and keeps what was
 * written, so a failure needs no notification and no held copy (a Retry there would send it twice)
 */
object InApp {
    private val ids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun add(id: String) = ids.add(id)
    fun remove(id: String) = ids.remove(id)
    fun has(id: String) = id in ids
}

/** The one notification each drop ends in */
class Notes(private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)

    val allowed: Boolean
        get() = nm.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    private fun base() = NotificationCompat.Builder(context, App.CHANNEL_SEND)
        .setSmallIcon(R.drawable.ic_tile)
        .setContentTitle(context.getString(R.string.app_name))

    fun progress(jobId: String, k: Int, n: Int, workId: UUID): Notification {
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(workId)
        return base()
            .setContentText(if (n > 1) context.getString(R.string.sending_n, k, n) else context.getString(R.string.sending))
            .setProgress(n, k, n <= 1)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, context.getString(R.string.action_cancel), cancel)
            .build()
    }

    /** [quiet]: the card on screen already says it; only the buzz */
    fun sent(jobId: String, text: String, extra: List<String>, quiet: Boolean = false) {
        buzz()
        if (quiet) return
        // A note that does nothing when tapped looks broken: it opens dropit, and goes
        val b = base().setContentText(text).setAutoCancel(true).setContentIntent(open())
        if (extra.isEmpty()) b.setTimeoutAfter(3_000)
        else b.setStyle(NotificationCompat.BigTextStyle().bigText((listOf(text) + extra).joinToString("\n")))
        post(jobId, b.build())
    }

    fun failed(jobId: String, reason: String, retry: Boolean, rejoin: Boolean = false, upgrade: String? = null) {
        val b = base()
            .setContentTitle(context.getString(R.string.send_failed))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
        // Some systems fold a notification's buttons away (HyperOS: two taps deep), so tapping the note itself
        // does the one useful thing too: rejoin, or retry
        if (rejoin) b.setContentIntent(open())
        // A limit that a paid plan lifts: trying again would hit it again, so the way out is Upgrade
        if (upgrade != null && xyz.smart_kits.dropit.BuildConfig.PAYMENTS) {
            val plans = open(MainActivity.SHOW_PLANS)
            b.setContentIntent(plans)
            b.addAction(0, context.getString(R.string.action_upgrade), plans)
        }
        if (retry) {
            val again = SendActions.retry(context, jobId)
            if (!rejoin) b.setContentIntent(again)
            b.addAction(0, context.getString(R.string.action_retry), again)
        }
        post(jobId, b.build())
    }

    fun cancel(jobId: String) = nm.cancel(idFor(jobId))

    private fun open(show: String? = null) = PendingIntent.getActivity(
        context, show.hashCode(),
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { if (show != null) putExtra(MainActivity.SHOW, show) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission")
    private fun post(jobId: String, n: Notification) {
        if (allowed) nm.notify(idFor(jobId), n)
    }

    /** A short buzz: it went */
    private fun buzz() = runCatching {
        val v = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    companion object {
        fun idFor(jobId: String) = jobId.hashCode()

        /**
         * The progress note is the foreground job's, and the system takes it away when the job ends — after the
         * result is posted. Sharing the result's id would take the result away with it.
         */
        fun progressIdFor(jobId: String) = "progress:$jobId".hashCode()
    }
}
