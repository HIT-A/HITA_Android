package cn.limpu.hita.data.repository

import android.app.Application
import android.util.Log
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.TimetableChangeOutboxEntity
import com.google.gson.Gson
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Callable

/**
 * The outer Room transaction stores both course changes and their pending file publications.
 * A committed outbox row is replayable after any file failure or application reconstruction.
 */
internal class TimetableChangeRecovery(
    application: Application,
    private val database: AppDatabase,
    private val changeStore: TimetableChangeStore = TimetableChangeStore(application),
    private val snapshotStore: TimetableSnapshotStore = TimetableSnapshotStore(application, database),
    private val mutationLock: TimetableMutationLock = TimetableMutationLock(),
) {
    private data class Payload(
        val version: Int = 1,
        val changes: TimetableChangeStore.Delta? = null,
        val snapshots: List<TimetableVersionSnapshot>? = null,
    )

    private val gson = Gson()
    private val outbox = database.timetableChangeOutboxDao()

    /**
     * This method must own COMMIT. Ordinary nested DAO transactions inside [block] are supported;
     * calling it from an existing outer transaction would publish files too early and is rejected.
     */
    fun <T> commit(block: () -> T): T = mutationLock.exclusive {
        check(!database.inTransaction()) { "Timetable recovery must own the outer transaction" }
        check(recoverLocked()) { "Previous timetable changes are awaiting file recovery" }
        val result = changeStore.stage {
            snapshotStore.stage {
                database.runInTransaction(Callable {
                    val value = block()
                    val delta = changeStore.stagedDelta()
                    val snapshots = snapshotStore.stagedSnapshots()
                    if (delta.before != delta.after || snapshots.isNotEmpty()) {
                        outbox.insert(TimetableChangeOutboxEntity(
                            operationId = UUID.randomUUID().toString(),
                            payload = gson.toJson(Payload(changes = delta, snapshots = snapshots)),
                            createdAtMillis = System.currentTimeMillis(),
                        ))
                    }
                    value
                })
            }
        }
        // The course transaction has succeeded. File/ack failure retains the durable row and must
        // not be reported as a course rollback (or invite a second adoption of the stale decision).
        recoverLocked()
        result
    }

    fun recover(): Boolean = mutationLock.exclusive {
        check(!database.inTransaction()) { "Cannot recover timetable files within a transaction" }
        recoverLocked()
    }

    private fun recoverLocked(): Boolean {
        try {
            for (operation in outbox.pending()) {
                val payload = gson.fromJson(operation.payload, Payload::class.java)
                    ?: throw IOException("Invalid timetable outbox payload")
                if (payload.version != 1 || payload.changes == null || payload.snapshots == null) {
                    throw IOException("Unsupported timetable outbox payload")
                }
                snapshotStore.publishCommitted(requireNotNull(payload.snapshots))
                changeStore.applyCommitted(operation.operationId, requireNotNull(payload.changes))
                outbox.acknowledge(operation.operationId)
            }
        } catch (failure: Exception) {
            Log.e(TAG, "Committed timetable files await recovery", failure)
            return false
        }
        // Marker pruning is optional maintenance after durable acknowledgement. A failure here
        // cannot invalidate a completed publication or allow an unacknowledged row to repeat.
        try {
            changeStore.pruneAppliedOperations(outbox.pending().mapTo(hashSetOf()) { it.operationId })
        } catch (failure: Exception) {
            Log.w(TAG, "Timetable recovery marker cleanup deferred", failure)
        }
        return true
    }

    private companion object {
        const val TAG = "TimetableChangeRecovery"
    }
}
