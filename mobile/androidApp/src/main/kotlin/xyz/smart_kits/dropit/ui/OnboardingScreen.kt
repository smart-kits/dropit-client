package xyz.smart_kits.dropit.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.PhoneFamily
import xyz.smart_kits.dropit.ui.kit.Cta
import xyz.smart_kits.dropit.ui.kit.CtaBar
import xyz.smart_kits.dropit.ui.kit.Glyph
import xyz.smart_kits.dropit.ui.kit.Icons
import xyz.smart_kits.dropit.ui.kit.Panel
import xyz.smart_kits.dropit.ui.kit.PanelDivider
import xyz.smart_kits.dropit.ui.kit.PanelRow
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.label
import xyz.smart_kits.dropit.ui.theme.sans
import xyz.smart_kits.dropit.work.Notes

private enum class Step { Intro1, Intro2, Receive, Tile, Faster }

private val STEPS = Step.entries

/**
 * The one guide, the same for everyone and shown once: what dropit is, how to send, then the settings the system will not let an app
 * change by itself, each with one button and a way on ("Not now" just goes to the next screen). A setting that is already on shows as
 * on, and its button becomes "Next". None of them needs an account. [page] starts it part-way (the settings page opens the gestures).
 */
@Composable
fun OnboardingScreen(resumed: Int, page: Int = 0, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val c = ink
    val steps = STEPS
    val pager = rememberPagerState(initialPage = page) { steps.size }
    val scope = rememberCoroutineScope()
    val at = pager.currentPage
    val step = steps[at]
    val last = at == steps.size - 1
    val next: () -> Unit = { if (!last) scope.launch { pager.animateScrollToPage(at + 1) } else onDone() }
    val family = remember { PhoneFamily.of(Build.MANUFACTURER, Build.BRAND) }
    BackHandler(enabled = at > 0) { scope.launch { pager.animateScrollToPage(at - 1) } }

    // Read again when the screen comes back from the system's pages, and after the permission question
    var asked by remember { mutableIntStateOf(0) }
    val notifyOk = remember(resumed, asked) { Notes(ctx).allowed }
    val backgroundOk = remember(resumed, asked) { ctx.app.runsInBackground }
    fun openNotifySettings() {
        runCatching {
            ctx.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName),
            )
        }
    }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        asked++
        // Said no (perhaps for good, when the system will not ask again): the system's page is the way to change it
        if (!granted) openNotifySettings()
    }
    var tileManual by remember { mutableStateOf(false) }
    var tileOn by remember(resumed) { mutableStateOf(ctx.app.store.tileAdded) }

    Column(Modifier.fillMaxSize().background(c.ground).safeDrawingPadding()) {
        Brand(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp))
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            val s = steps[page]
            // Each screen sits in the middle of the room above the button (and scrolls when it is taller than that), so there is no
            // empty half under it
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.height(230.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        when (s) {
                            Step.Intro1 -> WhatItIs()
                            Step.Intro2 -> HowToSend()
                            Step.Receive -> ItArrives()
                            Step.Tile -> SampleQuickSettings()
                            Step.Faster -> SampleShareSheet()
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    Txt(
                        stringResource(
                            when (s) {
                                Step.Intro1 -> R.string.wl_1_title; Step.Intro2 -> R.string.wl_2_title; Step.Receive -> R.string.wl_3_title
                                Step.Tile -> R.string.label_ob_tile; Step.Faster -> R.string.label_ob_faster
                            },
                        ),
                        sans(26.sp, FontWeight.Bold, lineHeight = 32.sp), c.ink, Modifier.semantics { heading() }, align = TextAlign.Center,
                    )
                    Txt(
                        stringResource(
                            when (s) {
                                Step.Intro1 -> R.string.wl_1_body; Step.Intro2 -> R.string.wl_2_body; Step.Receive -> R.string.wl_3_body
                                Step.Tile -> if (tileManual) R.string.ob_tile_manual else R.string.ob_tile_body
                                Step.Faster -> R.string.ob_faster_body
                            },
                        ),
                        sans(15.5.sp, lineHeight = 24.sp), c.dim, Modifier.padding(top = 10.dp).widthIn(max = 330.dp), align = TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    when (s) {
                        Step.Receive -> Panel(Modifier.widthIn(max = 340.dp)) {
                            PanelRow(stringResource(R.string.ob_qs_notifications), sub = stringResource(R.string.rcv_notify_sub), icon = Icons.Bell) { Allowed(notifyOk) }
                            PanelDivider()
                            PanelRow(stringResource(R.string.rcv_bg_title), sub = stringResource(R.string.rcv_bg_sub), icon = Icons.Bolt) { Allowed(backgroundOk) }
                        }
                        Step.Tile -> if (tileOn) Panel(Modifier.widthIn(max = 340.dp)) {
                            PanelRow(stringResource(R.string.ob_tile_added).removePrefix("✓ "), iconRes = R.drawable.ic_tile) { Allowed(true) }
                        }
                        Step.Faster -> Faster(family)
                        else -> {}
                    }
                }
            }
        }
        val pageName = stringResource(R.string.wl_page, at + 1, steps.size)
        Box(Modifier.fillMaxWidth().padding(bottom = 14.dp).semantics { contentDescription = pageName }, contentAlignment = Alignment.Center) {
            Dots(steps.size, at)
        }
        CtaBar {
            when (step) {
                Step.Intro1, Step.Intro2 -> Cta(stringResource(R.string.ob_next), next)
                Step.Receive -> {
                    when {
                        !notifyOk -> Cta(stringResource(R.string.ob_notify_allow), {
                            if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotifySettings()
                        })
                        !backgroundOk -> Cta(stringResource(R.string.rcv_bg_allow), { ctx.app.askRunInBackground(ctx) })
                        else -> Cta(stringResource(R.string.ob_next), next)
                    }
                    if (!notifyOk || !backgroundOk) Skip(stringResource(R.string.ob_not_now), next)
                }
                Step.Tile -> {
                    if (tileOn) {
                        Cta(stringResource(R.string.ob_next), next)
                    } else if (!tileManual) {
                        Cta(stringResource(R.string.ob_tile_add), {
                            ctx.requestTile { answer ->
                                when (answer) {
                                    TileAsk.ADDED -> { ctx.app.store.tileAdded = true; tileOn = true; next() }
                                    TileAsk.DECLINED -> next()           // said no for now: on to the next screen (the tip on the home screen and settings still offer it)
                                    TileAsk.UNAVAILABLE -> tileManual = true
                                }
                            }
                        })
                        Skip(stringResource(R.string.ob_not_now), next)
                    } else {
                        Cta(stringResource(R.string.ob_next), next)
                    }
                }
                Step.Faster -> Cta(stringResource(R.string.ob_start), next)
            }
        }
    }
}

/** The tick at the end of a row whose setting is already allowed */
@Composable
private fun Allowed(done: Boolean) {
    if (done) Glyph(Icons.Check, ink.ok, size = 22.dp, name = stringResource(R.string.rcv_done))
}

/** Where in the steps: the one now is a longer pill */
@Composable
internal fun Dots(count: Int, at: Int) {
    val c = ink
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (k in 0 until count) Box(Modifier.height(8.dp).width(if (k == at) 22.dp else 8.dp).clip(CircleShape).background(if (k == at) c.navy else c.line))
    }
}

@Composable
private fun Skip(text: String, onClick: () -> Unit) {
    Txt(
        text, sans(14.5.sp, FontWeight.SemiBold), ink.navy,
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp).padding(top = 14.dp), align = TextAlign.Center,
    )
}

/** What a notification from dropit looks like, drawn plainly: the system decides its real shape */
@Composable
internal fun SampleNotification() {
    val c = ink
    Panel(Modifier.widthIn(max = 340.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)))
                Txt(stringResource(R.string.ob_sample_app), sans(12.sp), c.dim)
            }
            Txt(stringResource(R.string.ob_sample_title), sans(15.sp, FontWeight.Bold), c.ink, Modifier.padding(top = 6.dp))
            Txt(stringResource(R.string.ob_sample_text), sans(14.5.sp, lineHeight = 20.sp), c.dim)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in listOf(R.string.copy, R.string.share)) {
                    Box(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(12.dp)).background(c.tile).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Txt(stringResource(t), sans(13.5.sp, FontWeight.Bold), c.navy)
                    }
                }
            }
        }
    }
}

/** The three battery settings the system offers, the one to pick marked: what "unrestricted" is, among its neighbours */
@Composable
private fun BatteryChoices() {
    val c = ink
    Panel(Modifier.widthIn(max = 340.dp)) {
        val rows = listOf(R.string.ob_bat_optimized to false, R.string.ob_bat_unrestricted to true, R.string.ob_bat_restricted to false)
        rows.forEachIndexed { k, (name, picked) ->
            if (k > 0) PanelDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape).border(if (picked) 7.dp else 2.dp, if (picked) c.navy else c.line, CircleShape),
                )
                Txt(stringResource(name), sans(15.5.sp, if (picked) FontWeight.Bold else FontWeight.Normal), if (picked) c.navy else c.ink)
            }
        }
    }
}

/** Quick settings with dropit's tile in it, the one to look for in blue */
@Composable
private fun SampleQuickSettings() {
    val c = ink
    @Composable
    fun round(icon: ImageVector?, text: String, mine: Boolean) {
        Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(width = 64.dp, height = 44.dp).clip(RoundedCornerShape(22.dp)).background(if (mine) c.navy else Color(0xFF3A3F48)), contentAlignment = Alignment.Center) {
                // The tile that is really there: dropit's own mark, in white like the line icons beside it
                if (icon != null) Glyph(icon, Color.White, size = 22.dp)
                else Image(painterResource(R.drawable.ic_tile), contentDescription = null, colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(26.dp))
            }
            Txt(text, sans(11.5.sp, if (mine) FontWeight.Bold else FontWeight.Normal, lineHeight = 14.sp), if (mine) c.navy else c.dim, maxLines = 2, align = TextAlign.Center)
        }
    }
    Panel(Modifier.widthIn(max = 340.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            round(Icons.Globe, stringResource(R.string.ob_wifi), false)
            round(Icons.Bell, stringResource(R.string.ob_qs_notifications), false)
            round(Icons.Bolt, stringResource(R.string.ob_qs_saver), false)
            round(null, stringResource(R.string.send_clipboard), true)
        }
    }
}

/** A share sheet with dropit in it: where to look when sharing from another app */
@Composable
private fun SampleShareSheet() {
    val c = ink
    @Composable
    fun app(name: String, mine: Boolean) {
        Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(c.seg).then(if (mine) Modifier.border(2.dp, c.navy, RoundedCornerShape(16.dp)) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                // dropit's own logo, as it shows in the real share sheet; the others are grey stand-ins
                if (mine) Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)))
            }
            Txt(name, sans(11.5.sp, if (mine) FontWeight.Bold else FontWeight.Normal, lineHeight = 14.sp), if (mine) c.navy else c.dim, maxLines = 1)
        }
    }
    Panel(Modifier.widthIn(max = 340.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            app(stringResource(R.string.ob_app_browser), false)
            app(stringResource(R.string.ob_app_messages), false)
            app("dropit", true)
            app(stringResource(R.string.ob_app_more), false)
        }
    }
}

/** The last step differs by maker: only what is known to work on that family is said, never a guess */
@Composable
private fun Faster(family: PhoneFamily) {
    val rows: List<Pair<Int, Int>> = when (family) {
        PhoneFamily.Stock -> listOf(
            R.string.ob_share_row to R.string.ob_share_row_sub,
            R.string.ob_share_pin to R.string.ob_share_pin_sub,
            R.string.ob_backtap to R.string.ob_backtap_pixel,
        )
        PhoneFamily.Samsung -> listOf(
            R.string.ob_share_row to R.string.ob_share_row_sub,
            R.string.ob_backtap_send to R.string.ob_backtap_samsung,
        )
        PhoneFamily.Xiaomi -> listOf(
            R.string.ob_share_recent to R.string.ob_share_recent_sub_mi,
            R.string.ob_backtap to R.string.ob_backtap_mi,
        )
        PhoneFamily.Oppo, PhoneFamily.Vivo -> listOf(
            R.string.ob_share_recent to R.string.ob_share_recent_sub,
            R.string.ob_after_copy to R.string.ob_after_copy_sub,
        )
    }
    Panel(Modifier.widthIn(max = 340.dp)) {
        rows.forEachIndexed { k, (title, sub) ->
            if (k > 0) PanelDivider()
            PanelRow(stringResource(title), sub = stringResource(sub))
        }
    }
}
