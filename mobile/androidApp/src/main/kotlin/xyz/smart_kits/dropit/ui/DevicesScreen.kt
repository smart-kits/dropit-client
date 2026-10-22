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

/** One device: its tile, its name with a tag ("this phone", "send only"), when it was last on, and what sits at the far end */
@Composable
internal fun DeviceRow(d: Peer, self: Boolean, now: Long, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    val c = ink
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(if (self) Icons.Phone else Icons.Laptop, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(d.name, sans(15.5.sp, FontWeight.Medium, lineHeight = 21.sp), c.ink, Modifier.weight(1f, fill = false), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                when {
                    self -> Tag(stringResource(R.string.label_this_phone), self = true)
                    !d.canReceive -> Tag(stringResource(R.string.label_scope_send))
                }
            }
            val note = if (self) ctx.getString(R.string.dev_active_now) else lastSeen(ctx, d.lastSeenAt, now)
            if (!note.isNullOrEmpty()) Txt(note, sans(12.5.sp, lineHeight = 17.sp).copy(fontFeatureSettings = "tnum"), c.dim)
        }
        trailing()
    }
}

/** Every device on the account: who they are, when they were last on, and a way to remove any but this one */
@Composable
fun DevicesScreen(resumed: Int, go: (Screen) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ink
    var devices by remember { mutableStateOf<List<Peer>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var limit by remember { mutableStateOf(0) }
    var account by remember { mutableStateOf<Me?>(null) }
    var removing by remember { mutableStateOf<Peer?>(null) }
    var removingNow by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableStateOf(0) }
    val me = ctx.app.store.deviceId

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
    }
    val slow = rememberSlow(devices == null && error == null)

    PageWithBar(
        stringResource(R.string.label_devices), onBack,
        bar = { CtaBar { Cta(stringResource(R.string.label_add_device), { go(Screen.AddDevice) }, icon = Icons.Plus) } },
        trailing = { devices?.let { Txt(stringResource(R.string.set_devices_count, it.size, limit), mono(13.sp), c.dim, Modifier.padding(end = 4.dp)) } },
    ) {
        val list = devices
        when {
            list == null && error == null -> LoadingRows(slow, Modifier.padding(top = 4.dp))
            list != null -> {
                val now = remember(list) { System.currentTimeMillis() }
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
                        xyz.smart_kits.dropit.ui.kit.Panel {
                            DeviceRow(d, self = d.id == me, now = now) {
                                // No remove on this phone's own row: leaving is "Sign out" in settings
                                if (d.id != me) Box {
                                    LinkAction({ menuFor = d.id }, icon = Icons.Dots, name = stringResource(R.string.more), minSize = 48.dp)
                                    DropdownMenu(expanded = menuFor == d.id, onDismissRequest = { menuFor = null }, containerColor = c.paper) {
                                        DropdownMenuItem(text = { Txt(stringResource(R.string.remove), sans(15.sp), c.red) }, onClick = { menuFor = null; removing = d })
                                    }
                                }
                            }
                        }
                    }
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
            stringResource(R.string.dev_remove_ask, d.name), stringResource(R.string.dev_remove_note), stringResource(R.string.remove),
            onConfirm = {
                removing = null
                removingNow = d.name
                scope.launch {
                    try { ctx.app.client.send(Requests.revoke(d.id)) } catch (e: Throwable) { error = e }
                    removingNow = null
                    // The devices the panel offers are read again, and what was chosen as the default is checked against them
                    ctx.app.peers.refresh()
                    round++
                }
            },
            onDismiss = { removing = null },
        )
    }
}

/**
 * A code for another device to join with, as large type and as a QR code. When every slot is in use the
 * device it replaces is picked first — here, where the list is, not on the device that is joining.
 */
@Composable
fun AddDeviceScreen(go: (Screen) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val buy = rememberBuy()
    val c = ink
    var devices by remember { mutableStateOf<List<Peer>?>(null) }
    var limit by remember { mutableStateOf<Int?>(null) }
    var replace by remember { mutableStateOf<Peer?>(null) }
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

    Page(stringResource(R.string.label_add_device), onBack) {
        when {
            joined != null -> {
                Txt(stringResource(R.string.add_joined, joined!!), sans(15.sp, FontWeight.SemiBold), c.navy, Modifier.padding(vertical = 16.dp))
                Cta(stringResource(R.string.ob_done), onBack)
            }
            full && replace == null && list != null -> {
                Txt(stringResource(R.string.add_full, list.size), sans(14.sp, lineHeight = 21.sp), c.ink, Modifier.padding(top = 4.dp, bottom = 10.dp, start = 2.dp))
                val now = remember(list) { System.currentTimeMillis() }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    list.filter { it.id != me }.forEach { d ->
                        xyz.smart_kits.dropit.ui.kit.Panel { DeviceRow(d, self = false, now = now) { Btn(stringResource(R.string.add_replace), { replace = d }, small = true) } }
                    }
                }
                // The other way out, said once: a paid plan has room for more
                if (BuildConfig.PAYMENTS && LocalContext.current.app.plans.offering) Cta(upgradeWord("devices"), { buy.start(UpgradeFrom.DEVICES, "devices") }, Modifier.padding(top = 14.dp), loading = buy.opening != null, ghost = true)
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
