package xyz.smart_kits.dropit.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.smart_kits.dropit.BuildConfig
import xyz.smart_kits.dropit.Me
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.FileState
import xyz.smart_kits.dropit.shared.state
import xyz.smart_kits.dropit.receive.Downloader
import xyz.smart_kits.dropit.shared.Letter
import xyz.smart_kits.dropit.shared.LetterFile
import xyz.smart_kits.dropit.shared.LetterShape
import xyz.smart_kits.dropit.shared.ResendAvail
import xyz.smart_kits.dropit.shared.Targets
import xyz.smart_kits.dropit.shared.resendAvail
import xyz.smart_kits.dropit.work.Resend
import xyz.smart_kits.dropit.shared.Plans
import xyz.smart_kits.dropit.shared.UpgradeFrom
import xyz.smart_kits.dropit.shared.fileSize
import xyz.smart_kits.dropit.shared.seqRange
import xyz.smart_kits.dropit.shared.shortUrl
import xyz.smart_kits.dropit.ui.kit.Btn
import xyz.smart_kits.dropit.ui.kit.Envelope
import xyz.smart_kits.dropit.ui.kit.Glyph
import xyz.smart_kits.dropit.ui.kit.Icons
import xyz.smart_kits.dropit.ui.kit.LinkAction
import xyz.smart_kits.dropit.ui.kit.Postmark
import xyz.smart_kits.dropit.ui.kit.ErrorLine
import xyz.smart_kits.dropit.ui.kit.Returned
import xyz.smart_kits.dropit.ui.kit.SectionHead
import xyz.smart_kits.dropit.ui.kit.SplitSend
import xyz.smart_kits.dropit.ui.kit.reducedMotion
import xyz.smart_kits.dropit.ui.kit.Tag
import xyz.smart_kits.dropit.ui.kit.Tip
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.kit.dashed
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.label
import xyz.smart_kits.dropit.ui.theme.mono
import xyz.smart_kits.dropit.ui.theme.sans
import xyz.smart_kits.dropit.work.InApp
import xyz.smart_kits.dropit.work.Inbound
import xyz.smart_kits.dropit.work.Outbox
import xyz.smart_kits.dropit.work.SendWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Picked(val uri: Uri, val name: String, val size: Long?)

/** How the last send from this screen ended: said in the envelope until the next edit or send */
private sealed interface Outcome {
    /** [near]: the day's items or the space are close, said once beside the result (see Moments) */
    data class Sent(val line: String, val extra: List<String>, val near: NearView? = null) : Outcome
    data class Failed(val reasons: List<String>, val upgrade: String?, val why: String?) : Outcome
}

private fun Context.describe(uri: Uri): Picked {
    var name: String? = null
    var size: Long? = null
    runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    }
    return Picked(uri, name ?: uri.lastPathSegment ?: "?", size)
}

/**
 * Joined: the envelope to send from on top, then what arrived and what this phone sent, newest first.
 * Sending never closes anything: the envelope empties and is ready for the next thing.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    resumed: Int, offer: ClipOffer?, onOfferDone: (ClipOffer) -> Unit, onSignedOut: () -> Unit, go: (Screen) -> Unit,
    /** A limit's notification was tapped: the checkout for what was met opens at once ("" when it is not known which) */
    checkoutWhy: String? = null, onCheckoutStarted: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ink
    val draft = rememberTextFieldState()
    val files = remember { mutableStateListOf<Picked>() }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var me by remember { mutableStateOf<Me?>(null) }
    // A free account, with paying on offer: the places that meet a limit can offer the way past it (its checkout, naming what that plan gives for exactly that limit)
    val upgradeOffered = BuildConfig.PAYMENTS && ctx.app.plans.offering && me?.paid == false
    val letters by ctx.app.letters.all.collectAsState()
    // A limit that was just met goes straight to the checkout; the pill and the cards open the plans first (see Buy)
    val buy = rememberBuy()
    val moments = ctx.app.moments
    // What was sent since the account was last asked, so the notes about a day's items or the space being close are right without asking after every send
    var sentSince by remember { mutableStateOf(0) }
    var sentBytes by remember { mutableStateOf(0L) }
    // Chosen again whenever the account's answer changes or a card is put away
    var cardTick by remember { mutableStateOf(0) }
    val momentsNow = remember(me, cardTick, resumed) { System.currentTimeMillis() }
    // A limit that was just met says so in the envelope, which comes before every note
    val wallShowing = (outcome as? Outcome.Failed)?.upgrade != null
    val card = remember(me, cardTick, wallShowing, momentsNow) { me?.let { moments.homeCard(it, momentsNow, wallShowing) } }
    // The pill moves only under pressure, and then only a few times (see PlanChip)
    val pressing = remember(me, card, outcome, sentSince, momentsNow) { me?.let { moments.pressure(it, ctx.app.store.meAt, momentsNow, card, sentSince) } == true }
    // Clearing the list: a long press picks a letter, then taps pick more; the heading says how many and offers all / clear.
    // Clearing takes effect at once and can be undone for a few seconds, so there is no "are you sure"
    var picked by remember { mutableStateOf(setOf<String>()) }
    var undo by remember { mutableStateOf<List<Letter>?>(null) }
    val selecting = picked.isNotEmpty()
    LaunchedEffect(letters) { picked = picked.filter { id -> letters.any { it.id == id } }.toSet() }
    BackHandler(enabled = selecting) { picked = emptySet() }
    LaunchedEffect(undo) {
        val shown = undo ?: return@LaunchedEffect
        delay(6000)
        if (undo === shown) { undo = null; ctx.app.letters.release() }
    }

    // What was known shows at once; then the service's answer, each time the screen comes back (devices and
    // plan change elsewhere: another device joins, a payment goes through)
    LaunchedEffect(resumed) {
        if (me == null) me = ctx.app.account.me(stale = true)
        me?.let { moments.observe(it, System.currentTimeMillis()) }
        cardTick++
        me = ctx.app.account.me(fresh = true)
        sentSince = 0; sentBytes = 0
        me?.let { moments.observe(it, System.currentTimeMillis()) }
        cardTick++
        if (!ctx.app.account.signedIn) onSignedOut()
    }
    // A tap on a limit's notification: the checkout for it (or, where paying is not on offer, nothing more than the plans' words)
    LaunchedEffect(checkoutWhy) {
        val w = checkoutWhy ?: return@LaunchedEffect
        onCheckoutStarted()
        if (BuildConfig.PAYMENTS && ctx.app.plans.offering) buy.start(UpgradeFrom.SHARE, w.ifEmpty { null }) else go(Screen.Plans(w.ifEmpty { null }, UpgradeFrom.SHARE))
    }
    // How the last send went stays until the next edit, then goes: it is about what was there
    var settled by remember { mutableStateOf("" to 0) }
    LaunchedEffect(draft.text, files.size) {
        if (outcome != null && !busy && (draft.text.toString() to files.size) != settled) outcome = null
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        files += uris.map(ctx::describe)
    }

    /**
     * Hold, hand to the background, and say here how it went. [sent] runs once it is in. [to]: the devices chosen for this one send;
     * without it, this phone's default (everyone unless the person chose some).
     */
    fun send(inbound: Inbound, to: Targets? = null, sent: () -> Unit) {
        if (inbound.isEmpty || busy) return
        busy = true; outcome = null; progress = 0 to 0
        scope.launch {
            val outbox = Outbox(ctx)
            val job = withContext(Dispatchers.IO) { outbox.hold(inbound, to) }
            InApp.add(job.id)
            outbox.enqueue(job.id)
            val info = try {
                WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(job.id)
                    .onEach { list -> list.firstOrNull()?.progress?.let { p -> progress = p.getInt(SendWorker.K, 0) to p.getInt(SendWorker.N, 0) } }
                    .first { list -> list.firstOrNull()?.state?.isFinished == true }.first()
            } finally {
                InApp.remove(job.id)      // the screen went away first: the result is a notification again
            }
            busy = false
            val out = info.outputData
            if (info.state == WorkInfo.State.SUCCEEDED) {
                val said = out.getString(SendWorker.DONE).orEmpty().lines()
                val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val seqs = out.getLongArray(SendWorker.SEQS)?.toList().orEmpty()
                val bytesSent = files.sumOf { it.size ?: 0L }
                outcome = Outcome.Sent(ctx.getString(R.string.sent_at, seqRange(seqs), clock), said.drop(1))
                sent()
                settled = draft.text.toString() to files.size
                sentSince += seqs.size; sentBytes += bytesSent
                var latest = ctx.app.account.me()
                // Close to a limit by what was sent since the last answer: ask for the real numbers once, so the line beside the result is right
                if (latest != null && !latest.paid && (itemsTier(latest, ctx.app.store.meAt, System.currentTimeMillis(), sentSince) != Tier.NONE || spaceNear(latest, sentBytes))) {
                    val fresh = ctx.app.account.me(fresh = true)
                    if (fresh != null) { latest = fresh; sentSince = 0; sentBytes = 0 }
                }
                me = latest
                // One note at a time: the cards at the top come first
                if (latest != null && moments.homeCard(latest, System.currentTimeMillis()) == null) {
                    val near = moments.takeNear(latest, ctx.app.store.meAt, System.currentTimeMillis(), sentSince, sentBytes)
                    val cur = outcome
                    if (near != null && cur is Outcome.Sent) outcome = cur.copy(near = near)
                }
            } else {
                // Kept as written, to try again: press the stamp once more
                outcome = Outcome.Failed(
                    (out.getString(SendWorker.ERROR) ?: ctx.getString(R.string.send_failed)).lines(),
                    out.getString(SendWorker.UPGRADE), out.getString(SendWorker.WHY),
                )
                settled = draft.text.toString() to files.size
                if (!ctx.app.account.signedIn) onSignedOut()
            }
        }
    }

    val ops = remember { LetterOps(ctx, scope) }
    // Received and Sent are two lists; which one shows is remembered across a turn of the screen, not across launches
    var showSent by rememberSaveable { mutableStateOf(false) }
    val received = remember(letters) { letters.filter { !it.mine } }
    val sent = remember(letters) { letters.filter { it.mine } }
    val shown = if (showSent) sent else received
    var sheetId by remember { mutableStateOf<String?>(null) }
    val now = remember(letters, resumed) { System.currentTimeMillis() }
    // The first file that expired says once what a phone left closed costs on the free plan; it stays up while this screen does
    val keptPeriod = me?.createdAt?.toString() ?: "account"
    var keptNoteId by remember { mutableStateOf<String?>(null) }
    val firstExpired = if (me?.paid == false && !selecting) shown.firstOrNull { it.upgradeWhy(now, ops) == "kept" }?.id else null
    LaunchedEffect(firstExpired) {
        if (keptNoteId == null && firstExpired != null && moments.keptNoteDue(keptPeriod)) { keptNoteId = firstExpired; moments.markKeptNote(keptPeriod) }
    }
    // The dot on the Sent tab: something was sent since that list was last looked at (one time kept on this phone)
    var sentSeen by remember { mutableLongStateOf(ctx.app.store.sentSeenAt.takeIf { it != 0L } ?: System.currentTimeMillis().also { ctx.app.store.sentSeenAt = it }) }
    LaunchedEffect(showSent, sent.size) {
        if (showSent) { val t = System.currentTimeMillis(); sentSeen = t; ctx.app.store.sentSeenAt = t }
    }
    val sentDot = !showSent && sent.any { it.at > sentSeen }

    // Pulling the list down asks the service for what is new
    var pulling by remember { mutableStateOf(false) }
    var pullLine by remember { mutableStateOf<String?>(null) }
    fun pullList() {
        if (pulling) return
        pulling = true
        scope.launch {
            val r = ctx.app.receiving.pullNow("manual")
            pulling = false
            pullLine = when {
                r.failed -> ctx.getString(R.string.pull_failed)
                r.count == 0 -> ctx.getString(R.string.pull_none)
                else -> ctx.getString(R.string.pull_n, r.count)
            }
            delay(3000)
            pullLine = null
        }
    }
    /** Say something under the tabs for a few seconds */
    fun flash(text: String) {
        scope.launch { pullLine = text; delay(3000); if (pullLine == text) pullLine = null }
    }
    fun clear(ids: Set<String>) {
        undo = ctx.app.letters.remove(ids)
        picked = emptySet()
        sheetId = null
    }
    val pickedLetters = letters.filter { it.id in picked }

    // Who it goes to. The devices are asked for when there is a reason (another device exists, the panel opens, the sent list shows), not on a timer
    val peers = ctx.app.peers
    val peerState by peers.state.collectAsState()
    val defaultTo by peers.defaultTo.collectAsState()
    val receivers = peers.receivers(peerState.peers)
    val selfId = ctx.app.store.deviceId
    var sheet by remember { mutableStateOf<SheetMode?>(null) }
    var sheetBusy by remember { mutableStateOf(false) }
    var sheetError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resumed, me?.devicesUsed) { if ((me?.devicesUsed ?: 0) > 1) peers.refresh(maxAgeMs = 300_000) }
    LaunchedEffect(showSent) { if (showSent && (me?.devicesUsed ?: 0) > 1) peers.refresh(maxAgeMs = 60_000) }
    LaunchedEffect(sheet) { if (sheet != null) peers.refresh(maxAgeMs = 15_000) }
    ops.onResend = { l ->
        when (resendAvail(l, System.currentTimeMillis(), receivers.size)) {
            ResendAvail.OK -> { sheetError = null; sheet = SheetMode.Resend(l) }
            ResendAvail.EXPIRED -> ops.note(l.id, R.string.st_resend_expired)
            ResendAvail.NONE -> {}
        }
    }
    val pullState = rememberPullToRefreshState()

    Box(Modifier.fillMaxSize().background(c.ground)) {
    PullToRefreshBox(
        isRefreshing = pulling, onRefresh = { if (!selecting) pullList() }, state = pullState,
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
        indicator = {
            val f = pullState.distanceFraction
            if (pulling || f > 0f) {
                val ready = f >= 1f
                Row(
                    Modifier.align(Alignment.TopCenter)
                        .offset { IntOffset(0, ((if (pulling) 1f else f.coerceAtMost(1.2f)) * 64.dp.toPx() - 36.dp.toPx()).roundToInt()) }
                        .shadow(3.dp, CircleShape).clip(CircleShape).background(c.paper).padding(horizontal = 16.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pulling) xyz.smart_kits.dropit.ui.kit.Spinner(c.navy, 18.dp) else Glyph(Icons.Up, c.navy, Modifier.rotate(if (ready) 0f else 180f), size = 18.dp)
                    Txt(
                        stringResource(when { pulling -> R.string.pulling; ready -> R.string.pull_release; else -> R.string.pull_hint }),
                        sans(13.5.sp, FontWeight.SemiBold), c.navy,
                    )
                }
            }
        },
    ) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = if (selecting) 110.dp else 28.dp),
    ) {
        if (!selecting) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Brand()
                // What the account is, in a line next to the name; a free account with paying on offer has the pill instead, in the same place
                val offering = BuildConfig.PAYMENTS && ctx.app.plans.offering && me?.paid == false
                val holdsPill = BuildConfig.PAYMENTS && ctx.app.plans.offering && (me == null || me?.paid == false)
                Spacer(Modifier.width(8.dp))
                when {
                    holdsPill -> Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        val m = me
                        if (m == null) {
                            // Until the plan is known the pill's place is held, so it does not pop in and push things
                            Box(Modifier.size(width = 84.dp, height = 34.dp).clip(RoundedCornerShape(17.dp)).border(1.5.dp, c.line, RoundedCornerShape(17.dp)))
                        } else if (offering) {
                            // Where an upgrade starts with nothing pressing: it says what it costs to leave the free plan (the first month's price when
                            // the account can still have it, else the year's) and opens the plans, where all three ways to pay are laid out. It moves
                            // only while something is close (see Moments.pressure), and only a few times
                            val prices = pricesOf(ctx.app.plans.catalog.value, m.trialAvailable, open = true)
                            val label = when {
                                prices.trial != null -> stringResource(R.string.mo_pill_trial, prices.trial)
                                prices.year != null -> stringResource(R.string.mo_pill_year, prices.year)
                                else -> stringResource(R.string.action_upgrade)
                            }
                            PlanChip(label, moving = pressing, action = stringResource(R.string.chip_upgrade_label)) { go(Screen.Plans(from = UpgradeFrom.PILL)) }
                        }
                    }
                    me == null -> Spacer(Modifier.weight(1f))
                    else -> me?.let {
                        Txt(
                            stringResource(R.string.plan_line, stringResource(if (it.paid) R.string.label_paid else R.string.label_free), it.devicesUsed, it.devicesLimit),
                            sans(12.5.sp).copy(fontFeatureSettings = "tnum"), c.dim, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))     // the pill and the gear are two targets: far enough apart that one finger picks one
                LinkAction({ go(Screen.Settings) }, Modifier.offset(x = 10.dp), icon = Icons.Gear, name = stringResource(R.string.label_settings), iconSize = 26.dp, minSize = 48.dp)
            }
            buy.error?.let { ErrorLine(errorText(it), Modifier.padding(bottom = 10.dp)) }
            // One note about the plan at a time, only when something is close or has just changed (see Moments)
            card?.let { k ->
                me?.let { m ->
                    HomeMomentCard(k, m, momentsNow, onDismiss = { moments.dismiss(k); cardTick++ }, go = go, buy = buy, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
            TargetsNote()

            SetupTips(resumed)

            // Copied something before opening dropit: one tap sends it. A quiet slip; the stamp stays the main button
            if (offer != null) {
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(3.dp)).background(c.paper).border(1.dp, c.line, RoundedCornerShape(3.dp))
                        .padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 2.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(Icons.Copy, c.dim, size = 14.dp)
                        Txt(stringResource(R.string.label_clip_new).uppercase(), label(13.sp, tracking = .06.em), c.dim)
                    }
                    Txt(offer.preview, sans(14.sp, lineHeight = 20.sp), c.ink, Modifier.padding(top = 3.dp, end = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        LinkAction({ onOfferDone(offer) }, text = stringResource(R.string.dismiss))
                        Spacer(Modifier.width(4.dp))
                        Btn(stringResource(R.string.send_this), {
                            val o = offer
                            // Gone once it is in; if it fails it stays, with the reason in the envelope, to try again
                            send(Inbound(text = o.text, streams = listOfNotNull(o.uri?.toString()))) { onOfferDone(o) }
                        }, small = true, enabled = !busy)
                    }
                }
            }

            Envelope(Modifier.fillMaxWidth(), transit = busy, padding = androidx.compose.foundation.layout.PaddingValues(10.dp)) {
                val nothing = draft.text.isBlank() && files.isEmpty()
                fun sendNow(to: Targets?) {
                    // Sent: the envelope empties, ready for the next one
                    send(Inbound(text = draft.text.toString(), streams = files.map { it.uri.toString() }), to) { draft.clearText(); files.clear() }
                }
                // The place to write, and the send button beside it, level with the bottom of it: as the writing grows (up to six lines),
                // the button stays the height of one line
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    BasicTextField(
                        state = draft,
                        modifier = Modifier.weight(1f).contentReceiver(
                            // A picture pasted or dropped in from the keyboard becomes a file to send
                            ReceiveContentListener { content ->
                                if (!content.hasMediaType(MediaType.Image)) content
                                else content.consume { item -> item.uri?.let { files += ctx.describe(it); true } ?: false }
                            },
                        ),
                        textStyle = sans(15.sp, lineHeight = 22.5.sp).copy(color = c.ink),
                        cursorBrush = SolidColor(c.red),
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                        decorator = { field ->
                            Row(Modifier.fillMaxWidth().dashed(c.line).heightIn(min = 56.dp).padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.Bottom) {
                                Box(Modifier.weight(1f).padding(vertical = 17.dp)) {
                                    if (draft.text.isEmpty()) Txt(stringResource(R.string.compose_hint), sans(15.sp, lineHeight = 22.5.sp), c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    field()
                                }
                                // Attaching sits inside the place to write, where the eye already is
                                LinkAction({ pick.launch(arrayOf("*/*")) }, icon = Icons.Clip, name = stringResource(R.string.attach), iconSize = 22.dp, minSize = 52.dp, enabled = !busy)
                            }
                        },
                    )
                    SplitSend(
                        stringResource(if (busy) R.string.label_sending else R.string.label_send).uppercase(), busy, chooser = receivers.isNotEmpty(),
                        onSend = { sendNow(null) },
                        onChoose = { sheetError = null; sheet = SheetMode.Send },
                        partial = defaultTo.size.takeIf { it > 0 && receivers.isNotEmpty() },
                    )
                }
                // The panel for this one send (see TargetSheet): the default is ticked; nothing goes until its button is pressed
                if (sheet == SheetMode.Send) TargetSheet(
                    SheetMode.Send, peerState, receivers, defaultTo, busy = false, error = null,
                    nothingToSend = nothing,
                    onSubmit = { targets -> sheet = null; sendNow(targets) },
                    onOpenDefault = { go(Screen.DefaultTargets) },
                    onRetryLoad = { scope.launch { peers.refresh() } },
                    onDismiss = { sheet = null },
                )
                if (files.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (f in files.toList()) {
                            Row(
                                Modifier.clip(RoundedCornerShape(6.dp)).background(c.paper).border(1.dp, c.line, RoundedCornerShape(6.dp)).padding(start = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Txt(f.name, sans(12.sp), c.ink, Modifier.widthIn(max = 150.dp), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                f.size?.let { Txt(fileSize(it), mono(11.sp), c.dim, Modifier.padding(start = 6.dp)) }
                                LinkAction({ files.remove(f) }, icon = Icons.X, name = stringResource(R.string.remove_file, f.name), enabled = !busy)
                            }
                        }
                    }
                }
                val line = when {
                    busy && progress.second > 1 -> stringResource(R.string.sending_n, progress.first.coerceAtLeast(1), progress.second)
                    busy -> stringResource(R.string.sending)
                    else -> null
                }
                line?.let { Txt(it, sans(12.sp), c.dim, Modifier.padding(top = 6.dp)) }
                when (val o = outcome) {
                    is Outcome.Sent -> {
                        Txt(o.line, sans(12.sp, FontWeight.SemiBold), c.navy, Modifier.padding(top = 6.dp))
                        for (x in o.extra) Txt(x, sans(12.sp, lineHeight = 17.sp), c.dim)
                        o.near?.let { NearNote(it, momentsNow, go, Modifier.padding(top = 2.dp)) }
                    }
                    is Outcome.Failed -> {
                        Returned(o.reasons, Modifier.padding(top = 12.dp))
                        if (o.upgrade != null && BuildConfig.PAYMENTS && LocalContext.current.app.plans.offering) {
                            Btn(upgradeWord(o.why), { buy.start(UpgradeFrom.WALL, o.why) }, Modifier.fillMaxWidth().padding(top = 10.dp), solid = true, loading = buy.opening != null)
                        }
                    }
                    null -> {}
                }
            }

        }

        if (selecting) {
            SelectHeader(picked.size, picked.size >= shown.size, onExit = { picked = emptySet() }, onAll = { picked = shown.map { it.id }.toSet() })
        } else {
            ListTabs(showSent, received.size, sent.size, sentDot, { showSent = it }, Modifier.padding(top = 14.dp))
            pullLine?.let { Txt(it, sans(12.5.sp), c.dim, Modifier.fillMaxWidth().padding(top = 8.dp), align = TextAlign.Center) }
        }
        if (shown.isEmpty()) {
            ListEmpty(showSent, alone = (me?.devicesUsed ?: 0) <= 1, onAddDevice = { go(Screen.AddDevice) })
        } else {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (l in shown) key(l.id) {
                    LetterRow(
                        l, now, ops, upgrade = if (upgradeOffered) { why -> buy.start(UpgradeFrom.WALL, why) } else null,
                        keptNote = l.id == keptNoteId,
                        picked = if (selecting) l.id in picked else null,
                        peers = peerState.peers, selfId = selfId,
                        onOpen = { sheetId = l.id },
                        onPick = { picked = if (l.id in picked) picked - l.id else picked + l.id },
                        onClear = { clear(setOf(l.id)) },
                    )
                }
            }
        }
    }
    }
    if (selecting) {
        SelectDock(
            canCopy = pickedLetters.any { it.text != null },
            canSave = pickedLetters.any { l -> !l.mine && l.files.any { it.state(now) == FileState.AVAILABLE } },
            canShare = pickedLetters.any { l -> l.text != null || (!l.mine && l.files.any { it.state(now) != FileState.EXPIRED }) },
            onCopy = { ops.copyTexts(pickedLetters); picked = emptySet() },
            onSave = {
                pickedLetters.filter { l -> !l.mine && l.files.any { it.state(now) == FileState.AVAILABLE } }.forEach(ops::saveAll)
                picked = emptySet()
            },
            onShare = { ops.share(pickedLetters); picked = emptySet() },
            onClear = { clear(picked) },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
    undo?.let { gone ->
        UndoToast(
            gone.size, gone.sumOf { l -> if (l.mine) 0 else l.files.count { it.state(now) == FileState.AVAILABLE } },
            onUndo = { ctx.app.letters.restore(gone); undo = null },
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        )
    }
    letters.firstOrNull { it.id == sheetId }?.let { l ->
        LetterSheet(l, now, ops, resendAvail(l, now, receivers.size), onDismiss = { sheetId = null }, onClear = { clear(setOf(l.id)) })
    }
    // Sending a letter again: nothing is ticked, each device says where it stands
    (sheet as? SheetMode.Resend)?.let { mode ->
        TargetSheet(
            mode, peerState, receivers, defaultTo, busy = sheetBusy, error = sheetError, nothingToSend = false,
            onSubmit = { targets ->
                sheetBusy = true; sheetError = null
                scope.launch {
                    when (val r = Resend(ctx).run(mode.letter, targets)) {
                        is Resend.Result.Done -> {
                            sheet = null
                            // The new letter is at the top of the list of what was sent
                            showSent = true
                            flash(ctx.getString(if (r.already) R.string.resend_already else R.string.resend_done, targetText(ctx, targets)))
                        }
                        is Resend.Result.Failed -> sheetError = r.text
                    }
                    sheetBusy = false
                    me = ctx.app.account.me()
                }
            },
            onOpenDefault = {},
            onRetryLoad = { scope.launch { peers.refresh() } },
            onDismiss = { if (!sheetBusy) sheet = null },
        )
    }
    }
}


/** What the upgrade button says, by what was hit: what the paid plan gives for exactly that */
@Composable
internal fun upgradeWord(why: String?): String {
    val paid = LocalContext.current.app.plans.paid   // the service's numbers when it has answered
    return when (why) {
        "per_day" -> stringResource(R.string.up_per_day, paid.perDay)
        "file_size" -> stringResource(R.string.up_file_size, paid.fileMB)
        "space" -> stringResource(R.string.up_space, Plans.space(paid.spaceMB))
        "queue" -> stringResource(R.string.up_queue, paid.queue)
        "devices" -> stringResource(R.string.up_devices, paid.devices)
        "realtime" -> stringResource(R.string.up_realtime)
        "uploads" -> stringResource(R.string.up_uploads)
        "downloads" -> stringResource(R.string.up_downloads, paid.downloads)
        "kept" -> stringResource(R.string.up_kept, paid.days)
        else -> stringResource(R.string.up_generic)
    }
}

/** Something about the default devices that the person has not been told yet: some were removed, or all of them were */
@Composable
private fun TargetsNote() {
    val ctx = LocalContext.current
    val note by ctx.app.peers.note.collectAsState()
    val n = note ?: return
    val text = when {
        n == "reset" -> stringResource(R.string.dt_note_reset)
        n.startsWith("removed:") -> n.removePrefix("removed:").toIntOrNull().let { k -> pluralStringResource(R.plurals.dt_note_removed, k ?: 1, k ?: 1) }
        else -> return
    }
    Tip(text, Modifier.padding(bottom = 10.dp), onDismiss = { ctx.app.peers.noteRead() })
}

/**
 * Setup left undone, one line each, under the header: gone once done, or once waved away. The system does
 * not let an app change these itself.
 */
@Composable
private fun SetupTips(resumed: Int) {
    val ctx = LocalContext.current
    val store = ctx.app.store
    var tileAdded by remember(resumed) { mutableStateOf(store.tileAdded) }
    var tileManual by remember { mutableStateOf(android.os.Build.VERSION.SDK_INT < 33) }
    var tileHidden by remember { mutableStateOf(store.tipHidden("tile")) }
    // Checked again each time the screen comes back: the person may have just changed it in settings
    val background = remember(resumed) { ctx.app.runsInBackground }
    var backgroundHidden by remember { mutableStateOf(store.tipHidden("background")) }

    if (!tileAdded && !tileHidden) {
        Tip(
            stringResource(if (tileManual) R.string.tip_tile_manual else R.string.tip_tile), Modifier.padding(bottom = 10.dp),
            action = if (tileManual) null else stringResource(R.string.tip_tile_add),
            onAction = {
                ctx.requestTile { answer ->
                    when (answer) {
                        TileAsk.ADDED -> { store.tileAdded = true; tileAdded = true }
                        // Said no, or the system would not ask: the by-hand steps show, and the line can be waved away
                        TileAsk.DECLINED, TileAsk.UNAVAILABLE -> tileManual = true
                    }
                }
            },
            onDismiss = { store.hideTip("tile"); tileHidden = true },
        )
    }
    if (!background && !backgroundHidden) {
        Tip(
            stringResource(R.string.tip_background), Modifier.padding(bottom = 10.dp),
            action = stringResource(R.string.tip_background_allow), onAction = { ctx.app.askRunInBackground(ctx) },
            onDismiss = { store.hideTip("background"); backgroundHidden = true },
        )
    }
}

private fun Context.fileUri(f: LetterFile): Uri? = f.saved?.let(Uri::parse)

/** True when something took it */
internal fun Context.openSaved(f: LetterFile): Boolean {
    val uri = fileUri(f) ?: return false
    return runCatching {
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, f.mime ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

/**
 * Saved files to another app: one as it is, several as a set (with their words alongside, when the drop had any).
 * The type is the files' own when they agree, their kind (all pictures) when only the kind does, else anything.
 */
internal fun Context.shareFiles(files: List<LetterFile>, text: String? = null): Boolean {
    val uris = files.mapNotNull(::fileUri)
    if (uris.isEmpty()) return false
    val mimes = files.map { it.mime ?: "*/*" }.distinct()
    val type = when {
        mimes.size == 1 -> mimes[0]
        mimes.map { it.substringBefore('/') }.distinct().size == 1 -> mimes[0].substringBefore('/') + "/*"
        else -> "*/*"
    }
    val send = (if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)))
        .setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (text != null) send.putExtra(Intent.EXTRA_TEXT, text)
    return runCatching { startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

/** The picture itself on the clipboard, to paste into another app */
internal fun Context.copyImage(f: LetterFile): Boolean {
    val uri = fileUri(f) ?: return false
    if (!app.downloader.stillThere(f.saved!!)) return false
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newUri(contentResolver, "dropit", uri))
    app.store.clipSeen = System.currentTimeMillis()
    return true
}

/** True when something took the address */
internal fun Context.openLink(url: String): Boolean =
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

internal fun Context.copy(text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(sensitiveClip(text))
    // What dropit itself put there is not offered back as "new on your clipboard"
    app.store.clipSeen = System.currentTimeMillis()
}

internal fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Text for the clipboard that the system keeps out of its on-screen preview (Android 13 and newer): what arrived is the person's own, not for the screen to show */
internal fun sensitiveClip(text: String): ClipData = ClipData.newPlainText("dropit", text).also { clip ->
    if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
}

/**
 * The way to upgrade at the top of the home screen: a pill in the brand blue that says what the first step costs, with a small arrow, and
 * opens the plans. It is still. Only while something is close ([moving]: the trial's card is up, the day's items are nearly used, a limit
 * was just met) the arrow rises a few times and stops; with the system's animations turned off it never moves. The pill looks 34 dp high
 * but its touch area is 48.
 */
@Composable
private fun PlanChip(text: String, moving: Boolean, action: String?, onClick: () -> Unit) {
    val c = ink
    val shape = RoundedCornerShape(17.dp)
    val still = reducedMotion()
    val rise = remember { Animatable(0f) }
    LaunchedEffect(moving, still) {
        if (moving && !still) {
            repeat(3) {
                rise.animateTo(-2.5f, tween(325, easing = FastOutSlowInEasing))
                rise.animateTo(2f, tween(325, easing = FastOutSlowInEasing))
            }
        }
        rise.animateTo(0f, tween(180))
    }
    Box(
        Modifier.defaultMinSize(minHeight = 48.dp).clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClickLabel = action, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.clip(shape).border(1.5.dp, c.navy, shape).defaultMinSize(minHeight = 34.dp).padding(start = 13.dp, end = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Txt(text, sans(13.sp, FontWeight.SemiBold), c.navy, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Glyph(Icons.Up, c.navy, Modifier.offset(y = rise.value.dp), size = 16.dp)
        }
    }
}
