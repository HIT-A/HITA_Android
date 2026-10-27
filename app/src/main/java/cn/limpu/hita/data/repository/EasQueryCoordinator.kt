package cn.limpu.hita.data.repository

import cn.limpu.hita.data.source.dao.ScoreCacheDao
import cn.limpu.hita.data.source.preference.EasQuerySessionState
import cn.limpu.hita.utils.LogUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-shared ordering for each actual cache owner, including manually created repositories. */
internal class EasQueryCoordinator private constructor(private val sessionState: EasQuerySessionState) {
    private class OwnerLane(pending: Long) {
        val execution = AtomicLong()
        val invalidation = AtomicLong(pending)
        val cleanupLock = Any()
    }

    class Ticket internal constructor(private val current: AtomicLong, private val value: Long) {
        fun isCurrent(): Boolean = current.get() == value
    }

    private val owners = ConcurrentHashMap<String, OwnerLane>()
    private val markerLock = Any()
    val autoImportInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun lane(owner: String): OwnerLane = owners.computeIfAbsent(owner) {
        OwnerLane(sessionState.storage.getLong(PENDING_PREFIX + owner, 0L))
    }

    fun begin(owner: String?): Ticket? = owner?.let {
        val execution = lane(it).execution
        Ticket(execution, execution.incrementAndGet())
    }

    fun hasPendingCleanup(owner: String): Boolean = lane(owner).invalidation.get() != 0L

    /** Must succeed before clearing credentials; pending markers survive process death and token clear. */
    fun invalidateOwner(owner: String) = synchronized(markerLock) {
        val lane = lane(owner)
        lane.execution.incrementAndGet()
        val version = maxOf(System.currentTimeMillis(), lane.invalidation.get() + 1L)
        lane.invalidation.set(version)
        check(sessionState.storage.edit().putLong(PENDING_PREFIX + owner, version).commit()) {
            "Unable to persist pending EAS cache cleanup"
        }
    }

    fun scheduleCleanup(owner: String, dao: ScoreCacheDao) {
        applicationScope.launch { cleanOwnerSync(owner, dao) }
    }

    fun replayPendingCleanup(dao: ScoreCacheDao) {
        sessionState.storage.all.keys.filter { it.startsWith(PENDING_PREFIX) }.forEach {
            scheduleCleanup(it.removePrefix(PENDING_PREFIX), dao)
        }
    }

    /** No token/marker lock is held while waiting for SQLite. Failure blocks cache, not remote IO. */
    fun cleanOwnerSync(owner: String, dao: ScoreCacheDao): Boolean {
        val lane = lane(owner)
        return synchronized(lane.cleanupLock) cleanup@{
            try {
                while (true) {
                    val version = lane.invalidation.get()
                    if (version == 0L) return@cleanup true
                    dao.clearOwnerSync(owner) { lane.invalidation.get() == version }
                    synchronized(markerLock) {
                        if (lane.invalidation.get() == version) {
                            if (!sessionState.storage.edit().remove(PENDING_PREFIX + owner).commit()) {
                                return@cleanup false
                            }
                            lane.invalidation.set(0L)
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } catch (failure: Exception) {
                LogUtils.e("Unable to clear pending EAS cache", failure)
                false
            }
        }
    }

    companion object {
        private const val PENDING_PREFIX = "pending_owner:"
        private val coordinators = mutableMapOf<EasQuerySessionState, EasQueryCoordinator>()
        fun forSession(state: EasQuerySessionState): EasQueryCoordinator = synchronized(coordinators) {
            coordinators.getOrPut(state) { EasQueryCoordinator(state) }
        }
    }
}
