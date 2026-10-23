package xyz.smart_kits.dropit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import xyz.smart_kits.dropit.shared.PlanLimits
import xyz.smart_kits.dropit.shared.UpgradeFrom
import xyz.smart_kits.dropit.shared.Plans
import xyz.smart_kits.dropit.shared.fileSize
import xyz.smart_kits.dropit.shared.mb
import xyz.smart_kits.dropit.ui.kit.Btn
import xyz.smart_kits.dropit.ui.kit.Icons
import xyz.smart_kits.dropit.ui.kit.LinkAction
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.sans

// What the notes about the plan look like (what they say and when is decided in Moments.kt). They are plain text in a plain card: no
// alert role, no colour of warning, nothing that moves. The words follow one order: what the account has already had, what changes,
// what Paid keeps. Where a number or a price is not known, the sentence that needs it is left out, not guessed.

/** The pieces of one card: a title, a sentence or two, optionally a line of ticked phrases (what Paid keeps), up to two buttons (the first is the main one) and a line of small print */
private class CardParts(val title: String?, val text: String, val perks: List<String>? = null, val primary: Pair<String, () -> Unit>? = null, val primaryLoading: Boolean = false, val secondary: Pair<String, () -> Unit>? = null, val fine: String? = null)

/** "Days, items, devices" for the trial's last days: the parts that are known, in the sentence that fits them; null when there is nothing to say */
@Composable
private fun enjoyedSentence(e: Enjoyed): String? {
    val d = e.days; val i = e.items; val v = e.devices
    return when {
        d != null && i != null && v != null -> pluralStringResource(R.plurals.mo_enjoy_a, i, d, i, v)
        d != null && i != null -> pluralStringResource(R.plurals.mo_enjoy_b, i, d, i)
        d != null && v != null -> stringResource(R.string.mo_enjoy_c, d, v)
        i != null && v != null -> pluralStringResource(R.plurals.mo_enjoy_d, i, i, v)
        i != null -> pluralStringResource(R.plurals.mo_enjoy_e, i, i)
        v != null -> stringResource(R.string.mo_enjoy_f, v)
        else -> null
    }
}

/**
 * What Paid keeps, as short ticked phrases side by side: real-time delivery first, then only the numbers the service has given
 * (null when its plan list was never answered), so nothing here is a number the app made up.
 */
@Composable
private fun perksLine(paid: PlanLimits): List<String> {
    val parts = listOfNotNull(
        stringResource(R.string.mo_perk_realtime),
        paid.perDay.takeIf { it > 0 }?.let { stringResource(R.string.mo_perk_per_day, it) },
        paid.fileMB.takeIf { it > 0 }?.let { stringResource(R.string.mo_perk_file, it) },
        paid.devices.takeIf { it > 0 }?.let { stringResource(R.string.mo_perk_devices, it) },
        paid.days.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.mo_perk_days, it, it) },
    )
    val tick = stringResource(R.string.mo_perk)
    return parts.map { String.format(tick, it) }
}

/**
 * The card at the top of the home screen: the trial of real-time delivery ending or ended, the paid plan about to end or ended, or
 * a paid plan just begun. Dismissing it (the cross, or "OK") is remembered for its period; its buttons are there only when paying is on offer in this build.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun HomeMomentCard(card: Card, me: Me, now: Long, onDismiss: () -> Unit, go: (Screen) -> Unit, buy: Buy, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = ink
    val plans = ctx.app.plans
    val payOpen = BuildConfig.PAYMENTS && plans.offering
    val prices = pricesOf(plans.catalog.value, me.trialAvailable, payOpen)
    val free = plans.free
    val paid = plans.paid
    val sep = stringResource(R.string.mo_sep)
    val keptDays = me.keptDays ?: free.days
    val days = trialDays(me, plans.catalog.value?.free?.realtimeTrialDays)
    // Keeping real-time delivery is what the trial's cards are about. They are not a limit that was just met, so the button opens the plans
    // (all three ways to pay laid out, the price on the button is the one the plans page will show); the checkout is one more press there
    val keepLabel = when {
        prices.trial != null -> stringResource(R.string.mo_keep_trial, prices.trial)
        prices.year != null -> stringResource(R.string.mo_keep_year, prices.year)
        else -> stringResource(R.string.mo_keep)
    }
    val fine = when {
        prices.trial != null && prices.year != null -> stringResource(R.string.mo_fine_after, prices.year)
        prices.year != null -> stringResource(R.string.mo_fine_cancel)
        else -> null
    }
    val keep: () -> Unit = { go(Screen.Plans(why = "realtime", from = UpgradeFrom.TRIAL_CARD)) }
    // The fuller wording for an account with a good deal behind it, the short one otherwise; what Paid keeps only where Paid can be had
    val rich = richTrialCopy(me)
    val known = plans.catalog.value != null
    val perks = if (rich && payOpen && known) perksLine(paid) else null
    val parts: CardParts = when (card.kind) {
        CardKind.T1, CardKind.T2 -> {
            val head = if (card.kind == CardKind.T2) stringResource(R.string.mo_t2_head)
            else daysLeft((me.realtimeUntil ?: now) - now).let { n -> pluralStringResource(R.plurals.mo_t1_head, n, n) }
            // Inside the trial the account's age is within its days, so "in D days" is true
            val had = if (rich) enjoyedSentence(enjoyed(me, now, days, ended = false)) else null
            val tail = if (rich) stringResource(R.string.mo_trial_tail_rich) else pluralStringResource(R.plurals.mo_trial_tail, keptDays, keptDays)
            CardParts(head, listOfNotNull(had, tail).joinToString(sep), perks, primary = if (payOpen) keepLabel to keep else null, fine = if (payOpen) fine else null)
        }
        CardKind.T3 -> {
            // The trial is over, so the account is older than its days: what it has had is "so far", not "in D days"
            val had = if (rich) enjoyedSentence(enjoyed(me, now, days, ended = true).copy(days = null)) else null
            val tail = if (rich) stringResource(R.string.mo_t3_now_rich) else pluralStringResource(R.plurals.mo_t3_now, keptDays, keptDays)
            CardParts(stringResource(R.string.mo_t3_head), listOfNotNull(had, tail).joinToString(sep), perks, primary = if (payOpen) keepLabel to keep else null, fine = if (payOpen) fine else null)
        }
        CardKind.P1 -> {
            val had = if (rich) enjoyedSentence(enjoyed(me, now, days, ended = false).copy(days = null)) else null
            CardParts(
                stringResource(R.string.mo_p1_head, formatDate(me.paidUntil ?: now)),
                listOfNotNull(had, stringResource(R.string.mo_p1_tail)).joinToString(sep),
                if (known) perksLine(paid) else null,
                primary = if (payOpen && prices.once != null) stringResource(R.string.mo_renew, prices.once) to { buy.start(UpgradeFrom.NOTICE, plan = "year_once") } else null,
                primaryLoading = buy.opening == "year_once",
                secondary = if (me.canManage && BuildConfig.PAYMENTS) stringResource(R.string.mo_manage) to { buy.manage() } else null,
            )
        }
        CardKind.P2 -> CardParts(
            stringResource(R.string.mo_p2_head), stringResource(R.string.mo_p2_body), if (payOpen && known) perksLine(paid) else null,
            primary = if (payOpen) stringResource(R.string.mo_reopen) to { go(Screen.Plans(from = UpgradeFrom.NOTICE)) } else null,
            fine = me.paidGraceUntil?.let { stringResource(R.string.mo_p2_kept, formatDate(it)) },
        )
        CardKind.P4 -> {
            val sub = when {
                me.paidKind == "life" -> stringResource(R.string.mo_p4_life)
                me.renews == true -> when {
                    ctx.app.moments.firstMonthWasOffered() && prices.trialListed != null && prices.year != null -> stringResource(R.string.mo_p4_sub_trial, prices.trialListed, prices.year)
                    prices.year != null -> stringResource(R.string.mo_p4_sub, prices.year)
                    else -> stringResource(R.string.mo_p4_sub_plain)
                }
                me.paidUntil != null -> stringResource(R.string.mo_p4_once, formatDate(me.paidUntil))
                else -> null
            }
            CardParts(null, listOfNotNull(stringResource(R.string.mo_p4_now, paid.perDay, paid.fileMB, paid.devices, paid.days), sub).joinToString(sep))
        }
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(c.paper).border(1.dp, c.line, RoundedCornerShape(3.dp))
            .padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 4.dp, end = 4.dp, bottom = 4.dp)) {
                parts.title?.let { Txt(it, sans(14.5.sp, FontWeight.SemiBold, lineHeight = 20.sp), c.ink, Modifier.padding(bottom = 2.dp)) }
                Txt(parts.text, sans(13.5.sp, lineHeight = 20.sp), c.ink)
                // Each ticked phrase wraps as a whole: "10 devices" is never split across two lines
                parts.perks?.let { list ->
                    androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (x in list) Txt(x, sans(12.5.sp, FontWeight.SemiBold, lineHeight = 18.sp), c.navy)
                    }
                }
            }
            LinkAction(onDismiss, icon = Icons.X, name = stringResource(R.string.dismiss))
        }
        if (parts.primary != null || parts.secondary != null) {
            Row(Modifier.padding(top = 4.dp, end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                parts.primary?.let { (label, act) -> Btn(label, act, solid = true, small = true, tint = c.navy, loading = parts.primaryLoading) }
                parts.secondary?.let { (label, act) -> LinkAction(act, text = label, color = c.navy, modifier = Modifier.heightIn(min = 48.dp)) }
            }
        }
        parts.fine?.let { Txt(it, sans(12.sp, lineHeight = 17.sp), c.dim, Modifier.padding(bottom = 6.dp, end = 8.dp)) }
    }
}

/**
 * Beside the result of a send: the day's items or the space are close. One or two lines of small type; the sentence that says what Paid
 * gives is the way to the plans when paying is on offer, and plain words otherwise.
 */
@Composable
internal fun NearNote(v: NearView, now: Long, go: (Screen) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = ink
    val plans = ctx.app.plans
    val style = sans(12.sp, lineHeight = 17.sp).copy(fontFeatureSettings = "tnum")
    Column(modifier) {
        when (v.near.kind) {
            NearKind.ITEMS, NearKind.ITEMS_WARN -> {
                val back = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(nextUtcMidnight(now)))
                Txt(stringResource(R.string.mo_n1, v.today, v.perDay, back), style, c.dim)
                if (v.near.kind == NearKind.ITEMS_WARN) {
                    val paidText = stringResource(R.string.mo_n1_paid, plans.paid.perDay)
                    if (BuildConfig.PAYMENTS && plans.offering) {
                        Txt(
                            paidText, style, c.navy,
                            Modifier.clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button) { go(Screen.Plans(from = UpgradeFrom.NOTICE)) }.heightIn(min = 32.dp).padding(vertical = 7.dp),
                        )
                    } else Txt(paidText, style, c.dim)
                }
            }
            NearKind.SPACE -> {
                Txt(stringResource(R.string.mo_n2, fileSize(v.usedBytes), mb(v.limitBytes)), style, c.dim)
                // The way to the plans from a space that is nearly full: only where Paid can be had (no price here, the plans page has them)
                if (BuildConfig.PAYMENTS && plans.offering) {
                    Txt(
                        stringResource(R.string.mo_n2_paid, Plans.space(plans.paid.spaceMB)), style, c.navy,
                        Modifier.clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button) { go(Screen.Plans(from = UpgradeFrom.NOTICE)) }.heightIn(min = 32.dp).padding(vertical = 7.dp),
                    )
                }
            }
        }
    }
}

/** The one-time explanation under the first file that expired: what a closed phone costs on Free, said as a fact */
@Composable
internal fun KeptNote(modifier: Modifier = Modifier) {
    val plans = LocalContext.current.app.plans
    val days = plans.free.days
    Txt(pluralStringResource(R.plurals.mo_w6, days, days, plans.paid.days), sans(12.sp, lineHeight = 17.sp), ink.dim, modifier)
}
