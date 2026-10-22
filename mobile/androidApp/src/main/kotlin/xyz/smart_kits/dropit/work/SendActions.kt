package xyz.smart_kits.dropit.work

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Retry from a "not sent" notification: the held copies go again; what already went is not sent twice. Or, when the devices it was
 * addressed to are gone, send what was held to everyone this time.
 */
class SendActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(JOB) ?: return
        val outbox = Outbox(context)
        Notes(context).cancel(id)          // an action button does not close its notification; the new result replaces it
        val job = outbox.load(id) ?: return
        if (intent.action == TO_ALL) outbox.save(job.copy(to = emptyList(), toNames = emptyList()))
        outbox.enqueue(id)
    }

    companion object {
        private const val JOB = "job"
        private const val TO_ALL = "xyz.smart_kits.dropit.SEND_TO_ALL"

        fun retry(context: Context, jobId: String): PendingIntent = PendingIntent.getBroadcast(
            context, jobId.hashCode(),
            Intent(context, SendActions::class.java).putExtra(JOB, jobId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** What was held goes to everyone: the choice of devices that no longer exist is dropped from it, then it is sent */
        fun toAll(context: Context, jobId: String): PendingIntent = PendingIntent.getBroadcast(
            context, ("all:" + jobId).hashCode(),
            Intent(context, SendActions::class.java).setAction(TO_ALL).putExtra(JOB, jobId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
