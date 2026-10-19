package xyz.smart_kits.dropit.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.shared.AttemptTimeout
import xyz.smart_kits.dropit.shared.OfflineError
import xyz.smart_kits.dropit.shared.Requests
import xyz.smart_kits.dropit.ui.kit.GIVE_UP_AFTER_MS
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Paying, in one place. Every way to upgrade — the pill at the top, the menu, a limit that was just hit, the plan page — ends
 * here: the service makes the transaction for [plan] and the browser opens its checkout. Nothing is shown in between, so the
 * button that was pressed is the only wait ([opening] says which plan it is for, for its spinner). The plan page stays for
 * comparing the other ways to pay; it is never on the way to the default one.
 */
@Stable
class Buy(private val ctx: Context, private val scope: CoroutineScope) {
    /** The plan whose checkout is being opened, until it is open */
    var opening by mutableStateOf<String?>(null)
        private set

    /** Why the last try did not open a checkout */
    var error by mutableStateOf<Throwable?>(null)

    fun start(plan: String = "year") {
        if (opening != null) return
        opening = plan; error = null
        scope.launch {
            try {
                // However the network is, the wait ends: with an answer, or with a sentence saying what happened
                val url = withTimeoutOrNull(GIVE_UP_AFTER_MS) { ctx.app.client.send(Requests.checkout(plan)).str("checkout_url") }
                    ?: throw OfflineError(AttemptTimeout())
                // An address that did not open must not look like a button that does nothing
                if (!ctx.openLink(url)) error = IllegalStateException(ctx.getString(R.string.no_browser))
            } catch (e: Throwable) {
                error = e
            } finally {
                opening = null
            }
        }
    }
}

@Composable
fun rememberBuy(): Buy {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { Buy(ctx, scope) }
}
