package xyz.smart_kits.dropit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import xyz.smart_kits.dropit.shared.Plans
import xyz.smart_kits.dropit.shared.fileSize
import xyz.smart_kits.dropit.shared.mb
import xyz.smart_kits.dropit.ui.kit.Btn
import xyz.smart_kits.dropit.ui.kit.Glyph
import xyz.smart_kits.dropit.ui.kit.Icons
import xyz.smart_kits.dropit.ui.kit.LinkAction
import xyz.smart_kits.dropit.ui.kit.LoadingRows
import xyz.smart_kits.dropit.ui.kit.Meter
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.label
import xyz.smart_kits.dropit.ui.theme.sans

/**
 * What the account is on, and the one way to change it: a free account sees what it has used and a button to upgrade (when
 * paying is on offer in this build and the service says it can be bought); a paid one sees what it has. Shown in the menu and
 * at the top of the account page, so an upgrade is one tap from wherever the person is, and never in the way.
 */
@Composable
fun PlanCard(go: (Screen) -> Unit, modifier: Modifier = Modifier, onGo: () -> Unit = {}, buy: Buy = rememberBuy()) {
    val ctx = LocalContext.current
    val c = ink
    var me by remember { mutableStateOf<Me?>(null) }
    LaunchedEffect(Unit) {
        me = ctx.app.account.me(stale = true)
        me = ctx.app.account.me(fresh = true)
    }
    val m = me ?: run { LoadingRows(false, modifier, rows = 1); return }
    val cat = ctx.app.plans.catalog.value
    val year = cat?.offer("year")?.takeIf { BuildConfig.PAYMENTS && cat.canBuy }
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(c.paper).border(1.dp, c.line, shape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Txt(stringResource(if (m.paid) R.string.plan_name_paid else R.string.plan_name_free), label(20.sp), c.ink, Modifier.weight(1f))
            Txt(stringResource(R.string.card_used, fileSize(m.bytesUsed), mb(m.bytesLimit)), sans(12.sp), c.dim)
        }
        Meter(if (m.bytesLimit > 0) m.bytesUsed.toFloat() / m.bytesLimit else 0f)
        if (m.paid) {
            val line = if (m.paidKind == "life" || m.paidUntil == null) stringResource(R.string.plan_paid_life)
            else stringResource(R.string.plan_paid_until, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(m.paidUntil)))
            Txt(line, sans(13.sp, FontWeight.SemiBold), c.navy)
        } else if (year != null) {
            val trial = year.trialCents != null && m.trialAvailable != false
            Btn(
                if (trial) stringResource(R.string.card_upgrade_trial, Plans.money(year.trialCents!!, year.currency))
                else stringResource(R.string.card_upgrade, Plans.money(year.cents, year.currency)),
                { buy.start(); onGo() }, Modifier.fillMaxWidth(), solid = true, tint = c.navy, loading = buy.opening != null,
            )
            // The other ways to pay (a lifetime plan, a year paid once) are one quiet link away, not on the way to the default one
            LinkAction({ onGo(); go(Screen.Plans()) }, Modifier.align(Alignment.CenterHorizontally), text = stringResource(R.string.other_plans), color = c.dim, underline = true)
        }
    }
}

/** The menu behind the icon at the top right: the plan card, then settings, the account and about, each a full-height row */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(go: (Screen) -> Unit, buy: Buy, onDismiss: () -> Unit) {
    val c = ink
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.paper) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            PlanCard(go, onGo = onDismiss, buy = buy)
            Spacer(Modifier.padding(top = 8.dp))
            MenuRow(R.string.label_settings, R.string.menu_settings_sub) { onDismiss(); go(Screen.Settings) }
            MenuRow(R.string.label_group_account, R.string.menu_account_sub) { onDismiss(); go(Screen.Account) }
            MenuRow(R.string.label_group_about, R.string.menu_about_sub) { onDismiss(); go(Screen.About) }
        }
    }
}

@Composable
private fun MenuRow(title: Int, sub: Int, onClick: () -> Unit) {
    val c = ink
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(stringResource(title), sans(16.sp), c.ink)
        Txt(stringResource(sub), sans(12.5.sp), c.dim, Modifier.weight(1f).padding(start = 12.dp), align = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1)
        Glyph(Icons.Chevron, c.dim, Modifier.padding(start = 8.dp), size = 16.dp)
    }
}
