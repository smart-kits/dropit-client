package xyz.smart_kits.dropit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.collectAsState
import xyz.smart_kits.dropit.shared.Pick
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import xyz.smart_kits.dropit.BuildConfig
import xyz.smart_kits.dropit.Me
import xyz.smart_kits.dropit.deviceSuffix
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.AttemptTimeout
import xyz.smart_kits.dropit.shared.OfflineError
import xyz.smart_kits.dropit.shared.Peer
import xyz.smart_kits.dropit.shared.Qr
import xyz.smart_kits.dropit.shared.Requests
import xyz.smart_kits.dropit.shared.UpgradeFrom
import xyz.smart_kits.dropit.shared.pairingLink
import xyz.smart_kits.dropit.shared.peersFrom
import xyz.smart_kits.dropit.ui.kit.Btn
import xyz.smart_kits.dropit.ui.kit.Cta
import xyz.smart_kits.dropit.ui.kit.CtaBar
import xyz.smart_kits.dropit.ui.kit.ErrorLine
import xyz.smart_kits.dropit.ui.kit.GIVE_UP_AFTER_MS
import xyz.smart_kits.dropit.ui.kit.Glyph
import xyz.smart_kits.dropit.ui.kit.IconTile
import xyz.smart_kits.dropit.ui.kit.Icons
import xyz.smart_kits.dropit.ui.kit.LinkAction
import xyz.smart_kits.dropit.ui.kit.LoadingRows
import xyz.smart_kits.dropit.ui.kit.Tag
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.kit.rememberSlow
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.mono
import xyz.smart_kits.dropit.ui.theme.sans

/** When a device was last on, as a line ("Active 2 h ago"); null when that is not known */
private fun lastSeen(ctx: android.content.Context, at: Long?, now: Long): String? {
    at ?: return null
    val min = (now - at) / 60_000
    return when {
        min < 2 -> ctx.getString(R.string.dev_active_now)
        min < 60 -> ctx.getString(R.string.dev_active, ctx.getString(R.string.ago_min, min.toInt()))
        min < 1440 -> ctx.getString(R.string.dev_active, ctx.getString(R.string.ago_hour, (min / 60).toInt()))
        else -> ctx.getString(R.string.dev_active, ctx.getString(R.string.ago_day, (min / 1440).toInt()))
    }
}

/**
 * One device: its tile, its name with a tag ("this phone" beside it, "send only" under it so a long name keeps its room), when it was last
 * on, and what sits at the far end
 */
@Composable
internal fun DeviceRow(d: Peer, self: Boolean, now: Long, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    val c = ink
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(if (self) Icons.Phone else Icons.Laptop, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(d.name, sans(15.5.sp, FontWeight.Medium, lineHeight = 21.sp), c.ink, Modifier.weight(1f, fill = false), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (self) Tag(stringResource(R.string.label_this_phone), self = true)
            }
            if (!self && !d.canReceive) Tag(stringResource(R.string.label_scope_send))
            val note = if (self) ctx.getString(R.string.dev_active_now) else lastSeen(ctx, d.lastSeenAt, now)
            // The second line, small and grey: the end of its number (so two devices with one name can be told apart), then when it was last on
            val line = listOfNotNull(deviceSuffix(d.id), note?.takeIf { it.isNotEmpty() }).joinToString(" · ")
            if (line.isNotEmpty()) Txt(line, sans(12.5.sp, lineHeight = 17.sp).copy(fontFeatureSettings = "tnum"), c.dim)
        }
        trailing()
    }
}

/** The thin line between the parts at the far end of a row */
@Composable
private fun Rule() {
    Box(Modifier.width(1.dp).height(26.dp).background(ink.line))
}

/** The tick that puts a device in this phone's default, in a 48 dp square; the whole square is the target */
@Composable
private fun TickCell(name: String, ticked: Boolean, onClick: () -> Unit) {
    val on = stringResource(R.string.state_ticked)
    val off = stringResource(R.string.state_not_ticked)
    Box(
        Modifier.size(48.dp).clickable(role = Role.Checkbox, onClick = onClick).semantics { contentDescription = name; stateDescription = if (ticked) on else off },
        contentAlignment = Alignment.Center,
    ) { xyz.smart_kits.dropit.ui.kit.Check(ticked) }
}

/** The way to take a device off the account: a 40 dp outlined button in a 48 dp touch square */
@Composable
private fun RemoveCell(name: String, onClick: () -> Unit) {
    val c = ink
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = name, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).clip(shape).border(1.2.dp, c.red.copy(alpha = .5f), shape), contentAlignment = Alignment.Center) {
            Glyph(Icons.Trash, c.red, size = 20.dp, name = name)
        }
    }
}

/** This phone's own way out, in the place the others' remove buttons are: its right edge lines up with theirs */
@Composable
private fun SignOutCell(onClick: () -> Unit) {
    val c = ink
    val shape = RoundedCornerShape(12.dp)
    val name = stringResource(R.string.sign_out_confirm)
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(min = 40.dp).widthIn(min = 56.dp).clip(shape).border(1.2.dp, c.red.copy(alpha = .5f), shape).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Txt(name, sans(14.5.sp, FontWeight.Bold), c.red, maxLines = 1)
        }
    }
}

/**
 * Every device on the account, and who this phone sends to when nothing is chosen. A row is a device: who it is, when it was last on, a
 * tick (only a device that can receive, and only while there are two or more of them), and the way off the account — "Remove" on the
 * others, "Sign out" on this phone. A tick is saved at once, on this phone only (the service is not told); at least one stays ticked, and
 * everything ticked means everyone, so a device that joins later is included.
 */
@Composable
fun DevicesScreen(resumed: Int, go: (Screen) -> Unit, onBack: () -> Unit, onSignedOut: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ink
    var devices by remember { mutableStateOf<List<Peer>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var limit by remember { mutableStateOf(0) }
    var account by remember { mutableStateOf<Me?>(null) }
    var removing by remember { mutableStateOf<Peer?>(null) }
    var removingNow by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var round by remember { mutableStateOf(0) }
    val me = ctx.app.store.deviceId
    val peers = ctx.app.peers
    val default by peers.defaultTo.collectAsState()

    LaunchedEffect(resumed, round) {
        // A wait has an end: past it the page says so and offers to try again, instead of turning for ever
        error = null
        val finished = withTimeoutOrNull(GIVE_UP_AFTER_MS) {
            try { devices = peersFrom(ctx.app.client.send(Requests.devices()).data) } catch (e: Throwable) { error = e }
            true
        }
        if (finished == null) error = OfflineError(AttemptTimeout())
        val answer = withTimeoutOrNull(8_000) { ctx.app.account.me(fresh = true) }
        account = answer ?: account
        limit = answer?.devicesLimit ?: limit
        // What was ticked is checked against the devices that are there now (those that were removed drop out)
        peers.refresh(maxAgeMs = 30_000)
    }
    val slow = rememberSlow(devices == null && error == null)

    Box {
    PageWithBar(
        stringResource(R.string.label_devices), onBack,
        bar = { CtaBar { Cta(stringResource(R.string.label_add_device), { go(Screen.AddDevice) }, icon = Icons.Plus) } },
        trailing = { devices?.let { Txt(stringResource(R.string.set_devices_count, it.size, limit), mono(13.sp), c.dim, Modifier.padding(end = 4.dp)) } },
    ) {
        val list = devices
        // Which account this is: a short code, selectable so it can be copied and read out
        account?.accountTag?.let { tag ->
            androidx.compose.foundation.text.selection.SelectionContainer {
                Txt(stringResource(R.string.dev_account, tag), mono(12.5.sp), c.dim, Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp))
            }
        }
        when {
            list == null && error == null -> LoadingRows(slow, Modifier.padding(top = 4.dp))
            list != null -> {
                val now = remember(list) { System.currentTimeMillis() }
                val receivers = peers.receivers(list)
                // The default means something only when there is a choice: two other devices that can receive, or more
                val choose = receivers.size >= 2
                val pick = Pick.forSending(receivers, default)
                if (choose) {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val value = if (pick.isAll) stringResource(R.string.target_all) else pluralStringResource(R.plurals.target_count, pick.count, pick.count)
                        // "This phone sends to: All" — the value in the brand blue
                        val head = stringResource(R.string.dev_default_head)
                        androidx.compose.foundation.text.BasicText(
                            androidx.compose.ui.text.buildAnnotatedString {
                                append(head)
                                withStyle(androidx.compose.ui.text.SpanStyle(color = c.navy)) { append(value) }
                            },
                            Modifier.weight(1f).semantics { heading() }, style = sans(17.sp, FontWeight.Bold, lineHeight = 23.sp).copy(color = c.ink),
                        )
                        if (saved) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph(Icons.Check, c.ok, size = 15.dp)
                            Txt(stringResource(R.string.dt_saved), sans(12.5.sp), c.ok)
                        }
                        if (!pick.isAll) Txt(
                            stringResource(R.string.panel_all), sans(13.5.sp, FontWeight.SemiBold), c.navy,
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { peers.setDefault(emptyList()); refused = false; saved = true }
                                .heightIn(min = 48.dp).padding(horizontal = 10.dp).padding(vertical = 14.dp),
                        )
                    }
                    Txt(
                        stringResource(if (pick.isAll) R.string.dt_note_all else R.string.dt_note_part), sans(13.sp, lineHeight = 19.sp), c.dim,
                        Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 4.dp),
                    )
                }
                // Every place in use: said as a plain line; what to do about it is in it (remove one, or, for a free account, Paid)
                if (limit > 0 && list.size >= limit) {
                    val plans = ctx.app.plans
                    val upgrade = account?.paid == false && BuildConfig.PAYMENTS && plans.offering
                    Txt(
                        if (upgrade) stringResource(R.string.mo_n3_up, list.size, limit, plans.paid.devices) else stringResource(R.string.mo_n3, list.size, limit),
                        sans(13.sp, lineHeight = 19.sp).copy(fontFeatureSettings = "tnum"), c.dim, Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
                    )
                }
                // This phone first, then the rest in the order the service gives them; each device is a card of its own
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    list.sortedByDescending { it.id == me }.forEach { d ->
                        val self = d.id == me
                        xyz.smart_kits.dropit.ui.kit.Panel {
                            DeviceRow(d, self = self, now = now) {
                                if (self) SignOutCell { leaving = true } else {
                                    if (choose) {
                                        Rule()
                                        if (d.canReceive) TickCell(d.name, d.id in pick.ticked) {
                                            val t = pick.toggled(d.id, keepOne = true)
                                            refused = t.refused
                                            // Everything ticked is "everyone" (empty): a device that joins later is included
                                            if (!t.refused) { peers.setDefault(if (t.pick.isAll) emptyList() else t.pick.to().orEmpty()); saved = true }
                                        } else Spacer(Modifier.size(48.dp))
                                    }
                                    Rule()
                                    RemoveCell(stringResource(R.string.remove)) { removing = d }
                                }
                            }
                        }
                    }
                }
                if (refused) Row(Modifier.padding(top = 8.dp, start = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    Glyph(Icons.Alert, c.red, Modifier.padding(top = 1.dp), size = 15.dp)
                    Txt(stringResource(R.string.panel_keep_one), sans(12.5.sp, lineHeight = 17.5.sp), c.red)
                }
                removingNow?.let { n -> Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { xyz.smart_kits.dropit.ui.kit.Spinner(c.dim, 14.dp); Txt(stringResource(R.string.removing_device, n), sans(13.sp), c.dim) } }
                Txt(stringResource(R.string.dev_remove_note), sans(12.5.sp, lineHeight = 18.5.sp), c.dim, Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp))
            }
        }
        error?.let {
            ErrorLine(errorText(it), Modifier.padding(top = 12.dp))
            Btn(stringResource(R.string.try_again), { round++ }, Modifier.padding(top = 10.dp), small = true)
        }
    }

    removing?.let { d ->
        Confirm(
            stringResource(R.string.dev_remove_ask, d.name), stringResource(R.string.dev_remove_body), stringResource(R.string.remove),
            onConfirm = {
                removing = null
                removingNow = d.name
                scope.launch {
                    val removed = try { ctx.app.client.send(Requests.revoke(d.id)); true } catch (e: Throwable) { error = e; false }
                    removingNow = null
                    // The devices the panel offers are read again, and what was chosen as the default is checked against them
                    ctx.app.peers.refresh()
                    // Reading the list again starts by clearing [error]: only after a removal that went through, or the refusal would be gone
                    // before it could be read. The list is as it was, and the error stays until the next thing the person does
                    if (removed) round++
                }
            },
            onDismiss = { removing = null },
        )
    }
    if (leaving) {
        Confirm(
            stringResource(R.string.sign_out_ask), stringResource(R.string.sign_out_body), stringResource(R.string.sign_out_confirm),
            onConfirm = {
                leaving = false; signingOut = true
                scope.launch { ctx.app.account.signOut(); ctx.app.letters.clear(); ctx.app.peers.reset(); onSignedOut() }
            },
            onDismiss = { leaving = false },
        )
    }
    // Leaving waits for the service for a few seconds at most; until then the screen says so and takes no more taps
    if (signingOut) {
        Box(
            Modifier.matchParentSize().background(c.ground.copy(alpha = .85f)).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = {}),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                xyz.smart_kits.dropit.ui.kit.Spinner(c.ink, 20.dp)
                Txt(stringResource(R.string.signing_out), sans(15.sp), c.ink)
            }
        }
    }
    }
}

/**
 * A code for another device to join with, as large type and as a QR code. When every slot is in use the
 * device it replaces is picked first — here, where the list is, not on the device that is joining.
 */
@Composable
fun AddDeviceScreen(go: (Screen) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ink
    var devices by remember { mutableStateOf<List<Peer>?>(null) }
    var limit by remember { mutableStateOf<Int?>(null) }
    var replace by remember { mutableStateOf<Peer?>(null) }
    var choosing by remember { mutableStateOf(false) }   // "Change": back to the list to pick another device to replace
    var code by remember { mutableStateOf<String?>(null) }
    var expiresAt by remember { mutableStateOf(0L) }
    var left by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var joined by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableStateOf(0) }
    val me = ctx.app.store.deviceId

    // Keyed on [round] too: "try again" starts the whole thing over, including the list of devices when that was what failed
    LaunchedEffect(round) {
        if (devices != null) return@LaunchedEffect
        error = null
        val finished = withTimeoutOrNull(GIVE_UP_AFTER_MS) {
            try {
                devices = peersFrom(ctx.app.client.send(Requests.devices()).data)
                limit = withTimeoutOrNull(8_000) { ctx.app.account.me(fresh = true)?.devicesLimit }
            } catch (e: Throwable) { error = e }
            true
        }
        if (finished == null) error = OfflineError(AttemptTimeout())
    }
    val list = devices
    val full = list != null && limit != null && list.size >= limit!!
    // Every place in use is a limit met: the pill at the top of the home screen may move for a day
    LaunchedEffect(full) { if (full) ctx.app.moments.markWall(System.currentTimeMillis()) }
    // Every place in use: the device idle longest is taken by default, so the code shows at once; "Change" opens the list
    LaunchedEffect(list, full) {
        if (full && replace == null && !choosing) replace = list.orEmpty().filter { it.id != me }.minByOrNull { it.lastSeenAt ?: 0L }
    }
    // A code is asked for once it is known whose place it takes (or that there is room)
    LaunchedEffect(list, replace, round) {
        if (list == null || (full && replace == null)) return@LaunchedEffect
        error = null
        val finished = withTimeoutOrNull(GIVE_UP_AFTER_MS) {
            try {
                val a = ctx.app.client.send(Requests.pair(replace?.id))
                code = a.str("code")
                expiresAt = a.data["expires_at"]?.jsonPrimitive?.longOrNull ?: (System.currentTimeMillis() + 300_000)
            } catch (e: Throwable) { error = e }
            true
        }
        if (finished == null) error = OfflineError(AttemptTimeout())
    }
    val slow = rememberSlow(error == null && code == null && joined == null && !(full && replace == null && list != null))
    // Count down, and look every few seconds for the new device: when it is there, say so and stop
    LaunchedEffect(code) {
        val before = list.orEmpty().map { it.id }.toSet()
        var tick = 0
        while (code != null && joined == null) {
            left = (expiresAt - System.currentTimeMillis()).coerceAtLeast(0)
            if (left == 0L) break
            if (tick++ % 4 == 3) {
                runCatching { peersFrom(ctx.app.client.send(Requests.devices()).data) }.getOrNull()
                    ?.firstOrNull { it.id !in before }?.let { joined = it.name; ctx.app.peers.refresh() }
            }
            delay(1000)
        }
    }

    // Another device came in: said as a screen of its own, not a line in the middle of the old one
    joined?.let { name ->
        // Room for one more only when the new one did not take a place over: a device was replaced, or the places were already full
        val roomLeft = replace == null && list != null && limit != null && list.size + 1 < limit!!
        DeviceJoined(
            name, replaced = replace?.name, onDone = onBack,
            onAnother = if (roomLeft) ({
                joined = null; code = null; devices = null; replace = null; choosing = false; round++
            }) else null,
        )
        return
    }
    Page(stringResource(R.string.label_add_device), onBack) {
        when {
            full && (replace == null || choosing) && list != null -> {
                Txt(stringResource(R.string.add_full, list.size), sans(14.sp, lineHeight = 21.sp), c.ink, Modifier.padding(top = 4.dp, bottom = 10.dp, start = 2.dp))
                val now = remember(list) { System.currentTimeMillis() }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    list.filter { it.id != me }.forEach { d ->
                        xyz.smart_kits.dropit.ui.kit.Panel { DeviceRow(d, self = false, now = now) { Btn(stringResource(R.string.add_replace), { if (replace?.id != d.id) code = null; replace = d; choosing = false }, small = true) } }
                    }
                }
                // The other way out, said once: a paid plan has room for more
                if (BuildConfig.PAYMENTS && LocalContext.current.app.plans.offering) Cta(upgradeWord("devices"), { go(Screen.Plans("devices", UpgradeFrom.DEVICES)) }, Modifier.padding(top = 14.dp), ghost = true)
            }
            code != null -> {
                xyz.smart_kits.dropit.ui.kit.Panel(Modifier.padding(top = 8.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Txt(code!!, mono(40.sp, FontWeight.Bold).copy(letterSpacing = .14.em), c.ink, Modifier.padding(bottom = 12.dp))
                        // Copy it or send it as a link: the link opens the right client with the code filled in
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LinkPill(stringResource(R.string.copy), Icons.Copy) { ctx.copy(code!!) }
                            LinkPill(stringResource(R.string.add_share_link), Icons.Share) { ctx.shareText(pairingLink(code!!)) }
                        }
                        if (left > 0) {
                            val clock = "%d:%02d".format(left / 60_000, left / 1000 % 60)
                            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Glyph(Icons.Clock, c.dim, size = 16.dp)
                                Txt(stringResource(R.string.add_expires, clock), sans(13.sp).copy(fontFeatureSettings = "tnum"), c.dim)
                            }
                            QrCode(pairingLink(code!!), Modifier.padding(top = 14.dp))
                        } else {
                            Txt(stringResource(R.string.add_expired), sans(13.sp), c.red, Modifier.padding(top = 12.dp))
                            Btn(stringResource(R.string.add_new_code), { round++ }, Modifier.padding(top = 12.dp), small = true)
                        }
                        replace?.let { Txt(stringResource(R.string.add_replaces, it.name), sans(12.5.sp), c.dim, Modifier.padding(top = 10.dp)) }
                        if (replace != null && list != null && list.size > 2) Btn(stringResource(R.string.add_change), { choosing = true }, Modifier.padding(top = 6.dp), small = true)
                    }
                }
                // What to do on the other device, in two steps
                xyz.smart_kits.dropit.ui.kit.Panel(Modifier.padding(top = 12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Step(1, stringResource(R.string.add_step1))
                        Step(2, stringResource(R.string.add_step2))
                    }
                }
                // The new device may have nothing yet: say where to get it, once. The link opens here; sharing sends it to the new device, so nothing has to be typed there
                Txt(stringResource(R.string.add_install), sans(12.5.sp, lineHeight = 18.5.sp), c.dim, Modifier.padding(start = 4.dp, end = 4.dp, top = 14.dp))
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Txt(
                        "dropit.smart-kits.xyz", sans(13.5.sp).copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), c.navy,
                        Modifier.padding(start = 4.dp).clickable(role = Role.Button) { ctx.openLink(xyz.smart_kits.dropit.shared.INSTALL_URL) }.heightIn(min = 48.dp).padding(vertical = 14.dp),
                    )
                    LinkAction({ ctx.shareText(xyz.smart_kits.dropit.shared.INSTALL_URL) }, text = stringResource(R.string.add_install_share), icon = Icons.Share, color = c.navy)
                }
            }
            error == null -> LoadingRows(slow, Modifier.padding(top = 4.dp), rows = 2)
        }
        error?.let {
            ErrorLine(errorText(it), Modifier.padding(top = 12.dp))
            Btn(stringResource(R.string.try_again), { round++ }, Modifier.padding(top = 10.dp), small = true)
        }
    }
}

/**
 * A device has joined: the envelope with a device in it and a tick on its corner, who it is, what that means, and the way out (Done),
 * with "Add another" quietly under it when a place is left.
 */
@Composable
private fun DeviceJoined(name: String, replaced: String?, onDone: () -> Unit, onAnother: (() -> Unit)?) {
    val c = ink
    Column(Modifier.fillMaxSize().background(c.ground).safeDrawingPadding()) {
        xyz.smart_kits.dropit.ui.kit.TitleBar(stringResource(R.string.label_add_device), onDone, Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        // The picture and the words sit in the middle of what is left, the button stays at the bottom
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    xyz.smart_kits.dropit.ui.kit.Envelope(
                        Modifier.size(width = 232.dp, height = 144.dp), stripe = xyz.smart_kits.dropit.ui.kit.Stripe.Card,
                        padding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Glyph(Icons.Devices, c.navy, size = 64.dp) }
                    }
                    Box(
                        Modifier.align(Alignment.BottomEnd).offset(x = 20.dp, y = 20.dp).size(64.dp).clip(CircleShape).background(c.ok),
                        contentAlignment = Alignment.Center,
                    ) { Glyph(Icons.Check, if (c.dark) Color(0xFF0E1626) else Color.White, size = 38.dp) }
                }
                Txt(
                    stringResource(R.string.add_joined, name), sans(26.sp, FontWeight.Bold, lineHeight = 32.sp), c.ink,
                    Modifier.padding(top = 56.dp).semantics { heading() }, align = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Txt(
                    stringResource(R.string.add_joined_sub), sans(16.sp, lineHeight = 24.sp), c.dim,
                    Modifier.padding(top = 10.dp).widthIn(max = 320.dp), align = androidx.compose.ui.text.style.TextAlign.Center,
                )
                if (replaced != null) Txt(
                    stringResource(R.string.add_joined_replaced, replaced), sans(14.sp, lineHeight = 21.sp), c.dim,
                    Modifier.padding(top = 6.dp).widthIn(max = 320.dp), align = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        CtaBar {
            Cta(stringResource(R.string.ob_done), onDone)
            if (onAnother != null) Txt(
                stringResource(R.string.add_another), sans(14.5.sp, FontWeight.SemiBold), c.navy,
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onAnother).heightIn(min = 48.dp).padding(top = 14.dp),
                align = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** A step: a number in a round tile, and what to do */
@Composable
private fun Step(n: Int, text: String) {
    val c = ink
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(c.tile), contentAlignment = Alignment.Center) { Txt(n.toString(), mono(13.sp, FontWeight.Bold), c.navy) }
        Txt(text, sans(14.5.sp, lineHeight = 20.sp), c.ink, Modifier.weight(1f))
    }
}

/** A small blue-wash button with an icon: Copy, Share link */
@Composable
private fun LinkPill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val c = ink
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(c.tile).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, c.navy, size = 20.dp)
        Txt(text, sans(14.5.sp, FontWeight.SemiBold, lineHeight = 19.sp), c.navy, maxLines = 1)
    }
}

/** The pairing link as a QR code: always dark on white with the quiet margin around it, or cameras won't read it */
@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { runCatching { Qr.matrix(text) }.getOrNull() } ?: return
    val name = stringResource(R.string.qr_name)
    Canvas(modifier.size(176.dp).background(Color.White).border(1.dp, ink.line).semantics { contentDescription = name }) {
        val quiet = 4
        val cell = size.minDimension / (matrix.size + 2 * quiet)
        for (r in matrix.indices) for (col in matrix.indices) {
            if (matrix[r][col] == 1) {
                // A hair wider than a cell, so no light seams show between dark modules
                drawRect(Color(0xFF15171C), Offset((col + quiet) * cell, (r + quiet) * cell), Size(cell + .5f, cell + .5f))
            }
        }
    }
}
