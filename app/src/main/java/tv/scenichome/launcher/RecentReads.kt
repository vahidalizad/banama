package tv.scenichome.launcher

import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RecentReads<K, V : Any>(
    private val limit: Int,
    private val lifetimeMs: Long,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private class Entry<V> {
        val lock = Mutex()
        var value: V? = null
        var expiresAt = 0L
    }

    private val entries = LinkedHashMap<K, Entry<V>>(limit, .75f, true)

    suspend fun get(key: K, read: suspend () -> V): V {
        val entry = synchronized(entries) {
            entries.getOrPut(key) { Entry() }.also {
                if (entries.size > limit) entries.remove(entries.keys.first())
            }
        }
        return entry.lock.withLock {
            entry.value?.takeIf { now() < entry.expiresAt } ?: read().also {
                entry.value = it
                entry.expiresAt = now() + lifetimeMs
            }
        }
    }

    fun clear() = synchronized(entries) { entries.clear() }
}
