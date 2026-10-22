package xyz.smart_kits.dropit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.fileSize
import xyz.smart_kits.dropit.ui.kit.Btn
import xyz.smart_kits.dropit.ui.kit.Cta
import xyz.smart_kits.dropit.ui.kit.CtaBar
import xyz.smart_kits.dropit.ui.kit.Glyph
import xyz.smart_kits.dropit.ui.kit.Icons
import xyz.smart_kits.dropit.ui.kit.Panel
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.label
import xyz.smart_kits.dropit.ui.theme.sans
import xyz.smart_kits.dropit.update.UpdateUi
import xyz.smart_kits.dropit.update.Why
import xyz.smart_kits.dropit.update.mustUpdate
import java.util.Locale

/** What the update says to a person, and the one button that goes on with it */
@Composable
private fun UpdateBody(state: UpdateUi, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ink
    val info = when (state) {
        is UpdateUi.Available -> state.info
        is UpdateUi.Downloading -> state.info
        is UpdateUi.Ready -> state.info
        is UpdateUi.Failed -> state.info
        UpdateUi.None -> return
    }
    val notes = info.notesFor(Locale.getDefault().toLanguageTag())
    Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        Txt(stringResource(R.string.update_version, info.versionName), sans(16.sp, androidx.compose.ui.text.font.FontWeight.SemiBold), c.ink)
        if (notes != null) Txt(notes, sans(14.sp, lineHeight = 21.sp), c.dim)
        when (state) {
            is UpdateUi.Downloading -> Txt(
                if (info.size > 0) stringResource(R.string.update_downloading_of, fileSize(state.done), fileSize(info.size)) else stringResource(R.string.update_downloading),
                sans(13.sp), c.dim,
            )
            is UpdateUi.Failed -> Txt(stringResource(if (state.reason == Why.MISMATCH) R.string.update_mismatch else R.string.update_failed), sans(13.sp), c.red)
            else -> {}
        }
        when (state) {
            is UpdateUi.Available -> Btn(stringResource(R.string.update_now), { ctx.app.scope.launch { ctx.app.updater.download() } }, Modifier.fillMaxWidth(), solid = true)
            is UpdateUi.Failed -> Btn(stringResource(R.string.action_retry), { ctx.app.scope.launch { ctx.app.updater.download() } }, Modifier.fillMaxWidth(), solid = true)
            is UpdateUi.Ready -> Btn(stringResource(R.string.update_install), { ctx.app.updater.install() }, Modifier.fillMaxWidth(), solid = true)
            else -> {}
        }
    }
}

/**
 * A newer build is out. One that must be installed takes the whole screen (back and a tap outside do nothing, there is no Later): the
 * version, what changed, one button. Any other is a dialog on whatever screen is open, and Later closes it for now.
 */
@Composable
fun UpdateDialog(state: UpdateUi) {
    val ctx = LocalContext.current
    val dismissed by ctx.app.updater.dismissed.collectAsState()
    val info = when (state) {
        is UpdateUi.Available -> state.info
        is UpdateUi.Downloading -> state.info
        is UpdateUi.Ready -> state.info
        is UpdateUi.Failed -> state.info
        UpdateUi.None -> return
    }
    val must = state.mustUpdate
    if (!must && dismissed == info.versionCode) return
    if (must) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) { UpdatePage(state, info) }
        return
    }
    UpdateAsk(state, info)
}

/** The page for an update that must be installed: a large mark, the version, what changed as a short list, and one button at the bottom */
@Composable
private fun UpdatePage(state: UpdateUi, info: xyz.smart_kits.dropit.shared.UpdateInfo) {
    val ctx = LocalContext.current
    val c = ink
    val notes = info.notesFor(Locale.getDefault().toLanguageTag())
    Column(Modifier.fillMaxSize().background(c.ground).safeDrawingPadding()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, top = 40.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)).background(if (c.dark) c.tile else Color(0xFFDCE1EA)), contentAlignment = Alignment.Center) {
                Glyph(Icons.Down, c.navy, size = 48.dp)
            }
            Txt(
                stringResource(R.string.update_page_title, info.versionName), label(32.sp, tracking = .01.em, lineHeight = 36.sp), c.ink,
                Modifier.padding(top = 24.dp).semantics { heading() }, align = TextAlign.Center,
            )
            Txt(stringResource(R.string.update_must), sans(15.sp, lineHeight = 22.sp), c.dim, Modifier.padding(top = 8.dp), align = TextAlign.Center)
            val lines = notes?.lines()?.map { it.trim().trimStart('-', '•', '·', ' ') }?.filter { it.isNotEmpty() }.orEmpty()
            if (lines.isNotEmpty()) Panel(Modifier.padding(top = 26.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (line in lines) Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 1.dp).size(28.dp).clip(CircleShape).background(c.tile), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(c.navy))
                        }
                        Txt(line, sans(15.sp, lineHeight = 21.sp), c.ink, Modifier.weight(1f))
                    }
                }
            }
            when (state) {
                is UpdateUi.Downloading -> Txt(
                    if (info.size > 0) stringResource(R.string.update_downloading_of, fileSize(state.done), fileSize(info.size)) else stringResource(R.string.update_downloading),
                    sans(13.sp), c.dim, Modifier.padding(top = 16.dp),
                )
                is UpdateUi.Failed -> Txt(stringResource(if (state.reason == Why.MISMATCH) R.string.update_mismatch else R.string.update_failed), sans(13.sp, lineHeight = 19.sp), c.red, Modifier.padding(top = 16.dp), align = TextAlign.Center)
                else -> {}
            }
        }
        CtaBar(note = stringResource(R.string.update_note)) {
            when (state) {
                is UpdateUi.Available -> Cta(stringResource(R.string.update_now), { ctx.app.scope.launch { ctx.app.updater.download() } })
                is UpdateUi.Failed -> Cta(stringResource(R.string.action_retry), { ctx.app.scope.launch { ctx.app.updater.download() } })
                is UpdateUi.Ready -> Cta(stringResource(R.string.update_install), { ctx.app.updater.install() })
                is UpdateUi.Downloading -> Cta(stringResource(R.string.update_downloading), {}, enabled = false, loading = true)
                UpdateUi.None -> {}
            }
        }
    }
}

/** An update that may wait: a dialog with the version and what changed, and Later */
@Composable
private fun UpdateAsk(state: UpdateUi, info: xyz.smart_kits.dropit.shared.UpdateInfo) {
    val ctx = LocalContext.current
    val c = ink
    val notes = info.notesFor(Locale.getDefault().toLanguageTag())
    AlertDialog(
        onDismissRequest = { ctx.app.updater.dismiss(info.versionCode) },
        shape = RoundedCornerShape(20.dp),
        containerColor = c.paper,
        title = { Txt(stringResource(R.string.update_version, info.versionName), sans(17.sp, androidx.compose.ui.text.font.FontWeight.SemiBold), c.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (notes != null) Txt(notes, sans(14.sp, lineHeight = 21.sp), c.dim)
                when (state) {
                    is UpdateUi.Downloading -> Txt(
                        if (info.size > 0) stringResource(R.string.update_downloading_of, fileSize(state.done), fileSize(info.size)) else stringResource(R.string.update_downloading),
                        sans(13.sp), c.dim,
                    )
                    is UpdateUi.Failed -> Txt(stringResource(if (state.reason == Why.MISMATCH) R.string.update_mismatch else R.string.update_failed), sans(13.sp), c.red)
                    else -> {}
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(stringResource(R.string.update_later), { ctx.app.updater.dismiss(info.versionCode) }, Modifier.weight(1f))
                when (state) {
                    is UpdateUi.Available -> Btn(stringResource(R.string.update_now), { ctx.app.scope.launch { ctx.app.updater.download() } }, Modifier.weight(1f), solid = true)
                    is UpdateUi.Failed -> Btn(stringResource(R.string.action_retry), { ctx.app.scope.launch { ctx.app.updater.download() } }, Modifier.weight(1f), solid = true)
                    is UpdateUi.Ready -> Btn(stringResource(R.string.update_install), { ctx.app.updater.install() }, Modifier.weight(1f), solid = true)
                    is UpdateUi.Downloading -> Btn(stringResource(R.string.update_downloading), {}, Modifier.weight(1f), solid = true, enabled = false)
                    UpdateUi.None -> {}
                }
            }
        },
    )
}

/** A newer build is out: a quiet card under the version row, with the way to get it */
@Composable
fun UpdateCard(state: UpdateUi, modifier: Modifier = Modifier) {
    if (state == UpdateUi.None) return
    androidx.compose.foundation.layout.Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ink.ground).padding(12.dp),
    ) { UpdateBody(state) }
}
