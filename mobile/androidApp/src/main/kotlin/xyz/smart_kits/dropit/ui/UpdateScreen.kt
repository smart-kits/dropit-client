package xyz.smart_kits.dropit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.fileSize
import xyz.smart_kits.dropit.ui.kit.Btn
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.theme.ink
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
 * A newer build is out: a dialog on whatever screen is open, with the version and what changed. When the update is
 * required it cannot be closed (back and a tap outside do nothing, there is no Later); otherwise Later closes it for now.
 */
@Composable
fun UpdateDialog(state: UpdateUi) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dismissed by ctx.app.updater.dismissed.collectAsState()
    val c = ink
    val info = when (state) {
        is UpdateUi.Available -> state.info
        is UpdateUi.Downloading -> state.info
        is UpdateUi.Ready -> state.info
        is UpdateUi.Failed -> state.info
        UpdateUi.None -> return
    }
    val must = state.mustUpdate
    if (!must && dismissed == info.versionCode) return
    val notes = info.notesFor(Locale.getDefault().toLanguageTag())
    AlertDialog(
        onDismissRequest = { if (!must) ctx.app.updater.dismiss(info.versionCode) },
        properties = DialogProperties(dismissOnBackPress = !must, dismissOnClickOutside = !must),
        containerColor = c.paper,
        title = { Txt(stringResource(R.string.update_version, info.versionName), sans(17.sp, androidx.compose.ui.text.font.FontWeight.SemiBold), c.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                if (must) Txt(stringResource(R.string.update_must), sans(14.sp, androidx.compose.ui.text.font.FontWeight.SemiBold, lineHeight = 21.sp), c.red)
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
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                if (!must) Btn(stringResource(R.string.update_later), { ctx.app.updater.dismiss(info.versionCode) }, Modifier.weight(1f))
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

/** A newer build is out: a quiet card under the header, with the way to get it */
@Composable
fun UpdateCard(state: UpdateUi, modifier: Modifier = Modifier) {
    if (state == UpdateUi.None) return
    androidx.compose.foundation.layout.Box(
        modifier.fillMaxWidth().background(ink.paper, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
            .border(1.dp, ink.line, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).padding(12.dp),
    ) { UpdateBody(state) }
}
