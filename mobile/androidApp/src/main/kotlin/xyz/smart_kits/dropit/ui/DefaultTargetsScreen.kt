package xyz.smart_kits.dropit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.Pick
import xyz.smart_kits.dropit.ui.kit.Glyph
import xyz.smart_kits.dropit.ui.kit.Icons
import xyz.smart_kits.dropit.ui.kit.Panel
import xyz.smart_kits.dropit.ui.kit.PanelDivider
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.sans

/**
 * Who a send from this phone goes to when nothing is chosen. A tick is saved the moment it is made (here, on this phone: the service is
 * not told), at least one stays ticked, and everything ticked means everyone, so a device that joins later is included; a part of them
 * means only those.
 */
@Composable
fun DefaultTargetsScreen(resumed: Int, go: (Screen) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = ink
    val peers = ctx.app.peers
    val state by peers.state.collectAsState()
    val default by peers.defaultTo.collectAsState()
    LaunchedEffect(resumed) { peers.refresh(maxAgeMs = 30_000) }
    val receivers = peers.receivers(state.peers)
    val now = remember(receivers) { System.currentTimeMillis() }
    var refused by remember { mutableStateOf(false) }
    val pick = Pick.forSending(receivers, default)

    Page(stringResource(R.string.set_default_to), onBack) {
        Txt(stringResource(R.string.dt_intro), sans(14.sp, lineHeight = 21.sp), c.dim, Modifier.padding(start = 2.dp, top = 4.dp, bottom = 10.dp))
        if (receivers.isEmpty()) {
            Panel {
                Txt(
                    stringResource(if (state.peers == null && !state.failed) R.string.loading else R.string.dt_none), sans(14.sp, lineHeight = 21.sp), c.dim,
                    Modifier.fillMaxWidth().padding(16.dp),
                )
                if (state.peers != null) {
                    PanelDivider()
                    Txt(
                        stringResource(R.string.label_add_device), sans(14.5.sp, FontWeight.SemiBold), c.navy,
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { go(Screen.AddDevice) }.heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 15.dp),
                    )
                }
            }
            return@Page
        }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(Icons.Check, c.ok, size = 16.dp)
                Txt(stringResource(R.string.dt_saved), sans(13.sp), c.ok)
            }
            Txt(
                stringResource(R.string.panel_all), sans(13.5.sp, FontWeight.SemiBold), c.navy,
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { peers.setDefault(emptyList()); refused = false }
                    .heightIn(min = 48.dp).padding(horizontal = 10.dp).padding(vertical = 14.dp),
            )
        }
        Panel {
            receivers.forEachIndexed { k, peer ->
                if (k > 0) PanelDivider()
                val sub = seenText(ctx, xyz.smart_kits.dropit.shared.seenOf(peer.lastSeenAt, now)).orEmpty()
                TargetRow(peer, peer.id in pick.ticked, Sub(sub), warn = null, modifier = Modifier.padding(horizontal = 14.dp)) {
                    val t = pick.toggled(peer.id, keepOne = true)
                    refused = t.refused
                    // Everything ticked is "everyone" (empty): a device that joins later is included
                    if (!t.refused) peers.setDefault(if (t.pick.isAll) emptyList() else t.pick.to().orEmpty())
                }
            }
        }
        if (refused) Row(Modifier.padding(top = 8.dp, start = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            Glyph(Icons.Alert, c.red, Modifier.padding(top = 1.dp), size = 15.dp)
            Txt(stringResource(R.string.panel_keep_one), sans(12.5.sp, lineHeight = 17.5.sp), c.red)
        }
        Txt(
            stringResource(if (default.isEmpty()) R.string.dt_note_all else R.string.dt_note_part), sans(13.sp, lineHeight = 19.5.sp), c.dim,
            Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp), align = TextAlign.Start,
        )
    }
}
