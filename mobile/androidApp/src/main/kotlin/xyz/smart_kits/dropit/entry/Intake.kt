package xyz.smart_kits.dropit.entry

import android.widget.Toast
import androidx.activity.ComponentActivity
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.ui.MainActivity
import xyz.smart_kits.dropit.work.Inbound
import xyz.smart_kits.dropit.work.Notes
import xyz.smart_kits.dropit.work.Outbox
import kotlin.concurrent.thread

/**
 * Every way in ends here: copy what was handed over, hand it to the background, then either close or stay
 * as a small card until it is sent (see [SendCard]). No questions are asked before sending. Copying runs off
 * the main thread but before the screen closes, while the other app's permission to read its files still holds.
 */
fun ComponentActivity.takeIn(inbound: Inbound, emptyMessage: Int = R.string.nothing_to_send) {
    if (inbound.isEmpty) {
        if (!app.account.signedIn) startActivity(android.content.Intent(this, MainActivity::class.java))
        else Toast.makeText(this, emptyMessage, Toast.LENGTH_LONG).show()
        finish()
        return
    }
    // Not joined yet: what was handed over is kept (copied now, while it can still be read) and goes out
    // as soon as the phone has joined, instead of being lost on the way to the join screen
    if (!app.account.signedIn) {
        thread(name = "dropit-intake") {
            val job = Outbox(this).hold(inbound)
            app.store.waiting = app.store.waiting + job.id
            runOnUiThread {
                startActivity(android.content.Intent(this, MainActivity::class.java))
                finish()
            }
        }
        return
    }
    thread(name = "dropit-intake") {
        val outbox = Outbox(this)
        val job = outbox.hold(inbound)
        outbox.enqueue(job.id)
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            if (!app.runsInBackground) {
                SendCard(this, job.id).show()
                return@runOnUiThread
            }
            // The result arrives as a notification; without notifications, at least say it was taken
            if (!Notes(this).allowed) Toast.makeText(this, R.string.taken, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
