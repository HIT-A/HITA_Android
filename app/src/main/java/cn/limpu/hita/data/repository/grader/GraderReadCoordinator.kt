package cn.limpu.hita.data.repository.grader

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared local loading only. Remote refresh remains an explicit action of the homework page. */
class GraderReadCoordinator internal constructor(
    private val reload: suspend () -> Boolean,
    private val generation: () -> Long,
) {
    private val consumers = mutableSetOf<String>()
    private var period = 0L
    private val mutex = Mutex()
    @Volatile private var completionVersion = 0L
    private var completed: Pair<Pair<Long, Long>, Boolean>? = null

    @Synchronized fun setVisible(consumer: String, visible: Boolean) {
        if (visible) {
            if (consumers.isEmpty()) period++
            consumers += consumer
        } else consumers -= consumer
    }

    @Synchronized private fun key(consumer: String): Pair<Long, Long>? =
        if (consumer in consumers) period to generation() else null

    /** Waiters reuse both success and failure; only retry or a new period/generation loads again. */
    suspend fun load(consumer: String, retry: Boolean = false): Boolean {
        val requested = key(consumer) ?: return false
        val observedVersion = completionVersion
        return mutex.withLock {
            if (key(consumer) != requested) return@withLock false
            completed?.takeIf {
                it.first == requested && (!retry || completionVersion != observedVersion)
            }?.let { return@withLock it.second }
            val success = reload()
            if (key(consumer) != requested) return@withLock false
            completed = requested to success
            completionVersion++
            success
        }
    }
}
