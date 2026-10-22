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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import xyz.smart_kits.dropit.BuildConfig
import xyz.smart_kits.dropit.Me
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.UpgradeFrom
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

/** The pieces of one card: its words and up to two buttons (the first is the main one) */
private class CardParts(val text: String, val primary: Pair<String, () -> Unit>? = null, val primaryLoading: Boolean = false, val secondary: Pair<String, () -> Unit>? = null)

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

/** The part of the ended-trial card that lists what the account had: "During it: 14 days, 42 items sent, 3 devices receiving live." */
@Composable
private fun duringSentence(e: Enjoyed): String? {
    val parts = listOfNotNull(
        e.days?.let { stringResource(R.string.mo_t3_days, it) },
        e.items?.let { pluralStringResource(R.plurals.mo_t3_items, it, it) },
        e.devices?.let { stringResource(R.string.mo_t3_devices, it) },
    )
    if (parts.isEmpty()) return null
    return stringResource(R.string.mo_t3_during, parts.joinToString(stringResource(R.string.mo_list_sep)))
}

/**
 * The card at the top of the home screen: the trial of background receiving ending or ended, the paid plan about to end or ended, or
 * a paid plan just begun. Dismissing it (the cross, or "OK") is remembered for its period; its buttons are there only when paying is on offer in this build.
 */
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
    val ok = stringResource(R.string.mo_ok) to onDismiss
    // Keeping background receiving is what this card is about: one tap to the checkout; the plans are the other button
    val keepLabel = when {
        prices.trial != null -> stringResource(R.string.mo_keep_trial, prices.trial)
        prices.year != null -> stringResource(R.string.mo_keep_year, prices.year)
        else -> stringResource(R.string.mo_keep)
    }
    val keep: () -> Unit = { buy.start(UpgradeFrom.TRIAL_CARD, "realtime") }
    val parts: CardParts = when (card.kind) {
        CardKind.T1, CardKind.T2 -> {
            val head = if (card.kind == CardKind.T2) stringResource(R.string.mo_t2_head)
            else daysLeft((me.realtimeUntil ?: now) - now).let { n -> pluralStringResource(R.plurals.mo_t1_head, n, n) }
            val text = listOfNotNull(head, enjoyedSentence(enjoyed(me, now, days, ended = false)), pluralStringResource(R.plurals.mo_trial_tail, keptDays, keptDays)).joinToString(sep)
            CardParts(
                text,
                primary = if (payOpen) keepLabel to keep else null, primaryLoading = buy.opening == "year",
                secondary = if (payOpen) stringResource(R.string.mo_see_plans) to { go(Screen.Plans(from = UpgradeFrom.TRIAL_CARD)) } else null,
            )
        }
        CardKind.T3 -> {
            val tail = when {
                prices.trial != null && prices.year != null -> stringResource(R.string.mo_t3_price_trial, prices.trial, prices.year)
                prices.year != null -> stringResource(R.string.mo_t3_price_year, prices.year)
                else -> stringResource(R.string.mo_t3_price_none)
            }
            val text = listOfNotNull(
                stringResource(R.string.mo_t3_head),
                duringSentence(enjoyed(me, now, days, ended = true)),
                pluralStringResource(R.plurals.mo_t3_now, keptDays, keptDays) + tail,
            ).joinToString(sep)
            CardParts(
                text,
                primary = if (payOpen) stringResource(R.string.mo_keep) to keep else null, primaryLoading = buy.opening == "year",
                secondary = ok,
            )
        }
        CardKind.P1 -> CardParts(
            stringResource(R.string.mo_p1, formatDate(me.paidUntil ?: now), free.perDay, free.fileMB, free.devices, paid.days),
            primary = if (payOpen && prices.once != null) stringResource(R.string.mo_renew, prices.once) to { buy.start(UpgradeFrom.NOTICE, plan = "year_once") } else null,
            primaryLoading = buy.opening == "year_once",
            secondary = if (me.canManage && BuildConfig.PAYMENTS) stringResource(R.string.mo_manage) to { buy.manage() } else null,
        )
        CardKind.P2 -> {
            val total = me.itemsTotal?.takeIf { it > 0 }
            val text = listOfNotNull(
                stringResource(R.string.mo_p2_head),
                total?.let { pluralStringResource(R.plurals.mo_p2_items, it, it) },
                stringResource(R.string.mo_p2_now, free.perDay, free.fileMB, free.devices),
                me.paidGraceUntil?.let { stringResource(R.string.mo_p2_kept, formatDate(it)) },
            ).joinToString(sep)
            CardParts(text, primary = if (payOpen) stringResource(R.string.mo_reopen) to { go(Screen.Plans(from = UpgradeFrom.NOTICE)) } else null, secondary = ok)
        }
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
            CardParts(listOfNotNull(stringResource(R.string.mo_p4_now, paid.perDay, paid.fileMB, paid.devices, paid.days), sub).joinToString(sep), secondary = ok)
        }
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)).background(c.paper).border(1.dp, c.line, RoundedCornerShape(3.dp))
            .padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Txt(parts.text, sans(13.5.sp, lineHeight = 20.sp), c.ink, Modifier.weight(1f).padding(top = 4.dp, end = 4.dp, bottom = 4.dp))
            LinkAction(onDismiss, icon = Icons.X, name = stringResource(R.string.dismiss))
        }
        if (parts.primary != null || parts.secondary != null) {
            Row(Modifier.padding(top = 4.dp, end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                parts.primary?.let { (label, act) -> Btn(label, act, solid = true, small = true, tint = c.navy, loading = parts.primaryLoading) }
                parts.secondary?.let { (label, act) -> LinkAction(act, text = label, color = c.navy, modifier = Modifier.heightIn(min = 48.dp)) }
            }
        }
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
                val kept = plans.free.days
                Txt(pluralStringResource(R.plurals.mo_n2, kept, fileSize(v.usedBytes), mb(v.limitBytes), kept), style, c.dim)
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
