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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.runtime.mutableStateListOf
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
import xyz.smart_kits.dropit.shared.Plans
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
import xyz.smart_kits.dropit.ui.kit.Stamp
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
    data class Sent(val line: String, val extra: List<String>) : Outcome
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
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(resumed: Int, offer: ClipOffer?, onOfferDone: (ClipOffer) -> Unit, onSignedOut: () -> Unit, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    val c = ink
    val draft = rememberTextFieldState()
    val files = remember { mutableStateListOf<Picked>() }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var me by remember { mutableStateOf<Me?>(null) }
    // A free account, with paying on offer: the places that meet a limit can offer the way past it (the plans screen, naming it)
    val upgradeOffered = BuildConfig.PAYMENTS && ctx.app.plans.offering && me?.paid == false
    val letters by ctx.app.letters.all.collectAsState()
    // Every way to upgrade on this screen goes straight to the checkout (see Buy)
    val buy = rememberBuy()
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
        me = ctx.app.account.me(fresh = true)
        if (!ctx.app.account.signedIn) onSignedOut()
    }
    // How the last send went stays until the next edit, then goes: it is about what was there
    var settled by remember { mutableStateOf("" to 0) }
    LaunchedEffect(draft.text, files.size) {
        if (outcome != null && !busy && (draft.text.toString() to files.size) != settled) outcome = null
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        files += uris.map(ctx::describe)
    }

    /** Hold, hand to the background, and say here how it went. [sent] runs once it is in. */
    fun send(inbound: Inbound, sent: () -> Unit) {
        if (inbound.isEmpty || busy) return
        busy = true; outcome = null; progress = 0 to 0
        scope.launch {
            val outbox = Outbox(ctx)
            val job = withContext(Dispatchers.IO) { outbox.hold(inbound) }
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
                outcome = Outcome.Sent(ctx.getString(R.string.sent_at, seqRange(out.getLongArray(SendWorker.SEQS)?.toList().orEmpty()), clock), said.drop(1))
                sent()
                settled = draft.text.toString() to files.size
                me = ctx.app.account.me()
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

    Column(
        Modifier.fillMaxSize().background(c.ground).safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Brand()
            Spacer(Modifier.weight(1f))
            // Until the plan is known the pill's place is held, so it does not pop in and push things
            if (me == null) Box(Modifier.size(width = 84.dp, height = 34.dp).clip(RoundedCornerShape(17.dp)).border(1.5.dp, c.line, RoundedCornerShape(17.dp)))
            me?.let {
                // Where an upgrade starts: a free account, when paying is on offer, gets a pill that says so and opens the plans in one tap.
                // Anything else (paid; or paying is off in this build) is just a statement of the plan, and says nothing about buying
                val offering = BuildConfig.PAYMENTS && ctx.app.plans.offering && !it.paid
                when {
                    offering -> {
                        // The pill says what the account is (the free plan) and what it costs to leave it, with the arrow that says it can be tapped: the first month's price when the account can still have it
                        val year = ctx.app.plans.catalog.value?.offer("year")
                        val price = year?.let { y -> if (y.trialCents != null && it.trialAvailable) Plans.money(y.trialCents!!, y.currency) else Plans.money(y.cents, y.currency) }
                        PlanChip(
                            if (price != null) stringResource(R.string.chip_upgrade_price, price) else stringResource(R.string.chip_free),
                            accent = true, moving = true, loading = buy.opening != null, action = stringResource(R.string.chip_upgrade_label),
                        ) { buy.start() }
                    }
                    it.paid -> PlanChip(stringResource(R.string.chip_paid), accent = false) { go(Screen.Account) }
                    else -> Txt(
                        stringResource(R.string.plan_line, stringResource(R.string.label_free), it.devicesUsed, it.devicesLimit), sans(12.sp).copy(fontFeatureSettings = "tnum"), c.dim,
                        Modifier.padding(end = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))     // the pill and the menu are two targets: far enough apart that one finger picks one
            LinkAction({ menuOpen = true }, icon = Icons.Menu, name = stringResource(R.string.menu_name), iconSize = 26.dp, minSize = 48.dp)
        }
        buy.error?.let { ErrorLine(errorText(it), Modifier.padding(bottom = 10.dp)) }
        if (menuOpen) MenuSheet(go, onDismiss = { menuOpen = false })

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

        Envelope(Modifier.fillMaxWidth(), transit = busy) {
            BasicTextField(
                state = draft,
                modifier = Modifier.fillMaxWidth().contentReceiver(
                    // A picture pasted or dropped in from the keyboard becomes a file to send
                    ReceiveContentListener { content ->
                        if (!content.hasMediaType(MediaType.Image)) content
                        else content.consume { item -> item.uri?.let { files += ctx.describe(it); true } ?: false }
                    },
                ),
                textStyle = sans(15.sp, lineHeight = 22.5.sp).copy(color = c.ink),
                cursorBrush = SolidColor(c.red),
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 9),
                decorator = { field ->
                    Box(Modifier.fillMaxWidth().dashed(c.line).heightIn(min = 76.dp).padding(horizontal = 12.dp, vertical = 10.dp)) {
                        if (draft.text.isEmpty()) Txt(stringResource(R.string.compose_hint), sans(15.sp, lineHeight = 22.5.sp), c.dim)
                        field()
                    }
                },
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
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                LinkAction({ pick.launch(arrayOf("*/*")) }, Modifier.offset(x = (-6).dp), text = stringResource(R.string.attach), icon = Icons.Clip, enabled = !busy)
                Spacer(Modifier.weight(1f))
                Stamp(
                    stringResource(if (busy) R.string.label_sending else R.string.label_send).uppercase(),
                    onClick = {
                        // Sent: the envelope empties, ready for the next one
                        send(Inbound(text = draft.text.toString(), streams = files.map { it.uri.toString() })) { draft.clearText(); files.clear() }
                    },
                    busy = busy,
                )
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
                }
                is Outcome.Failed -> {
                    Returned(o.reasons, Modifier.padding(top = 12.dp))
                    if (o.upgrade != null && BuildConfig.PAYMENTS && LocalContext.current.app.plans.offering) {
                        Btn(upgradeWord(o.why), { buy.start() }, Modifier.fillMaxWidth().padding(top = 10.dp), solid = true, loading = buy.opening != null)
                    }
                }
                null -> {}
            }
        }

        val appContext = LocalContext.current
        var pulling by remember { mutableStateOf(false) }
        var pullLine by remember { mutableStateOf<String?>(null) }
        if (selecting) {
            SectionHead(stringResource(R.string.sel_count, picked.size), Modifier.padding(top = 18.dp, bottom = 4.dp)) {
                if (picked.size < letters.size) LinkAction({ picked = letters.map { it.id }.toSet() }, text = stringResource(R.string.sel_all), color = c.navy)
                LinkAction({
                    undo = ctx.app.letters.remove(picked)
                    picked = emptySet()
                }, text = stringResource(R.string.sel_clear), icon = Icons.Check, color = c.navy, underline = false)
            }
        } else SectionHead(stringResource(R.string.label_received), Modifier.padding(top = 18.dp, bottom = 4.dp), count = letters.size.takeIf { it > 0 }?.toString()) {
            LinkAction(
                onClick = {
                    pulling = true
                    scope.launch {
                        val r = ctx.app.receiving.pullNow("manual")
                        pulling = false
                        pullLine = when {
                            r.failed -> appContext.getString(R.string.pull_failed)
                            r.count == 0 -> appContext.getString(R.string.pull_none)
                            else -> appContext.getString(R.string.pull_n, r.count)
                        }
                        delay(3000)
                        pullLine = null
                    }
                },
                text = when {
                    pulling -> stringResource(R.string.pulling)
                    pullLine != null -> pullLine!!
                    else -> stringResource(R.string.label_pull)
                },
                icon = Icons.Pull,
                enabled = !pulling,
                loading = pulling,
                color = c.navy,
            )
        }
        undo?.let { gone ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(3.dp)).background(c.paper).border(1.dp, c.line, RoundedCornerShape(3.dp)).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(stringResource(R.string.cleared_n, gone.size), sans(13.5.sp), c.ink, Modifier.weight(1f))
                LinkAction({ ctx.app.letters.restore(gone); undo = null }, text = stringResource(R.string.undo), color = c.navy)
            }
        }
        if (letters.isEmpty()) {
            Column(Modifier.fillMaxWidth().dashed(c.line, radius = 3.dp).padding(horizontal = 16.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(stringResource(R.string.empty_title), sans(13.5.sp), c.dim, align = TextAlign.Center)
                // Alone on the account: say what makes dropit useful (a second device) instead of asking for something to be sent
                Txt(stringResource(if ((me?.devicesUsed ?: 0) <= 1) R.string.empty_body_alone else R.string.empty_body), sans(13.5.sp), c.dim, align = TextAlign.Center)
                Txt(
                    stringResource(R.string.label_add_device), sans(13.5.sp).copy(textDecoration = TextDecoration.Underline), c.navy,
                    Modifier.clickable(role = Role.Button) { go(Screen.AddDevice) }.padding(vertical = 10.dp),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (l in letters) LetterCard(
                    l, resumed, upgrade = if (upgradeOffered) { _ -> buy.start() } else null,
                    picked = if (selecting) l.id in picked else null,
                    onPick = { picked = if (l.id in picked) picked - l.id else picked + l.id },
                )
            }
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

private fun relative(ctx: Context, at: Long, now: Long): String {
    val min = (now - at) / 60_000
    return when {
        min < 1 -> ctx.getString(R.string.now)
        min < 60 -> ctx.getString(R.string.ago_min, min.toInt())
        min < 1440 -> ctx.getString(R.string.ago_hour, (min / 60).toInt())
        else -> ctx.getString(R.string.ago_day, (min / 1440).toInt())
    }
}

/** One drop as one letter. What arrived carries a postmark; what this phone sent says so instead. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LetterCard(l: Letter, resumed: Int, upgrade: ((String) -> Unit)? = null, picked: Boolean? = null, onPick: () -> Unit = {}) {
    val ctx = LocalContext.current
    val c = ink
    val now = remember(resumed, l.id) { System.currentTimeMillis() }
    val kind = when (val s = l.shape) {
        LetterShape.Text -> stringResource(R.string.label_kind_text)
        LetterShape.Link -> stringResource(R.string.label_kind_link)
        LetterShape.Image -> stringResource(R.string.label_kind_image)
        LetterShape.Video -> stringResource(R.string.label_kind_video)
        LetterShape.File -> stringResource(R.string.label_kind_file)
        is LetterShape.Files -> pluralStringResource(R.plurals.label_n_files, s.n, s.n)
        is LetterShape.TextAndFiles -> pluralStringResource(R.plurals.label_text_n_files, s.n, s.n)
    }
    val selecting = picked != null
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier.fillMaxWidth()
            // Long press starts picking (the buttons inside a letter still take their own taps)
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onLongClickLabel = stringResource(R.string.sel_start), onLongClick = onPick, onClick = {}),
    ) {
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (picked == true) c.navy.copy(alpha = .07f) else c.paper)
            .border(if (picked == true) 2.dp else 1.dp, if (picked == true) c.navy else c.line, shape)
            .padding(start = 11.dp, end = 7.dp, top = 9.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (selecting) {
            Box(
                Modifier.padding(end = 10.dp, top = 2.dp).size(20.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (picked == true) c.navy else Color.Transparent).border(1.5.dp, if (picked == true) c.navy else c.dim, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { if (picked == true) Glyph(Icons.Check, c.paper, size = 14.dp) }
        }
        Column(Modifier.weight(1f)) {
            Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(kind.uppercase(), label(12.5.sp, tracking = .06.em), c.dim, maxLines = 1)
                if (l.mine) Tag(stringResource(R.string.label_this_phone), self = true)
                Txt(
                    listOf(seqRange(l.seqs), relative(ctx, l.at, now)).filter { it.isNotEmpty() }.joinToString(" · "),
                    mono(11.sp), c.dim, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val text = l.text
            if (text != null) {
                if (l.kind == "url") {
                    // The title is the link; the address sits under it, short
                    Txt(
                        l.title ?: shortUrl(text), sans(14.5.sp, lineHeight = 21.sp).copy(textDecoration = TextDecoration.Underline), c.navy,
                        Modifier.clickable(role = Role.Button) { ctx.openLink(text) }, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    if (l.title != null) Txt(shortUrl(text), sans(12.5.sp), c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Txt(text, sans(14.5.sp, lineHeight = 21.5.sp), c.ink, maxLines = 12, overflow = TextOverflow.Ellipsis)
                }
            }
            l.fromTitle?.let { Txt(stringResource(R.string.from_page, it), sans(12.sp), c.dim, Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            LetterFiles(l, upgrade)
            if (l.mine) {
                val clock = remember(l.at) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(l.at)) }
                Txt(stringResource(R.string.sent_mark, clock), mono(12.sp), c.navy, Modifier.padding(top = 4.dp, bottom = 4.dp))
            } else {
                Row(Modifier.offset(x = (-6).dp)) {
                    if (l.kind == "url" && text != null) LinkAction({ ctx.openLink(text) }, text = stringResource(R.string.open), icon = Icons.Open)
                    if (text != null) {
                        LinkAction({ ctx.copy(text) }, text = stringResource(if (l.files.isEmpty()) R.string.copy else R.string.copy_text), icon = Icons.Copy)
                        LinkAction({ ctx.shareText(text) }, text = stringResource(R.string.share), icon = Icons.Share)
                    }
                }
            }
        }
        if (!l.mine) {
            val date = remember(l.at) { SimpleDateFormat("MM-dd", Locale.US).format(Date(l.at)) }
            val time = remember(l.at) { SimpleDateFormat("HH:mm", Locale.US).format(Date(l.at)) }
            Postmark(date, time, l.source ?: "dropit", l.seqs.firstOrNull() ?: 0, Modifier.padding(start = 8.dp))
        }
    }
    // While picking, a tap anywhere on the letter picks it (nothing inside it opens or copies by accident)
    if (selecting) Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Checkbox, onClickLabel = null, onClick = onPick).semantics { selected = picked == true })
    }
}

@Composable
private fun LetterFiles(letter: Letter, upgrade: ((String) -> Unit)? = null) {
    val files = letter.files
    if (files.isEmpty()) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ink
    val now = remember(letter) { System.currentTimeMillis() }
    var saving by remember { mutableStateOf(setOf<Long>()) }     // each file saves on its own: several at once, each its own spinner
    var note by remember { mutableStateOf<Int?>(null) }
    val pictures = files.filter { it.thumb != null }
    if (pictures.isNotEmpty()) {
        Row(Modifier.padding(top = 6.dp).widthIn(max = if (pictures.size == 1) 120.dp else 200.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (f in pictures.take(3)) {
                val bitmap = remember(f.thumb) { runCatching { BitmapFactory.decodeFile(f.thumb)?.asImageBitmap() }.getOrNull() }
                val name = f.name
                Box(Modifier.weight(1f).aspectRatio(if (pictures.size == 1) 4f / 3f else 1f).clip(RoundedCornerShape(2.dp)).background(c.line).semantics { contentDescription = name }) {
                    if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            // Keeps three columns when there are fewer pictures than that
            if (pictures.size == 2) Spacer(Modifier.weight(1f))
        }
        if (pictures.size > 3) Txt("+${pictures.size - 3}", mono(11.5.sp), c.dim, Modifier.padding(top = 3.dp))
    }
    for (f in files) {
        val state = f.state(now)
        Column(Modifier.padding(top = 5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(Icons.File, c.dim, size = 16.dp)
                Txt(f.name, sans(13.5.sp), if (state == FileState.EXPIRED) c.dim else c.ink, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Txt(fileSize(f.size), mono(11.5.sp), c.dim)
            }
            when (state) {
                // On the phone: open it, share it, whatever the service has done with its copy
                FileState.SAVED -> Row(Modifier.offset(x = (-6).dp)) {
                    LinkAction({
                        if (ctx.app.downloader.stillThere(f.saved!!)) ctx.openSaved(f) else { ctx.app.letters.update(letter.id, f.seq) { it.copy(saved = null) }; note = R.string.file_gone }
                    }, text = stringResource(R.string.open), icon = Icons.Open)
                    LinkAction({
                        if (ctx.app.downloader.stillThere(f.saved!!)) ctx.shareSaved(f) else { ctx.app.letters.update(letter.id, f.seq) { it.copy(saved = null) }; note = R.string.file_gone }
                    }, text = stringResource(R.string.share), icon = Icons.Share)
                    if (f.mime.orEmpty().startsWith("image/")) {
                        LinkAction({ if (ctx.copyImage(f)) note = R.string.file_image_copied else note = R.string.file_gone }, text = stringResource(R.string.file_copy_image), icon = Icons.Copy)
                    }
                }
                // Gone from the service: dropit delivers, it does not store
                FileState.EXPIRED -> {
                    Txt(stringResource(R.string.file_expired), sans(12.sp, lineHeight = 17.sp), c.dim, Modifier.padding(top = 2.dp))
                    // A free account lost this to the one-day keep; Paid keeps longer, for the next one
                    if (upgrade != null) LinkAction({ upgrade("kept") }, text = upgradeWord("kept"), color = c.navy, modifier = Modifier.offset(x = (-6).dp))
                }
                FileState.AVAILABLE -> {
                    Row(Modifier.offset(x = (-6).dp), verticalAlignment = Alignment.CenterVertically) {
                        if (f.seq in saving) {
                            Row(Modifier.padding(start = 6.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                xyz.smart_kits.dropit.ui.kit.Spinner(c.dim, 14.dp)
                                Txt(stringResource(R.string.file_saving), sans(13.sp), c.dim)
                            }
                        } else {
                            LinkAction({
                                saving = saving + (f.seq ?: return@LinkAction); note = null
                                scope.launch {
                                    note = when (ctx.app.downloader.save(letter, f)) {
                                        is Downloader.Result.Saved -> R.string.file_saved
                                        Downloader.Result.Expired -> R.string.file_expired
                                        Downloader.Result.LimitToday -> R.string.file_limit
                                        Downloader.Result.Offline -> R.string.file_offline
                                        Downloader.Result.Unsupported -> R.string.file_unsupported
                                        Downloader.Result.Failed -> R.string.file_failed
                                    }
                                    saving = saving - (f.seq ?: 0L)
                                }
                            }, text = stringResource(R.string.file_save), icon = Icons.Down)
                        }
                    }
                    f.expires?.let { Txt(stringResource(R.string.file_until, untilText(it, now)), sans(11.5.sp, lineHeight = 16.sp), c.dim) }
                }
            }
        }
    }
    note?.let { Txt(stringResource(it), sans(12.sp, lineHeight = 17.sp), if (it == R.string.file_saved || it == R.string.file_image_copied) c.navy else c.dim, Modifier.padding(top = 4.dp)) }
    // The two things Paid fixes, said right where they are met: today's downloads used up, a file that has expired
    val why = when (note) { R.string.file_limit -> "downloads"; R.string.file_expired -> "kept"; else -> null }
    if (why != null && upgrade != null) LinkAction({ upgrade(why) }, text = upgradeWord(why), color = c.navy, modifier = Modifier.offset(x = (-6).dp))
}

/** When the service lets go of a file, in words a person plans by: "14:20" if that is today, else the day too */
private fun untilText(at: Long, now: Long): String {
    val dayOf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val today = dayOf.format(Date(at)) == dayOf.format(Date(now))
    return SimpleDateFormat(if (today) "HH:mm" else "MM-dd HH:mm", Locale.getDefault()).format(Date(at))
}

private fun Context.fileUri(f: LetterFile): Uri? = f.saved?.let(Uri::parse)

internal fun Context.openSaved(f: LetterFile) {
    val uri = fileUri(f) ?: return
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, f.mime ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

internal fun Context.shareSaved(f: LetterFile) {
    val uri = fileUri(f) ?: return
    val send = Intent(Intent.ACTION_SEND).setType(f.mime ?: "*/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
 * The plan at the top of the home screen: a pill in the brand blue when it leads to an upgrade, with a small arrow that keeps
 * rising (it is the one thing on this screen that moves, so the way to upgrade is seen without a banner or a dot); a quiet pill
 * when it only says what the account is
 */
@Composable
private fun PlanChip(text: String, accent: Boolean, moving: Boolean = false, loading: Boolean = false, action: String? = null, onClick: () -> Unit) {
    val c = ink
    val shape = RoundedCornerShape(17.dp)
    Row(
        Modifier.clip(shape).border(1.5.dp, if (accent) c.navy else c.line, shape)
            .clickable(enabled = !loading, role = Role.Button, onClickLabel = action, onClick = onClick)
            .defaultMinSize(minHeight = 34.dp).padding(start = 13.dp, end = if (moving) 9.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Txt(text, sans(13.sp, FontWeight.SemiBold), if (accent) c.navy else c.dim)
        if (loading) {
            xyz.smart_kits.dropit.ui.kit.Spinner(c.navy, 14.dp)
        } else if (moving) {
            val rise by rememberInfiniteTransition(label = "upgrade").animateFloat(
                initialValue = 2f, targetValue = -2.5f,
                animationSpec = infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "rise",
            )
            Glyph(Icons.Up, c.navy, Modifier.offset(y = rise.dp), size = 16.dp)
        }
    }
}
