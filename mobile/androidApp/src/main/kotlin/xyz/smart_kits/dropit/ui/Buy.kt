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
import xyz.smart_kits.dropit.shared.UpgradeFrom
import xyz.smart_kits.dropit.ui.kit.GIVE_UP_AFTER_MS
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Paying, in one place: the service makes the transaction for [plan] and the browser opens its checkout. Nothing is shown in between, so
 * the button that was pressed is the only wait ([opening] says which plan it is for, for its spinner).
 *
 * Where it starts from decides what the person sees first. A limit that was just met (the failed send, the share card, the
 * notification, the add-device panel, a file that expired) goes straight here: they know what they want. A way in with no limit behind it
 * (the pill at the top, the settings card) opens the plan page first, so the plans are seen before they are paid for; the plan page's own
 * buttons end here too. [from] is only counted, see [UpgradeFrom].
 */
@Stable
class Buy(private val ctx: Context, private val scope: CoroutineScope) {
    /** The plan whose checkout is being opened, until it is open */
    var opening by mutableStateOf<String?>(null)
        private set

    /** Why the last try did not open a checkout */
    var error by mutableStateOf<Throwable?>(null)

    /** [why]: the limit that was just met, if that is what brought the person (anything that is not a limit of a plan is left out). [onOpened]: the browser has the checkout */
    fun start(from: String, why: String? = null, plan: String = "year", onOpened: () -> Unit = {}) {
        if (opening != null) return
        opening = plan; error = null
        scope.launch {
            try {
                // However the network is, the wait ends: with an answer, or with a sentence saying what happened
                val url = withTimeoutOrNull(GIVE_UP_AFTER_MS) { ctx.app.client.send(Requests.checkout(plan, from, UpgradeFrom.why(why))).str("checkout_url") }
                    ?: throw OfflineError(AttemptTimeout())
                // An address that did not open must not look like a button that does nothing
                // The checkout is opened only when the address is a secure one, whatever the answer says
                if (!ctx.openLink(url, httpsOnly = true)) error = IllegalStateException(ctx.getString(R.string.no_browser)) else onOpened()
            } catch (e: Throwable) {
                error = e
            } finally {
                opening = null
            }
        }
    }

    /** The page for managing the subscription (payments, invoices, cancelling) in the browser; [opening] is "manage" while it is asked for */
    fun manage() {
        if (opening != null) return
        opening = MANAGE; error = null
        scope.launch {
            try {
                val url = withTimeoutOrNull(GIVE_UP_AFTER_MS) { ctx.app.client.send(Requests.billing()).str("url") }
                    ?: throw OfflineError(AttemptTimeout())
                if (!ctx.openLink(url, httpsOnly = true)) error = IllegalStateException(ctx.getString(R.string.no_browser))
            } catch (e: Throwable) {
                error = e
            } finally {
                opening = null
            }
        }
    }

    companion object {
        const val MANAGE = "manage"
    }
}

@Composable
fun rememberBuy(): Buy {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { Buy(ctx, scope) }
}
