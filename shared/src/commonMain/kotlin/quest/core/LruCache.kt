package quest.core

/**
 * MH3: the smallest least-recently-used map the app needs — bounded by **entry count**, not by bytes.
 *
 * It exists because a decoded image must not be kept for the life of the process. The app's older picture caches are
 * plain `HashMap`s (`StopImages`, `SchoolLogo`), which is fair for small authored assets and wrong for a weekly plan:
 * the server takes a 5 MB photograph, and a parent scrolling her archive would otherwise keep every week she opened.
 *
 * A [get] or a [put] makes an entry the most recently used; the least recently used is dropped when [maxEntries] is
 * exceeded. Not thread-safe — callers hold it behind composition on the main thread, the way the caches it replaces do.
 */
class LruCache<K, V>(private val maxEntries: Int) {
    init { require(maxEntries > 0) { "an LRU of $maxEntries entries would cache nothing" } }

    private val entries = LinkedHashMap<K, V>()

    val size: Int get() = entries.size

    /** The keys held, least recently used first — the order [put] evicts in. */
    val keys: List<K> get() = entries.keys.toList()

    operator fun get(key: K): V? {
        val found = entries.remove(key) ?: return null
        entries[key] = found
        return found
    }

    operator fun set(key: K, value: V) {
        entries.remove(key)
        entries[key] = value
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    fun clear() = entries.clear()
}
