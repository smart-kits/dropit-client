package xyz.smart_kits.dropit.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.smart_kits.dropit.Me
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.ApiError
import xyz.smart_kits.dropit.shared.OfflineError
import xyz.smart_kits.dropit.shared.PRIVACY_URL
import xyz.smart_kits.dropit.work.InApp
import xyz.smart_kits.dropit.work.Inbound
import xyz.smart_kits.dropit.work.Outbox
import xyz.smart_kits.dropit.work.SendWorker

// A plain, working screen for development. The designed one replaces it once its drawings are approved.

class MainActivity : ComponentActivity() {
    private var incomingCode by mutableStateOf<String?>(null)
    private var signedIn by mutableStateOf(false)
    private var resumed by mutableStateOf(0)
    private var offer by mutableStateOf<ClipOffer?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system draws under the bars from Android 15 on anyway; this also gives them icons that show on the
        // light background (they were white on white)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        signedIn = app.account.signedIn
        incomingCode = codeFrom(intent)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    if (signedIn) Home(resumed, offer, onOfferDone = ::offerDone, onSignedOut = { signedIn = false })
                    else Join(incomingCode, resumed, onJoined = { signedIn = true; app.sendWaiting() })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        signedIn = app.account.signedIn
        resumed++
    }

    // Only now may the clipboard be read: the window has focus
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && signedIn) offer = newClip(app.store.clipSeen) ?: offer
    }

    /** Sent or waved away: not offered again */
    private fun offerDone(o: ClipOffer) {
        app.store.clipSeen = maxOf(app.store.clipSeen, o.at)
        offer = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        codeFrom(intent)?.let { incomingCode = it }
    }

    /** A code handed over by the web page (`?pair=`) or another app */
    private fun codeFrom(i: Intent?): String? =
        i?.getStringExtra("code") ?: i?.data?.getQueryParameter("pair")
}

@Composable
private fun errorText(e: Throwable): String {
    val ctx = LocalContext.current
    return when (e) {
        is ApiError -> ctx.app.strings.render(e.sentence())
        is OfflineError -> ctx.app.strings.render(e.sentence)
        else -> e.message ?: e.toString()
    }
}

@Composable
private fun Join(code: String?, resumed: Int, onJoined: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember(code) { mutableStateOf(code.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }

    fun run(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { block(); onJoined() } catch (e: Throwable) { error = e } finally { busy = false }
        }
    }

    Column(
        Modifier.safeDrawingPadding().imePadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.join_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.join_hint), style = MaterialTheme.typography.bodyMedium)
        // Read again each time the screen comes back: a share may have just been held for after joining
        val waiting = remember(resumed) { ctx.app.store.waiting.isNotEmpty() }
        if (waiting) {
            Text(stringResource(R.string.join_waiting), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.uppercase().filter(Char::isLetterOrDigit).take(6) },
            label = { Text(stringResource(R.string.join_code)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.consent), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) }) {
            Text(stringResource(R.string.privacy))
        }
        Button(onClick = { run { ctx.app.account.join(input) } }, enabled = !busy && input.length == 6, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.join_agree))
        }
        error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = { run { ctx.app.account.createAccount() } }, enabled = !busy) {
            Text(stringResource(R.string.first_device), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private data class Picked(val uri: Uri, val name: String)

@Composable
private fun Home(resumed: Int, offer: ClipOffer?, onOfferDone: (ClipOffer) -> Unit, onSignedOut: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    val files = remember { mutableStateListOf<Picked>() }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var me by remember { mutableStateOf<Me?>(null) }

    // What was known shows at once; then the service's answer, each time the screen comes back (devices and
    // plan change elsewhere: another device joins, a payment goes through)
    LaunchedEffect(resumed) {
        if (me == null) me = ctx.app.account.me()
        me = ctx.app.account.me(fresh = true)
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (u in uris) {
            val name = ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: u.lastPathSegment ?: "?"
            files += Picked(u, name)
        }
    }

    /** Hold, hand to the background, and say here how it went. [sent] runs once it is in. */
    fun send(inbound: Inbound, sent: () -> Unit) {
        busy = true; status = null
        scope.launch {
            val outbox = Outbox(ctx)
            val job = withContext(Dispatchers.IO) { outbox.hold(inbound) }
            InApp.add(job.id)
            outbox.enqueue(job.id)
            val info = try {
                WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(job.id)
                    .first { list -> list.firstOrNull()?.state?.isFinished == true }.first()
            } finally {
                InApp.remove(job.id)      // the screen went away first: the result is a notification again
            }
            busy = false
            if (info.state == WorkInfo.State.SUCCEEDED) {
                status = "✓ " + info.outputData.getString(SendWorker.DONE)
                sent()
                me = ctx.app.account.me()
            } else {
                status = info.outputData.getString(SendWorker.ERROR)   // kept as written, to try again
                if (!ctx.app.account.signedIn) onSignedOut()
            }
        }
    }

    Column(
        Modifier.safeDrawingPadding().imePadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            me?.let { Text(stringResource(R.string.account_line, it.plan, it.devicesUsed, it.devicesLimit), style = MaterialTheme.typography.bodySmall) }
        }
        // Copied something before opening dropit: one tap sends it
        if (offer != null) Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 8.dp, 4.dp)) {
                Text(stringResource(R.string.clip_new), style = MaterialTheme.typography.labelMedium)
                Text(offer.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 3, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onOfferDone(offer) }) { Text(stringResource(R.string.close)) }
                    TextButton(enabled = !busy, onClick = {
                        val o = offer
                        // Gone once it is in; if it fails it stays, with the reason below, to try again
                        send(Inbound(text = o.text, streams = listOfNotNull(o.uri?.toString()))) { onOfferDone(o) }
                    }) { Text(stringResource(R.string.send)) }
                }
            }
        }
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.compose_hint)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        )
        for (f in files.toList()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📎 ${f.name}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { files.remove(f) }) { Text(stringResource(R.string.remove)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.attach)) }
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                // Sent: clear and get ready for the next one
                send(Inbound(text = text, streams = files.map { it.uri.toString() })) { text = ""; files.clear() }
            }, enabled = !busy && (text.isNotBlank() || files.isNotEmpty())) { Text(stringResource(R.string.send)) }
        }
        // Said before pressing: what Send will send
        val hasText = text.isNotBlank()
        val line = when {
            hasText && files.isNotEmpty() -> stringResource(R.string.will_send_both, files.size)
            files.isNotEmpty() -> stringResource(R.string.will_send_files, files.size)
            hasText -> stringResource(R.string.will_send_text)
            else -> null
        }
        line?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        // The fastest way after copying, offered until it is there
        var tileAdded by remember(resumed) { mutableStateOf(ctx.app.store.tileAdded) }
        var tileManual by remember { mutableStateOf(Build.VERSION.SDK_INT < 33) }
        if (!tileAdded) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(if (tileManual) R.string.tile_manual else R.string.tile_hint), style = MaterialTheme.typography.bodySmall)
            if (!tileManual) OutlinedButton(onClick = {
                ctx.requestTile { added -> if (added) { ctx.app.store.tileAdded = true; tileAdded = true } else tileManual = true }
            }) { Text(stringResource(R.string.tile_add)) }
        }
        // Checked again each time the screen comes back: the person may have just changed it in settings
        val background = remember(resumed) { ctx.app.runsInBackground }
        if (!background) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.bg_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { ctx.app.askRunInBackground(ctx) }) { Text(stringResource(R.string.bg_allow)) }
        }
        Spacer(Modifier.height(32.dp))
        TextButton(onClick = { scope.launch { ctx.app.account.signOut(); onSignedOut() } }) {
            Text(stringResource(R.string.sign_out))
        }
    }
}

/**
 * The system's one-tap "add this tile" (Android 13+). [done] gets false when the system would not show it
 * (declined, or a system that leaves it out), so the screen falls back to saying how to add it by hand.
 */
@android.annotation.SuppressLint("NewApi")
private fun android.content.Context.requestTile(done: (Boolean) -> Unit) {
    val sbm = getSystemService(android.app.StatusBarManager::class.java)
    runCatching {
        sbm.requestAddTileService(
            android.content.ComponentName(this, xyz.smart_kits.dropit.entry.SendClipboardTile::class.java),
            getString(R.string.send_clipboard),
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile),
            mainExecutor,
        ) { result ->
            done(result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
        }
    }.onFailure { done(false) }
}
