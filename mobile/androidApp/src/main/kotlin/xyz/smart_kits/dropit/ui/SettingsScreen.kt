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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import xyz.smart_kits.dropit.receive.ReceiveStatus
import xyz.smart_kits.dropit.update.UpdateUi
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
import xyz.smart_kits.dropit.ui.kit.Toggle
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
fun SettingsScreen(resumed: Int, go: (Screen) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ink
    val unrestricted = remember(resumed) { ctx.app.runsInBackground }

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
            if (ctx.app.receiving.available) {
                val receive by ctx.app.receiving.status.collectAsState()
                val notifyOff = remember(resumed) { !ctx.app.arrivals.allowed }
                PanelRow(
                    stringResource(R.string.set_receive),
                    sub = when {
                        receive == ReceiveStatus.Off -> stringResource(R.string.set_receive_off)
                        notifyOff -> stringResource(R.string.set_receive_notify)
                        receive == ReceiveStatus.Push -> stringResource(R.string.set_receive_push)
                        receive == ReceiveStatus.NoPush -> stringResource(R.string.set_receive_none)
                        receive is ReceiveStatus.Refused -> ctx.app.strings.render((receive as ReceiveStatus.Refused).why)
                        else -> stringResource(R.string.set_receive_unknown)
                    },
                    subColor = if (receive == ReceiveStatus.Push && !notifyOff) c.navy else c.dim,
                ) {
                    Toggle(receive != ReceiveStatus.Off, { on ->
                        scope.launch { if (on) ctx.app.receiving.enable() else ctx.app.receiving.disable() }
                    }, name = stringResource(R.string.set_receive))
                }
                PanelDivider()
            }
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
    }
}

/** The account: its devices and plan, and leaving it */
@Composable
fun AccountScreen(resumed: Int, go: (Screen) -> Unit, onBack: () -> Unit, onSignedOut: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ink
    var me by remember { mutableStateOf<Me?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    LaunchedEffect(resumed) {
        if (me == null) me = ctx.app.account.me(stale = true)
        me = ctx.app.account.me(fresh = true)
    }

    Box {
    Page(stringResource(R.string.label_group_account), onBack) {
        PlanCard(go, Modifier.padding(bottom = 6.dp))
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

        GroupHead(stringResource(R.string.label_group_leave))
        Panel {
            // Red only for what cannot be taken back
            PanelRow(stringResource(R.string.set_sign_out), sub = stringResource(R.string.set_sign_out_sub), titleColor = c.red, onClick = { leaving = true })
        }
    }

    if (leaving) {
        Confirm(
            stringResource(R.string.sign_out_ask), stringResource(R.string.set_sign_out_sub), stringResource(R.string.sign_out_confirm),
            onConfirm = { leaving = false; signingOut = true; scope.launch { ctx.app.account.signOut(); ctx.app.letters.clear(); onSignedOut() } },
            onDismiss = { leaving = false },
        )
    }
    // Leaving waits for the service for a few seconds at most; until then the screen says so and takes no more taps
    if (signingOut) {
        Box(
            Modifier.matchParentSize().background(c.ground.copy(alpha = .85f)).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = {}),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                xyz.smart_kits.dropit.ui.kit.Spinner(c.ink, 20.dp)
                Txt(stringResource(R.string.signing_out), sans(15.sp), c.ink)
            }
        }
    }
    }
}


/** The version, updates, privacy and the source */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = ink

    Page(stringResource(R.string.label_group_about), onBack) {
        Panel {
            var checking by remember { mutableStateOf<Boolean?>(null) }   // null: not asked; true: asking; false: asked
            val update by ctx.app.updater.state.collectAsState()
            PanelRow(
                stringResource(R.string.set_version),
                sub = when {
                    !BuildConfig.SELF_UPDATE -> BuildConfig.VERSION_NAME
                    checking == true -> stringResource(R.string.update_checking)
                    update != UpdateUi.None -> stringResource(R.string.update_available_sub, BuildConfig.VERSION_NAME)
                    checking == false -> stringResource(R.string.update_latest, BuildConfig.VERSION_NAME)
                    else -> stringResource(R.string.update_check_sub, BuildConfig.VERSION_NAME)
                },
                onClick = if (BuildConfig.SELF_UPDATE && checking != true) ({
                    checking = true
                    scope.launch { ctx.app.updater.check(asked = true); checking = false }
                }) else null,
            ) { if (checking == true) xyz.smart_kits.dropit.ui.kit.Spinner(c.dim, 16.dp) }
            if (BuildConfig.SELF_UPDATE && update != UpdateUi.None) UpdateCard(update, Modifier.padding(12.dp))
            PanelDivider()
            PanelRow(stringResource(R.string.privacy), onClick = { ctx.openLink(PRIVACY_URL) }) { Glyph(Icons.External, c.dim, size = 16.dp) }
            PanelDivider()
            PanelRow(stringResource(R.string.set_source), onClick = { ctx.openLink(SOURCE_URL) }) { Glyph(Icons.External, c.dim, size = 16.dp) }
        }
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
