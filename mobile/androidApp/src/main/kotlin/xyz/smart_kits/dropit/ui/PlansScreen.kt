package xyz.smart_kits.dropit.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import xyz.smart_kits.dropit.ui.kit.Btn
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
import xyz.smart_kits.dropit.shared.Offer
import xyz.smart_kits.dropit.shared.PlanLimits
import xyz.smart_kits.dropit.shared.Plans
import xyz.smart_kits.dropit.shared.Requests
import xyz.smart_kits.dropit.shared.fileSize
import xyz.smart_kits.dropit.shared.mb
import xyz.smart_kits.dropit.ui.kit.Burst
import xyz.smart_kits.dropit.ui.kit.Meter
import xyz.smart_kits.dropit.ui.kit.Panel
import xyz.smart_kits.dropit.ui.kit.PanelRow
import xyz.smart_kits.dropit.ui.kit.ErrorLine
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
    val buyer = rememberBuy()
    val error = buyer.error
    val opening = buyer.opening
    // What is on sale and for how much comes from the service; reading it here redraws this screen when it changes
    val plans = ctx.app.plans
    val cat = plans.catalog.value
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(resumed) { plans.refresh(); asked = true }
    val slowPlans = xyz.smart_kits.dropit.ui.kit.rememberSlow(cat == null && !asked)
    // Back from paying in the browser: the plan is asked for again, and the page changes by itself
    LaunchedEffect(resumed) {
        if (me == null) me = ctx.app.account.me(stale = true)
        me = ctx.app.account.me(fresh = true)
    }
    val paid = me?.paid == true

    fun buy(plan: String) = buyer.start(plan)

    Page(stringResource(if (why != null && !paid) R.string.label_upgrade else R.string.label_plan), onBack) {
        if (why != null && !paid) {
            val f = plans.free
            val p = plans.paid
            val (head, sub) = when (why) {
                "per_day" -> stringResource(R.string.why_per_day, f.perDay) to stringResource(R.string.why_per_day_sub, p.perDay)
                "file_size" -> stringResource(R.string.why_file_size, f.fileMB) to stringResource(R.string.why_file_size_sub, p.fileMB)
                "space" -> stringResource(R.string.why_space, Plans.space(f.spaceMB)) to stringResource(R.string.why_space_sub, Plans.space(p.spaceMB))
                "queue" -> stringResource(R.string.why_queue) to stringResource(R.string.why_queue_sub, p.queue)
                "devices" -> stringResource(R.string.why_devices, f.devices) to stringResource(R.string.why_devices_sub, p.devices)
                "realtime" -> stringResource(R.string.why_realtime, f.realtimeTrialDays ?: 0) to stringResource(R.string.why_realtime_sub)
                "uploads" -> stringResource(R.string.why_uploads) to stringResource(R.string.why_uploads_sub)
                "downloads" -> stringResource(R.string.why_downloads, f.downloads) to stringResource(R.string.why_downloads_sub, p.downloads)
                "kept" -> stringResource(R.string.why_kept) to stringResource(R.string.why_kept_sub, f.days, p.days)
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

        PlanTable(plans.free, plans.paid, cat?.offer("year")?.takeIf { cat.canBuy }, mine = if (paid) 2 else 1, bold = why.takeIf { !paid })

        // Prices and paying only in the build from the website; a store build says what the limits are and stops
        if (BuildConfig.PAYMENTS && !paid) {
            val year = cat?.offer("year")
            val once = cat?.offer("year_once")
            val life = cat?.offer("life")
            if (cat == null && !asked) {
                // Not answered yet: what is coming has its place, instead of a line that says nothing is known
                xyz.smart_kits.dropit.ui.kit.LoadingRows(slowPlans, Modifier.padding(top = 22.dp), rows = 3)
            } else if (cat == null || !cat.canBuy || year == null || once == null || life == null) {
                // The service has not said what is on sale (nothing kept and it can't be reached), or says it is closed: no price tags, one line says so
                Txt(
                    stringResource(if (cat == null) R.string.buy_unknown else R.string.buy_closed),
                    sans(12.5.sp, lineHeight = 18.5.sp), c.dim, Modifier.padding(top = 22.dp),
                )
            } else {
                // The same order and the same look as the website (DESIGN.md, "Plans and the sale sticker"): the two it recommends first,
                // each with a heavier blue edge and a sale sticker — the subscription (its first month, when the account can still have it
                // and the service offers one; else what it saves) and lifetime — then, quietly, a year paid once. Every card ends in its own button.
                val trial = year.trialCents != null && me?.trialAvailable != false
                val yearly = Plans.money(year.cents, year.currency)
                val perMonth = Plans.perMonthCents(year.cents)?.let { Plans.money(it, year.currency) }
                BuyCard(
                    stringResource(R.string.tile_year_name),
                    if (trial) Plans.money(year.trialCents!!, year.currency) else yearly,
                    stringResource(if (trial) R.string.tile_per_first_month else R.string.plan_per_year),
                    when {
                        trial && perMonth != null -> stringResource(R.string.tile_year_sub_trial, yearly, perMonth)
                        trial -> stringResource(R.string.tile_trial_note, yearly)
                        perMonth != null -> stringResource(R.string.tile_year_sub, perMonth)
                        else -> stringResource(R.string.tile_year_note)
                    },
                    if (trial) stringResource(R.string.tile_year_btn_trial, Plans.money(year.trialCents!!, year.currency)) else stringResource(R.string.tile_year_btn, yearly),
                    hero = true, busy = opening == "year", modifier = Modifier.fillMaxWidth().padding(top = 22.dp), onClick = { buy("year") },
                ) {
                    if (trial) Burst(stringResource(R.string.label_try_month), small = stringResource(R.string.label_try).uppercase(), size = 56.dp)
                    else cat.yearSaves?.let { Burst("$it%", small = stringResource(R.string.label_save).uppercase()) }
                }
                val years = Plans.lifetimeInYears(life.cents, year.cents)?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
                BuyCard(
                    stringResource(R.string.tile_life_name), Plans.money(life.cents, life.currency), null,
                    if (years != null) stringResource(R.string.tile_life_sub, years) else stringResource(R.string.tile_life_note),
                    stringResource(R.string.tile_life_btn),
                    hero = false, busy = opening == "life", modifier = Modifier.fillMaxWidth().padding(top = 22.dp), onClick = { buy("life") },
                ) { Burst(stringResource(R.string.label_best_big), small = stringResource(R.string.label_best_small), size = 56.dp) }
                BuyRow(
                    stringResource(R.string.tile_once_name), Plans.money(once.cents, once.currency), stringResource(R.string.tile_once_note),
                    stringResource(R.string.tile_once_btn), busy = opening == "year_once", modifier = Modifier.fillMaxWidth().padding(top = 12.dp), onClick = { buy("year_once") },
                )
                Txt(stringResource(R.string.buy_note), sans(12.5.sp, lineHeight = 18.5.sp), c.dim, Modifier.padding(top = 10.dp))
            }
        }
        error?.let { ErrorLine(errorText(it), Modifier.padding(top = 12.dp)) }
    }
}

/** Free and Paid side by side, a row per limit. `mine`: which column is the account's; `bold`: the limit just hit */
@Composable
private fun PlanTable(f: PlanLimits, p: PlanLimits, yearOffer: Offer?, mine: Int, bold: String?) {
    val c = ink
    @Composable
    fun days(n: Int) = pluralStringResource(R.plurals.plan_days, n, n)
    val rows = listOf(
        Triple("per_day", stringResource(R.string.plan_row_per_day), f.perDay.toString() to p.perDay.toString()),
        Triple("downloads", stringResource(R.string.plan_row_downloads), f.downloads.toString() to p.downloads.toString()),
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
                    if (BuildConfig.PAYMENTS && yearOffer != null) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Txt(if (col == 1) Plans.money(0, yearOffer.currency).removeSuffix(".00") else Plans.money(yearOffer.cents, yearOffer.currency), label(18.sp), c.ink)
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

/**
 * One of the two ways to pay that are recommended: its name, the price large in the label face with what it is for beside it,
 * one line on what it comes to, and its own button. A heavier blue edge (the subscription, [hero], also a tinted ground),
 * and a sale sticker on the corner.
 */
@Composable
private fun BuyCard(
    name: String, price: String, per: String?, sub: String, button: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    hero: Boolean = false, busy: Boolean = false, sticker: (@Composable () -> Unit)? = null,
) {
    val c = ink
    val shape = RoundedCornerShape(8.dp)
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().alpha(if (busy) .6f else 1f).clip(shape).background(c.paper).background(if (hero) c.wash else Color.Transparent)
                .border(2.dp, c.navy, shape).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        ) {
            Column(Modifier.padding(end = 52.dp)) {
                Txt(name, sans(13.sp, FontWeight.SemiBold, lineHeight = 18.sp), c.dim)
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                    Txt(price, label(34.sp), c.ink)
                    if (per != null) Txt(" $per", sans(14.sp), c.dim, Modifier.padding(start = 4.dp, bottom = 5.dp))
                }
            }
            Txt(sub, sans(13.sp, lineHeight = 18.5.sp), c.dim, Modifier.padding(top = 6.dp))
            Btn(button, onClick, Modifier.fillMaxWidth().padding(top = 12.dp), solid = hero, loading = busy, tint = c.navy)
        }
        if (sticker != null) Box(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-18).dp)) { sticker() }
    }
}

/** The way to pay that is not recommended, kept quiet: one row, the price and what it is, and a small button */
@Composable
private fun BuyRow(name: String, price: String, note: String, button: String, onClick: () -> Unit, modifier: Modifier = Modifier, busy: Boolean = false) {
    val c = ink
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.alpha(if (busy) .6f else 1f).clip(shape).background(c.paper).border(1.dp, c.line, shape)
            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Txt(price, label(24.sp), c.ink)
                Txt(" $name", sans(13.sp, FontWeight.SemiBold), c.dim, Modifier.padding(start = 6.dp, bottom = 3.dp))
            }
            Txt(note, sans(12.5.sp, lineHeight = 17.sp), c.dim)
        }
        Btn(button, onClick, small = true, enabled = !busy, tint = c.navy)
    }
}
