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
    /** Bring back what this phone already had: everything, the newest 50, the last three days */
    data object Repull : Screen
    data object Devices : Screen
    data object AddDevice : Screen
    /** `why`: what limit was just hit, when that is what brought the person here; `from`: where in the app they came from (counted with the checkout, see UpgradeFrom) */
    data class Plans(val why: String? = null, val from: String = UpgradeFrom.PLANS) : Screen
    /** The guide again, from the settings page, opened at one of its screens */
    data class Onboarding(val page: Int = 0) : Screen
    data object Scan : Screen
}

class MainActivity : ComponentActivity() {
    private var incomingCode by mutableStateOf<String?>(null)
    /** The code that came from the camera: only that one is joined at once; a code from a link or typed waits for the button */
    private var scanned by mutableStateOf<String?>(null)
    private var signedIn by mutableStateOf(false)
    /** The first-run guide is up: shown once, to everyone, ahead of joining or ahead of the home screen */
    private var guide by mutableStateOf(false)
    private var resumed by mutableStateOf(0)
    private var offer by mutableStateOf<ClipOffer?>(null)
    private val stack = mutableStateListOf<Screen>()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system draws under the bars from Android 15 on anyway; this also gives them icons that show on
        // the light ground (they were white on white)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        signedIn = app.account.signedIn
        read(intent)
        // One guide, the same for everyone, once; after it comes joining, or the home screen for a phone that has joined already
        guide = !app.store.onboarded
        setContent {
            DropitTheme {
                val go: (Screen) -> Unit = { stack += it }
                val back: () -> Unit = { stack.removeLastOrNull() }
                BackHandler(enabled = stack.isNotEmpty(), onBack = back)
                val top = stack.lastOrNull()
                val update by app.updater.state.collectAsState()
                when {
                    guide -> OnboardingScreen(resumed, onDone = { app.store.onboarded = true; guide = false })
                    !signedIn && top == Screen.Scan -> ScanScreen(onCode = { incomingCode = it; scanned = it; back() }, onBack = back)
                    !signedIn -> JoinScreen(incomingCode, resumed, joinNow = scanned != null && scanned == incomingCode, onJoined = ::joined, onScan = { scanned = null; go(Screen.Scan) })
                    top == null || top == Screen.Scan -> HomeScreen(resumed, offer, onOfferDone = ::offerDone, onSignedOut = ::signedOut, go = go)
                    top is Screen.Onboarding -> OnboardingScreen(resumed, top.page, onDone = { app.store.onboarded = true; back() })
                    top == Screen.Settings -> SettingsScreen(resumed, go = go, onBack = back)
                    top == Screen.Repull -> RepullScreen(resumed, go = go, onBack = back)
                    top == Screen.Devices -> DevicesScreen(resumed, go = go, onBack = back, onSignedOut = ::signedOut)
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
        scanned = null
        stack.clear()
        app.sendWaiting()
        // Background receiving starts on; if the account can't have it, settings say why
        app.scope.launch { app.receiving.enable() }
    }

    private fun signedOut() {
        app.letters.clear()    // the same clean-up as leaving by hand: nothing of the old account is left on the home screen
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

    /** A code handed over by the web page (`?pair=`); or a notification asking for a screen */
    private fun read(i: Intent?) {
        i?.data?.getQueryParameter("pair")?.let { incomingCode = it }
        // The share card or a limit's notification: the plans page, at the limit that was met (the year plan is the one picked there)
        val show = i?.getStringExtra(SHOW)
        if ((show == SHOW_PLANS || show == SHOW_CHECKOUT) && signedIn) {
            stack.clear()
            stack += Screen.Plans(i?.getStringExtra(WHY)?.ifEmpty { null }, UpgradeFrom.SHARE)
        }
    }

    companion object {
        const val SHOW = "show"
        const val SHOW_PLANS = "plans"
        const val SHOW_CHECKOUT = "checkout"
        const val WHY = "why"
    }
}
