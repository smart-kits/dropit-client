package xyz.smart_kits.dropit.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xyz.smart_kits.dropit.BuildConfig
import xyz.smart_kits.dropit.Me
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.PRIVACY_URL
import xyz.smart_kits.dropit.shared.SOURCE_URL
import xyz.smart_kits.dropit.shared.fileSize
import xyz.smart_kits.dropit.shared.mb
import xyz.smart_kits.dropit.ui.kit.Btn
import xyz.smart_kits.dropit.ui.kit.Glyph
import xyz.smart_kits.dropit.ui.kit.GroupHead
import xyz.smart_kits.dropit.ui.kit.Icons
import xyz.smart_kits.dropit.ui.kit.Panel
import xyz.smart_kits.dropit.ui.kit.PanelDivider
import xyz.smart_kits.dropit.ui.kit.PanelRow
import xyz.smart_kits.dropit.ui.kit.TitleBar
import xyz.smart_kits.dropit.ui.kit.Txt
import xyz.smart_kits.dropit.ui.theme.ink
import xyz.smart_kits.dropit.ui.theme.sans

/** A screen one step in: tissue ground, a title bar, and what follows scrolling under it */
@Composable
internal fun Page(title: String, onBack: () -> Unit, trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(ink.ground).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
    ) {
        TitleBar(title, onBack, trailing = trailing)
        content()
    }
}

/**
 * Settings, as a plain grouped list the way the system draws its own: the brand shows only in details.
 * Every number comes from the service.
 */
@Composable
fun SettingsScreen(resumed: Int, go: (Screen) -> Unit, onBack: () -> Unit, onSignedOut: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ink
    var me by remember { mutableStateOf<Me?>(null) }
    var leaving by remember { mutableStateOf(false) }
    val unrestricted = remember(resumed) { ctx.app.runsInBackground }
    LaunchedEffect(resumed) {
        if (me == null) me = ctx.app.account.me()
        me = ctx.app.account.me(fresh = true)
    }

    Page(stringResource(R.string.label_settings), onBack) {
        // The system keeps the app's language from Android 13 on: one value, changed here or in system settings
        if (Build.VERSION.SDK_INT >= 33) {
            GroupHead(stringResource(R.string.label_group_general))
            Panel {
                PanelRow(stringResource(R.string.set_language), sub = stringResource(R.string.set_language_sub), onClick = {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
                }) { Glyph(Icons.Chevron, c.dim, size = 16.dp) }
            }
        }

        GroupHead(stringResource(R.string.label_group_phone))
        Panel {
            PanelRow(stringResource(R.string.set_device_name)) { Txt(ctx.app.account.deviceName(), sans(13.sp), c.dim) }
            PanelDivider()
            PanelRow(
                stringResource(R.string.ob_battery), sub = stringResource(R.string.set_battery_sub),
                onClick = if (unrestricted) null else ({ ctx.app.askRunInBackground(ctx) }),
            ) {
                if (unrestricted) Txt(stringResource(R.string.ob_battery_ok), sans(13.sp), c.navy)
                else Txt(stringResource(R.string.ob_battery_unset), sans(13.sp), c.red)
            }
            PanelDivider()
            PanelRow(stringResource(R.string.label_gestures), onClick = { go(Screen.Onboarding(gesturesOnly = true)) }) { Glyph(Icons.Chevron, c.dim, size = 16.dp) }
        }

        GroupHead(stringResource(R.string.label_group_account))
        Panel {
            PanelRow(
                stringResource(R.string.label_devices), sub = me?.let { stringResource(R.string.set_devices_sub, it.devicesUsed, it.devicesLimit) },
                onClick = { go(Screen.Devices) },
            ) { Glyph(Icons.Chevron, c.dim, size = 16.dp) }
            PanelDivider()
            PanelRow(
                stringResource(R.string.label_plan),
                sub = me?.let { stringResource(R.string.set_plan_sub, stringResource(if (it.paid) R.string.label_paid else R.string.label_free), fileSize(it.bytesUsed), mb(it.bytesLimit)) },
                onClick = { go(Screen.Plans()) },
            ) { Glyph(Icons.Chevron, c.dim, size = 16.dp) }
        }

        GroupHead(stringResource(R.string.label_group_about))
        Panel {
            PanelRow(stringResource(R.string.set_version), sub = BuildConfig.VERSION_NAME)
            PanelDivider()
            PanelRow(stringResource(R.string.privacy), onClick = { ctx.openLink(PRIVACY_URL) }) { Glyph(Icons.External, c.dim, size = 16.dp) }
            PanelDivider()
            PanelRow(stringResource(R.string.set_source), onClick = { ctx.openLink(SOURCE_URL) }) { Glyph(Icons.External, c.dim, size = 16.dp) }
        }

        GroupHead(stringResource(R.string.label_group_leave))
        Panel {
            // Red only for what cannot be taken back
            PanelRow(stringResource(R.string.set_sign_out), sub = stringResource(R.string.set_sign_out_sub), titleColor = c.red, onClick = { leaving = true })
        }
    }

    if (leaving) {
        Confirm(
            stringResource(R.string.sign_out_ask), stringResource(R.string.set_sign_out_sub), stringResource(R.string.sign_out_confirm),
            onConfirm = { leaving = false; scope.launch { ctx.app.account.signOut(); ctx.app.letters.clear(); onSignedOut() } },
            onDismiss = { leaving = false },
        )
    }
}

/** One question before something that cannot be undone; the way out is always there */
@Composable
internal fun Confirm(title: String, text: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = ink
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.paper,
        title = { Txt(title, sans(17.sp, androidx.compose.ui.text.font.FontWeight.SemiBold), c.ink) },
        text = text?.let { { Txt(it, sans(14.sp, lineHeight = 21.sp), c.dim) } },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(stringResource(R.string.action_cancel), onDismiss, Modifier.weight(1f))
                Btn(confirm, onConfirm, Modifier.weight(1f), solid = true)
            }
        },
    )
}
