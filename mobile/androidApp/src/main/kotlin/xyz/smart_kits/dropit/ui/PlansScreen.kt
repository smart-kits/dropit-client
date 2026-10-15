package xyz.smart_kits.dropit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xyz.smart_kits.dropit.BuildConfig
import xyz.smart_kits.dropit.Me
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.Plans
import xyz.smart_kits.dropit.shared.Requests
import xyz.smart_kits.dropit.shared.fileSize
import xyz.smart_kits.dropit.shared.mb
import xyz.smart_kits.dropit.ui.kit.Burst
import xyz.smart_kits.dropit.ui.kit.Meter
import xyz.smart_kits.dropit.ui.kit.Panel
import xyz.smart_kits.dropit.ui.kit.PanelRow
import xyz.smart_kits.dropit.ui.kit.Returned
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.label
import xyz.smart_kits.dropit.ui.theme.sans
import java.text.DateFormat
import java.util.Date

/**
 * What the account uses and what each plan gives: one plain table, and — in the build from the website —
 * three ways to pay, each opening the checkout in the browser. Arrived at from a limit that was just hit
 * ([why]), the top says which one and that row stands out.
 */
@Composable
fun PlansScreen(resumed: Int, why: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ink
    var me by remember { mutableStateOf<Me?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var opening by remember { mutableStateOf<String?>(null) }
    // Back from paying in the browser: the plan is asked for again, and the page changes by itself
    LaunchedEffect(resumed) {
        if (me == null) me = ctx.app.account.me()
        me = ctx.app.account.me(fresh = true)
    }
    val paid = me?.paid == true

    fun buy(plan: String) {
        if (opening != null) return
        opening = plan; error = null
        scope.launch {
            try {
                ctx.app.client.send(Requests.checkout(plan)).str("checkout_url")?.let(ctx::openLink)
            } catch (e: Throwable) {
                error = e
            } finally {
                opening = null
            }
        }
    }

    Page(stringResource(if (why != null && !paid) R.string.label_upgrade else R.string.label_plan), onBack) {
        if (why != null && !paid) {
            val f = Plans.free
            val p = Plans.paid
            val (head, sub) = when (why) {
                "per_day" -> stringResource(R.string.why_per_day, f.perDay) to stringResource(R.string.why_per_day_sub, p.perDay)
                "file_size" -> stringResource(R.string.why_file_size, f.fileMB) to stringResource(R.string.why_file_size_sub, p.fileMB)
                "space" -> stringResource(R.string.why_space, Plans.space(f.spaceMB)) to stringResource(R.string.why_space_sub, Plans.space(p.spaceMB))
                "queue" -> stringResource(R.string.why_queue) to stringResource(R.string.why_queue_sub, p.queue)
                "devices" -> stringResource(R.string.why_devices, f.devices) to stringResource(R.string.why_devices_sub, p.devices)
                "realtime" -> stringResource(R.string.why_realtime, f.realtimeTrialDays ?: 0) to stringResource(R.string.why_realtime_sub)
                else -> null to null
            }
            if (head != null) {
                Column(Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(3.dp)).background(c.paper).border(1.dp, c.line, RoundedCornerShape(3.dp)).padding(12.dp)) {
                    Txt(head, sans(14.5.sp, FontWeight.SemiBold), c.ink)
                    Txt(sub!!, sans(12.5.sp, lineHeight = 18.sp), c.dim, Modifier.padding(top = 2.dp))
                }
            }
        } else {
            me?.let {
                Panel(Modifier.padding(bottom = 12.dp)) {
                    PanelRow(
                        stringResource(R.string.plan_space), sub = stringResource(R.string.plan_space_used, fileSize(it.bytesUsed), mb(it.bytesLimit)),
                        extra = { Meter(if (it.bytesLimit > 0) it.bytesUsed.toFloat() / it.bytesLimit else 0f) },
                    )
                }
                if (paid) {
                    val line = if (it.paidKind == "life" || it.paidUntil == null) stringResource(R.string.plan_paid_life)
                    else stringResource(R.string.plan_paid_until, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.paidUntil)))
                    Txt(line, sans(13.sp, FontWeight.SemiBold), c.navy, Modifier.padding(bottom = 12.dp))
                }
            }
        }

        PlanTable(mine = if (paid) 2 else 1, bold = why.takeIf { !paid })

        // Prices and paying only in the build from the website; a store build says what the limits are and stops
        if (BuildConfig.PAYMENTS && !paid) {
            // One subscription tile: an account that has never had the first month gets it, everyone else the yearly price
            val trial = me?.trialAvailable != false
            BuyTile(
                if (trial) stringResource(R.string.buy_trial, Plans.price(Plans.PRICE_TRIAL)) else stringResource(R.string.buy_year, Plans.price(Plans.PRICE_YEAR)),
                if (trial) stringResource(R.string.buy_trial_sub, Plans.price(Plans.PRICE_YEAR)) else stringResource(R.string.buy_year_sub),
                solid = true, busy = opening == "year", modifier = Modifier.fillMaxWidth().padding(top = 22.dp), onClick = { buy("year") },
            ) {
                // With the first month on offer, that is the deal the sticker shouts; without it, what the subscription saves
                if (trial) Burst(stringResource(R.string.label_try_month), small = stringResource(R.string.label_try).uppercase(), size = 56.dp)
                else Burst("${Plans.yearSaves}%", small = stringResource(R.string.label_save).uppercase())
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BuyTile(
                    stringResource(R.string.buy_once, Plans.price(Plans.PRICE_YEAR_ONCE)), stringResource(R.string.buy_once_sub),
                    busy = opening == "yearOnce", modifier = Modifier.weight(1f).fillMaxHeight(), onClick = { buy("yearOnce") },
                )
                BuyTile(
                    stringResource(R.string.buy_life, Plans.price(Plans.PRICE_LIFE)), stringResource(R.string.buy_life_sub),
                    busy = opening == "life", modifier = Modifier.weight(1f).fillMaxHeight(), onClick = { buy("life") },
                ) { Burst(stringResource(R.string.label_limited).uppercase()) }
            }
            Txt(stringResource(R.string.buy_note), sans(12.5.sp, lineHeight = 18.5.sp), c.dim, Modifier.padding(top = 10.dp))
        }
        error?.let { Returned(listOf(errorText(it)), Modifier.padding(top = 12.dp)) }
    }
}

/** Free and Paid side by side, a row per limit. `mine`: which column is the account's; `bold`: the limit just hit */
@Composable
private fun PlanTable(mine: Int, bold: String?) {
    val c = ink
    val f = Plans.free
    val p = Plans.paid
    @Composable
    fun days(n: Int) = pluralStringResource(R.plurals.plan_days, n, n)
    val rows = listOf(
        Triple("per_day", stringResource(R.string.plan_row_per_day), f.perDay.toString() to p.perDay.toString()),
        Triple("file_size", stringResource(R.string.plan_row_file), "${f.fileMB} MB" to "${p.fileMB} MB"),
        Triple("space", stringResource(R.string.plan_row_space), Plans.space(f.spaceMB) to Plans.space(p.spaceMB)),
        Triple("kept", stringResource(R.string.plan_row_kept), days(f.days) to days(p.days)),
        Triple("devices", stringResource(R.string.plan_row_devices), f.devices.toString() to p.devices.toString()),
        Triple("realtime", stringResource(R.string.plan_row_realtime), stringResource(R.string.plan_first_days, f.realtimeTrialDays ?: 0) to stringResource(R.string.plan_always)),
    )
    Column(Modifier.fillMaxWidth().background(c.paper).border(1.dp, c.line)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.weight(.38f))
            for (col in 1..2) {
                Column(Modifier.weight(.31f).fillMaxHeight().background(if (col == mine) c.wash else c.paper).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Txt(stringResource(if (col == 1) R.string.label_free else R.string.label_paid).uppercase(), label(15.sp, tracking = .02.em), c.ink)
                    if (BuildConfig.PAYMENTS) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Txt(if (col == 1) "$0" else "$" + Plans.price(Plans.PRICE_YEAR), label(18.sp), c.ink)
                            if (col == 2) Txt(" " + stringResource(R.string.plan_per_year), sans(11.sp), c.dim, Modifier.padding(bottom = 2.dp))
                        }
                    }
                }
            }
        }
        for ((key, name, values) in rows) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.weight(.38f).fillMaxHeight().padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                    Txt(name, sans(13.sp, lineHeight = 18.sp), c.dim)
                }
                for (col in 1..2) {
                    val hit = key == bold && col == 2
                    Box(
                        Modifier.weight(.31f).fillMaxHeight().background(if (col == mine) c.wash else c.paper).padding(horizontal = 10.dp, vertical = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Txt(
                            if (col == 1) values.first else values.second,
                            sans(13.sp, if (hit) FontWeight.Bold else FontWeight.Normal, lineHeight = 18.sp).copy(fontFeatureSettings = "tnum"), c.ink,
                        )
                    }
                }
            }
        }
    }
}

/** A way to pay: its name with the price, and its one advantage under it; a sale sticker on the corner if it has one */
@Composable
private fun BuyTile(title: String, sub: String, onClick: () -> Unit, modifier: Modifier = Modifier, solid: Boolean = false, busy: Boolean = false, sticker: (@Composable () -> Unit)? = null) {
    val c = ink
    val shape = RoundedCornerShape(6.dp)
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight().alpha(if (busy) .6f else 1f).clip(shape).background(if (solid) c.ink else c.paper).border(1.dp, if (solid) c.ink else c.line, shape)
                .clickable(enabled = !busy, role = Role.Button, onClick = onClick).heightIn(min = 54.dp)
                .padding(start = 12.dp, end = if (sticker != null) 40.dp else 12.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            val fg = if (solid) c.paper else c.ink
            Txt(title, sans(14.5.sp, FontWeight.SemiBold, lineHeight = 19.sp).copy(fontFeatureSettings = "tnum"), fg)
            Txt(sub, sans(11.5.sp, lineHeight = 16.sp), fg.copy(alpha = .72f))
        }
        if (sticker != null) Box(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-16).dp)) { sticker() }
    }
}
