package xyz.smart_kits.dropit.receive

import android.content.Context
import android.os.SystemClock
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import xyz.smart_kits.dropit.BuildConfig
import xyz.smart_kits.dropit.Store
import xyz.smart_kits.dropit.LetterBox
import xyz.smart_kits.dropit.shared.ApiError
import xyz.smart_kits.dropit.shared.Channel
import xyz.smart_kits.dropit.shared.DropitClient
import xyz.smart_kits.dropit.shared.Failure
import xyz.smart_kits.dropit.shared.OfflineError
import xyz.smart_kits.dropit.shared.PUSH_PROBE_WAIT_MS
import xyz.smart_kits.dropit.shared.Probe
import xyz.smart_kits.dropit.shared.Requests
import xyz.smart_kits.dropit.shared.arrive
import xyz.smart_kits.dropit.shared.chooseChannel
import xyz.smart_kits.dropit.shared.pullAll
import xyz.smart_kits.dropit.shared.realtimeRefusal
import xyz.smart_kits.dropit.shared.Message
import kotlin.coroutines.resume

/** What background receiving comes to on this phone, for the settings row */
sealed interface ReceiveStatus {
    /** Switched off by the person, or this build has no system push */
    data object Off : ReceiveStatus
    /** Not found out yet, or no network to find out with */
    data object Unknown : ReceiveStatus
    /** A test push got through */
    data object Push : ReceiveStatus
    /** The test push did not arrive: no system push on this phone */
    data object NoPush : ReceiveStatus
    /** The service said real-time is not this account's right now */
    data class Refused(val why: Message) : ReceiveStatus
}

private const val TOKEN_WAIT_MS = 10_000L      // how long to wait for the push address
private const val OPEN_PULL_GAP_MS = 30_000L   // opening and coming back this close together is one pull

/**
 * Hearing that something arrived while dropit is closed. The service pushes through the system's push
 * service; the push only says "something new", and this fetches what it was. All the choosing is in
 * the shared `chooseChannel`; this does the registering, waiting and fetching.
 */
class Receiving(
    private val context: Context,
    private val store: Store,
    private val client: DropitClient,
    private val letters: LetterBox,
    private val arrivals: Arrivals,
    /** How long the account's plan keeps things on the service, for telling each received file when it goes */
    private val retention: suspend () -> Long? = { null },
) {
    /** Whether this build has a push project to talk to (a build from source without one has none) */
    val available: Boolean get() = BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank()

    private val state = MutableStateFlow(read())
    val status: StateFlow<ReceiveStatus> get() = state

    private val pulling = Mutex()
    @Volatile private var waiting: CompletableDeferred<Unit>? = null

    fun init() {
        if (!available || FirebaseApp.getApps(context).isNotEmpty()) return
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build(),
        )
    }

    private fun read(): ReceiveStatus {
        if (!store.receiveOn) return ReceiveStatus.Off
        val s = store.receiveState ?: return ReceiveStatus.Unknown
        return when {
            s == "push" -> ReceiveStatus.Push
            s == "none" -> ReceiveStatus.NoPush
            s.startsWith("refused:") -> ReceiveStatus.Refused(Message(s.removePrefix("refused:")))
            else -> ReceiveStatus.Unknown
        }
    }

    private fun set(status: ReceiveStatus, saved: String?) {
        store.receiveState = saved
        state.value = status
    }

    /** The system's address for this phone, or null when there is none (no Google services, no network) */
    private suspend fun token(): String? = withTimeoutOrNull(TOKEN_WAIT_MS) { fetchToken() }

    // Never waits forever: where Google's services can't be reached the task neither succeeds nor fails, and the switch, the lock
    // it holds and the pull that follows it would all wait with it
    private suspend fun fetchToken(): String? = suspendCancellableCoroutine { k ->
        // Task.getResult() throws when the task failed (no Google services, no network): ask isSuccessful first, and never let
        // anything escape this callback, which runs on the main thread
        runCatching {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                val address = runCatching { if (task.isSuccessful) task.result else null }.getOrNull()
                if (k.isActive) k.resume(address)
            }
        }.onFailure { if (k.isActive) k.resume(null) }
    }

    /** Google Play services are on this phone and usable: a missing address then means no network for now, not no push for good */
    private fun googleServices(): Boolean = runCatching { context.packageManager.getPackageInfo("com.google.android.gms", 0); true }.getOrDefault(false)

    /** One at a time: the start of the app, joining and the switch can all ask, and the test push they wait for is one */
    private val enabling = Mutex()

    /** Tell the service this phone's push address, if it is not the one it already has. True when it has it. */
    suspend fun register(address: String? = null): Boolean {
        if (!available || !store.receiveOn || store.tokenNow() == null) return false
        val t = address ?: token() ?: return false
        if (t == store.pushToken) return true
        return try {
            client.send(Requests.registerPush(t))
            store.pushToken = t
            true
        } catch (e: ApiError) {
            false
        } catch (e: OfflineError) {
            false
        }
    }

    /** The system gave the phone a new address */
    suspend fun addressChanged(address: String) { register(address) }

    /** Called by the push service when a test push arrives */
    fun testArrived() { waiting?.complete(Unit) }

    /** Ask for one test push and see if it comes. Null when the question could not even be asked (no network). */
    private suspend fun probe(): Probe? {
        val arrived = CompletableDeferred<Unit>().also { waiting = it }
        val t0 = SystemClock.elapsedRealtime()
        try {
            client.send(Requests.testPush())
        } catch (e: ApiError) {
            waiting = null
            // The service could not do it (no push set up there, push provider down, busy): that says nothing about
            // this phone, so it is not "no push here": try again later
            if (e.failure == Failure.AUTH_LOST || e.status >= 500) return null
            return if (realtimeRefusal(e) != null) Probe.Refused(e) else Probe.Unavailable
        } catch (e: OfflineError) {
            waiting = null
            return null
        }
        val ok = withTimeoutOrNull(PUSH_PROBE_WAIT_MS) { arrived.await() }
        waiting = null
        return if (ok != null) Probe.Arrived(SystemClock.elapsedRealtime() - t0) else Probe.Silent
    }

    /**
     * Switch background receiving on: give the service this phone's address, ask for a test push, and
     * keep what came of it. The first time, what is already waiting is set aside, so the first push
     * does not announce everything from before.
     */
    suspend fun enable(): ReceiveStatus = enabling.withLock {
        store.receiveOn = true
        if (!available) return@withLock ReceiveStatus.Off.also { state.value = it }
        // On at once: the answer below can take as long as the push address does (up to TOKEN_WAIT_MS), and a switch that
        // stays off meanwhile looks as if it was not pressed
        if (state.value == ReceiveStatus.Off) state.value = ReceiveStatus.Unknown
        val address = token()
        // Switched off again while the address was being waited for: that is the last word (disable() does not wait for this lock)
        if (!store.receiveOn) return@withLock ReceiveStatus.Off.also { state.value = it }
        if (address == null || !register(address)) {
            // No address: no network for now, or no system push on this phone. "None for good" is only said when Google services
            // are not there; otherwise it is asked again on the next start (a first start with no network must not stick as "none")
            val none = address == null && !googleServices()
            return@withLock (if (none) ReceiveStatus.NoPush else ReceiveStatus.Unknown).also { set(it, if (none) "none" else null) }
        }
        baseline()
        val probe = probe() ?: return@withLock ReceiveStatus.Unknown.also { set(it, null) }
        val choice = chooseChannel(on = true, probe = probe)
        when {
            choice.channel == Channel.PUSH -> ReceiveStatus.Push.also { set(it, "push") }
            choice.why != null -> ReceiveStatus.Refused(choice.why!!).also { set(it, "refused:${choice.why!!.key}") }
            else -> ReceiveStatus.NoPush.also { set(it, "none") }
        }
    }

    /** Switch it off: the service stops pushing to this phone */
    suspend fun disable() {
        store.receiveOn = false
        state.value = ReceiveStatus.Off
        if (store.pushToken != null && store.tokenNow() != null) {
            runCatching { client.send(Requests.registerPush(null)) }.onSuccess { store.pushToken = null }
        }
    }

    /**
     * Take what is waiting into the list, without announcing any of it (switching receiving on does not announce everything
     * from before). It goes in the list, not just past the cursor: anything that came during the seconds the address took
     * would otherwise be gone from this phone for good.
     */
    private suspend fun baseline() {
        if (store.cursor != 0L) return
        pullOnce("open", announce = false)
    }

    /**
     * Fetch what came since the last pull, keep it, and announce it (not when the app is being opened: the list shows it).
     * `why` is how the pull was set off. False when it did not work out and is worth trying again.
     */
    suspend fun catchUp(why: String, announce: Boolean = true): Boolean {
        if (store.tokenNow() == null || !store.receiveOn) return true
        return !pullOnce(why, announce).retry
    }

    /** What one pull came to: how many letters it brought or grew; if it could not be made, and whether that is worth trying again */
    class Pulled(val count: Int = 0, val failed: Boolean = false, val retry: Boolean = false)

    private var lastOpenPull = 0L

    /**
     * A pull that needs neither the background switch nor a push address: when the app opens or comes back, and when the person
     * asks. Nothing is announced (the list shows it). Opening and coming back within 30 seconds of each other is one pull.
     */
    suspend fun pullNow(why: String): Pulled {
        if (store.tokenNow() == null) return Pulled()
        if (why == "open") {
            val now = SystemClock.elapsedRealtime()
            if (lastOpenPull != 0L && now - lastOpenPull < OPEN_PULL_GAP_MS) return Pulled()
            lastOpenPull = now
        }
        return pullOnce(why, announce = false).also { if (it.failed) lastOpenPull = 0L }
    }

    private suspend fun pullOnce(why: String, announce: Boolean): Pulled {
        return pulling.withLock {
            val page = try {
                pullAll(store.cursor, why, client::send)
            } catch (e: ApiError) {
                if (e.failure == Failure.AUTH_LOST) store.forget()
                return@withLock Pulled(failed = true, retry = e.failure == Failure.UNAVAILABLE)
            } catch (e: OfflineError) {
                return@withLock Pulled(failed = true, retry = true)
            }
            // Nothing downloads by itself: a file is saved on a tap (Downloader), because each one counts against the day's downloads
            val arrival = arrive(page.items, store.deviceId, letters.all.value, store.cursor, unmetered = false, retentionMs = runCatching { retention() }.getOrNull(), cellularMax = -1)
            arrival.letters.forEach(letters::add)
            store.cursor = arrival.cursor
            // A notice about a file opens the app, where it is saved on a tap
            val withFiles = arrival.letters.filter { it.files.isNotEmpty() }.map { it.id }.toSet()
            if (announce) arrival.notices.forEach { n ->
                arrivals.show(if (n.id in withFiles) n.copy(actions = emptyList(), opensApp = true) else n)
            }
            Pulled(count = arrival.letters.size)
        }
    }
}
