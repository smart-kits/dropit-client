package xyz.smart_kits.dropit.entry

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import xyz.smart_kits.dropit.BuildConfig
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.Letter
import xyz.smart_kits.dropit.ui.MainActivity
import xyz.smart_kits.dropit.ui.kit.Glyph
import xyz.smart_kits.dropit.ui.kit.Icons
import xyz.smart_kits.dropit.ui.kit.Spinner
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.kit.reducedMotion
import xyz.smart_kits.dropit.ui.theme.DropitTheme
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.sans
import xyz.smart_kits.dropit.work.Notes
import xyz.smart_kits.dropit.work.OnScreen
import xyz.smart_kits.dropit.work.Outbox
import xyz.smart_kits.dropit.work.SendWorker

/**
 * A pill at the bottom of the screen while a drop goes out, gone by itself once it is sent: a turning ring, what is being sent and how far,
 * and Cancel; then a check and "Sent #131 · 3 photos" for two seconds, and it fades; or a pale-red edge and the reason, and the whole pill
 * is Retry. 48 dp high, one line, 34 dp above the bottom of the screen, about 78 % see-through (a blur of what is behind it where the system
 * has one and the person has not turned it off; otherwise about 94 % solid). It has no dim over the screen and takes only the room it needs.
 *
 * It is the one window of the activity that took the drop in (an invisible activity — see [takeIn]); whether the app behind it can be
 * touched through the rest of the screen depends on that and is to be tried on a phone, not assumed.
 *
 * Why it is there at all: some systems (HyperOS) freeze an app about half a second after it leaves the screen, foreground job or not,
 * until the person touches it again — a drop could sit at "sending" for minutes. An app that is on screen is not frozen. Where the person
 * has let dropit run unrestricted the card is not needed, and not shown.
 */
class SendCard(private val activity: ComponentActivity, private val jobId: String) {
    fun show() = activity.run {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        val dp = resources.displayMetrics.density
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        window.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.attributes = window.attributes.apply { y = (BOTTOM_GAP * dp).toInt() }
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // The window is only as big as the pill, and a touch outside it is not the pill's business: it is not closed by one (on a phone that
        // freezes apps that are not on screen, the pill closing is what lets the send be frozen). An earlier try (10-11) found touches outside
        // a full-width card did not reach the app below; with the window this small that is to be tried again on a phone
        setFinishOnTouchOutside(false)
        // A blur of what is behind, when the system draws one and the person has not turned it off: the window's own background is the
        // pill (rounded, about 78 % of the card colour) and the system blurs what is under it. Without it, the pill draws itself, 94 % solid
        blurred = Build.VERSION.SDK_INT >= 31 && windowManager.isCrossWindowBlurEnabled
        if (blurred) {
            val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            window.setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = HEIGHT * dp / 2
                setColor(if (dark) 0xC71B1E24.toInt() else 0xC7FFFFFF.toInt())     // 78 %
            })
            window.setBackgroundBlurRadius((28 * dp).toInt())
        }
        OnScreen.add(jobId)
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { OnScreen.remove(jobId) }
        })
        setContent { DropitTheme { Pill() } }
    }

    private var blurred = false

    @Composable
    private fun Pill() {
        val c = ink
        val work = remember { WorkManager.getInstance(activity) }
        // The run now being watched: the drop's own name until Retry makes a new one (the name still holds the failed run for a moment)
        var run by remember { mutableStateOf<UUID?>(null) }
        val info by remember(run) {
            (run?.let { work.getWorkInfoByIdFlow(it) } ?: work.getWorkInfosForUniqueWorkFlow(jobId).map { it.firstOrNull() })
        }.collectAsState(null)
        val state = info?.state
        val out = info?.outputData
        val upgrade = out?.getString(SendWorker.UPGRADE)?.takeIf { BuildConfig.PAYMENTS }
        val said = out?.getString(SendWorker.DONE).orEmpty().lines()
        val still = reducedMotion()

        // Gone by itself: sent, after two seconds, fading; with something to read or do (extra lines, a limit a paid plan lifts), a little longer
        var fade by remember { mutableStateOf(false) }
        val alpha by animateFloatAsState(if (fade) 0f else 1f, tween(if (still) 0 else 300), label = "fade", finishedListener = { if (it == 0f) activity.finish() })
        var countdown by remember { mutableStateOf(1f) }
        // What did not go (too big, unreadable, past the count): kept apart from the receipt, and shown on the card in red
        val notSent = out?.getString(SendWorker.PROBLEMS).orEmpty().lines().filter { it.isNotBlank() }
        val problems = notSent.isNotEmpty()
        // A limit a paid plan lifts was among them: the way out is on the card
        // (the worker only says so for a free account: a paid plan is already at the highest limit, there is nothing to upgrade to)
        val bigUpgrade = state == WorkInfo.State.SUCCEEDED && problems && BuildConfig.PAYMENTS && activity.app.plans.offering && out?.getBoolean(SendWorker.BIG, false) == true
        // What did not go, one entry per item: the file's name (null where it is not about one file) and why, short
        val names = out?.getStringArray(SendWorker.CARD_NAMES).orEmpty()
        val whys = out?.getStringArray(SendWorker.CARD_WHYS).orEmpty()
        val cards = whys.indices.map { (names.getOrNull(it)?.takeIf { n -> n.isNotEmpty() }) to whys[it] }
        LaunchedEffect(state, problems) {
            when (state) {
                WorkInfo.State.SUCCEEDED -> {
                    val total = if (bigUpgrade) 10_000L else if (problems) 8_000L else 2_000L
                    val began = android.os.SystemClock.elapsedRealtime()
                    while (true) {
                        val left = total - (android.os.SystemClock.elapsedRealtime() - began)
                        if (left <= 0) break
                        countdown = left.toFloat() / total
                        delay(33)
                    }
                    countdown = 0f
                    fade = true
                }
                // A limit a paid plan lifts stays a little longer; the notification stays after either
                WorkInfo.State.FAILED -> { delay(if (upgrade != null) 8_000 else 6_000); fade = true }
                WorkInfo.State.CANCELLED -> activity.finish()
                else -> {}
            }
        }

        // Darker words and buttons than the plain screen's, so they hold their contrast over whatever shows through
        val second = if (c.dark) Color(0xFFC5C9D0) else Color(0xFF3F434A)
        val action = if (c.dark) Color(0xFFA9C2F8) else Color(0xFF173A78)
        val shape = RoundedCornerShape(HEIGHT.dp / 2)
        val failed = state == WorkInfo.State.FAILED
        // The devices it was addressed to are gone: what was held can go to everyone this time, in one tap
        val toAll = failed && out?.getString(SendWorker.WHY) == "target"
        val retryable = failed && upgrade == null && (toAll || out?.getBoolean(SendWorker.RETRY, false) == true)
        val letter = if (failed) activity.app.letters.all.value.firstOrNull { it.id == jobId } else null
        fun again() {
            Notes(activity).cancel(jobId)
            // Not on the main thread (the system is asked to take the job, a database write). Pressed again while a run is going: nothing
            // new is made (null), and the card goes on showing the run that has it
            activity.app.scope.launch { run = Outbox(activity).start(jobId, toAll) }
        }
        // A limit that a paid plan lifts: the app's plans page, at that limit and with the year plan picked; the card goes. No checkout is
        // made from here: the person sees what the plan is before paying for it
        fun upgradeNow() {
            val why = out?.getString(SendWorker.WHY) ?: if (bigUpgrade) "file_size" else null
            activity.startActivity(
                Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.SHOW, MainActivity.SHOW_PLANS).apply { if (why != null) putExtra(MainActivity.WHY, why) },
            )
            activity.finish()
        }

        Box(
            // A fixed width (340 dp, less on a narrow screen) that fills the window: the window is centred on the screen, so the pill is too, and the
            // blur behind it is exactly its shape
            Modifier.alpha(alpha).widthIn(max = 340.dp).fillMaxWidth().heightIn(min = HEIGHT.dp).clip(shape)
                .then(if (blurred) Modifier else Modifier.background(if (c.dark) Color(0xF01B1E24) else Color(0xF0FFFFFF)))      // 94 % when there is no blur
                .border(if (failed || problems) 1.5.dp else 1.dp, if (failed || problems) c.red.copy(alpha = .55f) else c.line.copy(alpha = .8f), shape)
                // The whole pill is the button when there is one to press (retry, send to everyone, upgrade); otherwise it still takes the touch,
                // so a press on it is never passed to what is behind it
                .then(
                    when {
                        failed && retryable -> Modifier.clickable(role = Role.Button, onClick = ::again)
                        failed && upgrade != null -> Modifier.clickable(role = Role.Button, onClick = ::upgradeNow)
                        else -> Modifier.pointerInput(Unit) { detectTapGestures { } }
                    },
                ),
        ) {
            // The text takes all the room there is and the action sits at the right edge, whatever the state says; the card is as tall as its
            // words need (never more than a few lines) and never taller
            val detail = (state == WorkInfo.State.SUCCEEDED && problems) || (failed && cards.isNotEmpty())
            Row(
                Modifier.fillMaxWidth().heightIn(min = HEIGHT.dp).padding(vertical = if (detail) 8.dp else 0.dp).padding(start = 14.dp, end = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    state == WorkInfo.State.SUCCEEDED && problems -> Glyph(Icons.Alert, c.red, size = 20.dp)
                    state == WorkInfo.State.SUCCEEDED -> Glyph(Icons.Check, c.ok.let { if (c.dark) it else Color(0xFF17623F) }, size = 20.dp)
                    failed -> Glyph(Icons.Alert, c.red, size = 20.dp)
                    else -> Spinner(action, 20.dp)
                }
                Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                    val first = cards.firstOrNull()
                    // A failure that is about named files: the name (shortened in the middle, its ending kept) and under it why, in full
                    if (failed && first != null) {
                        if (first.first != null) androidx.compose.foundation.text.BasicText(
                            first.first!!, style = sans(14.sp, FontWeight.Bold, lineHeight = 18.sp).copy(color = c.ink), maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                        )
                        androidx.compose.foundation.text.BasicText(
                            if (cards.size > 1) stringResource(R.string.card_not_sent_more, first.second, cards.size - 1) else first.second,
                            Modifier.padding(top = if (first.first != null) 2.dp else 0.dp),
                            style = sans(13.sp, lineHeight = 17.sp).copy(color = c.red), maxLines = if (first.first != null) 2 else 3, overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        androidx.compose.foundation.text.BasicText(
                            pillText(state, info, said, letter, c.ink, second),
                            style = sans(14.sp, lineHeight = 18.sp).copy(color = c.ink), maxLines = if (failed) 2 else 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Sent, with some that were not: the first of them under the receipt, the name on a line of its own and why under it; how many
                    // more when there are more (all of them are in the app). Three lines at most, and the reason is the last of them
                    if (state == WorkInfo.State.SUCCEEDED && problems) {
                        if (first != null) {
                            if (first.first != null) androidx.compose.foundation.text.BasicText(
                                first.first!!, Modifier.padding(top = 2.dp), style = sans(13.sp, FontWeight.SemiBold, lineHeight = 17.sp).copy(color = c.ink),
                                maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                            )
                            androidx.compose.foundation.text.BasicText(
                                if (cards.size > 1) stringResource(R.string.card_not_sent_more, first.second, cards.size - 1) else first.second,
                                Modifier.padding(top = 1.dp),
                                style = sans(13.sp, lineHeight = 17.sp).copy(color = c.red), maxLines = if (first.first != null) 1 else 2, overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            val line = notSent.first()
                            androidx.compose.foundation.text.BasicText(
                                if (notSent.size > 1) stringResource(R.string.card_not_sent_more, line, notSent.size - 1) else line,
                                Modifier.padding(top = 2.dp),
                                style = sans(13.sp, lineHeight = 17.sp).copy(color = c.red), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Box(Modifier.height(24.dp).width(1.dp).background(second.copy(alpha = .35f)))
                val label = when {
                    bigUpgrade -> stringResource(R.string.action_upgrade)
                    state == WorkInfo.State.SUCCEEDED -> stringResource(R.string.card_done)
                    upgrade != null && failed -> stringResource(R.string.action_upgrade)
                    toAll -> stringResource(R.string.action_send_all)
                    retryable -> stringResource(R.string.action_retry)
                    failed -> stringResource(R.string.close)
                    else -> stringResource(R.string.action_cancel)
                }
                Txt(
                    label, sans(14.5.sp, FontWeight.Bold, lineHeight = 18.sp), action,
                    Modifier.clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button) {
                        when {
                            bigUpgrade -> upgradeNow()
                            state == null || !state.isFinished -> work.cancelUniqueWork(jobId)
                            retryable -> again()
                            upgrade != null && failed -> upgradeNow()
                            // Only puts the card away: sending goes on without it
                            else -> activity.finish()
                        }
                    }.heightIn(min = 44.dp).padding(horizontal = 10.dp).padding(vertical = 13.dp),
                    maxLines = 1,
                )
            }
            // The line along the bottom edge: how far a send has got, or how long the "sent" stays
            val line: Float? = when {
                state == WorkInfo.State.SUCCEEDED -> countdown
                failed || state == WorkInfo.State.CANCELLED -> null
                else -> {
                    val k = info?.progress?.getInt(SendWorker.K, 0) ?: 0
                    val n = info?.progress?.getInt(SendWorker.N, 0) ?: 0
                    if (n > 1 && k > 0) k.toFloat() / n else -1f   // -1: no count yet: a line that moves
                }
            }
            if (line != null) {
                val moving = if (line < 0f && !still) rememberInfiniteTransition(label = "wait").animateFloat(.15f, .85f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "line").value else .3f
                val fraction = if (line < 0f) moving else line
                val color = if (state == WorkInfo.State.SUCCEEDED) (if (c.dark) c.ok else Color(0xFF17623F)) else action
                Box(Modifier.matchParentSize().drawBehind {
                    drawRect(color, Offset(0f, size.height - 3.dp.toPx()), Size(size.width * fraction, 3.dp.toPx()))
                })
            }
        }
    }

    /** What the pill says, in one line: what is going and how far, what went, or which part did not and why */
    @Composable
    private fun pillText(state: WorkInfo.State?, info: WorkInfo?, said: List<String>, letter: Letter?, first: Color, second: Color): androidx.compose.ui.text.AnnotatedString {
        val job = remember { Outbox(activity).load(jobId) }
        val photos = job != null && job.files.isNotEmpty() && job.files.all { it.mime.orEmpty().startsWith("image/") }
        val count = job?.files?.size ?: letter?.files?.size ?: 0
        val what = when {
            count == 0 -> null
            photos -> pluralStringResource(R.plurals.card_n_photos, count, count)
            else -> pluralStringResource(R.plurals.card_n_files, count, count)
        }
        return androidx.compose.ui.text.buildAnnotatedString {
            val bold = androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = first)
            val soft = androidx.compose.ui.text.SpanStyle(color = second)
            when (state) {
                WorkInfo.State.SUCCEEDED -> {
                    withStyle(bold) { append(said.firstOrNull().orEmpty()) }
                    if (what != null) withStyle(soft) { append(" · $what") }
                }
                WorkInfo.State.FAILED -> {
                    val why = info?.outputData?.getString(SendWorker.ERROR)?.lines()?.firstOrNull() ?: stringResource(R.string.send_failed)
                    val f = letter?.failure
                    if (f != null && f.files.size == 1 && letter.files.size > 1) {
                        withStyle(bold) { append(stringResource(if (photos) R.string.card_nth_photo else R.string.card_nth_file, f.files[0] + 1)) }
                        withStyle(soft) { append(" · $why") }
                    } else withStyle(bold) { append(why) }
                }
                else -> {
                    val k = info?.progress?.getInt(SendWorker.K, 0) ?: 0
                    val n = info?.progress?.getInt(SendWorker.N, 0) ?: 0
                    withStyle(bold) { append(if (what == null) stringResource(R.string.card_sending) else stringResource(R.string.card_sending_what, what)) }
                    if (n > 1 && k > 0) withStyle(soft) { append(" · " + stringResource(R.string.card_progress, k, n)) }
                }
            }
        }
    }

    private companion object {
        /** The pill's height and how far it floats above the bottom of the screen, in dp */
        const val HEIGHT = 48
        const val BOTTOM_GAP = 34
    }
}
