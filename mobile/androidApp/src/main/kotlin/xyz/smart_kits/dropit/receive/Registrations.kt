package xyz.smart_kits.dropit.receive

/**
 * What has been registered with the system and not yet taken off (the connection's watch on the network). Each is taken off once, however
 * many times it is asked: a watch that was left registered keeps the system calling it, and one taken off twice is an error.
 */
class Registrations<T : Any> {
    private val held = mutableSetOf<T>()

    @Synchronized
    fun hold(item: T) { held += item }

    /** Takes [item] off by [release], once. False when it was not held (already taken off, or never registered) */
    @Synchronized
    fun release(item: T, release: (T) -> Unit): Boolean {
        if (!held.remove(item)) return false
        release(item)
        return true
    }

    val count: Int @Synchronized get() = held.size
}
