package xyz.smart_kits.dropit.shared

import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** What the app keeps for the client: its key and the service addresses that worked */
interface ClientStore {
    suspend fun token(): String?
    suspend fun endpoints(): List<String>?
    suspend fun setEndpoints(list: List<String>)
}

/** A file to send: opened again for every attempt, so a retry starts from the beginning */
class FileBody(val length: Long, val open: () -> ByteReadChannel)

/** After a connection dropped on the way (a flaky proxy), the same address is tried again after these waits */
val RETRY_MS = listOf(400L, 1200L)

/**
 * How long the first try of an ordinary request (not a file) may take; each later try gets one more of these (10 s, 20 s, 30 s). A try
 * that needs a new connection is slow on a bad network, so the first is short only to find a dead connection quickly, and the
 * ones after it wait long enough for a new connection to be made. A stuck request costs seconds, not minutes.
 */
const val ATTEMPT_MS = 10_000L

private val MONOTONIC_ORIGIN = kotlin.time.TimeSource.Monotonic.markNow()

/** The warm-up gives up after this long (it is never waited for) */
const val WARM_TIMEOUT_MS = 15_000L

/** One try ran out of time: nothing came back */
class AttemptTimeout : Exception("no answer in time")

/**
 * The only layer between a phone app and the dropit service. Falls back to the next address and remembers
 * the one that works; a server's answer (even an error) is never a reason to try another address.
 */
/** [block] with the per-try deadline, unless it is a file on its way (those take as long as they take) */
private suspend fun <T> withDeadline(on: Boolean, ms: Long, block: suspend () -> T): T =
    // On the real clock (a test's virtual time must not make a slow answer look like a quiet one)
    if (!on) block() else withContext(Dispatchers.Default) { withTimeoutOrNull(ms) { block() } } ?: throw AttemptTimeout()

class DropitClient(
    private val http: HttpClient,
    private val store: ClientStore,
    private val defaults: List<String> = listOf(DEFAULT_ENDPOINT),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val attemptMs: Long = ATTEMPT_MS,
    /** One line per try (path, which try, how it ended, how long): for finding out where a slow request spends its time. Never a token or a body */
    private val trace: (String) -> Unit = {},
    /** Milliseconds on a clock that only goes forward: what the warm-up's spacing is measured on */
    private val clock: () -> Long = { MONOTONIC_ORIGIN.elapsedNow().inWholeMilliseconds },
) {
    private var lastWarmAt: Long? = null

    /**
     * Open a connection to the service before one is needed (the person is still choosing or writing), by asking for a tiny file
     * that is just there for this: no key, nothing sent, nothing kept. Quiet: never more than once in [WARM_GAP_MS], only the
     * first address, no second try, no other address, and any failure is swallowed — this is not a send. True when it connected.
     */
    suspend fun warm(): Boolean {
        val now = clock()
        if (!warmDue(lastWarmAt, now)) return false
        lastWarmAt = now
        val spec = Requests.warm()
        val url = endpointList(store.endpoints(), defaults).first().trimEnd('/') + spec.target
        val began = kotlin.time.TimeSource.Monotonic.markNow()
        return try {
            val status = withDeadline(true, WARM_TIMEOUT_MS) {
                val res = http.request(url) { method = HttpMethod.Get }
                res.bodyAsText()
                res.status.value
            }
            trace("warm: $status in ${began.elapsedNow().inWholeMilliseconds} ms")
            status in 200..299
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            lastWarmAt = null     // nothing was warmed: the next chance may try
            trace("warm: ${e::class.simpleName}")
            false
        }
    }

    suspend fun send(spec: RequestSpec, file: FileBody? = null): Answer {
        val endpoints = endpointList(store.endpoints(), defaults)
        val token = if (spec.auth) store.token() else null
        var lastErr: Throwable? = null
        for (base in endpoints) {
            val url = base.trimEnd('/') + spec.target
            val (status, text) = try {
                attempt(spec, url, token, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastErr = e
                continue
            }
            if (base != endpoints.first()) store.setEndpoints(listOf(base) + endpoints.filter { it != base })
            return parse(status, text)
        }
        throw OfflineError(lastErr)
    }

    private suspend fun attempt(spec: RequestSpec, url: String, token: String?, file: FileBody?): Pair<Int, String> {
        var tries = 0
        while (true) {
            try {
                val began = kotlin.time.TimeSource.Monotonic.markNow()
                var serverTime: Long? = null
                val answer = withDeadline(file == null, attemptMs * (tries + 1)) { val res = http.request(url) {
                    method = HttpMethod.parse(spec.method)
                    if (token != null) header("authorization", "Bearer $token")
                    when {
                        file != null -> setBody(object : OutgoingContent.ReadChannelContent() {
                            override val contentLength = file.length
                            override val contentType = spec.contentType?.let { ContentType.parse(it) }
                            override fun readFrom() = file.open()
                        })
                        spec.body != null -> {
                            header("content-type", "application/json")
                            setBody(spec.body.toString())
                        }
                    }
                }
                    serverTime = serverMs(res.headers["server-timing"])
                    res.status.value to res.bodyAsText()
                }
                // The service's own part of the time, when it says it: what is left is the network and making the connection
                val inService = serverTime?.let { " (service $it ms)" }.orEmpty()
                trace("${spec.method} ${spec.path.substringBefore('?')} try ${tries + 1}: ${answer.first} in ${began.elapsedNow().inWholeMilliseconds} ms$inService")
                return answer
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                trace("${spec.method} ${spec.path.substringBefore('?')} try ${tries + 1}: ${e::class.simpleName}")
                // A request that never got a connection was never sent: trying again cannot do it twice, even for creating or joining
                // Said again: only what is safe to send twice is tried again after it ran out of time (it may have got there)
                if (!(spec.retriable || e is ConnectTimeoutException) || tries >= RETRY_MS.size) throw e
                sleep(RETRY_MS[tries++])
            }
        }
    }
}
