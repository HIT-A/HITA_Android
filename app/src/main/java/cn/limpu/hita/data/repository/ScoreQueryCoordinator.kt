package cn.limpu.hita.data.repository

import cn.limpu.hita.data.source.preference.EasQuerySessionState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Per score owner/key ordering; unrelated terms and query types do not cancel one another. */
internal class ScoreQueryCoordinator private constructor(
) {
    private val generations = ConcurrentHashMap<String, AtomicLong>()

    class Ticket internal constructor(
        private val generation: AtomicLong,
        private val value: Long,
    ) {
        fun isCurrent(): Boolean = generation.get() == value
    }

    fun begin(owner: String?, key: String): Ticket? {
        val ownerKey = owner ?: return null
        val generation = generations.computeIfAbsent("$ownerKey|$key") { AtomicLong() }
        return Ticket(generation, generation.incrementAndGet())
    }

    companion object {
        private val coordinators = mutableMapOf<EasQuerySessionState, ScoreQueryCoordinator>()

        fun forSession(state: EasQuerySessionState): ScoreQueryCoordinator = synchronized(coordinators) {
            coordinators.getOrPut(state) { ScoreQueryCoordinator() }
        }
    }
}
