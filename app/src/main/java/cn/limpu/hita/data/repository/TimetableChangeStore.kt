package cn.limpu.hita.data.repository

import android.app.Application
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/** 一次已应用刷新的变更摘要（用户点开详情即清除）。 */
data class TimetableChangeInfo(
    val updatedAtMillis: Long,
    val updated: List<CourseChange>,
    val added: List<String>,
    val keptCourses: List<KeptCourse>
)

/** 一条待用户确认的课程决策。 */
data class TimetableDecisionItem(
    val termId: String,
    val timetableId: String,
    val courseKey: String,
    val subjectId: String,
    val name: String,
    val reason: CourseVetoReason,
    val localLessonCount: Int,
    val firstSeenMillis: Long,
    val observationCount: Int,
    /** 源端该课的数据；整门缺失时为 null（采纳=删除本地课）。 */
    val incoming: MergeCourse?
)

/** 整批挂起的源端数据（低重叠时保留，等用户决定采用还是放弃）。 */
data class TimetableHeldBatch(
    val createdAtMillis: Long,
    val termId: String,
    val timetableId: String,
    val timetableName: String,
    val timetableCode: String,
    val startMillis: Long,
    val schedule: List<TimePeriodInDay>,
    val localCount: Int,
    val incomingCount: Int,
    val matchedCount: Int,
    val courses: List<MergeCourse>,
    /** 源端未返回的本地课；用户保留当前课表后据此建立 veto 追踪。 */
    val unmatchedLocal: List<MergeCourse> = emptyList()
)

data class TimetableChangeState(
    val info: TimetableChangeInfo? = null,
    val decisions: List<TimetableDecisionItem> = emptyList(),
    val heldBatch: TimetableHeldBatch? = null,
    val pendingUpdates: List<PendingCourseUpdate> = emptyList(),
    val pendingRevision: Long = 0L,
) {
    val pendingCount: Int
        get() = pendingUpdates.sumOf { it.rows.size } +
            decisions.size + if (heldBatch != null) 1 else 0
}

/**
 * 课表变更的持久化存储。
 *
 * 职责：
 * - 变更摘要（info）：刷新采纳了哪些改动，用户查看后清除；
 * - 待确认决策（decisions）：持续缺失/削减的课与整批异常的源端数据，用户决策后逐项清除；
 * - veto 记忆：按学期记录课程被源端遗漏的观察次数，支撑"3 次且 48 小时才升级确认"。
 *
 * 与 [TimetableSnapshotStore] 一样落盘为 JSON 文件（载荷可能较大，不用 SharedPreferences），
 * 内存里持有一份完整状态，对外暴露 [TimetableChangeState] 的 LiveData 供课表界面观察。
 */
@Singleton
class TimetableChangeStore @Inject constructor(application: Application) {

    private data class PersistedState(
        val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
        val info: TimetableChangeInfo? = null,
        val decisions: List<TimetableDecisionItem> = emptyList(),
        val heldBatch: TimetableHeldBatch? = null,
        val vetoes: Map<String, Map<String, CourseVetoRecord>> = emptyMap(),
        val pendingUpdates: List<PendingCourseUpdate> = emptyList(),
        val ignoredFingerprints: List<String> = emptyList(),
        val ignoredRevision: Long = 0L,
        val pendingRevision: Long = 0L,
        val appliedOperations: Set<String> = emptySet(),
    ) {
        fun toChangeState(): TimetableChangeState = TimetableChangeState(
            info = info?.let {
                TimetableChangeInfo(
                    updatedAtMillis = it.updatedAtMillis,
                    updated = it.updated.orEmpty().map { change ->
                        change.copy(slotChanges = change.slotChanges.orEmpty())
                    },
                    added = it.added.orEmpty(),
                    keptCourses = it.keptCourses.orEmpty()
                )
            },
            decisions = decisions.orEmpty(),
            heldBatch = heldBatch,
            pendingUpdates = pendingUpdates.orEmpty(),
            pendingRevision = pendingRevision,
        )
    }

    internal data class Delta(val before: String, val after: String)

    private class Draft(val before: PersistedState, var current: PersistedState = before)

    private class SharedState(initial: PersistedState, var loadFailure: Exception? = null) {
        var cached = initial
        val draft = ThreadLocal<Draft>()
        val liveData = MutableLiveData(initial.toChangeState())
    }

    private val gson = Gson()
    private val stateFile = File(application.filesDir, "timetable_change/state.json")
    private val atomicFile = TimetableAtomicFile(stateFile)
    // Manually constructed and injected facades must observe the same successful file publication.
    private val shared = synchronized(instances) {
        val path = stateFile.canonicalPath
        instances[path]?.get() ?: loadShared().also {
            instances[path] = WeakReference(it)
        }
    }
    private val lock get() = shared
    private val cached get() = shared.draft.get()?.current ?: shared.cached

    fun observeState(): LiveData<TimetableChangeState> = shared.liveData

    fun currentState(): TimetableChangeState = synchronized(lock) { cached.toChangeState() }

    /** File-only compound decisions publish once; failure leaves cache and observers unchanged. */
    internal fun <T> edit(block: () -> T): T = synchronized(lock) {
        if (shared.draft.get() != null) return@synchronized block()
        stage {
            val result = block()
            publish(cached)
            result
        }
    }

    /** Keep a draft until the caller has crossed the real outer Room COMMIT boundary. */
    internal fun <T> stage(block: () -> T): T = synchronized(lock) {
        check(shared.draft.get() == null) { "Nested timetable change staging" }
        ensureReadable()
        shared.draft.set(Draft(shared.cached))
        try {
            block()
        } finally {
            shared.draft.remove()
        }
    }

    internal fun stagedDelta(): Delta {
        val draft = checkNotNull(shared.draft.get()) { "Timetable changes are not staged" }
        return Delta(gson.toJson(draft.before), gson.toJson(draft.current))
    }

    /**
     * Apply only values that still match this transaction's before-image. A later refresh or
     * account-login reset must survive delayed recovery. The marker shares the same atomic write.
     */
    internal fun applyCommitted(operationId: String, delta: Delta) = synchronized(lock) {
        check(shared.draft.get() == null)
        ensureReadable()
        if (operationId in cached.appliedOperations) return@synchronized
        val before = decode(delta.before)
        val after = decode(delta.after)
        val current = cached
        val decisionBefore = before.decisions.associateBy { it.termId to it.courseKey }
        val decisionAfter = after.decisions.associateBy { it.termId to it.courseKey }
        val decisions = mergeUnchanged(
            current.decisions.associateBy { it.termId to it.courseKey }, decisionBefore, decisionAfter
        ).values.toList()
        val vetoes = current.vetoes.toMutableMap()
        (before.vetoes.keys + after.vetoes.keys).forEach { termId ->
            val merged = mergeUnchanged(
                current.vetoes[termId].orEmpty(), before.vetoes[termId].orEmpty(), after.vetoes[termId].orEmpty()
            )
            if (merged.isEmpty()) vetoes.remove(termId) else vetoes[termId] = merged
        }
        val ignoredUnchanged = current.ignoredRevision == before.ignoredRevision &&
            same(current.ignoredFingerprints, before.ignoredFingerprints)
        val pendingUnchanged = current.pendingRevision == before.pendingRevision &&
            same(current.pendingUpdates, before.pendingUpdates)
        publish(current.copy(
            info = if (same(current.info, before.info)) after.info else current.info,
            decisions = decisions,
            heldBatch = if (same(current.heldBatch, before.heldBatch)) after.heldBatch else current.heldBatch,
            vetoes = vetoes,
            pendingUpdates = if (pendingUnchanged) after.pendingUpdates else current.pendingUpdates,
            pendingRevision = if (pendingUnchanged) after.pendingRevision else current.pendingRevision,
            ignoredFingerprints = if (ignoredUnchanged) {
                after.ignoredFingerprints
            } else current.ignoredFingerprints,
            ignoredRevision = if (ignoredUnchanged) after.ignoredRevision else current.ignoredRevision,
            appliedOperations = current.appliedOperations + operationId,
        ))
    }

    /** Only call after Room has durably deleted every ID absent from [pendingIds]. */
    internal fun pruneAppliedOperations(pendingIds: Set<String>) = synchronized(lock) {
        val retained = cached.appliedOperations.intersect(pendingIds)
        if (retained != cached.appliedOperations) mutate { it.copy(appliedOperations = retained) }
    }

    private fun same(first: Any?, second: Any?): Boolean = gson.toJsonTree(first) == gson.toJsonTree(second)

    private fun <K, V> mergeUnchanged(current: Map<K, V>, before: Map<K, V>, after: Map<K, V>): Map<K, V> {
        val result = current.toMutableMap()
        (before.keys + after.keys).forEach { key ->
            if (!same(before[key], after[key]) && same(current[key], before[key])) {
                val replacement = after[key]
                if (replacement == null) result.remove(key) else result[key] = replacement
            }
        }
        return result
    }

    // ---------- veto 记忆 ----------

    fun getVetoes(termId: String): Map<String, CourseVetoRecord> =
        synchronized(lock) { cached.vetoes[termId].orEmpty() }

    fun putVetoes(termId: String, vetoes: Map<String, CourseVetoRecord>) =
        synchronized(lock) {
            mutate { state ->
                val next = state.vetoes.toMutableMap()
                if (vetoes.isEmpty()) next.remove(termId) else next[termId] = vetoes
                state.copy(vetoes = next)
            }
        }

    /** 用户选择"继续保留"：重置该课的观察窗口，避免立刻再次升级。 */
    fun resetVeto(termId: String, courseKey: String) = synchronized(lock) {
        mutate { state ->
            val termVetoes = state.vetoes[termId]?.toMutableMap() ?: return@mutate state
            termVetoes.remove(courseKey)
            val next = state.vetoes.toMutableMap()
            if (termVetoes.isEmpty()) next.remove(termId) else next[termId] = termVetoes
            state.copy(vetoes = next)
        }
    }

    // ---------- 变更摘要 ----------

    fun recordApplied(info: TimetableChangeInfo?, decisions: List<TimetableDecisionItem>) =
        synchronized(lock) {
            mutate { state ->
                state.copy(
                    info = info,
                    decisions = mergeDecisions(state.decisions, decisions)
                )
            }
        }

    fun ignoredFingerprints(): Set<String> =
        synchronized(lock) { cached.ignoredFingerprints.toSet() }

    fun recordPendingUpdates(updates: List<PendingCourseUpdate>) = synchronized(lock) {
        mutate { state ->
            state.copy(
                pendingUpdates = updates,
                // 单调递增：同一毫秒内的两次刷新也要能被 clearPendingUpdates 区分
                pendingRevision = maxOf(System.currentTimeMillis(), state.pendingRevision + 1),
            )
        }
    }

    /** 采纳结果提交后调用；期间若有新一批待确认写入（revision 已变）则保留新数据。 */
    fun clearPendingUpdates(revision: Long) = synchronized(lock) {
        if (cached.pendingRevision != revision || cached.pendingUpdates.isEmpty()) return@synchronized
        mutate { state -> state.copy(pendingUpdates = emptyList()) }
    }

    fun rememberIgnored(fingerprints: Collection<String>) = synchronized(lock) {
        if (fingerprints.isEmpty()) return@synchronized
        mutate { state ->
            state.copy(
                ignoredFingerprints = (state.ignoredFingerprints + fingerprints).distinct(),
                ignoredRevision = state.ignoredRevision + 1,
            )
        }
    }

    /**
     * 清空「记住忽略」指纹：重新登录后上游现状应重新提示一次，
     * 避免用户曾忽略的变更永远不再出现。vetoes/挂起批/待确认不受影响。
     */
    fun clearIgnoredFingerprints() = synchronized(lock) {
        // Even an empty reset is a newer login decision than an unpublished outbox addition.
        mutate { state -> state.copy(ignoredFingerprints = emptyList(), ignoredRevision = state.ignoredRevision + 1) }
    }

    fun markInfoViewed() = synchronized(lock) {
        mutate { state -> state.copy(info = null) }
    }

    // ---------- 待确认决策 ----------

    /**
     * 清理已回归源端的课程的旧待确认项：课重新出现后，针对它的"采用课表源"
     * 会误删已经回来的课，故每次刷新合并后调用。仅清理，不动其他学期的决策。
     */
    fun pruneResolvedDecisions(termId: String, resolvedCourseKeys: Set<String>) =
        synchronized(lock) {
            if (resolvedCourseKeys.isEmpty()) return@synchronized
            val hasStale = cached.decisions.any {
                it.termId == termId && it.courseKey in resolvedCourseKeys
            }
            if (!hasStale) return@synchronized
            mutate { state ->
                state.copy(
                    decisions = state.decisions.filterNot {
                        it.termId == termId && it.courseKey in resolvedCourseKeys
                    }
                )
            }
        }

    fun consumeDecision(termId: String, courseKey: String): TimetableDecisionItem? =
        synchronized(lock) {
            val item = cached.decisions.firstOrNull {
                it.termId == termId && it.courseKey == courseKey
            }
            if (item != null) {
                mutate { state ->
                    state.copy(
                        decisions = state.decisions.filterNot { it.courseKey == courseKey && it.termId == termId }
                    )
                }
            }
            item
        }

    // ---------- 整批挂起 ----------

    fun recordHeldBatch(batch: TimetableHeldBatch) = synchronized(lock) {
        mutate { state -> state.copy(heldBatch = batch) }
    }

    fun consumeHeldBatch(): TimetableHeldBatch? = synchronized(lock) {
        val batch = cached.heldBatch
        if (batch != null) mutate { state -> state.copy(heldBatch = null) }
        batch
    }

    /** 源端恢复正常后丢弃过期的挂起批（不建立 veto 追踪）。 */
    fun clearHeldBatch() = synchronized(lock) {
        mutate { state -> state.copy(heldBatch = null) }
    }

    /**
     * 用户对整批异常选择"保留当前课表"后调用：为源端未返回的本地课建立 veto 追踪，
     * 后续刷新走课程级保留/升级流程，不再每次打开应用都重复询问同一批缺失。
     */
    fun trackHeldBatchMissing(termId: String, courses: List<MergeCourse>, nowMillis: Long) =
        synchronized(lock) {
            if (courses.isEmpty()) return@synchronized
            mutate { state ->
                val termVetoes = state.vetoes[termId].orEmpty().toMutableMap()
                courses.forEach { course ->
                    val key = TimetableRefreshMergePolicy.courseKey(course.name, course.code)
                    if (termVetoes.containsKey(key)) return@forEach
                    termVetoes[key] = CourseVetoRecord(
                        courseKey = key,
                        name = course.name,
                        firstSeenMillis = nowMillis,
                        lastSeenMillis = nowMillis,
                        observationCount = 1
                    )
                }
                state.copy(vetoes = state.vetoes + (termId to termVetoes))
            }
        }

    /** 整批采用源端后调用：该学期的旧待确认项与摘要全部作废。 */
    fun clearTerm(termId: String) = synchronized(lock) {
        mutate { state ->
            state.copy(
                info = null,
                decisions = state.decisions.filterNot { it.termId == termId },
                vetoes = state.vetoes.filterKeys { it != termId },
                pendingUpdates = state.pendingUpdates.filterNot { it.termId == termId },
            )
        }
    }

    // ---------- 内部 ----------

    /**
     * 新一批决策与旧决策合并：同一门课以新记录为准（观察次数递增）。
     * 已回归源端的课的过期决策由 [pruneResolvedDecisions] 在每次刷新后清除。
     */
    private fun mergeDecisions(
        existing: List<TimetableDecisionItem>,
        incoming: List<TimetableDecisionItem>
    ): List<TimetableDecisionItem> {
        val byKey = existing.associateBy { it.termId to it.courseKey }.toMutableMap()
        incoming.forEach { item -> byKey[item.termId to item.courseKey] = item }
        return byKey.values.toList()
    }

    private fun mutate(transform: (PersistedState) -> PersistedState) {
        ensureReadable()
        val next = transform(cached)
        val draft = shared.draft.get()
        if (draft != null) draft.current = next else publish(next)
    }

    private fun publish(state: PersistedState) {
        atomicFile.write(gson.toJson(state))
        shared.cached = state
        shared.liveData.postValue(state.toChangeState())
    }

    private fun loadShared(): SharedState = try {
        SharedState(load())
    } catch (failure: Exception) {
        // Keep the UI constructible, but never let an empty fallback replace unreadable decisions.
        Log.e("TimetableChangeStore", "Pending timetable state could not be read", failure)
        SharedState(PersistedState(), failure)
    }

    private fun ensureReadable() {
        if (shared.loadFailure == null) return
        try {
            shared.cached = load()
            shared.loadFailure = null
            shared.liveData.postValue(shared.cached.toChangeState())
        } catch (failure: Exception) {
            shared.loadFailure = failure
            throw IOException("Pending timetable state is unavailable; original file retained", failure)
        }
    }

    private fun load(): PersistedState = atomicFile.readOrNull()?.let(::decode) ?: PersistedState()

    private fun decode(json: String): PersistedState {
        // Never replace unreadable pending data with an empty state on a later write.
        val parsed = gson.fromJson(json, PersistedState::class.java)
            ?: throw IOException("Invalid timetable change state")
        val normalized = parsed.copy(
            decisions = parsed.decisions.orEmpty(),
            vetoes = parsed.vetoes.orEmpty(),
            pendingUpdates = parsed.pendingUpdates.orEmpty(),
            ignoredFingerprints = parsed.ignoredFingerprints.orEmpty(),
            appliedOperations = parsed.appliedOperations.orEmpty(),
        )
        // Historical schemas have incompatible transient rows; their veto memory remains valid.
        return if (normalized.schemaVersion == CURRENT_SCHEMA_VERSION) normalized else normalized.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            info = null,
            decisions = emptyList(),
            heldBatch = null,
            pendingUpdates = emptyList(),
        )
    }

    companion object {
        /** Additive outbox markers do not change existing schema-3 pending rows. */
        private const val CURRENT_SCHEMA_VERSION = 3
        private val instances = mutableMapOf<String, WeakReference<SharedState>>()
    }
}
