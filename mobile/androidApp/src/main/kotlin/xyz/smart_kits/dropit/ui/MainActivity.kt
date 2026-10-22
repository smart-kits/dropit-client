package xyz.smart_kits.dropit.ui

import kotlinx.coroutines.launch
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.shared.UpgradeFrom
import xyz.smart_kits.dropit.ui.theme.DropitTheme

/** Where the app is. Home is underneath everything; the rest stack on it and go back to it. */
sealed interface Screen {
    data object Settings : Screen
    /** Who a send from this phone goes to when nothing is chosen */
    data object DefaultTargets : Screen
    /** Bring back what this phone already had: everything, the newest 50, the last three days */
    data object Repull : Screen
    data object Devices : Screen
    data object AddDevice : Screen
    /** `why`: what limit was just hit, when that is what brought the person here; `from`: where in the app they came from (counted with the checkout, see UpgradeFrom) */
    data class Plans(val why: String? = null, val from: String = UpgradeFrom.PLANS) : Screen
    /** The first-run steps; `gesturesOnly` shows just the quick-settings and gesture ones again */
    data class Onboarding(val gesturesOnly: Boolean = false) : Screen
    data object Scan : Screen
}

class MainActivity : ComponentActivity() {
    private var incomingCode by mutableStateOf<String?>(null)
    /** The code came from this phone's own camera: joining goes ahead without another tap */
    private var scanned by mutableStateOf(false)
    private var signedIn by mutableStateOf(false)
    private var resumed by mutableStateOf(0)
    private var offer by mutableStateOf<ClipOffer?>(null)
    /** A limit's notification was tapped: the checkout opens from the home screen (the limit that was met is the text, "" when not said) */
    private var checkoutWhy by mutableStateOf<String?>(null)
    private val stack = mutableStateListOf<Screen>()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system draws under the bars from Android 15 on anyway; this also gives them icons that show on
        // the light ground (they were white on white)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        signedIn = app.account.signedIn
        read(intent)
        // Joined before the first-run steps existed, or left them half-way: they come up once
        if (signedIn && !app.store.onboarded) stack += Screen.Onboarding()
        setContent {
            DropitTheme {
                val go: (Screen) -> Unit = { stack += it }
                val back: () -> Unit = { stack.removeLastOrNull() }
                BackHandler(enabled = stack.isNotEmpty(), onBack = back)
                val top = stack.lastOrNull()
                val update by app.updater.state.collectAsState()
                when {
                    !signedIn && top == Screen.Scan -> ScanScreen(onCode = { incomingCode = it; scanned = true; back() }, onBack = back)
                    !signedIn -> JoinScreen(incomingCode, resumed, joinNow = scanned, onJoined = ::joined, onScan = { scanned = false; go(Screen.Scan) })
                    top == null || top == Screen.Scan -> HomeScreen(resumed, offer, onOfferDone = ::offerDone, onSignedOut = ::signedOut, go = go, checkoutWhy = checkoutWhy, onCheckoutStarted = { checkoutWhy = null })
                    top is Screen.Onboarding -> OnboardingScreen(resumed, top.gesturesOnly, onDone = { app.store.onboarded = true; back() })
                    top == Screen.Settings -> SettingsScreen(resumed, go = go, onBack = back, onSignedOut = ::signedOut)
                    top == Screen.DefaultTargets -> DefaultTargetsScreen(resumed, go = go, onBack = back)
                    top == Screen.Repull -> RepullScreen(resumed, onBack = back)
                    top == Screen.Devices -> DevicesScreen(resumed, go = go, onBack = back)
                    top == Screen.AddDevice -> AddDeviceScreen(go = go, onBack = back)
                    top is Screen.Plans -> PlansScreen(resumed, top.why, top.from, onBack = back)
                }
                // A newer build: a dialog on any screen; one that must be installed cannot be closed
                UpdateDialog(update)
            }
        }
    }

    private fun joined() {
        signedIn = true
        incomingCode = null
        scanned = false
        stack.clear()
        app.sendWaiting()
        // Background receiving starts on; if the account can't have it, settings say why
        app.scope.launch { app.receiving.enable() }
        if (!app.store.onboarded) stack += Screen.Onboarding()
    }

    private fun signedOut() {
        app.peers.reset()      // what was known about this account's devices goes with it
        signedIn = false
        stack.clear()
    }

    override fun onResume() {
        super.onResume()
        if (signedIn && !app.account.signedIn) signedOut()
        else if (signedIn) {
            app.warm()   // the connection to send over is opened while the person looks at the screen (not more than every 5 minutes)
            app.scope.launch { app.receiving.pullNow("open") }   // coming back: what came meanwhile (not more than every 30 s)
        }
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
        read(intent)
    }

    /** A code handed over by the web page (`?pair=`) or another app; or a notification asking for a screen */
    private fun read(i: Intent?) {
        (i?.getStringExtra("code") ?: i?.data?.getQueryParameter("pair"))?.let { incomingCode = it }
        if (i?.getStringExtra(SHOW) == SHOW_PLANS && signedIn && stack.lastOrNull() !is Screen.Plans) stack += Screen.Plans(from = UpgradeFrom.SHARE)
        // A limit's notification: straight to the checkout for what was met (the home screen does it, and says so if it can't)
        if (i?.getStringExtra(SHOW) == SHOW_CHECKOUT && signedIn) {
            stack.clear()
            checkoutWhy = i.getStringExtra(WHY).orEmpty()
        }
    }

    companion object {
        const val SHOW = "show"
        const val SHOW_PLANS = "plans"
        const val SHOW_CHECKOUT = "checkout"
        const val WHY = "why"
    }
}
