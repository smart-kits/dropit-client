package xyz.smart_kits.dropit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import xyz.smart_kits.dropit.shared.ApiError
import xyz.smart_kits.dropit.shared.DropitClient
import xyz.smart_kits.dropit.shared.Failure
import xyz.smart_kits.dropit.shared.OfflineError
import xyz.smart_kits.dropit.shared.Peer
import xyz.smart_kits.dropit.shared.Requests
import xyz.smart_kits.dropit.shared.Targets
import xyz.smart_kits.dropit.shared.peersFrom
import xyz.smart_kits.dropit.shared.receiversOf
import xyz.smart_kits.dropit.shared.reconcileDefault

/**
 * The devices on the account, and who a send goes to when nothing is chosen. The list is asked for when the panel opens and when the
 * sent list is shown (never behind the person's back on a timer); the last answer is kept, so the panel has something to show offline.
 * Each time it is read, the saved default is checked against it: devices that were removed drop out (the person is told once), and if
 * none of the default is left it is everyone again. What the choice is, is kept on this phone only.
 */
class Peers(private val store: Store, private val client: DropitClient) {
    /** [peers] null: never listed. [failed]: the last try did not work (what is kept stays). [at]: when the list was read, epoch ms */
    data class State(val peers: List<Peer>? = null, val at: Long = 0, val failed: Boolean = false)

    private val shown = MutableStateFlow(State(peers = cached()))
    val state: StateFlow<State> get() = shown

    private val chosen = MutableStateFlow(store.defaultTo)
    /** The default: device ids, empty = everyone */
    val defaultTo: StateFlow<List<String>> get() = chosen

    private val told = MutableStateFlow(store.targetsNote)
    /** Something about the default the person has not been told: `removed:<n>` or `reset` */
    val note: StateFlow<String?> get() = told

    private val asking = Mutex()

    private fun cached(): List<Peer>? = store.peersJson?.let { runCatching { peersFrom(Json.parseToJsonElement(it).jsonObject) }.getOrNull() }

    /** The devices something can be sent to, as far as they are known */
    fun receivers(peers: List<Peer>? = shown.value.peers): List<Peer> = receiversOf(peers.orEmpty(), store.deviceId)

    /**
     * Ask the service for the devices. With [maxAgeMs] a list read that recently is not asked for again. True when the list is current
     * (just read, or recent enough); on a failure the last list stays and [State.failed] says so.
     */
    suspend fun refresh(maxAgeMs: Long = 0): Boolean = asking.withLock {
        val now = System.currentTimeMillis()
        val have = shown.value
        if (maxAgeMs > 0 && have.peers != null && !have.failed && now - have.at < maxAgeMs) return@withLock true
        try {
            val data = client.send(Requests.devices()).data
            store.peersJson = data.toString()
            val peers = peersFrom(data)
            shown.value = State(peers, now, failed = false)
            reconcile(peers)
            true
        } catch (e: ApiError) {
            if (e.failure == Failure.AUTH_LOST) store.forget(e.sentence().key)
            shown.value = have.copy(failed = true)
            false
        } catch (e: OfflineError) {
            shown.value = have.copy(failed = true)
            false
        }
    }

    private fun reconcile(peers: List<Peer>) {
        val saved = store.defaultTo
        if (saved.isEmpty()) return
        val r = reconcileDefault(saved, receiversOf(peers, store.deviceId))
        if (r.ids != saved) setDefault(r.ids)
        if (r.removed > 0) say(if (r.fellBackToAll) "reset" else "removed:${r.removed}")
    }

    private fun say(note: String) {
        store.targetsNote = note
        told.value = note
    }

    /** The person has read it */
    fun noteRead() {
        store.targetsNote = null
        told.value = null
    }

    /** Save the default (empty = everyone) */
    fun setDefault(ids: List<String>) {
        store.defaultTo = ids
        chosen.value = store.defaultTo
    }

    /** The service says the devices a send was addressed to are not there: the default is everyone again, and the person is told */
    fun defaultGone() {
        setDefault(emptyList())
        say("reset")
    }

    /** Signed out: what was known about another account's devices goes */
    fun reset() {
        shown.value = State()
        chosen.value = emptyList()
        told.value = null
    }

    /** What a send goes to when nothing is chosen, with the names that are known */
    fun defaultTargets(): Targets {
        val ids = chosen.value
        if (ids.isEmpty()) return Targets.ALL
        val byId = shown.value.peers.orEmpty().associateBy { it.id }
        return Targets(ids, ids.mapNotNull { byId[it]?.name })
    }
}
