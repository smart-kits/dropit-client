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
import xyz.smart_kits.dropit.shared.Targets
import xyz.smart_kits.dropit.ui.MainActivity
import xyz.smart_kits.dropit.ui.targetText
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

    /**
     * The note while a drop goes out: what is being sent ("Sending 3 photos"), and under it who it goes to and how far ("→ All · 2 / 3"),
     * with a bar and Cancel
     */
    fun progress(jobId: String, k: Int, n: Int, workId: UUID): Notification {
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(workId)
        val job = runCatching { Outbox(context).load(jobId) }.getOrNull()
        val count = job?.files?.count { it.copied } ?: 0
        val what = when {
            count == 0 -> null
            job!!.files.filter { it.copied }.all { it.mime.orEmpty().startsWith("image/") } -> context.resources.getQuantityString(R.plurals.card_n_photos, count, count)
            else -> context.resources.getQuantityString(R.plurals.card_n_files, count, count)
        }
        val to = if (job == null) null else "→ " + targetText(context, Targets(job.to, job.toNames))
        val line = listOfNotNull(to, if (n > 1) context.getString(R.string.progress_k_of_n, k, n) else null).joinToString(" · ")
        return base()
            .setContentTitle(if (what == null) context.getString(R.string.card_sending) else context.getString(R.string.card_sending_what, what))
            .setContentText(line.ifEmpty { if (n > 1) context.getString(R.string.sending_n, k, n) else context.getString(R.string.sending) })
            .setProgress(n, k, n <= 1)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, context.getString(R.string.action_cancel), cancel)
            .build()
    }

    /** [quiet]: the card on screen already says it, what did not go included (the card shows it in red); only the buzz. Not on screen: the note carries it */
    fun sent(jobId: String, text: String, extra: List<String>, quiet: Boolean = false) {
        buzz()
        if (quiet) return
        // A note that does nothing when tapped looks broken: it opens dropit, and goes
        val b = base().setContentText(text).setAutoCancel(true).setContentIntent(open())
        if (extra.isEmpty()) b.setTimeoutAfter(3_000)
        else b.setStyle(NotificationCompat.BigTextStyle().bigText((listOf(text) + extra).joinToString("\n")))
        post(jobId, b.build())
    }

    fun failed(jobId: String, reason: String, retry: Boolean, rejoin: Boolean = false, upgrade: String? = null, toAll: Boolean = false, why: String? = null) {
        val b = base()
            .setContentTitle(context.getString(R.string.send_failed))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
        // Some systems fold a notification's buttons away (HyperOS: two taps deep), so tapping the note itself
        // does the one useful thing too: rejoin, or retry
        if (rejoin) b.setContentIntent(open())
        // A limit that a paid plan lifts: trying again would hit it again, so the way out is Upgrade, which opens the plans page at it
        if (upgrade != null && xyz.smart_kits.dropit.BuildConfig.PAYMENTS) {
            val checkout = open(MainActivity.SHOW_CHECKOUT, why)
            b.setContentIntent(checkout)
            b.addAction(0, context.getString(R.string.action_upgrade), checkout)
        }
        // The devices it was addressed to are gone: what was held can go to everyone this time, in one tap
        if (toAll) {
            val all = SendActions.toAll(context, jobId)
            b.setContentIntent(all)
            b.addAction(0, context.getString(R.string.action_send_all), all)
        }
        if (retry) {
            val again = SendActions.retry(context, jobId)
            if (!rejoin) b.setContentIntent(again)
            b.addAction(0, context.getString(R.string.action_retry), again)
        }
        post(jobId, b.build())
    }

    fun cancel(jobId: String) = nm.cancel(idFor(jobId))

    private fun open(show: String? = null, why: String? = null) = PendingIntent.getActivity(
        context, "$show/$why".hashCode(),
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
            if (show != null) putExtra(MainActivity.SHOW, show)
            if (why != null) putExtra(MainActivity.WHY, why)
        },
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
