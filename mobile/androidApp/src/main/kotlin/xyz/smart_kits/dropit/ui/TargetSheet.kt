package xyz.smart_kits.dropit.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xyz.smart_kits.dropit.Peers
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.shared.Letter
import xyz.smart_kits.dropit.shared.Peer
import xyz.smart_kits.dropit.shared.Pick
import xyz.smart_kits.dropit.shared.RowState
import xyz.smart_kits.dropit.shared.Seen
import xyz.smart_kits.dropit.shared.TargetWords
import xyz.smart_kits.dropit.shared.Targets
import xyz.smart_kits.dropit.shared.rowStateOf
import xyz.smart_kits.dropit.shared.seenOf
import xyz.smart_kits.dropit.shared.shortTarget
import xyz.smart_kits.dropit.shared.targetWordsOf
import xyz.smart_kits.dropit.ui.kit.Check
import xyz.smart_kits.dropit.ui.kit.Cta
import xyz.smart_kits.dropit.ui.kit.Glyph
import xyz.smart_kits.dropit.ui.kit.Icons
import xyz.smart_kits.dropit.ui.kit.IconTile
import xyz.smart_kits.dropit.ui.kit.Spinner
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.sans

// Who a send goes to, drawn: one list of devices in a bottom sheet. Sending: the default is ticked, a tick can be changed for this send only,
// and nothing goes until the big button is pressed; at least one stays ticked. Sending again: nothing is ticked, each device says where it
// stands, and the button waits for a choice. The rules are in the shared Targets.kt; this only draws them.

internal sealed interface SheetMode {
    data object Send : SheetMode
    data class Resend(val letter: Letter) : SheetMode
}

// ───────────────────────────── words ─────────────────────────────

/** A choice in words for a row or a button: "All", a name, "3 devices" */
@Composable
internal fun targetText(words: TargetWords): String = when (words) {
    TargetWords.All -> stringResource(R.string.target_all)
    is TargetWords.Names -> words.text
    is TargetWords.Count -> pluralStringResource(R.plurals.target_count, words.n, words.n)
}

/**
 * The words for devices by their ids and the names known for them, in the phone's language. Everyone only when there are no ids; when
 * there are fewer names than ids (some were not known yet) it is how many, never "all" or one name for two devices.
 */
@Composable
internal fun targetText(ids: List<String>, names: List<String>): String = targetText(targetWordsOf(ids, names, stringResource(R.string.target_sep)))

/** The same for what a send was addressed to (outside a composable, for a line that is said once) */
internal fun targetText(ctx: Context, t: Targets): String = when (val words = targetWordsOf(t.ids, t.names, ctx.getString(R.string.target_sep))) {
    TargetWords.All -> ctx.getString(R.string.target_all)
    is TargetWords.Names -> words.text
    is TargetWords.Count -> ctx.resources.getQuantityString(R.plurals.target_count, words.n, words.n)
}

/** Where a letter went, in words: the names kept with it, or only how many when the names are not known */
@Composable
internal fun wentTo(l: Letter): String {
    return targetText(l.toIds, l.toNames)
}

internal fun seenText(ctx: Context, seen: Seen): String? = when (seen) {
    Seen.Unknown -> null
    Seen.Now -> ctx.getString(R.string.seen_now)
    is Seen.Minutes -> ctx.getString(R.string.ago_min, seen.n)
    is Seen.Hours -> ctx.getString(R.string.ago_hour, seen.n)
    Seen.Yesterday -> ctx.getString(R.string.seen_yesterday)
    is Seen.Days -> ctx.getString(R.string.ago_day, seen.n)
    Seen.LongAgo -> ctx.getString(R.string.seen_long)
}

// ───────────────────────────── the sheet ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TargetSheet(
    mode: SheetMode,
    peers: Peers.State,
    receivers: List<Peer>,
    default: List<String>,
    busy: Boolean,
    error: String?,
    /** Nothing is written or chosen to send yet: the choice would have nothing to go with, so the button waits */
    nothingToSend: Boolean,
    onSubmit: (Targets) -> Unit,
    onOpenDefault: () -> Unit,
    onRetryLoad: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val c = ink
    val scope = rememberCoroutineScope()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val resend = mode is SheetMode.Resend
    val ids = receivers.map { it.id }
    // The ticks start again when the list of devices changes under the sheet (it was still being read when the sheet opened)
    var pick by remember(mode, ids) { mutableStateOf(if (resend) Pick.forResending(receivers) else Pick.forSending(receivers, default)) }
    var refused by remember(mode, ids) { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    val defaultWords = targetText(default, Targets.of(default, receivers).names)

    fun close(then: () -> Unit = {}) { then(); scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state, containerColor = c.paper,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = { Box(Modifier.padding(top = 8.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(
                    stringResource(if (resend) R.string.panel_resend_title else R.string.panel_send_title), sans(19.sp, FontWeight.SemiBold, lineHeight = 23.sp), c.ink,
                    Modifier.weight(1f).semantics { heading() },
                )
                if (receivers.size > 1) Txt(
                    stringResource(R.string.panel_all), sans(13.5.sp, FontWeight.SemiBold), c.navy,
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { pick = pick.everyone(); refused = false }
                        .heightIn(min = 48.dp).padding(start = 10.dp).padding(vertical = 14.dp),
                )
            }
            Txt(
                stringResource(if (resend) R.string.panel_resend_sub else R.string.panel_send_sub), sans(12.5.sp, lineHeight = 17.5.sp), c.dim,
                Modifier.padding(top = 2.dp, bottom = 6.dp),
            )

            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                when {
                    receivers.isNotEmpty() -> receivers.forEachIndexed { k, peer ->
                        if (k > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                        val on = peer.id in pick.ticked
                        val letter = (mode as? SheetMode.Resend)?.letter
                        val rs = letter?.let { rowStateOf(it, peer) }
                        TargetRow(
                            peer, on, subtitle(ctx, peer, rs, now),
                            // Ticking one that will get it anyway when it is next on: said, not forbidden
                            warn = if (rs == RowState.PENDING && on) stringResource(R.string.panel_twice) else null,
                        ) {
                            val t = pick.toggled(peer.id, keepOne = !resend)
                            pick = t.pick
                            refused = t.refused
                        }
                    }
                    peers.peers == null && !peers.failed -> Row(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalArrangement = Arrangement.Center) { Spinner(c.dim, 22.dp) }
                    else -> Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Txt(stringResource(if (peers.peers == null) R.string.panel_load_failed else R.string.panel_none), sans(14.sp, lineHeight = 20.sp), c.dim, align = TextAlign.Center)
                        if (peers.peers == null) Txt(
                            stringResource(R.string.try_again), sans(14.sp, FontWeight.SemiBold), c.navy,
                            Modifier.clickable(role = Role.Button, onClick = onRetryLoad).heightIn(min = 48.dp).padding(vertical = 14.dp),
                        )
                    }
                }
            }

            if (refused) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                Glyph(Icons.Alert, c.red, Modifier.padding(top = 1.dp), size = 15.dp)
                Txt(stringResource(R.string.panel_keep_one), sans(12.5.sp, lineHeight = 17.5.sp), c.red)
            }
            error?.let {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    Glyph(Icons.Alert, c.red, Modifier.padding(top = 1.dp), size = 15.dp)
                    Txt(it, sans(12.5.sp, lineHeight = 17.5.sp), c.red)
                }
            }

            if (nothingToSend) Txt(stringResource(R.string.panel_nothing), sans(12.5.sp, lineHeight = 17.5.sp), c.dim, Modifier.padding(top = 6.dp))
            val none = pick.count == 0
            Cta(
                text = when {
                    resend && none -> stringResource(R.string.panel_pick)
                    resend -> pluralStringResource(R.plurals.panel_resend_n, pick.count, pick.count)
                    else -> pluralStringResource(R.plurals.panel_send_n, pick.count, pick.count)
                },
                onClick = {
                    val chosen = pick.to()
                    onSubmit(if (chosen == null) Targets.ALL else Targets.of(chosen, receivers))
                },
                modifier = Modifier.padding(top = 12.dp), red = true, enabled = !none && receivers.isNotEmpty() && !nothingToSend, loading = busy, corner = 9.dp,
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(
                    stringResource(if (resend) R.string.panel_resend_note else R.string.panel_send_note), sans(12.5.sp, lineHeight = 17.5.sp), c.dim,
                    Modifier.weight(1f),
                )
                if (!resend) Txt(
                    stringResource(R.string.panel_default, defaultWords) + " ›", sans(13.sp, FontWeight.SemiBold), c.navy,
                    Modifier.padding(start = 10.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { close(onOpenDefault) }
                        .heightIn(min = 48.dp).padding(horizontal = 4.dp).padding(vertical = 14.dp),
                )
            }
        }
    }
}

/** The second line of a device's row: where it stands for what is being sent again, or when it was last on */
private fun subtitle(ctx: Context, peer: Peer, state: RowState?, now: Long): Sub {
    val seen = seenText(ctx, seenOf(peer.lastSeenAt, now))
    return when (state) {
        RowState.RECEIVED -> Sub(ctx.getString(R.string.row_received), SubTone.OK)
        RowState.PENDING -> Sub(ctx.getString(R.string.row_pending), SubTone.NV)
        RowState.NOT_SENT -> Sub(listOfNotNull(ctx.getString(R.string.row_not_sent), seen).joinToString(" · "), SubTone.DIM)
        null -> Sub(seen ?: "", SubTone.DIM)
    }
}

internal enum class SubTone { OK, NV, DIM }

/** The line under a device's name, and how it is coloured: green for done, blue for on its way, grey for the rest */
internal data class Sub(val text: String, val tone: SubTone = SubTone.DIM)

/** One device: its tile, its name and a line under it, and a checkbox at the far end; the whole row is the target */
@Composable
internal fun TargetRow(peer: Peer, ticked: Boolean, subtitle: Sub, modifier: Modifier = Modifier, warn: String? = null, onClick: () -> Unit) {
    val c = ink
    val tone = subtitle.tone
    val text = subtitle.text
    val on = stringResource(R.string.state_ticked)
    val off = stringResource(R.string.state_not_ticked)
    Column(
        modifier.fillMaxWidth().clickable(role = Role.Checkbox, onClick = onClick).semantics { stateDescription = if (ticked) on else off },
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Laptop, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Txt(peer.name, sans(15.sp, lineHeight = 20.sp), c.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (text.isNotEmpty()) {
                    val color = when (tone) { SubTone.OK -> c.ok; SubTone.NV -> c.navy; SubTone.DIM -> c.dim }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (tone == SubTone.OK) Glyph(Icons.Check, color, size = 14.dp)
                        Txt(text, sans(12.5.sp, lineHeight = 17.sp), color, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
            Check(ticked)
        }
        if (warn != null) Txt(warn, sans(12.sp, lineHeight = 16.5.sp), c.navy, Modifier.padding(start = 52.dp, bottom = 8.dp, end = 38.dp))
    }
}
