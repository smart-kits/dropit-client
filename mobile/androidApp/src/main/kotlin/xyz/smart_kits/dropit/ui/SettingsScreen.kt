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
import xyz.smart_kits.dropit.shared.UpgradeFrom
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import xyz.smart_kits.dropit.Me
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.entry.Overlay
import xyz.smart_kits.dropit.receive.Repull
import xyz.smart_kits.dropit.receive.ReceiveStatus
import xyz.smart_kits.dropit.receive.batteryLimited
import xyz.smart_kits.dropit.shared.PRIVACY_URL
import xyz.smart_kits.dropit.shared.REPULL_MAX_COUNT
import xyz.smart_kits.dropit.shared.REPULL_MIN_COUNT
import xyz.smart_kits.dropit.shared.REPULL_DEFAULT_COUNT
import xyz.smart_kits.dropit.shared.REPULL_DEFAULT_DAYS
import xyz.smart_kits.dropit.shared.RepullPlan
import xyz.smart_kits.dropit.shared.repullDaysLimit
import xyz.smart_kits.dropit.shared.repullDaysMovable
import xyz.smart_kits.dropit.shared.repullDaysShown
import xyz.smart_kits.dropit.shared.repullHintHasButton
import xyz.smart_kits.dropit.shared.repullKeptHintFor
import xyz.smart_kits.dropit.shared.repullTrackDays
import xyz.smart_kits.dropit.shared.repullPlanOf
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
import xyz.smart_kits.dropit.ui.kit.Cta
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
fun SettingsScreen(resumed: Int, go: (Screen) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ink
    var me by remember { mutableStateOf<Me?>(null) }
    LaunchedEffect(resumed) {
        if (me == null) me = ctx.app.account.me(stale = true)
        me = ctx.app.account.me(fresh = true)
    }

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
                TileSettingRow(TileKind.SEND_CLIPBOARD, resumed)
                if (Overlay.available) {
                    PanelDivider()
                    BallRow(resumed)
                }
                PanelDivider()
                PanelRow(stringResource(R.string.set_backtap), icon = Icons.Tap, sub = stringResource(R.string.set_backtap_sub), onClick = { go(Screen.Onboarding(page = 4)) }) { Go() }
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
            // With notifications off nothing can be heard, so the switch shows off to match the line; turning it on goes to where they are allowed
            Toggle(
                receive != ReceiveStatus.Off && !notifyOff,
                { on ->
                    if (on && notifyOff) runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                        )
                    } else scope.launch { if (on) receiving.enable() else receiving.disable() }
                },
                name = stringResource(R.string.set_receive),
            )
        }
        // The trial of real-time delivery is over: the way back is one tap from the line that says so (only where Paid can be had)
        val refused = receive as? ReceiveStatus.Refused
        if (refused?.why?.key == "realtimeOver" && BuildConfig.PAYMENTS && ctx.app.plans.offering) {
            PanelDivider()
            PanelRow(
                stringResource(R.string.mo_see_paid), icon = Icons.Star, titleColor = c.navy,
                onClick = { go(Screen.Plans(why = "realtime", from = UpgradeFrom.SETTINGS)) },
            ) { Go() }
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
    // What the guide asked for and was put off: still here, one tap away
    if (notifyOff) {
        PanelRow(
            stringResource(R.string.set_notify_title), icon = Icons.Bell, sub = stringResource(R.string.set_notify_sub),
            onClick = {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                    )
                }
            },
        ) { Go() }
        PanelDivider()
    }
    if (!unrestricted && !receive.batteryLimited(unrestricted)) {
        PanelRow(
            stringResource(R.string.set_bg_title), icon = Icons.Bolt, sub = stringResource(R.string.set_bg_sub),
            onClick = { ctx.app.askRunInBackground(ctx) },
        ) { Go() }
        PanelDivider()
    }
    var autoClip by remember { mutableStateOf(ctx.app.store.autoClip) }
    PanelRow(stringResource(R.string.set_auto_clip), iconRes = R.drawable.ic_tile) {
        Toggle(autoClip, { autoClip = it; ctx.app.store.autoClip = it }, name = stringResource(R.string.set_auto_clip))
    }
    PanelDivider()
    TileSettingRow(TileKind.RECEIVE_LATEST, resumed)
    PanelDivider()
    PanelRow(stringResource(R.string.set_repull), icon = Icons.Retry, sub = stringResource(R.string.set_repull_sub, pluralStringResource(R.plurals.repull_latest, REPULL_DEFAULT_COUNT, REPULL_DEFAULT_COUNT), pluralStringResource(R.plurals.repull_last_days, REPULL_DEFAULT_DAYS, REPULL_DEFAULT_DAYS)), onClick = { go(Screen.Repull) }) {
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
 * Pull again: bring back what this phone already had. One plan with two choices, each one tap: how many (10, 20 or 50: never more than
 * 50) and how far back (1 to 3 days, but no further than the account's content is kept: a free account has one day). It brings back the
 * newest of that many that are also not older than that. What comes back goes in the list without a notice; at most one a minute.
 */
@Composable
fun RepullScreen(resumed: Int, go: (Screen) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ink
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    var bad by remember { mutableStateOf(false) }
    // What the account is: Paid or free, and how long its content is kept as the service says it (what was kept on this phone first)
    var account by remember { mutableStateOf<xyz.smart_kits.dropit.Me?>(null) }
    LaunchedEffect(resumed) {
        account = ctx.app.account.me(stale = true)
        account = ctx.app.account.me() ?: account
    }
    val plans = ctx.app.plans
    // The days track is as long as a paid plan keeps content; only a Paid account can move along it
    val paidDays = plans.paid.days.takeIf { it > 0 }
    val daysLimit = repullDaysLimit(account?.paid == true, account?.keptDays, paidDays)
    var count by remember { mutableStateOf(REPULL_DEFAULT_COUNT) }
    var day by remember { mutableStateOf(REPULL_DEFAULT_DAYS) }
    val plan = repullPlanOf(count, day, daysLimit)
    // A free account that touches the stopped days slider is told, once, under it, for a few seconds
    var hint by remember { mutableStateOf(false) }
    LaunchedEffect(hint) { if (hint) { delay(3_000); hint = false } }

    fun run() {
        if (busy) return
        busy = true; said = null; hint = false
        scope.launch {
            val r = Repull(ctx.app).run(plan)
            busy = false
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
        Txt(
            stringResource(R.string.repull_intro, pluralStringResource(R.plurals.repull_count, plan.count, plan.count), pluralStringResource(R.plurals.repull_days, plan.days, plan.days)),
            sans(14.sp, lineHeight = 21.sp), c.dim, Modifier.padding(start = 2.dp, top = 4.dp, bottom = 12.dp),
        )
        Panel {
            val latest = pluralStringResource(R.plurals.repull_latest, plan.count, plan.count)
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(stringResource(R.string.repull_count_label), sans(13.5.sp, FontWeight.SemiBold), c.ink, Modifier.weight(1f))
                    Txt(latest, sans(14.sp, FontWeight.Bold).copy(fontFeatureSettings = "tnum"), c.navy)
                }
                Slider(
                    value = plan.count.toFloat(), onValueChange = { count = it.roundToInt(); hint = false },
                    valueRange = REPULL_MIN_COUNT.toFloat()..REPULL_MAX_COUNT.toFloat(),
                    colors = sliderColors(true),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = latest; stateDescription = latest },
                )
            }
            if (repullDaysShown(paidDays)) {
                PanelDivider()
                val track = repullTrackDays(paidDays)
                val movable = repullDaysMovable(daysLimit)
                val last = pluralStringResource(R.plurals.repull_last_days, plan.days, plan.days)
                Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(stringResource(R.string.repull_days_label), sans(13.5.sp, FontWeight.SemiBold), c.ink, Modifier.weight(1f))
                        Txt(last, sans(14.sp, FontWeight.Bold).copy(fontFeatureSettings = "tnum"), c.navy)
                    }
                    // The whole track is drawn for everyone, so a free account sees how far Paid goes. Stopped, it takes no touch of its own: a
                    // clear layer over it takes the touch instead and says why
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Slider(
                            value = plan.days.toFloat(), onValueChange = { day = it.roundToInt() },
                            valueRange = 1f..track.toFloat(), enabled = movable, colors = sliderColors(movable),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = last; stateDescription = last },
                        )
                        if (repullKeptHintFor(daysLimit, paidDays)) Box(
                            Modifier.matchParentSize().clickable(role = Role.Button, onClickLabel = stringResource(R.string.repull_see_plans)) { hint = true },
                        )
                    }
                }
            }
            if (hint) {
                PanelDivider()
                val free = pluralStringResource(R.plurals.repull_days, plans.free.days.coerceAtLeast(1), plans.free.days.coerceAtLeast(1))
                val text = if (paidDays != null) stringResource(R.string.repull_kept_hint, free, pluralStringResource(R.plurals.repull_days, paidDays, paidDays))
                else stringResource(R.string.repull_kept_hint_plain, free)
                Column(Modifier.fillMaxWidth().padding(14.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                    Txt(text, sans(13.5.sp, lineHeight = 20.sp), c.ink)
                    if (repullHintHasButton(BuildConfig.PAYMENTS, plans.offering)) Txt(
                        stringResource(R.string.repull_see_plans), sans(14.5.sp, FontWeight.SemiBold), c.navy,
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { go(Screen.Plans(why = "kept", from = UpgradeFrom.SETTINGS)) }
                            .heightIn(min = 48.dp).padding(vertical = 14.dp, horizontal = 2.dp),
                    )
                }
            }
        }
        Cta(stringResource(R.string.repull_go), ::run, Modifier.padding(top = 14.dp), icon = Icons.Retry, loading = busy)
        said?.let {
            Row(Modifier.padding(top = 12.dp, start = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                if (bad) Glyph(Icons.Alert, c.red, Modifier.padding(top = 1.dp), size = 15.dp) else Glyph(Icons.Check, c.ok, Modifier.padding(top = 1.dp), size = 15.dp)
                Txt(it, sans(13.sp, FontWeight.SemiBold, lineHeight = 18.sp), if (bad) c.red else c.ok)
            }
        }
        Txt(stringResource(R.string.repull_note), sans(12.5.sp, lineHeight = 18.sp), c.dim, Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp))
    }
}

/** The sliders' colours: the brand blue; a stopped one stays readable (a paler blue), since it shows how far Paid goes */
@Composable
private fun sliderColors(enabled: Boolean) = SliderDefaults.colors(
    thumbColor = ink.navy, activeTrackColor = ink.navy, inactiveTrackColor = ink.line,
    disabledThumbColor = ink.navy.copy(alpha = .55f), disabledActiveTrackColor = ink.navy.copy(alpha = .55f), disabledInactiveTrackColor = ink.line,
)

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
