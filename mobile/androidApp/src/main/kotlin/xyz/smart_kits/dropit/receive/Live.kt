package xyz.smart_kits.dropit.receive

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import xyz.smart_kits.dropit.BuildConfig
import xyz.smart_kits.dropit.Store
import xyz.smart_kits.dropit.shared.ApiError
import xyz.smart_kits.dropit.shared.Channel
import xyz.smart_kits.dropit.shared.DropitClient
import xyz.smart_kits.dropit.shared.Failure
import xyz.smart_kits.dropit.shared.Frame
import xyz.smart_kits.dropit.shared.HEARTBEAT_MS
import xyz.smart_kits.dropit.shared.Message
import xyz.smart_kits.dropit.shared.Next
import xyz.smart_kits.dropit.shared.OfflineError
import xyz.smart_kits.dropit.shared.Requests
import xyz.smart_kits.dropit.shared.STALE_MS
import xyz.smart_kits.dropit.shared.canTakeTicket
import xyz.smart_kits.dropit.shared.failedInARow
import xyz.smart_kits.dropit.shared.frameOf
import xyz.smart_kits.dropit.shared.msUntilUtcMidnight
import xyz.smart_kits.dropit.shared.nextAfter
import xyz.smart_kits.dropit.shared.renewAt
import xyz.smart_kits.dropit.shared.reprobeOnNetworkChange
import xyz.smart_kits.dropit.shared.tookTicket
import xyz.smart_kits.dropit.shared.withJitter
import xyz.smart_kits.dropit.shared.TICKETS_PER_DAY
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/** Whether this phone has a network to go out on (not whether the service answers): a ticket is not asked for without one */
internal fun Context.online(): Boolean {
    val cm = getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** `wifi`, `cellular` or `other` for the network in use: only to tell a switch between kinds from one between two of the same */
internal fun Context.networkKind(): String {
    val cm = getSystemService(ConnectivityManager::class.java) ?: return "other"
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "other"
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        else -> "other"
    }
}

/**
 * The connection kept open to hear that something arrived when the system's push does not get through. It only says "something new":
 * what it is gets fetched by [Receiving.catchUp], as with a push.
 *
 * Rules that keep it cheap (each ticket costs the service work, and the account has a daily count the other devices share):
 * - no network → no ticket (waits for one), a switch of network closes the old connection and connects on the new;
 * - at most [TICKETS_PER_DAY] tickets a day on this phone, counted in the app's own store;
 * - waits grow 1 s → 5 min after each failure, and only a connection that held a minute starts them over;
 * - a refusal for the day (count used up, places taken, trial over) stops it until UTC midnight (not for the trial being over);
 * - the heartbeat is the text `ping` every minute (anything else sent closes the connection); nothing heard for 150 s means dead;
 * - after every (re)connect what was missed is pulled once.
 * Runs inside [ReceiveService], which keeps the process alive; not tried on a phone yet.
 */
class Live(
    private val context: Context,
    private val store: Store,
    private val client: DropitClient,
    private val receiving: Receiving,
) {
    private var scope: CoroutineScope? = null
    private var loop: Job? = null

    /** The network in use, as the system last said: the id changes when the phone changes network */
    private data class Net(val up: Boolean, val id: Long, val kind: String)
    private val net = MutableStateFlow(Net(context.online(), 0, context.networkKind()))
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** The connection being held right now, so a change of network or a stop can close it */
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closing: CompletableDeferred<Closed>? = null

    private class Closed(val heldMs: Long, val end: String?, val reason: Why)
    private enum class Why { DROPPED, STALE, RENEW, NETWORK }

    val running: Boolean get() = loop?.isActive == true

    /** Start keeping the connection (once; a second call does nothing) */
    @Synchronized fun start() {
        if (running) return
        scope?.cancel()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        watchNetwork()
        loop = s.launch { run() }
    }

    /** Close it and stop: switched off, signed out, or push works after all */
    @Synchronized fun stop() {
        callback?.let { runCatching { context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        callback = null
        socket?.cancel()
        scope?.cancel()
        scope = null
        loop = null
        socket = null
    }

    private fun watchNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val kind = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    else -> "other"
                }
                val next = Net(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), network.networkHandle, kind)
                val before = net.value
                net.value = next
                // Another network: the old connection is dead whatever it looks like; and a new kind of network may let push through
                if (before.id != 0L && before.up && next.up && before.id != next.id) {
                    closing?.complete(Closed(0, null, Why.NETWORK))
                    scope?.launch { maybeReprobe(before, next) }
                }
            }

            override fun onLost(network: Network) {
                if (net.value.id == network.networkHandle) {
                    net.value = Net(false, 0, "other")
                    closing?.complete(Closed(0, null, Why.NETWORK))
                }
            }
        }
        callback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    private suspend fun maybeReprobe(before: Net, next: Net) {
        val since = store.probeAt.takeIf { it > 0 }?.let { System.currentTimeMillis() - it }
        if (reprobeOnNetworkChange(Channel.CONNECTION, true, sameKind = before.kind == next.kind, sinceLastProbeMs = since)) receiving.reprobe()
    }

    private suspend fun run() {
        var failures = 0
        while (scope?.isActive == true) {
            // No network: nothing is asked for; when one comes, this goes on
            if (!net.value.up) {
                receiving.liveStatus(connected = false, retryAtMs = null)
                net.first { it.up }
            }
            val now = System.currentTimeMillis()
            if (!canTakeTicket(store.ticketLedger, now)) {
                rest(Message("ticketsDay"), msUntilUtcMidnight(now) + (Random.nextDouble() * 600_000).toLong())
                continue
            }
            val ticket = try {
                client.send(Requests.wsTicket()).str("ticket") ?: error("no ticket")
            } catch (e: ApiError) {
                // The service answered, so the ticket counts against the day (a refusal for the day's count is the day's count being full)
                store.ticketLedger = tookTicket(store.ticketLedger, now)
                if (e.failure == Failure.AUTH_LOST) { receiving.liveEnded(); return }
                failures++
                when (val next = nextAfter(System.currentTimeMillis(), Random.nextDouble(), failures, refused = e)) {
                    is Next.Wait -> { receiving.liveStatus(false, System.currentTimeMillis() + next.delayMs); delay(next.delayMs) }
                    is Next.Stop -> {
                        val resume = next.resumeAfterMs
                        if (resume == null) { receiving.liveRefused(next.why, final = true); return }
                        rest(next.why, resume)
                        failures = 0
                    }
                }
                continue
            } catch (e: OfflineError) {
                failures++
                val wait = withJitter(xyz.smart_kits.dropit.shared.reconnectDelayMs(failures), Random.nextDouble())
                receiving.liveStatus(false, System.currentTimeMillis() + wait)
                delay(wait)
                continue
            }
            store.ticketLedger = tookTicket(store.ticketLedger, now)

            val closed = connect(ticket)
            when {
                closed.reason == Why.RENEW || closed.reason == Why.NETWORK -> failures = 0        // not a failure: no wait
                closed.end != null -> {
                    failures = failedInARow(failures, closed.heldMs)
                    when (val next = nextAfter(System.currentTimeMillis(), Random.nextDouble(), failures, end = closed.end)) {
                        is Next.Wait -> { receiving.liveStatus(false, System.currentTimeMillis() + next.delayMs); delay(next.delayMs) }
                        is Next.Stop -> {
                            val resume = next.resumeAfterMs
                            if (resume == null) { receiving.liveRefused(next.why, final = true); return }
                            rest(next.why, resume)
                            failures = 0
                        }
                    }
                }
                else -> {
                    failures = failedInARow(failures, closed.heldMs)
                    val wait = nextAfterDrop(failures)
                    receiving.liveStatus(false, System.currentTimeMillis() + wait)
                    delay(wait)
                }
            }
        }
    }

    private fun nextAfterDrop(failures: Int): Long =
        (nextAfter(System.currentTimeMillis(), Random.nextDouble(), failures) as? Next.Wait)?.delayMs ?: 1_000

    /** Say why there is no connection until [ms] from now, and wait for that (this is how the day's refusals end) */
    private suspend fun rest(why: Message, ms: Long) {
        receiving.liveRefused(why, final = false)
        delay(ms)
    }

    /** One connection, from opening to its end */
    private suspend fun connect(ticket: String): Closed {
        val base = (store.endpoints()?.firstOrNull() ?: BuildConfig.ENDPOINTS.split(',').first()).trimEnd('/')
        val done = CompletableDeferred<Closed>().also { closing = it }
        val openedAt = AtomicLong(0)
        val lastHeard = AtomicLong(SystemClock.elapsedRealtime())
        val end = AtomicReference<String?>(null)
        fun held() = if (openedAt.get() == 0L) 0 else SystemClock.elapsedRealtime() - openedAt.get()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openedAt.set(SystemClock.elapsedRealtime())
                lastHeard.set(openedAt.get())
                receiving.liveStatus(connected = true, retryAtMs = null)
                // Whatever came while there was no connection
                scope?.launch { receiving.liveCatchUp("reconnect") }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastHeard.set(SystemClock.elapsedRealtime())
                when (val f = frameOf(text)) {
                    is Frame.New -> scope?.launch { receiving.liveCatchUp("push") }
                    is Frame.End -> end.set(f.reason)
                    Frame.Pong, Frame.Other -> {}
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.complete(Closed(held(), end.get(), Why.DROPPED)) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.complete(Closed(held(), end.get(), Why.DROPPED)) }
        }
        val ws = http.newWebSocket(Request.Builder().url(base + Requests.wsTarget(ticket)).build(), listener)
        socket = ws
        val renew = renewAt(System.currentTimeMillis(), Random.nextDouble())
        return try {
            coroutineScope {
                // The heartbeat is the text "ping": the service answers it without waking anything; it closes a connection that sends anything else
                val beat = launch { while (isActive) { delay(HEARTBEAT_MS); ws.send("ping") } }
                // A connection that went quiet (asleep, a dead path) looks open: after 150 s of nothing it is closed and a new one made
                val watch = launch {
                    while (isActive) {
                        delay(15_000)
                        if (SystemClock.elapsedRealtime() - lastHeard.get() > STALE_MS) { done.complete(Closed(held(), end.get(), Why.STALE)); break }
                        // A moment after midnight UTC: a new ticket, so the service's "today" moves on for this phone too
                        if (System.currentTimeMillis() >= renew) { done.complete(Closed(held(), end.get(), Why.RENEW)); break }
                    }
                }
                val result = done.await()
                beat.cancel(); watch.cancel()
                result
            }
        } finally {
            ws.cancel()
            socket = null
            closing = null
        }
    }

    private val http: OkHttpClient by lazy {
        // Heartbeats are our own text frames, so OkHttp's protocol-level ping stays off; no read timeout: quiet is normal
        OkHttpClient.Builder()
            .pingInterval(0, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
