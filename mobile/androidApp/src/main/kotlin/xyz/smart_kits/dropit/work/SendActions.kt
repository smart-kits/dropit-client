package xyz.smart_kits.dropit.work

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Retry from a "not sent" notification: the held copies go again; what already went is not sent twice */
class SendActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(JOB) ?: return
        val outbox = Outbox(context)
        Notes(context).cancel(id)          // an action button does not close its notification; the new result replaces it
        if (outbox.load(id) != null) outbox.enqueue(id)
    }

    companion object {
        private const val JOB = "job"
        fun retry(context: Context, jobId: String): PendingIntent = PendingIntent.getBroadcast(
            context, jobId.hashCode(),
            Intent(context, SendActions::class.java).putExtra(JOB, jobId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
