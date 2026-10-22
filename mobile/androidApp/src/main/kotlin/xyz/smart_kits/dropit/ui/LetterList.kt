package xyz.smart_kits.dropit.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.shared.Act
import xyz.smart_kits.dropit.shared.Delivery
import xyz.smart_kits.dropit.shared.FileState
import xyz.smart_kits.dropit.shared.Letter
import xyz.smart_kits.dropit.shared.LetterFile
import xyz.smart_kits.dropit.shared.LetterOffer
import xyz.smart_kits.dropit.shared.Peer
import xyz.smart_kits.dropit.shared.ResendAvail
import xyz.smart_kits.dropit.shared.SaveFailure
import xyz.smart_kits.dropit.shared.SendFailure
import xyz.smart_kits.dropit.shared.deliveryOf
import xyz.smart_kits.dropit.shared.receiversOf
import xyz.smart_kits.dropit.shared.resendAvail
import xyz.smart_kits.dropit.shared.fileSize
import xyz.smart_kits.dropit.shared.isBatch
import xyz.smart_kits.dropit.shared.menu
import xyz.smart_kits.dropit.shared.primary
import xyz.smart_kits.dropit.shared.shortUrl
import xyz.smart_kits.dropit.shared.viewOf
import xyz.smart_kits.dropit.ui.kit.Envelope
import xyz.smart_kits.dropit.ui.kit.Glyph
import xyz.smart_kits.dropit.ui.kit.Icons
import xyz.smart_kits.dropit.ui.kit.LinkAction
import xyz.smart_kits.dropit.ui.kit.Spinner
import xyz.smart_kits.dropit.ui.kit.Stripe
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.mono
import xyz.smart_kits.dropit.ui.theme.sans
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// The two lists on the home screen ("received" and "sent"): a segmented control, rows of type block | words | one button,
// and a sheet with everything a letter can do. What each button is comes from the shared `primary` / `menu`; this file only draws.

private val RowShape = RoundedCornerShape(14.dp)

/** "14:18" today, "Yesterday 09:30", else "10-19 09:30" */
private fun whenText(ctx: Context, at: Long, now: Long): String {
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
    fun dayOf(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }
    val today = dayOf(now)
    return when {
        dayOf(at) == today -> clock
        dayOf(at) == dayOf(now - 86_400_000L) -> ctx.getString(R.string.when_yesterday, clock)
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))
    }
}

private fun LetterFile.isImage() = mime.orEmpty().startsWith("image/")

@Composable
private fun countLabel(l: Letter): String {
    val n = l.files.size
    return if (l.files.all { it.isImage() }) pluralStringResource(R.plurals.n_images, n, n) else pluralStringResource(R.plurals.label_n_files, n, n)
}

/** What the letter is called on its row: its words, a link's title, a file's name, or what a batch holds */
@Composable
private fun titleOf(l: Letter): String = when {
    l.text != null -> if (l.kind == "url") (l.title ?: shortUrl(l.text!!)) else l.text!!
    l.files.size == 1 -> l.files[0].name
    else -> countLabel(l)
}

/** The line under the title of what came in: where it came from, when, and how big, in that order; what is not known is left out */
@Composable
private fun metaOf(l: Letter, now: Long): String {
    val ctx = LocalContext.current
    val first = if (l.mine) stringResource(R.string.meta_sent) else l.source ?: "dropit"
    val parts = mutableListOf(first, whenText(ctx, l.at, now))
    when {
        l.files.isEmpty() -> if (l.kind == "url" && l.title != null) l.text?.let { parts += shortUrl(it) }
        l.isBatch -> parts += countLabel(l)
        else -> parts += fileSize(l.files[0].size)
    }
    return parts.joinToString(" · ")
}

// ───────────────────────────── the segmented control ─────────────────────────────

/** Received | Sent, each with its count; a dot on Sent when something was sent since it was last looked at */
@Composable
fun ListTabs(sentSelected: Boolean, received: Int, sent: Int, sentDot: Boolean, onSelect: (sent: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = ink
    Row(modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(c.seg).padding(3.dp)) {
        Tab(stringResource(R.string.tab_received), received, !sentSelected, false, Modifier.weight(1f)) { onSelect(false) }
        Tab(stringResource(R.string.tab_sent), sent, sentSelected, sentDot, Modifier.weight(1f)) { onSelect(true) }
    }
}

@Composable
private fun Tab(name: String, count: Int, on: Boolean, dot: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = ink
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier.fillMaxHeight().shadow(if (on) 1.dp else 0.dp, shape).clip(shape).background(if (on) c.paper else Color.Transparent)
            .selectable(selected = on, role = Role.Tab, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(name, sans(15.sp, FontWeight.SemiBold, lineHeight = 15.sp), if (on) c.ink else c.dim, maxLines = 1)
        if (count > 0) Txt(count.toString(), mono(12.5.sp), c.dim, maxLines = 1)
        if (dot) Box(Modifier.offset(x = (-2).dp).size(8.dp).clip(CircleShape).background(c.navy))
    }
}

// ───────────────────────────── a row ─────────────────────────────

/** The 40 dp block at the left: lines for words, a link, the picture itself, or the file's extension; a batch carries its count */
@Composable
fun TypeBlock(l: Letter, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val c = ink
    val shape = RoundedCornerShape(if (size > 44.dp) 12.dp else 10.dp)
    val lead = l.files.firstOrNull { it.thumb != null } ?: l.files.firstOrNull()
    val glyph = (size.value * .55f).dp
    val base = modifier.size(size).clip(shape)
    when {
        lead == null && l.kind == "url" -> Box(base.background(c.tile), contentAlignment = Alignment.Center) { Glyph(Icons.Link, c.navy, size = glyph) }
        lead == null -> Box(base.background(c.tile), contentAlignment = Alignment.Center) { Glyph(Icons.Lines, c.navy, size = glyph) }
        else -> Box(base) {
            val bitmap = remember(lead.thumb) { lead.thumb?.let { p -> runCatching { BitmapFactory.decodeFile(p)?.asImageBitmap() }.getOrNull() } }
            when {
                bitmap != null -> Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                lead.isImage() -> Box(Modifier.fillMaxSize().background(c.tile), contentAlignment = Alignment.Center) { Glyph(Icons.Picture, c.navy, size = glyph) }
                else -> {
                    val ext = lead.name.substringAfterLast('.', "").take(4).uppercase()
                    Box(Modifier.fillMaxSize().background(c.ground).border(1.dp, c.line, shape), contentAlignment = Alignment.Center) {
                        if (ext.isEmpty()) Glyph(Icons.File, c.dim, size = glyph) else Txt(ext, mono((size.value * .275f).sp, FontWeight.SemiBold), c.dim, maxLines = 1)
                    }
                }
            }
            if (l.isBatch) {
                val n = l.files.size + if (l.text != null) 1 else 0
                Txt(
                    n.toString(), mono(11.sp, FontWeight.SemiBold), c.paper,
                    Modifier.align(Alignment.BottomEnd).clip(RoundedCornerShape(topStart = 9.dp)).background(c.ink).defaultMinSize(minWidth = 18.dp).padding(horizontal = 4.dp, vertical = 2.dp),
                    align = TextAlign.Center,
                )
            }
        }
    }
}

private enum class Tone { OK, BAD, NV, DIM }

private data class Status(val tone: Tone, val text: String)

/** Today's downloads are used up: when they start again, in this phone's time zone; without a time (not told yet, or already past), only that they do */
@Composable
private fun limitText(until: Long?, now: Long): String {
    if (until == null || until <= now) return stringResource(R.string.file_limit)
    val at = java.util.Calendar.getInstance().apply { timeInMillis = until }
    val today = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(until))
    val sameDay = at.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) && at.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)
    return stringResource(when {
        sameDay -> R.string.file_limit_today
        at.get(java.util.Calendar.HOUR_OF_DAY) < 12 -> R.string.file_limit_morning
        else -> R.string.file_limit_tomorrow
    }, time)
}

/** The one line a row says about its files: saved, saving, why it failed, or gone. Null when there is nothing to say. */
@Composable
private fun statusOf(l: Letter, now: Long, ops: LetterOps): Status? {
    ops.notes[l.id]?.let { return Status(Tone.BAD, stringResource(it)) }
    l.failure?.let { return Status(Tone.BAD, failText(l, it)) }
    if (l.mine || l.files.isEmpty()) return null
    val views = l.files.map { l.viewOf(it, now, ops.saving, ops.failures) }
    views.firstOrNull { it.failure != null }?.let { v ->
        if (views.none { it.saving }) return when (v.failure) {
            SaveFailure.OFFLINE -> Status(Tone.BAD, stringResource(R.string.st_offline))
            SaveFailure.LIMIT -> Status(Tone.BAD, limitText(ops.limitUntil, now))
            SaveFailure.UNSUPPORTED -> Status(Tone.DIM, stringResource(R.string.file_unsupported))
            else -> Status(Tone.BAD, stringResource(R.string.st_failed))
        }
    }
    return when {
        views.any { it.saving } -> Status(Tone.NV, stringResource(R.string.st_saving))
        views.all { it.state == FileState.SAVED } -> {
            val pics = l.files.count { it.mime.orEmpty().startsWith("image/") }
            val n = l.files.size
            Status(Tone.OK, when {
                n == 1 -> stringResource(if (pics == 1) R.string.st_saved_photo else R.string.st_saved)
                pics == n -> pluralStringResource(R.plurals.st_saved_photos_n, n, n)
                pics == 0 -> pluralStringResource(R.plurals.st_saved_n, n, n)
                else -> pluralStringResource(R.plurals.st_saved_mixed_n, n, n)
            })
        }
        views.all { it.state == FileState.EXPIRED } -> Status(Tone.BAD, stringResource(R.string.st_expired))
        else -> null
    }
}

/** Which of a drop did not go and why: "File 2 didn't finish (no connection)". The words alone when they are what failed */
@Composable
private fun failText(l: Letter, f: SendFailure): String {
    val why = f.reason
    val left = f.files.size
    val pictures = l.files.all { it.isImage() }
    return when {
        f.target || left == 0 -> why
        l.files.size > 1 && left == 1 -> stringResource(if (pictures) R.string.fail_nth_photo else R.string.fail_nth_file, f.files[0] + 1, why)
        l.files.size > 1 -> pluralStringResource(R.plurals.fail_n_left, left, left, why)
        else -> why
    }
}

@Composable
private fun StatusLine(s: Status, modifier: Modifier = Modifier) {
    val c = ink
    val color = when (s.tone) { Tone.OK -> c.ok; Tone.BAD -> c.red; Tone.NV -> c.navy; Tone.DIM -> c.dim }
    Row(modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        if (s.tone == Tone.OK) Glyph(Icons.Check, color, Modifier.padding(top = 1.dp), size = 15.dp)
        if (s.tone == Tone.BAD) Glyph(Icons.Alert, color, Modifier.padding(top = 1.dp), size = 15.dp)
        Txt(s.text, sans(12.5.sp, lineHeight = 17.sp), color, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

private fun iconOf(act: Act): ImageVector? = when (act) {
    Act.COPY -> Icons.Copy
    Act.OPEN_LINK, Act.OPEN_FILE -> Icons.Open
    Act.SAVE, Act.SAVE_ALL -> Icons.Down
    Act.RETRY -> Icons.Retry
    Act.SHARE -> Icons.Share
    Act.COPY_IMAGE -> Icons.Copy
    Act.CLEAR -> Icons.Trash
    Act.RESEND -> Icons.Again
    Act.RETRY_SEND, Act.SEND_ALL -> Icons.Retry
    Act.SAVING, Act.NONE -> null
}

@Composable
private fun nameOf(act: Act, withFiles: Boolean = false): String = stringResource(
    when (act) {
        Act.COPY -> if (withFiles) R.string.copy_text else R.string.copy
        Act.OPEN_LINK, Act.OPEN_FILE -> R.string.open
        Act.SAVE -> R.string.file_save
        Act.SAVE_ALL -> R.string.save_all
        Act.RETRY -> R.string.retry
        Act.SHARE -> R.string.share
        Act.COPY_IMAGE -> R.string.file_copy_image
        Act.CLEAR -> R.string.clear
        Act.RESEND -> R.string.again
        Act.RETRY_SEND -> R.string.retry
        Act.SEND_ALL -> R.string.action_send_all
        Act.SAVING -> R.string.st_saving
        Act.NONE -> R.string.open
    },
)

/** The 44 × 44 button at the right of every row: the same size whatever it does */
@Composable
private fun RowButton(offer: LetterOffer, copied: Boolean, name: String, onClick: () -> Unit) {
    val c = ink
    val red = offer.act == Act.RETRY || offer.act == Act.RETRY_SEND || offer.act == Act.SEND_ALL
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.size(44.dp).alpha(if (offer.enabled) 1f else .4f).clip(shape)
            .background(when { offer.act == Act.SAVING -> Color.Transparent; red -> c.red.copy(alpha = .12f); else -> c.tile })
            .clickable(enabled = offer.enabled && offer.act != Act.SAVING, role = Role.Button, onClickLabel = name, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        if (offer.act == Act.SAVING) Spinner(c.navy, 22.dp)
        else iconOf(offer.act)?.let { Glyph(if (copied && offer.act == Act.COPY) Icons.Check else it, if (red) c.red else c.navy, size = 22.dp) }
    }
}

/** The pictures of a batch, three at most: each shows whether it is saved, and is its own next step */
@Composable
private fun Strip(l: Letter, now: Long, ops: LetterOps, selecting: Boolean) {
    val c = ink
    val pictures = l.files.withIndex().filter { it.value.isImage() }
    if (pictures.isEmpty()) return
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((at, f) in pictures.take(3)) {
            val v = l.viewOf(f, now, ops.saving, ops.failures)
            // A send that stopped: each picture says whether it went
            val didNotGo = l.failure?.files?.contains(at) == true
            val went = l.failure != null && !didNotGo
            val bitmap = remember(f.thumb) { f.thumb?.let { p -> runCatching { BitmapFactory.decodeFile(p)?.asImageBitmap() }.getOrNull() } }
            val actionable = !selecting && l.failure == null && (v.state == FileState.SAVED || (v.state == FileState.AVAILABLE && !v.saving))
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(c.line)
                    .then(if (actionable) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(if (v.state == FileState.SAVED) R.string.open else R.string.file_save)) { ops.tapFile(l, f) } else Modifier)
                    .semantics { contentDescription = f.name },
            ) {
                if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize().background(c.tile), contentAlignment = Alignment.Center) { Glyph(Icons.Picture, c.navy, size = 26.dp) }
                val badge: ImageVector? = when {
                    didNotGo -> Icons.Alert
                    went -> Icons.Check
                    v.state == FileState.SENT || v.state == FileState.EXPIRED || v.saving -> null
                    v.state == FileState.SAVED -> Icons.Check
                    v.failure != null && v.failure != SaveFailure.UNSUPPORTED -> Icons.Alert
                    else -> Icons.Down
                }
                if (v.saving) Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(Color.White.copy(alpha = .92f)), contentAlignment = Alignment.Center) { Spinner(c.navy, 12.dp) }
                if (badge != null) {
                    val bad = badge == Icons.Alert
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(if (bad) c.red else Color.White.copy(alpha = .92f)),
                        contentAlignment = Alignment.Center,
                    ) { Glyph(badge, if (bad) Color.White else Color(0xFF1F4E9E), size = 13.dp) }
                }
            }
        }
        if (pictures.size > 3) Txt("+${pictures.size - 3}", mono(11.5.sp), c.dim)
    }
}

/** What the line under a sent letter's title says: who it went to (tap it to send again), when, and how many of them have it */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SentMeta(l: Letter, now: Long, delivery: Delivery?, tappable: Boolean, onTarget: () -> Unit) {
    val c = ink
    val ctx = LocalContext.current
    FlowRow(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Txt(
            "→ ${wentTo(l)}", sans(12.5.sp, lineHeight = 17.sp), c.dim,
            Modifier.then(if (tappable) Modifier.clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button, onClick = onTarget) else Modifier),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (l.again) Txt(
            stringResource(R.string.tag_again), sans(11.sp, FontWeight.SemiBold, lineHeight = 14.sp), c.red,
            Modifier.border(1.dp, c.red, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp), maxLines = 1,
        )
        Txt("· ${whenText(ctx, l.at, now)}", sans(12.5.sp, lineHeight = 17.sp), c.dim, maxLines = 1)
        if (delivery != null) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt("· ${delivery.got}/${delivery.total}", sans(12.5.sp, lineHeight = 17.sp).copy(fontFeatureSettings = "tnum"), c.dim, maxLines = 1)
            delivery.dots()?.let { Txt(it, sans(12.5.sp, lineHeight = 17.sp), c.navy, maxLines = 1) }
        }
    }
}

/** What a paid plan would fix for this letter: the day's downloads are used up, or every file of it has expired; null when neither */
internal fun Letter.upgradeWhy(now: Long, ops: LetterOps): String? {
    val v = files.map { viewOf(it, now, ops.saving, ops.failures) }
    return when {
        v.any { it.failure == SaveFailure.LIMIT } -> "downloads"
        v.isNotEmpty() && v.all { it.state == FileState.EXPIRED } -> "kept"
        else -> null
    }
}

/**
 * One letter as a row: type block, words (the title at most two lines, a line of where / when / how big, at most one line of
 * state), and one button. Tapping the row opens its sheet; a long press starts picking; while picking, a tap picks it.
 * [picked] is null when not picking. [peers]: the devices, when they are known: they give "who it went to" its progress and say
 * whether a sent letter has anywhere to be sent again.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LetterRow(
    l: Letter, now: Long, ops: LetterOps, upgrade: ((String) -> Unit)?, picked: Boolean?,
    peers: List<Peer>?, selfId: String?, keptNote: Boolean = false,
    onOpen: () -> Unit, onPick: () -> Unit, onClear: () -> Unit,
) {
    val c = ink
    val selecting = picked != null
    val resend = resendAvail(l, now, receiversOf(peers.orEmpty(), selfId).size)
    // A retry is on its way: the button turns into a spinner until it comes back
    val offer = if (l.id in ops.retrying) LetterOffer(Act.SAVING, enabled = false) else l.primary(now, ops.saving, ops.failures, resend)
    val status = statusOf(l, now, ops)
    // A failed card has a thin pale-red edge, and no bar: the red icon, the reason and the button already say it
    val failed = l.failure != null ||
        (l.files.any { f -> l.viewOf(f, now, ops.saving, ops.failures).let { it.failure != null && it.failure != SaveFailure.UNSUPPORTED } } && status?.tone == Tone.BAD)
    val bg = if (picked == true) lerp(c.paper, c.navy, .06f) else c.paper
    Row(
        Modifier.fillMaxWidth().shadow(1.dp, RowShape).clip(RowShape).background(bg)
            .then(if (picked == true) Modifier.border(2.dp, c.navy, RowShape) else if (failed) Modifier.border(1.dp, c.red.copy(alpha = .5f), RowShape) else Modifier)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() }, indication = ripple(),
                role = if (selecting) Role.Checkbox else Role.Button,
                onClickLabel = stringResource(if (selecting) R.string.sel_toggle else R.string.sheet_open),
                onLongClickLabel = stringResource(R.string.sel_start), onLongClick = onPick,
                onClick = if (selecting) onPick else onOpen,
            )
            .semantics { if (selecting) selected = picked == true }
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (selecting) {
            Box(
                Modifier.padding(top = 8.dp, end = 10.dp).size(24.dp).clip(CircleShape).background(if (picked == true) c.navy else Color.Transparent)
                    .border(2.dp, if (picked == true) c.navy else c.dim, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (picked == true) Glyph(Icons.Check, c.paper, size = 14.dp) }
        }
        TypeBlock(l, Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            val title = titleOf(l)
            val link = l.kind == "url" && l.text != null
            Txt(
                title, sans(15.sp, lineHeight = 20.7.sp).let { if (link) it.copy(textDecoration = TextDecoration.Underline) else it }, if (link) c.navy else c.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (l.mine) SentMeta(l, now, deliveryOf(l, peers, selfId), tappable = !selecting && resend != ResendAvail.NONE) { ops.onResend(l) }
            else Txt(metaOf(l, now), sans(12.5.sp, lineHeight = 17.sp), c.dim, Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            status?.let { StatusLine(it) }
            if (l.isBatch) Strip(l, now, ops, selecting)
            // Where Paid fixes what was just met, said right there; the first expired file of a free account also says why (once, as a fact)
            if (!selecting && (upgrade != null || keptNote)) {
                val why = l.upgradeWhy(now, ops)
                if (why != null && upgrade != null) LinkAction({ upgrade(why) }, text = upgradeWord(why), color = c.navy, modifier = Modifier.offset(x = (-6).dp))
                if (why == "kept" && keptNote) KeptNote(Modifier.padding(top = if (upgrade == null) 4.dp else 0.dp, bottom = 2.dp))
            }
        }
        if (!selecting && offer.act != Act.NONE) {
            RowButton(offer, copied = ops.copied == l.id, name = nameOf(offer.act, l.files.isNotEmpty())) { ops.perform(offer.act, l, onClear) }
        }
    }
}

// ───────────────────────────── the sheet ─────────────────────────────

@Composable
private fun SheetBlock(offer: LetterOffer, withFiles: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = ink
    val clear = offer.act == Act.CLEAR
    val busy = offer.act == Act.SAVING
    Column(
        modifier.heightIn(min = 72.dp).alpha(if (offer.enabled) 1f else .4f).clip(RoundedCornerShape(12.dp))
            .background(if (clear) c.red.copy(alpha = .10f) else c.tile)
            .clickable(enabled = offer.enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        if (busy) Spinner(c.navy, 24.dp) else iconOf(offer.act)?.let { Glyph(it, if (clear) c.red else c.navy, size = 24.dp) }
        Txt(nameOf(offer.act, withFiles), sans(12.5.sp, FontWeight.SemiBold, lineHeight = 16.sp), c.ink, align = TextAlign.Center, maxLines = 2)
    }
}

/** One file of a batch inside the sheet: its name, size and state; a tap is its own next step */
@Composable
private fun FileLine(l: Letter, f: LetterFile, now: Long, ops: LetterOps) {
    val c = ink
    val v = l.viewOf(f, now, ops.saving, ops.failures)
    val actionable = v.state == FileState.SAVED || (v.state == FileState.AVAILABLE && !v.saving)
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp)
            .then(if (actionable) Modifier.clickable(role = Role.Button) { ops.tapFile(l, f) } else Modifier),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Txt(f.name, sans(14.sp, lineHeight = 19.sp), if (v.state == FileState.EXPIRED) c.dim else c.ink, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        Txt(fileSize(f.size), mono(11.5.sp), c.dim, maxLines = 1)
        Spacer(Modifier.weight(1f))
        when {
            v.saving -> Spinner(c.navy, 16.dp)
            v.state == FileState.SAVED -> Glyph(Icons.Check, c.ok, size = 18.dp, name = stringResource(if (f.mime.orEmpty().startsWith("image/")) R.string.st_saved_photo else R.string.st_saved))
            v.state == FileState.EXPIRED -> Txt(stringResource(R.string.expired_short), sans(12.sp), c.red, maxLines = 1)
            v.failure != null && v.failure != SaveFailure.UNSUPPORTED -> Glyph(Icons.Alert, c.red, size = 18.dp, name = stringResource(R.string.st_failed))
            v.state == FileState.AVAILABLE -> Glyph(Icons.Down, c.navy, size = 18.dp, name = stringResource(R.string.file_save))
            else -> {}
        }
    }
}

/**
 * Everything a letter can do, as one row of equal blocks, over a preview of what it is. Choosing a block does it and closes
 * the sheet; the row's own line then says how it went.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LetterSheet(l: Letter, now: Long, ops: LetterOps, resend: ResendAvail, onDismiss: () -> Unit, onClear: () -> Unit) {
    val c = ink
    val scope = rememberCoroutineScope()
    val state: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val menu = l.menu(now, ops.saving, ops.failures, resend)
    fun close(then: () -> Unit = {}) { then(); scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state, containerColor = c.paper,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = { Box(Modifier.padding(top = 8.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
            Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TypeBlock(l, size = 48.dp)
                Column(Modifier.weight(1f)) {
                    val plainText = l.text != null && l.kind != "url" && l.files.isEmpty()
                    Txt(
                        if (plainText) stringResource(R.string.label_kind_text) else titleOf(l), sans(15.sp, FontWeight.SemiBold, lineHeight = 20.7.sp), c.ink,
                        Modifier.semantics { heading() }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Txt(metaOf(l, now), sans(12.5.sp, lineHeight = 17.sp), c.dim, Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            l.fromTitle?.let { Txt(stringResource(R.string.from_page, it), sans(12.5.sp, lineHeight = 17.sp), c.dim, Modifier.padding(bottom = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            l.text?.let { text ->
                Box(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp)).background(c.ground).heightIn(max = 150.dp).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Txt(text, sans(15.sp, lineHeight = 22.5.sp), c.ink)
                }
            }
            if (l.isBatch && !l.mine) Column(Modifier.padding(bottom = 8.dp)) { for (f in l.files) FileLine(l, f, now, ops) }
            statusOf(l, now, ops)?.let { StatusLine(it, Modifier.padding(bottom = 8.dp)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (o in menu) SheetBlock(o, l.files.isNotEmpty(), Modifier.weight(1f)) {
                    if (o.act == Act.CLEAR) close(onClear) else close { ops.perform(o.act, l, onClear) }
                }
                // Keeps four columns when there are fewer blocks than that
                repeat(4 - menu.size) { Spacer(Modifier.weight(1f)) }
            }
            if (l.mine && resend == ResendAvail.EXPIRED) Txt(stringResource(R.string.sheet_resend_expired), sans(12.5.sp, lineHeight = 18.sp), c.dim, Modifier.fillMaxWidth().padding(top = 12.dp), align = TextAlign.Center)
            if (l.mine && l.files.isNotEmpty()) Txt(stringResource(R.string.sheet_sent_note), sans(12.5.sp, lineHeight = 18.sp), c.dim, Modifier.fillMaxWidth().padding(top = 12.dp), align = TextAlign.Center)
            Txt(stringResource(R.string.sheet_note), sans(12.5.sp, lineHeight = 18.sp), c.dim, Modifier.fillMaxWidth().padding(top = 12.dp), align = TextAlign.Center)
        }
    }
}

// ───────────────────────────── picking, undo, empty ─────────────────────────────

/** Where the tabs were while picking: close, how many, and "all" until all are */
@Composable
fun SelectHeader(count: Int, allPicked: Boolean, onExit: () -> Unit, onAll: () -> Unit) {
    val c = ink
    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = stringResource(R.string.sel_exit), onClick = onExit), contentAlignment = Alignment.Center) {
            Glyph(Icons.X, c.ink, size = 24.dp, name = stringResource(R.string.sel_exit))
        }
        Txt(
            stringResource(R.string.sel_count, count), sans(19.sp, FontWeight.SemiBold, lineHeight = 24.sp), c.ink,
            Modifier.weight(1f).padding(start = 6.dp).semantics { heading(); liveRegion = LiveRegionMode.Polite },
        )
        if (!allPicked) Txt(
            stringResource(R.string.sel_all), sans(14.5.sp, FontWeight.SemiBold), c.navy,
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onAll).defaultMinSize(minHeight = 48.dp).padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}

/** What a picked letter's four bottom buttons are: copy, save, share, clear */
@Composable
fun SelectDock(canCopy: Boolean, canSave: Boolean, canShare: Boolean, onCopy: () -> Unit, onSave: () -> Unit, onShare: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val c = ink
    Row(modifier.fillMaxWidth().background(c.paper).drawBehind { drawRect(c.line, Offset.Zero, Size(size.width, 1.dp.toPx())) }.padding(horizontal = 8.dp, vertical = 8.dp)) {
        @Composable
        fun Slot(icon: ImageVector, text: String, on: Boolean, tint: Color, click: () -> Unit) {
            Column(
                Modifier.weight(1f).heightIn(min = 62.dp).alpha(if (on) 1f else .38f).clip(RoundedCornerShape(12.dp)).clickable(enabled = on, role = Role.Button, onClick = click).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
            ) {
                Glyph(icon, tint, size = 24.dp)
                Txt(text, sans(12.5.sp, FontWeight.SemiBold, lineHeight = 16.sp), c.ink, align = TextAlign.Center, maxLines = 2)
            }
        }
        Slot(Icons.Copy, stringResource(R.string.copy), canCopy, c.navy, onCopy)
        Slot(Icons.Down, stringResource(R.string.file_save), canSave, c.navy, onSave)
        Slot(Icons.Share, stringResource(R.string.share), canShare, c.navy, onShare)
        Slot(Icons.Trash, stringResource(R.string.clear), true, c.red, onClear)
    }
}

/** After clearing: how many, that it is only this phone's list, and a way back for six seconds */
@Composable
fun UndoToast(count: Int, unsaved: Int, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val sub = buildString {
        append(stringResource(R.string.undo_sub))
        if (unsaved > 0) append(" · ").append(pluralStringResource(R.plurals.undo_unsaved, unsaved, unsaved))
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF23262D)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Txt(stringResource(R.string.cleared_n, count), sans(14.sp, lineHeight = 19.sp), Color.White)
            Txt(sub, sans(12.sp, lineHeight = 16.sp), Color.White.copy(alpha = .7f))
        }
        Txt(
            stringResource(R.string.undo), sans(14.5.sp, FontWeight.Bold), Color(0xFF9DB9FF),
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onUndo).defaultMinSize(minHeight = 48.dp).padding(horizontal = 12.dp, vertical = 14.dp),
        )
    }
}

/** An empty list: the striped envelope, what is missing, and what makes it fill. [alone]: no second device yet, so say that first. */
@Composable
fun ListEmpty(sent: Boolean, alone: Boolean, onAddDevice: () -> Unit) {
    val c = ink
    Column(Modifier.fillMaxWidth().padding(start = 36.dp, end = 36.dp, top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Envelope(Modifier.size(width = 112.dp, height = 68.dp), stripe = Stripe.Card, padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Box(Modifier.fillMaxSize().drawBehind {
                val w = size.width; val h = size.height
                val line = androidx.compose.ui.graphics.Path().apply { moveTo(w * .08f, h * .16f); lineTo(w * .5f, h * .66f); lineTo(w * .92f, h * .16f) }
                drawPath(line, c.line, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            })
        }
        Txt(
            stringResource(if (sent) R.string.empty_sent_title else R.string.empty_received_title), sans(19.sp, FontWeight.SemiBold, lineHeight = 25.sp), c.ink,
            Modifier.padding(top = 20.dp, bottom = 6.dp).semantics { heading() }, align = TextAlign.Center,
        )
        Txt(
            stringResource(if (sent) R.string.empty_sent_body else if (alone) R.string.empty_body_alone else R.string.empty_received_body), sans(14.5.sp, lineHeight = 22.sp), c.dim,
            align = TextAlign.Center,
        )
        if (!sent && alone) {
            Txt(
                stringResource(R.string.label_add_device), sans(14.5.sp).copy(textDecoration = TextDecoration.Underline), c.navy,
                Modifier.clickable(role = Role.Button, onClick = onAddDevice).defaultMinSize(minHeight = 48.dp).padding(vertical = 14.dp),
            )
        }
    }
}
