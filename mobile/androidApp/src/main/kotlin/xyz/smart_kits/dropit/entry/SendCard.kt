package xyz.smart_kits.dropit.entry

import android.content.Intent
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import xyz.smart_kits.dropit.BuildConfig
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.ui.MainActivity
import xyz.smart_kits.dropit.ui.kit.Envelope
import xyz.smart_kits.dropit.ui.kit.Icons
import xyz.smart_kits.dropit.ui.kit.LinkAction
import xyz.smart_kits.dropit.ui.kit.Returned
import xyz.smart_kits.dropit.ui.kit.Stripe
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.theme.DropitTheme
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.sans
import xyz.smart_kits.dropit.work.Notes
import xyz.smart_kits.dropit.work.OnScreen
import xyz.smart_kits.dropit.work.Outbox
import xyz.smart_kits.dropit.work.SendWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small envelope at the bottom of the screen while a drop goes out, gone by itself once it is sent: the
 * stripe runs while it is on its way, a navy line says it went, a RETURNED mark says it did not.
 *
 * Why it is there at all: some systems (HyperOS) freeze an app about half a second after it leaves the
 * screen, foreground job or not, until the person touches it again — a drop could sit at "sending" for
 * minutes. An app that is on screen is not frozen. Where the person has let dropit run unrestricted the
 * card is not needed, and not shown.
 */
class SendCard(private val activity: ComponentActivity, private val jobId: String) {
    fun show() = activity.run {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setFinishOnTouchOutside(true)    // touches outside cannot reach the app below (tried 10-11): they close the card
        OnScreen.add(jobId)
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { OnScreen.remove(jobId) }
        })
        setContent { DropitTheme { Card() } }
    }

    @Composable
    private fun Card() {
        val c = ink
        val work = remember { WorkManager.getInstance(activity) }
        val info by remember { work.getWorkInfosForUniqueWorkFlow(jobId).map { it.firstOrNull() } }.collectAsState(null)
        val state = info?.state
        val out = info?.outputData
        val upgrade = out?.getString(SendWorker.UPGRADE)?.takeIf { BuildConfig.PAYMENTS }
        val said = out?.getString(SendWorker.DONE).orEmpty().lines()

        // Gone by itself: quickly when it went, slower when there is something to read or to do
        LaunchedEffect(state, said.size) {
            when (state) {
                WorkInfo.State.SUCCEEDED -> { delay(if (said.size > 1) 4_200 else 1_200); activity.finish() }
                // A limit a paid plan lifts stays a little longer; the notification stays after either
                WorkInfo.State.FAILED -> { delay(if (upgrade != null) 8_000 else 6_000); activity.finish() }
                WorkInfo.State.CANCELLED -> activity.finish()
                else -> {}
            }
        }

        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
            Envelope(
                Modifier.fillMaxWidth(), transit = state == null || !state.isFinished, stripe = Stripe.Card,
                padding = PaddingValues(start = 12.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        when (state) {
                            WorkInfo.State.SUCCEEDED -> {
                                val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
                                // The worker's own words ("Sent #84–#86"), with the time it went
                                Txt("✓ ${said.first()} · $clock", sans(14.sp, FontWeight.SemiBold, lineHeight = 20.sp), c.navy)
                                for (x in said.drop(1)) Txt(x, sans(12.sp, lineHeight = 17.sp), c.dim)
                            }
                            WorkInfo.State.FAILED -> Returned((out?.getString(SendWorker.ERROR) ?: stringResource(R.string.send_failed)).lines())
                            else -> {
                                val k = info?.progress?.getInt(SendWorker.K, 0) ?: 0
                                val n = info?.progress?.getInt(SendWorker.N, 0) ?: 0
                                Txt(if (n > 1 && k > 0) stringResource(R.string.sending_n, k, n) else stringResource(R.string.sending), sans(14.sp, lineHeight = 20.sp), c.ink)
                            }
                        }
                    }
                    when {
                        // The notification's Cancel is folded away on some systems; here it is one tap
                        state == null || !state.isFinished ->
                            LinkAction({ work.cancelUniqueWork(jobId) }, text = stringResource(R.string.action_cancel), color = c.navy)
                        // Trying again would hit the same limit: the way out is the paid plan
                        state == WorkInfo.State.FAILED && upgrade != null -> LinkAction({
                            activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.SHOW, MainActivity.SHOW_PLANS))
                            activity.finish()
                        }, text = stringResource(R.string.action_upgrade), color = c.navy)
                        state == WorkInfo.State.FAILED && out?.getBoolean(SendWorker.RETRY, false) == true -> LinkAction({
                            Notes(activity).cancel(jobId)
                            Outbox(activity).enqueue(jobId)
                        }, text = stringResource(R.string.action_retry), color = c.navy)
                    }
                    // Only puts the card away: sending goes on without it
                    LinkAction({ activity.finish() }, icon = Icons.X, name = stringResource(R.string.close))
                }
            }
        }
    }
}
