package xyz.smart_kits.dropit.work

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * Retry from a "not sent" notification: the held copies go again; what already went is not sent twice. Or, when the devices it was
 * addressed to are gone, send what was held to everyone this time.
 */
class SendActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(JOB) ?: return
        val toAll = intent.action == TO_ALL
        // Handing the job to the system waits for a database write: not on the main thread, which is where a receiver runs
        val pending = goAsync()
        thread(name = "dropit-retry") {
            try {
                val outbox = Outbox(context)
                if (outbox.load(id) == null) return@thread
                // Pressed twice, or while a run is still going: the run that has it goes on (start says no); the notification still goes,
                // as an action button does not close its notification and the run's own result replaces it
                outbox.start(id, toAll)
                Notes(context).cancel(id)
            } finally {
                pending.finish()
            }
        }
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
