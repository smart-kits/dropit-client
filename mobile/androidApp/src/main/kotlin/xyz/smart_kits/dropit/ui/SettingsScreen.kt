package xyz.smart_kits.dropit.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xyz.smart_kits.dropit.BuildConfig
import xyz.smart_kits.dropit.Me
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.entry.Overlay
import xyz.smart_kits.dropit.receive.Repull
import xyz.smart_kits.dropit.receive.ReceiveStatus
import xyz.smart_kits.dropit.receive.batteryLimited
import xyz.smart_kits.dropit.shared.PRIVACY_URL
import xyz.smart_kits.dropit.shared.RepullSpan
import xyz.smart_kits.dropit.shared.SOURCE_URL
import xyz.smart_kits.dropit.shared.TileKind
import xyz.smart_kits.dropit.shared.Targets
import xyz.smart_kits.dropit.update.UpdateUi
import xyz.smart_kits.dropit.ui.kit.Btn
import xyz.smart_kits.dropit.ui.kit.Glyph
import xyz.smart_kits.dropit.ui.kit.GroupHead
import xyz.smart_kits.dropit.ui.kit.IconTile
import xyz.smart_kits.dropit.ui.kit.Icons
import xyz.smart_kits.dropit.ui.kit.Panel
import xyz.smart_kits.dropit.ui.kit.PanelDivider
import xyz.smart_kits.dropit.ui.kit.PanelRow
import xyz.smart_kits.dropit.ui.kit.Segmented
import xyz.smart_kits.dropit.ui.kit.Spinner
import xyz.smart_kits.dropit.ui.kit.TitleBar
import xyz.smart_kits.dropit.ui.kit.Toggle
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.kit.WashBtn
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.sans

/** A screen one step in: tissue ground, a title bar, and what follows scrolling under it */
@Composable
internal fun Page(title: String, onBack: () -> Unit, trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(ink.ground).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
    ) {
        TitleBar(title, onBack, trailing = trailing)
        content()
    }
}

/** A screen one step in that ends in one big button, kept at the bottom while the rest scrolls (see [xyz.smart_kits.dropit.ui.kit.CtaBar]) */
@Composable
internal fun PageWithBar(
    title: String, onBack: () -> Unit, bar: @Composable () -> Unit,
    trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(ink.ground).safeDrawingPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp)) {
            TitleBar(title, onBack, trailing = trailing)
            content()
        }
        bar()
    }
}

/** The arrow at the end of a row that goes somewhere */
@Composable
private fun Go() { Glyph(Icons.Chevron, ink.dim, size = 16.dp) }

/**
 * Settings, on one page in five groups: the account (its plan first, an upgrade one tap away), what is received, what is sent, the
 * devices, and about. Each row is a tile, what it is, and one line of how it stands; every number comes from the service.
 */
@Composable
fun SettingsScreen(resumed: Int, go: (Screen) -> Unit, onBack: () -> Unit, onSignedOut: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ink
    var me by remember { mutableStateOf<Me?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    LaunchedEffect(resumed) {
        if (me == null) me = ctx.app.account.me(stale = true)
        me = ctx.app.account.me(fresh = true)
    }
    val peers = ctx.app.peers
    val peerState by peers.state.collectAsState()
    val defaultTo by peers.defaultTo.collectAsState()
    LaunchedEffect(resumed, me?.devicesUsed) { if ((me?.devicesUsed ?: 0) > 1) peers.refresh(maxAgeMs = 300_000) }

    Box {
        Page(stringResource(R.string.label_settings), onBack) {
            // ───── account
            GroupHead(stringResource(R.string.label_group_account))
            PlanCard(go)

            // ───── receive
            GroupHead(stringResource(R.string.label_group_receiving))
            Panel {
                ReceiveRows(resumed, go)
            }

            // ───── send
            GroupHead(stringResource(R.string.set_group_send))
            Panel {
                val receivers = peers.receivers(peerState.peers)
                PanelRow(
                    stringResource(R.string.set_default_to), icon = Icons.Devices,
                    sub = targetText(defaultTo, Targets.of(defaultTo, receivers).names),
                    onClick = { go(Screen.DefaultTargets) },
                ) { Go() }
                PanelDivider()
                TileSettingRow(TileKind.SEND_CLIPBOARD, resumed)
                if (Overlay.available) {
                    PanelDivider()
                    BallRow(resumed)
                }
                PanelDivider()
                PanelRow(stringResource(R.string.set_backtap), icon = Icons.Tap, sub = stringResource(R.string.set_backtap_sub), onClick = { go(Screen.Onboarding(gesturesOnly = true)) }) { Go() }
                PanelDivider()
                var shrink by remember { mutableStateOf(ctx.app.store.shrinkImages) }
                PanelRow(stringResource(R.string.set_photo_quality), icon = Icons.Picture, sub = stringResource(R.string.set_photo_quality_sub)) {
                    Segmented(listOf(stringResource(R.string.photo_shrink), stringResource(R.string.photo_original)), if (shrink) 0 else 1, { k ->
                        shrink = k == 0
                        ctx.app.store.shrinkImages = shrink
                    })
                }
            }

            // ───── devices
            GroupHead(stringResource(R.string.label_devices))
            Panel {
                // The name is what the system calls this phone; it is only shown
                PanelRow(stringResource(R.string.set_device_name), icon = Icons.Phone) { Txt(ctx.app.account.deviceName(), sans(13.5.sp), c.dim, Modifier.padding(start = 8.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                // The system keeps the app's language from Android 13 on: one value, changed here or in system settings
                if (Build.VERSION.SDK_INT >= 33) {
                    PanelDivider()
                    PanelRow(stringResource(R.string.set_language), icon = Icons.Globe, onClick = {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
                    }) {
                        Txt(stringResource(R.string.lang_system), sans(13.5.sp), c.dim)
                        Go()
                    }
                }
                PanelDivider()
                PanelRow(
                    stringResource(R.string.label_devices), icon = Icons.Devices, sub = stringResource(R.string.set_devices_manage),
                    onClick = { go(Screen.Devices) },
                ) {
                    me?.let { Txt(stringResource(R.string.set_devices_count, it.devicesUsed, it.devicesLimit), sans(13.5.sp).copy(fontFeatureSettings = "tnum"), c.dim) }
                    Go()
                }
                PanelDivider()
                // Red only for what cannot be taken back
                PanelRow(stringResource(R.string.set_sign_out), icon = Icons.SignOut, danger = true, sub = stringResource(R.string.set_sign_out_sub), onClick = { leaving = true })
            }

            // ───── about
            GroupHead(stringResource(R.string.label_group_about))
            Panel {
                VersionRow()
                PanelDivider()
                PanelRow(stringResource(R.string.privacy), icon = Icons.Lock, onClick = { ctx.openLink(PRIVACY_URL) }) { Go() }
                PanelDivider()
                PanelRow(stringResource(R.string.set_source), icon = Icons.Code, sub = "smart-kits/dropit-client", onClick = { ctx.openLink(SOURCE_URL) }) {
                    Glyph(Icons.External, c.dim, size = 16.dp)
                }
            }
        }

        if (leaving) {
            Confirm(
                stringResource(R.string.sign_out_ask), stringResource(R.string.set_sign_out_sub), stringResource(R.string.sign_out_confirm),
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
                    Spinner(c.ink, 20.dp)
                    Txt(stringResource(R.string.signing_out), sans(15.sp), c.ink)
                }
            }
        }
    }
}

// ───────────────────────────── receive ─────────────────────────────

/** How one line says where background receiving stands. [accent]: it is on and working, so it is said in the brand blue. */
internal data class ReceiveLine(val text: String, val accent: Boolean)

@Composable
internal fun receiveLine(status: ReceiveStatus, notifyOff: Boolean): ReceiveLine {
    val ctx = LocalContext.current
    return when {
        status == ReceiveStatus.Off -> ReceiveLine(stringResource(R.string.set_receive_off), false)
        notifyOff -> ReceiveLine(stringResource(R.string.set_receive_notify), false)
        status == ReceiveStatus.Push -> ReceiveLine(stringResource(R.string.set_receive_push), true)
        status is ReceiveStatus.Connection -> when {
            status.connected -> ReceiveLine(stringResource(R.string.set_receive_connection), true)
            status.retryAtMs == null -> ReceiveLine(stringResource(R.string.set_receive_connection_wait), false)
            else -> {
                val s = ((status.retryAtMs - System.currentTimeMillis()) / 1000).coerceAtLeast(1).toInt()
                ReceiveLine(pluralStringResource(R.plurals.set_receive_connection_retry, s, s), false)
            }
        }
        status == ReceiveStatus.NoPush -> ReceiveLine(stringResource(R.string.set_receive_none), false)
        status is ReceiveStatus.Refused -> {
            val said = ctx.app.strings.render(status.why)
            // When the trial is over, say what still works
            ReceiveLine(if (status.why.key == "realtimeOver") said + stringResource(R.string.mo_stop) + stringResource(R.string.set_receive_still) else said, false)
        }
        else -> ReceiveLine(stringResource(R.string.set_receive_unknown), false)
    }
}

/** The rows of the receive group: background receiving (and the battery warning under it), text onto the clipboard, the tile, pull again */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ReceiveRows(resumed: Int, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ink
    val receiving = ctx.app.receiving
    val receive by receiving.status.collectAsState()
    // Read again each time the screen comes back: the person may have just changed these in the system's settings
    val notifyOff = remember(resumed) { !ctx.app.arrivals.allowed }
    val unrestricted = remember(resumed) { ctx.app.runsInBackground }
    if (receiving.available) {
        val line = receiveLine(receive, notifyOff)
        PanelRow(
            stringResource(R.string.set_receive), icon = Icons.Bell, sub = line.text, subColor = if (line.accent) c.navy else c.dim,
        ) {
            Toggle(receive != ReceiveStatus.Off, { on -> scope.launch { if (on) receiving.enable() else receiving.disable() } }, name = stringResource(R.string.set_receive))
        }
        // On a connection that the battery setting may cut off: said in red, and one tap leads to the setting
        if (receive.batteryLimited(unrestricted)) {
            PanelDivider()
            PanelRow(
                stringResource(R.string.set_battery_warn), icon = Icons.Bolt, danger = true, titleColor = c.red, sub = stringResource(R.string.set_battery_warn_sub),
                subColor = c.red, onClick = { ctx.app.askRunInBackground(ctx) },
            ) { Glyph(Icons.Chevron, c.dim, size = 16.dp) }
        }
        PanelDivider()
    }
    var autoClip by remember { mutableStateOf(ctx.app.store.autoClip) }
    PanelRow(stringResource(R.string.set_auto_clip), icon = Icons.Clip) {
        Toggle(autoClip, { autoClip = it; ctx.app.store.autoClip = it }, name = stringResource(R.string.set_auto_clip))
    }
    PanelDivider()
    TileSettingRow(TileKind.RECEIVE_LATEST, resumed)
    PanelDivider()
    PanelRow(stringResource(R.string.set_repull), icon = Icons.Retry, sub = stringResource(R.string.set_repull_sub), onClick = { go(Screen.Repull) }) {
        Glyph(Icons.Chevron, c.dim, size = 16.dp)
    }
}

/**
 * A tile in the pull-down panel: added (a check), can be added in one tap (the system asks), or has to be put there by hand (the system
 * will not ask, or is older than Android 13: the steps open under the row). The app cannot take a tile out; that is done in the panel.
 */
@Composable
private fun TileSettingRow(kind: TileKind, resumed: Int) {
    val ctx = LocalContext.current
    val c = ink
    var lastAsk by remember { mutableStateOf<TileAsk?>(null) }
    var steps by remember { mutableStateOf(false) }
    val row = remember(resumed, lastAsk) { ctx.tileRow(kind, lastAsk) }
    val title = stringResource(if (kind == TileKind.RECEIVE_LATEST) R.string.set_tile_latest else R.string.set_tile_send)
    PanelRow(
        title, icon = Icons.Tiles,
        sub = stringResource(
            when {
                row == TileRow.ADDED -> R.string.set_tile_added
                lastAsk == TileAsk.DECLINED -> R.string.set_tile_declined
                else -> R.string.set_tile_not_added
            },
        ),
        onClick = when (row) {
            TileRow.ADDED -> null
            TileRow.CAN_ADD -> ({ ctx.requestTile(kind) { ask -> lastAsk = ask; if (ask == TileAsk.UNAVAILABLE) steps = true } })
            TileRow.MANUAL -> ({ steps = !steps })
        },
        extra = if (steps && row != TileRow.ADDED) ({
            Txt(
                stringResource(if (kind == TileKind.RECEIVE_LATEST) R.string.tile_manual_latest else R.string.tip_tile_manual), sans(12.5.sp, lineHeight = 18.sp), c.dim,
                Modifier.padding(top = 6.dp),
            )
        }) else null,
    ) {
        when (row) {
            TileRow.ADDED -> Box(Modifier.size(width = 48.dp, height = 36.dp).clip(RoundedCornerShape(10.dp)).background(c.tile), contentAlignment = Alignment.Center) {
                Glyph(Icons.Check, c.navy, size = 20.dp, name = stringResource(R.string.set_tile_added))
            }
            TileRow.CAN_ADD -> WashBtn(stringResource(R.string.set_tile_add), { ctx.requestTile(kind) { ask -> lastAsk = ask; if (ask == TileAsk.UNAVAILABLE) steps = true } })
            TileRow.MANUAL -> Glyph(if (steps) Icons.ChevronDown else Icons.Chevron, c.dim, size = 16.dp)
        }
    }
}

/** The floating button (the build from the website only): one tap after copying sends it. Turning it on asks for the permission it needs. */
@Composable
private fun BallRow(resumed: Int) {
    val ctx = LocalContext.current
    val c = ink
    var state by remember(resumed) { mutableStateOf(Overlay.state(ctx)) }
    LaunchedEffect(resumed) { Overlay.resume(ctx); state = Overlay.state(ctx) }
    PanelRow(
        stringResource(R.string.set_ball), icon = Icons.Target,
        sub = stringResource(if (state == Overlay.State.NEEDS_PERMISSION) R.string.set_ball_permission else R.string.set_ball_sub),
        subColor = if (state == Overlay.State.NEEDS_PERMISSION) c.red else c.dim,
    ) {
        Toggle(state == Overlay.State.ON || state == Overlay.State.NEEDS_PERMISSION, { on ->
            state = Overlay.setEnabled(ctx, on)
            // Switched on without the permission to draw over other apps: the page where it is given
            if (on && state == Overlay.State.NEEDS_PERMISSION) runCatching { ctx.startActivity(Overlay.permissionIntent(ctx)) }
        }, name = stringResource(R.string.set_ball))
    }
}

// ───────────────────────────── about ─────────────────────────────

/** The version, and (in the build that updates itself) a button to check; a newer build shows its card right under */
@Composable
private fun VersionRow() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ink
    var checking by remember { mutableStateOf<Boolean?>(null) }   // null: not asked; true: asking; false: asked
    val update by ctx.app.updater.state.collectAsState()
    PanelRow(
        stringResource(R.string.set_version_n, BuildConfig.VERSION_NAME), icon = Icons.Info,
        sub = when {
            !BuildConfig.SELF_UPDATE -> null
            checking == true -> stringResource(R.string.update_checking)
            update != UpdateUi.None -> stringResource(R.string.update_ready_sub)
            checking == false -> stringResource(R.string.update_latest_sub)
            else -> stringResource(R.string.update_check_hint)
        },
    ) {
        if (BuildConfig.SELF_UPDATE) WashBtn(
            stringResource(R.string.update_check), { checking = true; scope.launch { ctx.app.updater.check(asked = true); checking = false } },
            enabled = checking != true, loading = checking == true,
        )
    }
    if (BuildConfig.SELF_UPDATE && update != UpdateUi.None) UpdateCard(update, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
}

// ───────────────────────────── pull again ─────────────────────────────

/**
 * Pull again: bring back what this phone already had. Three steps, nothing to type: everything the service still keeps, the newest 50,
 * or the last three days. What comes back goes in the list without a notice; at most one a minute.
 */
@Composable
fun RepullScreen(resumed: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ink
    var busy by remember { mutableStateOf<RepullSpan?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    var bad by remember { mutableStateOf(false) }

    fun run(span: RepullSpan) {
        if (busy != null) return
        busy = span; said = null
        scope.launch {
            val r = Repull(ctx.app).run(span)
            busy = null
            bad = r !is Repull.Result.Done
            said = when (r) {
                is Repull.Result.Done -> if (r.letters == 0) ctx.getString(R.string.repull_none) else ctx.resources.getQuantityString(R.plurals.repull_n, r.letters, r.letters)
                is Repull.Result.Wait -> ctx.getString(R.string.repull_wait, r.seconds)
                Repull.Result.Offline -> ctx.getString(R.string.pull_failed)
                Repull.Result.Failed -> ctx.getString(R.string.repull_failed)
            }
        }
    }

    Page(stringResource(R.string.set_repull), onBack) {
        Txt(stringResource(R.string.repull_intro), sans(14.sp, lineHeight = 21.sp), c.dim, Modifier.padding(start = 2.dp, top = 4.dp, bottom = 12.dp))
        Panel {
            val rows = listOf(
                Triple(RepullSpan.ALL, R.string.repull_all, R.string.repull_all_sub),
                Triple(RepullSpan.LAST_50, R.string.repull_50, R.string.repull_50_sub),
                Triple(RepullSpan.DAYS_3, R.string.repull_3d, R.string.repull_3d_sub),
            )
            rows.forEachIndexed { k, (span, title, sub) ->
                if (k > 0) PanelDivider()
                PanelRow(stringResource(title), icon = Icons.Retry, sub = stringResource(sub), onClick = if (busy == null) ({ run(span) }) else null) {
                    if (busy == span) Spinner(c.navy, 20.dp) else Glyph(Icons.Chevron, c.dim, size = 16.dp)
                }
            }
        }
        said?.let {
            Row(Modifier.padding(top = 12.dp, start = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                if (bad) Glyph(Icons.Alert, c.red, Modifier.padding(top = 1.dp), size = 15.dp) else Glyph(Icons.Check, c.ok, Modifier.padding(top = 1.dp), size = 15.dp)
                Txt(it, sans(13.sp, FontWeight.SemiBold, lineHeight = 18.sp), if (bad) c.red else c.ok)
            }
        }
        Txt(stringResource(R.string.repull_note), sans(12.5.sp, lineHeight = 18.sp), c.dim, Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp))
    }
}

/** One question before something that cannot be undone; the way out is always there */
@Composable
internal fun Confirm(title: String, text: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = ink
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.paper,
        title = { Txt(title, sans(17.sp, FontWeight.SemiBold), c.ink) },
        text = text?.let { { Txt(it, sans(14.sp, lineHeight = 21.sp), c.dim) } },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(stringResource(R.string.action_cancel), onDismiss, Modifier.weight(1f))
                Btn(confirm, onConfirm, Modifier.weight(1f), solid = true)
            }
        },
    )
}
