package xyz.smart_kits.dropit.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import xyz.smart_kits.dropit.BuildConfig
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.Plans
import xyz.smart_kits.dropit.shared.Qr
import xyz.smart_kits.dropit.shared.Requests
import xyz.smart_kits.dropit.shared.pairingLink
import xyz.smart_kits.dropit.ui.kit.Btn
import xyz.smart_kits.dropit.ui.kit.Icons
import xyz.smart_kits.dropit.ui.kit.LinkAction
import xyz.smart_kits.dropit.ui.kit.Panel
import xyz.smart_kits.dropit.ui.kit.PanelDivider
import xyz.smart_kits.dropit.ui.kit.Returned
import xyz.smart_kits.dropit.ui.kit.Tag
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.mono
import xyz.smart_kits.dropit.ui.theme.sans

/** A device on the account, as `/v1/devices` gives it */
internal data class Device(val id: String, val name: String, val scope: String, val lastSeen: Long?, val cursor: Long?) {
    companion object {
        fun listFrom(data: JsonObject): List<Device> = data["devices"]?.jsonArray.orEmpty().map { it.jsonObject }.map { o ->
            Device(
                id = o["device_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: "?",
                scope = o["scope"]?.jsonPrimitive?.contentOrNull ?: "full",
                lastSeen = o["last_seen_at"]?.jsonPrimitive?.longOrNull,
                cursor = o["cursor"]?.jsonPrimitive?.longOrNull,
            )
        }
    }
}

private fun lastSeen(ctx: Context, at: Long?, now: Long): String? {
    at ?: return null
    val min = (now - at) / 60_000
    return when {
        min < 2 -> ctx.getString(R.string.dev_active_now)
        min < 60 -> ctx.getString(R.string.dev_active, ctx.getString(R.string.ago_min, min.toInt()))
        min < 1440 -> ctx.getString(R.string.dev_active, ctx.getString(R.string.ago_hour, (min / 60).toInt()))
        else -> ctx.getString(R.string.dev_active, ctx.getString(R.string.ago_day, (min / 1440).toInt()))
    }
}

@Composable
internal fun DeviceRow(d: Device, self: Boolean, now: Long, trailing: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    val c = ink
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(d.name, sans(14.sp, FontWeight.SemiBold, lineHeight = 20.sp), c.ink, Modifier.weight(1f, fill = false), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                when {
                    self -> Tag(stringResource(R.string.label_this_phone), self = true)
                    d.scope == "full" -> Tag(stringResource(R.string.label_scope_full))
                    else -> Tag(stringResource(R.string.label_scope_send))
                }
            }
            val note = listOfNotNull(
                if (self) stringResource(R.string.dev_active_now) else lastSeen(ctx, d.lastSeen, now),
                d.cursor?.takeIf { it > 0 && !self && d.scope == "full" }?.let { stringResource(R.string.dev_up_to, it) },
            ).joinToString(" · ")
            if (note.isNotEmpty()) Txt(note, sans(12.sp, lineHeight = 17.sp).copy(fontFeatureSettings = "tnum"), c.dim)
        }
        trailing()
    }
}

/** Every device on the account: who they are, when they were last here, and a way to remove any but this one */
@Composable
fun DevicesScreen(resumed: Int, go: (Screen) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ink
    var devices by remember { mutableStateOf<List<Device>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var limit by remember { mutableStateOf(0) }
    var removing by remember { mutableStateOf<Device?>(null) }
    var round by remember { mutableStateOf(0) }
    val me = ctx.app.store.deviceId

    LaunchedEffect(resumed, round) {
        try {
            devices = Device.listFrom(ctx.app.client.send(Requests.devices()).data)
            error = null
        } catch (e: Throwable) {
            error = e
        }
        limit = ctx.app.account.me(fresh = true)?.devicesLimit ?: limit
    }

    Page(stringResource(R.string.label_devices), onBack, trailing = {
        devices?.let { Txt(stringResource(R.string.dev_count, it.size, limit), mono(12.sp), c.dim, Modifier.padding(end = 4.dp)) }
    }) {
        val list = devices
        when {
            list == null && error == null -> Txt(stringResource(R.string.loading), sans(13.sp), c.dim, Modifier.padding(vertical = 16.dp))
            list != null -> {
                val now = remember(list) { System.currentTimeMillis() }
                Panel {
                    // This phone first, then the rest in the order the service gives them
                    list.sortedByDescending { it.id == me }.forEachIndexed { k, d ->
                        if (k > 0) PanelDivider()
                        DeviceRow(d, self = d.id == me, now = now) {
                            // No remove on this phone's own row: leaving is "Sign out" in settings
                            if (d.id != me) LinkAction({ removing = d }, text = stringResource(R.string.remove), color = c.red)
                        }
                    }
                }
                Btn(stringResource(R.string.label_add_device), { go(Screen.AddDevice) }, Modifier.fillMaxWidth().padding(top = 14.dp), icon = Icons.Plus)
                Txt(stringResource(R.string.dev_remove_note), sans(12.5.sp, lineHeight = 18.5.sp), c.dim, Modifier.padding(top = 10.dp))
            }
        }
        error?.let {
            Returned(listOf(errorText(it)), Modifier.padding(top = 12.dp))
            Btn(stringResource(R.string.try_again), { round++ }, Modifier.padding(top = 10.dp), small = true)
        }
    }

    removing?.let { d ->
        Confirm(
            stringResource(R.string.dev_remove_ask, d.name), stringResource(R.string.dev_remove_note), stringResource(R.string.remove),
            onConfirm = {
                removing = null
                scope.launch {
                    try { ctx.app.client.send(Requests.revoke(d.id)) } catch (e: Throwable) { error = e }
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
    val c = ink
    var devices by remember { mutableStateOf<List<Device>?>(null) }
    var limit by remember { mutableStateOf<Int?>(null) }
    var replace by remember { mutableStateOf<Device?>(null) }
    var code by remember { mutableStateOf<String?>(null) }
    var expiresAt by remember { mutableStateOf(0L) }
    var left by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var joined by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableStateOf(0) }
    val me = ctx.app.store.deviceId

    LaunchedEffect(Unit) {
        try {
            devices = Device.listFrom(ctx.app.client.send(Requests.devices()).data)
            limit = ctx.app.account.me(fresh = true)?.devicesLimit
        } catch (e: Throwable) {
            error = e
        }
    }
    val list = devices
    val full = list != null && limit != null && list.size >= limit!!
    // A code is asked for once it is known whose place it takes (or that there is room)
    LaunchedEffect(list, replace, round) {
        if (list == null || (full && replace == null)) return@LaunchedEffect
        try {
            val a = ctx.app.client.send(Requests.pair(replace?.id))
            code = a.str("code")
            expiresAt = a.data["expires_at"]?.jsonPrimitive?.longOrNull ?: (System.currentTimeMillis() + 300_000)
            error = null
        } catch (e: Throwable) {
            error = e
        }
    }
    // Count down, and look every few seconds for the new device: when it is there, say so and stop
    LaunchedEffect(code) {
        val before = list.orEmpty().map { it.id }.toSet()
        var tick = 0
        while (code != null && joined == null) {
            left = (expiresAt - System.currentTimeMillis()).coerceAtLeast(0)
            if (left == 0L) break
            if (tick++ % 4 == 3) {
                runCatching { Device.listFrom(ctx.app.client.send(Requests.devices()).data) }.getOrNull()
                    ?.firstOrNull { it.id !in before }?.let { joined = it.name }
            }
            delay(1000)
        }
    }

    Page(stringResource(R.string.label_add_device), onBack) {
        when {
            joined != null -> {
                Txt(stringResource(R.string.add_joined, joined!!), sans(15.sp, FontWeight.SemiBold), c.navy, Modifier.padding(vertical = 16.dp))
                Btn(stringResource(R.string.ob_done), onBack, Modifier.fillMaxWidth(), solid = true)
            }
            full && replace == null && list != null -> {
                Txt(stringResource(R.string.add_full, list.size), sans(14.sp, lineHeight = 21.sp), c.ink, Modifier.padding(bottom = 10.dp))
                val now = remember(list) { System.currentTimeMillis() }
                Panel {
                    list.filter { it.id != me }.forEachIndexed { k, d ->
                        if (k > 0) PanelDivider()
                        DeviceRow(d, self = false, now = now) { Btn(stringResource(R.string.add_replace), { replace = d }, small = true) }
                    }
                }
                // The other way out, said once: a paid plan has room for more
                if (BuildConfig.PAYMENTS) Btn(upgradeWord("devices"), { go(Screen.Plans("devices")) }, Modifier.fillMaxWidth().padding(top = 14.dp))
            }
            code != null -> {
                Panel {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Txt(stringResource(R.string.add_help), sans(12.5.sp, lineHeight = 18.5.sp), c.dim, align = TextAlign.Center)
                        Txt(code!!, mono(34.sp, FontWeight.Bold).copy(letterSpacing = .08.em), c.ink, Modifier.padding(top = 10.dp, bottom = 4.dp))
                        if (left > 0) {
                            val clock = "%d:%02d".format(left / 60_000, left / 1000 % 60)
                            Txt(stringResource(R.string.add_expires, clock), sans(12.5.sp).copy(fontFeatureSettings = "tnum"), c.dim)
                            QrCode(pairingLink(code!!), Modifier.padding(top = 12.dp))
                        } else {
                            Txt(stringResource(R.string.add_expired), sans(12.5.sp), c.red)
                            Btn(stringResource(R.string.add_new_code), { round++ }, Modifier.padding(top = 12.dp), small = true)
                        }
                        replace?.let { Txt(stringResource(R.string.add_replaces, it.name), sans(12.5.sp), c.dim, Modifier.padding(top = 10.dp)) }
                    }
                }
            }
            error == null -> Txt(stringResource(R.string.loading), sans(13.sp), c.dim, Modifier.padding(vertical = 16.dp))
        }
        error?.let {
            Returned(listOf(errorText(it)), Modifier.padding(top = 12.dp))
            Btn(stringResource(R.string.try_again), { round++ }, Modifier.padding(top = 10.dp), small = true)
        }
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
