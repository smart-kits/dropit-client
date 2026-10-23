package xyz.smart_kits.dropit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import xyz.smart_kits.dropit.BuildConfig
import xyz.smart_kits.dropit.Me
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.UpgradeFrom
import xyz.smart_kits.dropit.ui.kit.Btn
import xyz.smart_kits.dropit.ui.kit.Glyph
import xyz.smart_kits.dropit.ui.kit.IconTile
import xyz.smart_kits.dropit.ui.kit.Icons
import xyz.smart_kits.dropit.ui.kit.LinkAction
import xyz.smart_kits.dropit.ui.kit.LoadingRows
import xyz.smart_kits.dropit.ui.kit.Panel
import xyz.smart_kits.dropit.ui.kit.Spinner
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.kit.btn
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.label
import xyz.smart_kits.dropit.ui.theme.sans

/**
 * What the account is on, at the top of settings: its plan in large type with one line under it (the day a paid plan ends, or what a free
 * one has), then, quietly, the one thing worth knowing about it now (the trial's days, a paid plan that is about to end or renews by itself,
 * what is left of one that ended). A free account, when paying is on offer in this build, gets an outlined blue "Upgrade" that opens the
 * plans (red is for sending); a paid plan about to end gets "Renew a year". Under it, the way to the page that compares the plans.
 */
@Composable
fun PlanCard(go: (Screen) -> Unit, modifier: Modifier = Modifier, buy: Buy = rememberBuy()) {
    val ctx = LocalContext.current
    val c = ink
    var me by remember { mutableStateOf<Me?>(null) }
    LaunchedEffect(Unit) {
        me = ctx.app.account.me(stale = true)
        me = ctx.app.account.me(fresh = true)
    }
    val m = me ?: run { LoadingRows(false, modifier, rows = 1); return }
    val plans = ctx.app.plans
    val now = remember(m) { System.currentTimeMillis() }
    LaunchedEffect(m) { ctx.app.moments.observe(m, now) }
    val canBuy = BuildConfig.PAYMENTS && plans.offering && plans.catalog.value?.canBuy == true && !m.paid
    val prices = pricesOf(plans.catalog.value, m.trialAvailable, open = BuildConfig.PAYMENTS && plans.offering)
    val note = ctx.app.moments.settingsLine(m, now)
    val canRenew = note?.kind == LineKind.P1 && BuildConfig.PAYMENTS && plans.offering && prices.once != null
    Panel(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Star, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Txt(stringResource(if (m.paid) R.string.plan_name_paid else R.string.plan_name_free), sans(20.sp, FontWeight.Bold, lineHeight = 25.sp), c.ink)
                val line = when {
                    m.paid && (m.paidKind == "life" || m.paidUntil == null) -> stringResource(R.string.plan_paid_life)
                    m.paid -> stringResource(R.string.plan_ends_on, formatDate(m.paidUntil!!))
                    else -> stringResource(R.string.plan_free_sub, m.devicesLimit, plans.free.perDay)
                }
                Txt(line, sans(13.5.sp, lineHeight = 19.sp).copy(fontFeatureSettings = "tnum"), c.dim)
                // The one quiet line: facts, no button of its own (the plan page and the buttons beside it are where to go)
                val text = when (note?.kind) {
                    LineKind.T0 -> pluralStringResource(R.plurals.mo_t0, note.days ?: 1, note.days ?: 1)
                    LineKind.P1 -> stringResource(R.string.mo_p1_line)
                    LineKind.P1B -> stringResource(if (m.canManage) R.string.mo_p1b_manage else R.string.mo_p1b)
                    LineKind.P3 -> stringResource(R.string.mo_p3, formatDate(note.until ?: 0L))
                    LineKind.P5 -> stringResource(R.string.mo_p5)
                    null -> null
                }
                // The trial's days are a way in as well: the line opens the plans (where paying is on offer), on the part about real-time delivery
                if (text != null && note?.kind == LineKind.T0 && canBuy) {
                    LinkAction(
                        { go(Screen.Plans(why = "realtime", from = UpgradeFrom.SETTINGS)) }, Modifier.offset(x = (-6).dp), text = text, color = c.navy,
                    )
                } else text?.let { Txt(it, sans(12.5.sp, lineHeight = 17.5.sp).copy(fontFeatureSettings = "tnum"), c.dim, Modifier.padding(top = 3.dp)) }
                // Manage: where a subscription is cancelled; only an account that has one to manage, and only where this build pays at all
                if (m.paid && m.canManage && BuildConfig.PAYMENTS && (note?.kind == LineKind.P1 || note?.kind == LineKind.P1B)) {
                    LinkAction(
                        { buy.manage() }, Modifier.offset(x = (-6).dp), text = stringResource(R.string.mo_manage), color = c.navy,
                        loading = buy.opening == Buy.MANAGE, enabled = buy.opening == null,
                    )
                }
            }
            // Cold ways to pay open the plans first; a renewal is one plain thing to do, so it goes straight to the checkout
            if (canBuy) Btn(stringResource(R.string.action_upgrade), { go(Screen.Plans(from = UpgradeFrom.SETTINGS)) }, tint = c.navy)
            else if (canRenew) Btn(
                stringResource(R.string.mo_renew, prices.once!!), { buy.start(UpgradeFrom.NOTICE, plan = "year_once") }, tint = c.navy,
                loading = buy.opening == "year_once",
            )
        }
        buy.error?.let { ErrorRow(it) }
        Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(1.dp).background(c.line))
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { go(Screen.Plans()) }.heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Txt(stringResource(R.string.plan_row), sans(15.sp), c.ink, Modifier.weight(1f))
            Txt(stringResource(R.string.plan_see), sans(13.5.sp), c.dim)
            Glyph(Icons.Chevron, c.dim, Modifier.padding(start = 6.dp), size = 16.dp)
        }
    }
}

/** A day as the settings card writes it: 2026-11-05 */
internal fun formatDate(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(Date(ms))

@Composable
private fun ErrorRow(e: Throwable) {
    xyz.smart_kits.dropit.ui.kit.ErrorLine(errorText(e), Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp))
}
