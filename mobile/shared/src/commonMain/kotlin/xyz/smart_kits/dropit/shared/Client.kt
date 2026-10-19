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
) {
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
                    res.status.value to res.bodyAsText()
                }
                trace("${spec.method} ${spec.path.substringBefore('?')} try ${tries + 1}: ${answer.first} in ${began.elapsedNow().inWholeMilliseconds} ms")
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
