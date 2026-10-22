package xyz.smart_kits.dropit.work

/**
 * The drops held before this phone joined, by id. Adding one and taking them all each read the set and write it back, so they are done
 * one at a time: two drops arriving together (two shares at once) must both be kept, and a drop added while the others are being taken
 * must not be taken away with them unsent, nor lost.
 */
class WaitingSet(private val read: () -> Set<String>, private val write: (Set<String>) -> Unit) {
    @Synchronized
    fun add(id: String) { write(read() + id) }

    /** All of them, leaving none */
    @Synchronized
    fun take(): Set<String> {
        val ids = read()
        if (ids.isNotEmpty()) write(emptySet())
        return ids
    }
}
