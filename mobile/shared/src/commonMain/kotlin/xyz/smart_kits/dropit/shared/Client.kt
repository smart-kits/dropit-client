package xyz.smart_kits.dropit.shared

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

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
 * The only layer between a phone app and the dropit service. Falls back to the next address and remembers
 * the one that works; a server's answer (even an error) is never a reason to try another address.
 */
class DropitClient(
    private val http: HttpClient,
    private val store: ClientStore,
    private val defaults: List<String> = listOf(DEFAULT_ENDPOINT),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
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
                val res = http.request(url) {
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
                return res.status.value to res.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!spec.retriable || tries >= RETRY_MS.size) throw e
                sleep(RETRY_MS[tries++])
            }
        }
    }
}
