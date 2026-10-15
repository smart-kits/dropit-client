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
import androidx.compose.foundation.ExperimentalFoundationApi
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.smart_kits.dropit.BuildConfig
import xyz.smart_kits.dropit.Me
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
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
    val c = ink
    val draft = rememberTextFieldState()
    val files = remember { mutableStateListOf<Picked>() }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var me by remember { mutableStateOf<Me?>(null) }
    val letters by ctx.app.letters.all.collectAsState()

    // What was known shows at once; then the service's answer, each time the screen comes back (devices and
    // plan change elsewhere: another device joins, a payment goes through)
    LaunchedEffect(resumed) {
        if (me == null) me = ctx.app.account.me()
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
            me?.let {
                val plan = stringResource(if (it.paid) R.string.label_paid else R.string.label_free)
                Txt(
                    stringResource(R.string.plan_line, plan, it.devicesUsed, it.devicesLimit), sans(12.sp).copy(fontFeatureSettings = "tnum"), c.dim,
                    Modifier.weight(1f).padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } ?: Spacer(Modifier.weight(1f))
            LinkAction({ go(Screen.Settings) }, icon = Icons.Gear, name = stringResource(R.string.settings))
        }

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
                    if (o.upgrade != null && BuildConfig.PAYMENTS) {
                        Btn(upgradeWord(o.why), { go(Screen.Plans(o.why)) }, Modifier.fillMaxWidth().padding(top = 10.dp), solid = true)
                    }
                }
                null -> {}
            }
        }

        SectionHead(stringResource(R.string.label_received), Modifier.padding(top = 18.dp, bottom = 4.dp), count = letters.size.takeIf { it > 0 }?.toString())
        if (letters.isEmpty()) {
            Column(Modifier.fillMaxWidth().dashed(c.line, radius = 3.dp).padding(horizontal = 16.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(stringResource(R.string.empty_title), sans(13.5.sp), c.dim, align = TextAlign.Center)
                Txt(stringResource(R.string.empty_body), sans(13.5.sp), c.dim, align = TextAlign.Center)
                Txt(
                    stringResource(R.string.label_add_device), sans(13.5.sp).copy(textDecoration = TextDecoration.Underline), c.navy,
                    Modifier.clickable(role = Role.Button) { go(Screen.AddDevice) }.padding(vertical = 10.dp),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (l in letters) LetterCard(l, resumed)
            }
        }
    }
}

/** What the upgrade button says, by what was hit: what the paid plan gives for exactly that */
@Composable
internal fun upgradeWord(why: String?): String = when (why) {
    "per_day" -> stringResource(R.string.up_per_day, Plans.paid.perDay)
    "file_size" -> stringResource(R.string.up_file_size, Plans.paid.fileMB)
    "space" -> stringResource(R.string.up_space, Plans.space(Plans.paid.spaceMB))
    "queue" -> stringResource(R.string.up_queue, Plans.paid.queue)
    "devices" -> stringResource(R.string.up_devices, Plans.paid.devices)
    "realtime" -> stringResource(R.string.up_realtime)
    else -> stringResource(R.string.up_generic)
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
@Composable
private fun LetterCard(l: Letter, resumed: Int) {
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
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(c.paper).border(1.dp, c.line, RoundedCornerShape(3.dp))
            .padding(start = 11.dp, end = 7.dp, top = 9.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
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
            LetterFiles(l.files)
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
}

@Composable
private fun LetterFiles(files: List<LetterFile>) {
    if (files.isEmpty()) return
    val c = ink
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
    for (f in files.filter { it.thumb == null }) {
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(Icons.File, c.dim, size = 16.dp)
            Txt(f.name, sans(13.5.sp), c.ink, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Txt(fileSize(f.size), mono(11.5.sp), c.dim)
        }
    }
}

internal fun Context.openLink(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

internal fun Context.copy(text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("dropit", text))
    // What dropit itself put there is not offered back as "new on your clipboard"
    app.store.clipSeen = System.currentTimeMillis()
}

internal fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
