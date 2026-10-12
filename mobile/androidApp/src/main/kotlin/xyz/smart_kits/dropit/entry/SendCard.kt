package xyz.smart_kits.dropit.entry

import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.work.Notes
import xyz.smart_kits.dropit.work.OnScreen
import xyz.smart_kits.dropit.work.Outbox
import xyz.smart_kits.dropit.work.SendWorker

/**
 * A small card at the bottom of the screen while a drop goes out, gone by itself once it is sent.
 *
 * Why it is there at all: some systems (HyperOS) freeze an app about half a second after it leaves the
 * screen, foreground job or not, until the person touches it again — a drop could sit at "sending" for
 * minutes. An app that is on screen is not frozen. Where the person has let dropit run unrestricted the
 * card is not needed, and not shown.
 *
 * Touches outside the card go to the app underneath.
 */
class SendCard(private val activity: ComponentActivity, private val jobId: String) {
    private lateinit var status: TextView
    private lateinit var action: TextView
    private var closing: Job? = null

    fun show() = activity.run {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContentView(R.layout.send_card)
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setFinishOnTouchOutside(true)    // touches outside cannot reach the app below (tried 10-11): they close the card
        // The window reaches under the navigation bar (or the bar is drawn black), so the card keeps clear of it
        val root = findViewById<View>(R.id.card_root)
        root.post {
            val insets = root.rootWindowInsets ?: return@post
            @Suppress("DEPRECATION") val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else insets.systemWindowInsetBottom
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, bottom)
        }
        status = findViewById(R.id.card_status)
        action = findViewById(R.id.card_action)
        findViewById<View>(R.id.card_close).setOnClickListener { finish() }   // goes on without the card
        status.setText(R.string.sending)
        OnScreen.add(jobId)
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { OnScreen.remove(jobId) }
        })
        lifecycleScope.launch {
            WorkManager.getInstance(this@run).getWorkInfosForUniqueWorkFlow(jobId).collect { list ->
                list.firstOrNull()?.let(::render)
            }
        }
    }

    private fun render(info: WorkInfo) {
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING -> {
                closing?.cancel()
                // The notification's Cancel is folded away on some systems; here it is one tap
                action.visibility = View.VISIBLE
                action.setText(R.string.action_cancel)
                action.setOnClickListener { WorkManager.getInstance(activity).cancelUniqueWork(jobId) }
                val k = info.progress.getInt(SendWorker.K, 0)
                val n = info.progress.getInt(SendWorker.N, 0)
                status.text = if (n > 1 && k > 0) activity.getString(R.string.sending_n, k, n) else activity.getString(R.string.sending)
            }
            WorkInfo.State.SUCCEEDED -> {
                val said = info.outputData.getString(SendWorker.DONE).orEmpty()
                status.text = "✓ $said"
                action.visibility = View.GONE
                closeAfter(if (said.contains('\n')) 3_000 else 1_200)   // something left out: time to read it
            }
            WorkInfo.State.FAILED -> {
                status.text = info.outputData.getString(SendWorker.ERROR) ?: activity.getString(R.string.send_failed)
                if (info.outputData.getBoolean(SendWorker.RETRY, false)) {
                    action.visibility = View.VISIBLE
                    action.setText(R.string.action_retry)
                    action.setOnClickListener {
                        Notes(activity).cancel(jobId)
                        Outbox(activity).enqueue(jobId)
                    }
                }
                closeAfter(6_000)      // the notification stays, with the same Retry
            }
            WorkInfo.State.CANCELLED -> activity.finish()
        }
    }

    private fun closeAfter(ms: Long) {
        closing?.cancel()
        closing = activity.lifecycleScope.launch { delay(ms); activity.finish() }
    }
}
