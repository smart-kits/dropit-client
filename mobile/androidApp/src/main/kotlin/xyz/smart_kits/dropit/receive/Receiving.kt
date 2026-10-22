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
import xyz.smart_kits.dropit.shared.NoAddress
import xyz.smart_kits.dropit.shared.NoticeAction
import xyz.smart_kits.dropit.shared.autoClipLetter
import xyz.smart_kits.dropit.shared.noticeOnPhone
import xyz.smart_kits.dropit.ui.sensitiveClip
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.shared.probeOfError
import xyz.smart_kits.dropit.shared.pullAll
import xyz.smart_kits.dropit.shared.whenNoAddress
import xyz.smart_kits.dropit.shared.realtimeRefusal
import xyz.smart_kits.dropit.shared.Message
import kotlin.coroutines.resume

/** What background receiving comes to on this phone, for the settings row */
sealed interface ReceiveStatus {
    /** Switched off by the person */
    data object Off : ReceiveStatus
    /** Not found out yet, or no network to find out with */
    data object Unknown : ReceiveStatus
    /** A test push got through */
    data object Push : ReceiveStatus
    /** Not used any more: with no system push the phone keeps a connection instead ([Connection]); kept for a state written by an older version */
    data object NoPush : ReceiveStatus
    /**
     * No system push gets through on this phone, so a connection is kept open instead (a foreground service, with a quiet notice).
     * [connected]: it is open right now. When it is not, [retryAtMs] is when the next try is (epoch ms), or null while there is no network.
     * Whether the battery setting will let it live is separate: see [ReceiveStatus.batteryLimited].
     */
    data class Connection(val connected: Boolean, val retryAtMs: Long? = null) : ReceiveStatus
    /**
     * The service said real-time is not this account's right now. [why]`.key` tells which:
     * `realtimeOver` (the free trial is over: stays until the account changes), `realtimeFull` (today's places are taken: comes back after
     * midnight UTC by itself), `ticketsDay` (this phone used its connections for today: same). In the last two the service keeps waiting.
     */
    data class Refused(val why: Message) : ReceiveStatus
}

/**
 * Whether to warn that the battery setting may stop the connection: on a connection, and the app is not on the list of apps that run
 * without battery limits ([unrestricted] = `App.runsInBackground`). Locking the screen can then cut the connection off.
 */
fun ReceiveStatus.batteryLimited(unrestricted: Boolean): Boolean = this is ReceiveStatus.Connection && !unrestricted

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
    val pushAvailable: Boolean get() = BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank()

    /** Background receiving can be offered on every build: by system push where this build has it and it gets through, otherwise by a connection */
    val available: Boolean get() = true

    /** The connection, for when the system push is not there or does not get through (see [Live]) */
    val live = Live(context, store, client, this)

    private val state = MutableStateFlow(read())
    val status: StateFlow<ReceiveStatus> get() = state

    private val pulling = Mutex()
    @Volatile private var waiting: CompletableDeferred<Unit>? = null

    fun init() {
        if (!pushAvailable || FirebaseApp.getApps(context).isNotEmpty()) return
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
            s == "connection" || s == "none" -> ReceiveStatus.Connection(connected = live.running)
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
        if (!pushAvailable || !store.receiveOn || store.tokenNow() == null) return false
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
            // Push the service cannot send at all counts as no push here (a connection then); the key being no good, or the service
            // being down, says nothing about this phone: try again later
            return probeOfError(e)
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
        // On at once: the answer below can take as long as the push address does (up to TOKEN_WAIT_MS), and a switch that
        // stays off meanwhile looks as if it was not pressed
        if (state.value == ReceiveStatus.Off) state.value = ReceiveStatus.Unknown
        if (!pushAvailable) {
            // No push in this build: a connection is the only way
            if (!context.online()) return@withLock ReceiveStatus.Unknown.also { set(it, null) }
            return@withLock useConnection()
        }
        val address = token()
        // Switched off again while the address was being waited for: that is the last word (disable() does not wait for this lock)
        if (!store.receiveOn) return@withLock ReceiveStatus.Off.also { state.value = it }
        if (address == null) {
            // No address. With no network that says nothing about push: asked again on the next start. With a network, push does
            // not get through here (Google's services are missing, or cannot be reached): a connection takes its place
            return@withLock when (whenNoAddress(context.online())) {
                NoAddress.RETRY_LATER -> ReceiveStatus.Unknown.also { set(it, null) }
                NoAddress.CONNECTION -> useConnection()
            }
        }
        if (!register(address)) return@withLock ReceiveStatus.Unknown.also { set(it, null) }
        baseline()
        val probe = probe() ?: return@withLock ReceiveStatus.Unknown.also { set(it, null) }
        store.probeAt = System.currentTimeMillis()
        store.probeNet = context.networkKind()
        val choice = chooseChannel(on = true, probe = probe)
        when {
            choice.channel == Channel.PUSH -> { stopLive(); ReceiveStatus.Push.also { set(it, "push") } }
            choice.why != null -> { stopLive(); ReceiveStatus.Refused(choice.why!!).also { set(it, "refused:${choice.why!!.key}") } }
            else -> useConnection()
        }
    }

    /** Keep a connection open instead of relying on push: what is waiting is set aside first, then the foreground service holds it */
    private suspend fun useConnection(): ReceiveStatus {
        baseline()
        val status = ReceiveStatus.Connection(connected = (state.value as? ReceiveStatus.Connection)?.connected == true)
        set(status, "connection")
        startLive()
        return status
    }

    /** The connection's own start: asked at launch and after a restart, when the last result was "connection" */
    fun resumeLive() {
        if (store.receiveOn && store.tokenNow() != null && store.receiveState.let { it == "connection" || it == "none" }) startLive()
    }

    private fun startLive() { ReceiveService.start(context) }
    private fun stopLive() { ReceiveService.stop(context) }

    /** The service has run the connection into the ground for good (the key is no longer good): nothing to keep running */
    internal fun liveEnded() { stopLive() }

    /** What the connection is doing, for the settings row; a state only (the saved result stays "connection") */
    internal fun liveStatus(connected: Boolean, retryAtMs: Long?) {
        if (!store.receiveOn) return
        state.value = ReceiveStatus.Connection(connected, retryAtMs)
    }

    /** The connection was refused: [final] when it will not be tried again by itself (the trial is over); else it waits (until midnight UTC) */
    internal fun liveRefused(why: Message, final: Boolean) {
        if (!store.receiveOn) return
        if (final) {
            set(ReceiveStatus.Refused(why), "refused:${why.key}")
            stopLive()
        } else {
            state.value = ReceiveStatus.Refused(why)
        }
    }

    /** A pull for the connection: after it (re)connects and when it says something is new. Where it can not finish, a retry is queued */
    internal suspend fun liveCatchUp(why: String) {
        if (!catchUp(why)) CatchUpWorker.enqueue(context)
    }

    /** The network changed to a kind that may let push through: test again (a phone on a connection does not test more than once a day per kind of network) */
    suspend fun reprobe() {
        if (store.receiveOn && state.value is ReceiveStatus.Connection) enable()
    }

    /**
     * What `/v1/me` says about real-time, for a phone that was refused because the trial is over: when the account has it again (it
     * was bought), receiving is switched on again by itself. Asked about at most once a day: the caller is the app's start.
     */
    suspend fun onAccount(realtimeAvailable: Boolean?) {
        if (realtimeAvailable == true && store.receiveOn && store.receiveState == "refused:realtimeOver") enable()
    }

    /** Switch it off: the service stops pushing to this phone */
    suspend fun disable() {
        store.receiveOn = false
        state.value = ReceiveStatus.Off
        stopLive()
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

    /** Writing the clipboard is allowed in the background; what dropit put there itself is not offered back as "new on your clipboard" */
    private fun putOnClipboard(text: String) {
        runCatching { context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(sensitiveClip(text)) }
        store.clipSeen = System.currentTimeMillis()
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
            // Text from another device goes on the clipboard when the person asked for that (only the newest of what came); its notice
            // then says so instead of offering Copy
            val clipped = if (announce && store.autoClip) autoClipLetter(arrival.letters)?.also { putOnClipboard(it.text.orEmpty()) } else null
            // A notice about one file has a Download button; about several, it opens the app, where they are saved on a tap
            if (announce) arrival.notices.forEach { n ->
                val letter = arrival.letters.firstOrNull { it.id == n.id }
                val shown = if (letter != null) noticeOnPhone(letter, n) else n
                arrivals.show(
                    if (clipped != null && n.id == clipped.id) shown.copy(title = context.getString(R.string.auto_copied), actions = shown.actions.filter { it != NoticeAction.COPY })
                    else shown,
                )
            }
            Pulled(count = arrival.letters.size)
        }
    }
}
