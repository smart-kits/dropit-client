package xyz.smart_kits.dropit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.smart_kits.dropit.BuildConfig
import xyz.smart_kits.dropit.Me
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.PlanLimits
import xyz.smart_kits.dropit.shared.fileSize
import xyz.smart_kits.dropit.shared.mb
import xyz.smart_kits.dropit.shared.Plans
import xyz.smart_kits.dropit.ui.kit.Cta
import xyz.smart_kits.dropit.ui.kit.CtaBar
import xyz.smart_kits.dropit.ui.kit.ErrorLine
import xyz.smart_kits.dropit.ui.kit.Meter
import xyz.smart_kits.dropit.ui.kit.Panel
import xyz.smart_kits.dropit.ui.kit.PanelRow
import xyz.smart_kits.dropit.ui.kit.PanelDivider
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.kit.btn
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.label
import xyz.smart_kits.dropit.ui.theme.sans
import java.text.DateFormat
import java.util.Date

/**
 * What the account uses and what each plan gives: one plain table, and — in the build from the website —
 * three ways to pay as a list to pick from, with one big button at the bottom that opens the checkout in the browser. Arrived at from a limit that was just hit
 * ([why]), the top says which one and that row stands out.
 */
@Composable
fun PlansScreen(resumed: Int, why: String?, from: String, onBack: () -> Unit) {
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
    // Which way to pay is picked (the subscription to begin with), and the button at the bottom goes to it
    var picked by remember { mutableStateOf("year") }
    val year = cat?.offer("year")
    val once = cat?.offer("year_once")
    val life = cat?.offer("life")
    val canBuy = BuildConfig.PAYMENTS && !paid && cat != null && cat.canBuy && year != null && once != null && life != null
    val trial = year?.trialCents != null && me?.trialAvailable != false

    PageWithBar(
        stringResource(if (why != null && !paid) R.string.label_upgrade else R.string.label_plan), onBack,
        bar = {
            if (canBuy) {
                val label = when (picked) {
                    "year_once" -> stringResource(R.string.plan_buy_once, Plans.money(once!!.cents, once.currency))
                    "life" -> stringResource(R.string.plan_buy_life, Plans.money(life!!.cents, life.currency))
                    else -> if (trial) stringResource(R.string.tile_year_btn_trial, Plans.money(year!!.trialCents!!, year.currency))
                    else stringResource(R.string.tile_year_btn, Plans.money(year!!.cents, year.currency))
                }
                CtaBar(note = stringResource(R.string.buy_note)) { Cta(label, { buyer.start(from, why, picked) }, red = true, loading = opening == picked) }
            }
        },
    ) {
        if (why == null || paid) {
            Txt(stringResource(R.string.plan_head), label(30.sp, tracking = .01.em, lineHeight = 34.sp), c.ink, Modifier.padding(start = 2.dp, top = 6.dp))
            Txt(stringResource(R.string.plan_tagline), sans(15.sp, lineHeight = 21.sp), c.dim, Modifier.padding(start = 2.dp, top = 2.dp, bottom = 14.dp))
        }
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
                Panel(Modifier.padding(bottom = 14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Txt(head, sans(15.sp, FontWeight.SemiBold), c.ink)
                        Txt(sub!!, sans(13.sp, lineHeight = 18.sp), c.dim, Modifier.padding(top = 2.dp))
                    }
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


        // Prices and paying only in the build from the website; a store build says what the limits are and stops
        if (BuildConfig.PAYMENTS && !paid) {
            if (cat == null && !asked) {
                // Not answered yet: what is coming has its place, instead of a line that says nothing is known
                xyz.smart_kits.dropit.ui.kit.LoadingRows(slowPlans, Modifier.padding(bottom = 14.dp), rows = 3)
            } else if (!canBuy) {
                // The service has not said what is on sale (nothing kept and it can't be reached), or says it is closed: no price tags, one line says so
                Txt(
                    stringResource(if (cat == null) R.string.buy_unknown else R.string.buy_closed),
                    sans(13.sp, lineHeight = 18.5.sp), c.dim, Modifier.padding(start = 4.dp, bottom = 14.dp),
                )
            } else {
                // The same order as the website: the subscription (its first month, when the account can still have it and the service offers one;
                // else what it saves), a year paid once, then lifetime. One is picked; the button at the bottom buys it
                val yearly = Plans.money(year!!.cents, year.currency)
                val perMonth = Plans.perMonthCents(year.cents)?.let { Plans.money(it, year.currency) }
                PlanOption(
                    picked == "year", stringResource(R.string.tile_year_name),
                    when {
                        trial && perMonth != null -> stringResource(R.string.tile_year_sub_trial, yearly, perMonth)
                        trial -> stringResource(R.string.tile_trial_note, yearly)
                        perMonth != null -> stringResource(R.string.tile_year_sub, perMonth)
                        else -> stringResource(R.string.tile_year_note)
                    },
                    yearly, stringResource(R.string.plan_per_year),
                    chip = when {
                        trial -> stringResource(R.string.plan_chip_first, Plans.money(year.trialCents!!, year.currency)) to true
                        cat!!.yearSaves != null -> stringResource(R.string.plan_chip_save, cat.yearSaves!!) to true
                        else -> null
                    },
                    modifier = Modifier.padding(bottom = 10.dp), onClick = { picked = "year" },
                )
                PlanOption(
                    picked == "year_once", stringResource(R.string.tile_once_name), stringResource(R.string.tile_once_note),
                    Plans.money(once!!.cents, once.currency), null, chip = null, modifier = Modifier.padding(bottom = 10.dp), onClick = { picked = "year_once" },
                )
                val years = Plans.lifetimeInYears(life!!.cents, year.cents)?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
                PlanOption(
                    picked == "life", stringResource(R.string.tile_life_name),
                    if (years != null) stringResource(R.string.tile_life_sub, years) else stringResource(R.string.tile_life_note),
                    Plans.money(life.cents, life.currency), null, chip = stringResource(R.string.label_best_big) to false,
                    modifier = Modifier.padding(bottom = 14.dp), onClick = { picked = "life" },
                )
            }
        }

        PlanTable(plans.free, plans.paid, mine = if (paid) 2 else 1, bold = why.takeIf { !paid })

        error?.let { ErrorLine(errorText(it), Modifier.padding(top = 12.dp)) }
    }
}

/**
 * One way to pay as a card to pick: a round mark at the left (filled when picked, and the edge turns blue), its name and what it comes
 * to, the price at the right in large type. [chip]: a small tab on its edge (red for what is cheaper right now, blue for the best value).
 */
@Composable
private fun PlanOption(
    picked: Boolean, name: String, sub: String, price: String, per: String?, chip: Pair<String, Boolean>?,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val c = ink
    val shape = RoundedCornerShape(16.dp)
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().shadow(1.dp, shape).clip(shape).background(c.paper)
                .border(2.dp, if (picked) c.navy else Color.Transparent, shape)
                .selectable(selected = picked, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape).border(if (picked) 7.dp else 2.dp, if (picked) c.navy else c.dim, androidx.compose.foundation.shape.CircleShape))
            Column(Modifier.weight(1f)) {
                Txt(name, sans(16.sp, FontWeight.Bold, lineHeight = 21.sp), c.ink)
                Txt(sub, sans(12.5.sp, lineHeight = 17.sp), c.dim, Modifier.padding(top = 2.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Txt(price, label(26.sp, tracking = .01.em), c.ink, maxLines = 1)
                if (per != null) Txt(per, sans(11.5.sp), c.dim)
            }
        }
        if (chip != null) {
            val (text, hot) = chip
            Txt(
                text, sans(11.5.sp, FontWeight.Bold, lineHeight = 14.sp), Color.White,
                Modifier.align(if (hot) Alignment.TopEnd else Alignment.TopStart).padding(horizontal = 14.dp).offset(y = (-9).dp)
                    .clip(RoundedCornerShape(7.dp)).background(if (hot) c.btn else c.navy).padding(horizontal = 9.dp, vertical = 5.dp),
                maxLines = 1,
            )
        }
    }
}

/** Free and Paid side by side, a row per limit. `mine`: which column is the account's; `bold`: the limit just hit */
@Composable
private fun PlanTable(f: PlanLimits, p: PlanLimits, mine: Int, bold: String?) {
    val c = ink
    @Composable
    fun days(n: Int) = pluralStringResource(R.plurals.plan_days, n, n)
    // The order that matters most first: what is lost when the trial ends, how long things are kept, the room, then the counts
    val rows = listOf(
        Triple("realtime", stringResource(R.string.plan_row_realtime), stringResource(R.string.plan_first_days, f.realtimeTrialDays ?: 0) to stringResource(R.string.plan_always)),
        Triple("kept", stringResource(R.string.plan_row_kept), days(f.days) to days(p.days)),
        Triple("space", stringResource(R.string.plan_row_space), Plans.space(f.spaceMB) to Plans.space(p.spaceMB)),
        Triple("file_size", stringResource(R.string.plan_row_file), "${f.fileMB} MB" to "${p.fileMB} MB"),
        Triple("devices", stringResource(R.string.plan_row_devices), f.devices.toString() to p.devices.toString()),
        Triple("per_day", stringResource(R.string.plan_row_per_day), f.perDay.toString() to p.perDay.toString()),
        Triple("downloads", stringResource(R.string.plan_row_downloads), f.downloads.toString() to p.downloads.toString()),
    )
    Panel {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(.38f))
            for (col in 1..2) {
                Box(Modifier.weight(.31f).padding(horizontal = 10.dp, vertical = 12.dp)) {
                    Txt(stringResource(if (col == 1) R.string.label_free else R.string.label_paid), sans(13.sp, FontWeight.Bold), if (col == mine) c.navy else c.dim)
                }
            }
        }
        for ((key, name, values) in rows) {
            PanelDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(.38f).padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                    Txt(name, sans(14.sp, lineHeight = 18.sp), c.ink)
                }
                for (col in 1..2) {
                    val hit = key == bold && col == 2
                    Box(Modifier.weight(.31f).padding(horizontal = 10.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                        Txt(
                            if (col == 1) values.first else values.second,
                            sans(14.sp, if (col == 2 || hit) FontWeight.Bold else FontWeight.Normal, lineHeight = 18.sp).copy(fontFeatureSettings = "tnum"),
                            if (col == 2) c.navy else c.ink,
                        )
                    }
                }
            }
        }
    }
}
