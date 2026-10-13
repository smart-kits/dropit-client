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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.ColorFilter
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
import xyz.smart_kits.dropit.ui.kit.Btn
import xyz.smart_kits.dropit.ui.kit.Panel
import xyz.smart_kits.dropit.ui.kit.PanelDivider
import xyz.smart_kits.dropit.ui.kit.PanelRow
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.label
import xyz.smart_kits.dropit.ui.theme.mono
import xyz.smart_kits.dropit.ui.theme.sans
import xyz.smart_kits.dropit.work.Notes

private enum class Step { Notify, Background, Tile, Faster }

/**
 * After joining: the settings the system will not let an app change by itself, one per screen, each with
 * one button and a way past. In the order of "leave this undone and something breaks": notifications,
 * background, quick settings, gestures. A step already done is not shown.
 */
@Composable
fun OnboardingScreen(resumed: Int, gesturesOnly: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val c = ink
    val steps = remember {
        buildList {
            if (!gesturesOnly) {
                if (!Notes(ctx).allowed) add(Step.Notify)
                if (!ctx.app.runsInBackground) add(Step.Background)
            }
            if (!ctx.app.store.tileAdded) add(Step.Tile)
            add(Step.Faster)
        }
    }
    var at by remember { mutableIntStateOf(0) }
    val next: () -> Unit = { if (at + 1 < steps.size) at++ else onDone() }
    val step = steps[at]
    val family = remember { PhoneFamily.of(Build.MANUFACTURER, Build.BRAND) }

    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { next() }
    // Back from the system's battery page: once it is set, this step is done by itself
    LaunchedEffect(resumed) { if (step == Step.Background && ctx.app.runsInBackground) next() }
    var tileManual by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.ground).safeDrawingPadding().padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 12.dp)) {
        Txt(
            if (step == Step.Faster) stringResource(R.string.ob_step_phone, at + 1, steps.size, Build.MODEL) else stringResource(R.string.ob_step, at + 1, steps.size),
            mono(12.sp), c.dim,
        )
        val title = when (step) {
            Step.Notify -> R.string.label_ob_notify
            Step.Background -> R.string.label_ob_background
            Step.Tile -> R.string.label_ob_tile
            Step.Faster -> R.string.label_ob_faster
        }
        Txt(stringResource(title), label(30.sp, tracking = (-.01).em, lineHeight = 32.sp), c.ink, Modifier.padding(top = 8.dp, bottom = 8.dp).semantics { heading() })
        when (step) {
            Step.Notify -> Body(stringResource(R.string.ob_notify_body))
            Step.Background -> Body(stringResource(R.string.ob_background_body))
            Step.Tile -> Body(stringResource(if (tileManual) R.string.ob_tile_manual else R.string.ob_tile_body))
            Step.Faster -> {}
        }
        // The picture sits in the middle of whatever room is left; on a small screen it scrolls
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = if (step == Step.Faster) Alignment.TopCenter else Alignment.Center) {
            when (step) {
                Step.Notify -> SampleNotification()
                Step.Background -> Panel(Modifier.width(260.dp)) {
                    PanelRow(stringResource(R.string.ob_battery), sub = stringResource(R.string.ob_battery_need)) {
                        Txt(stringResource(R.string.ob_battery_unset), sans(13.sp), c.red)
                    }
                }
                Step.Tile -> SampleQuickSettings()
                Step.Faster -> Column { Faster(family) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            for (k in steps.indices) Box(Modifier.size(6.dp).clip(CircleShape).background(if (k == at) c.ink else c.line))
        }
        when (step) {
            Step.Notify -> {
                Btn(stringResource(R.string.ob_notify_allow), {
                    if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) else next()
                }, Modifier.fillMaxWidth(), solid = true)
                Skip(stringResource(R.string.ob_not_now), next)
            }
            Step.Background -> {
                Btn(stringResource(R.string.ob_background_allow), { ctx.app.askRunInBackground(ctx) }, Modifier.fillMaxWidth(), solid = true)
                Skip(stringResource(R.string.ob_background_skip), next)
            }
            Step.Tile -> {
                if (!tileManual) {
                    Btn(stringResource(R.string.ob_tile_add), {
                        ctx.requestTile { answer ->
                            when (answer) {
                                TileAsk.ADDED -> { ctx.app.store.tileAdded = true; next() }
                                TileAsk.DECLINED -> {}               // said no for now: the button stays
                                TileAsk.UNAVAILABLE -> tileManual = true
                            }
                        }
                    }, Modifier.fillMaxWidth(), solid = true)
                    Skip(stringResource(R.string.ob_skip), next)
                } else {
                    Btn(stringResource(R.string.ob_next), next, Modifier.fillMaxWidth(), solid = true)
                }
            }
            Step.Faster -> Btn(stringResource(R.string.ob_done), next, Modifier.fillMaxWidth(), solid = true)
        }
    }
}

@Composable
private fun Body(text: String) {
    Txt(text, sans(14.sp, lineHeight = 21.5.sp), ink.dim, Modifier.padding(bottom = 10.dp))
}

@Composable
private fun Skip(text: String, onClick: () -> Unit) {
    Txt(text, sans(13.sp), ink.dim, Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp), align = TextAlign.Center)
}

/** What a notification from dropit looks like, drawn plainly: the system decides its real shape */
@Composable
private fun SampleNotification() {
    val c = ink
    Column(Modifier.width(260.dp).clip(RoundedCornerShape(18.dp)).background(c.paper).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 11.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)))
            Txt(stringResource(R.string.ob_sample_app), sans(11.5.sp), c.dim)
        }
        Txt(stringResource(R.string.ob_sample_title), sans(14.sp, FontWeight.SemiBold), c.ink, Modifier.padding(top = 4.dp))
        Txt(stringResource(R.string.ob_sample_text), sans(13.sp), c.ink.copy(alpha = .85f))
        Row(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Txt(stringResource(R.string.copy), sans(13.sp, FontWeight.SemiBold), c.navy)
            Txt(stringResource(R.string.share), sans(13.sp, FontWeight.SemiBold), c.navy)
        }
    }
}

/** Quick settings with dropit's tile in it, the one to look for marked in navy */
@Composable
private fun SampleQuickSettings() {
    val c = ink
    @Composable
    fun tile(text: String, mine: Boolean, modifier: Modifier) {
        Row(
            modifier.clip(RoundedCornerShape(18.dp)).background(c.paper).border(if (mine) 2.dp else 1.dp, if (mine) c.navy else c.line, RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mine) Image(painterResource(R.drawable.ic_tile), contentDescription = null, colorFilter = ColorFilter.tint(c.ink), modifier = Modifier.size(18.dp))
            Txt(text, sans(13.sp, lineHeight = 17.sp), c.ink, maxLines = 2)
        }
    }
    Column(Modifier.width(290.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tile(stringResource(R.string.send_clipboard), true, Modifier.weight(1.25f))
            tile(stringResource(R.string.ob_wifi), false, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tile(stringResource(R.string.ob_bluetooth), false, Modifier.weight(1.25f))
            Spacer(Modifier.weight(1f))
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
    Panel(Modifier.padding(top = 4.dp)) {
        rows.forEachIndexed { k, (title, sub) ->
            if (k > 0) PanelDivider()
            PanelRow(stringResource(title), sub = stringResource(sub))
        }
    }
    Spacer(Modifier.height(10.dp))
}
