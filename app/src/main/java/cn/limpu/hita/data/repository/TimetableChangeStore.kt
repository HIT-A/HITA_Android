package cn.limpu.hita.data.repository

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import com.google.gson.Gson
import java.io.File
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
        val pendingRevision: Long = 0L,
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

    private val appContext = application.applicationContext
    private val gson = Gson()
    private val directory = File(appContext.filesDir, "timetable_change")
    private val stateFile = File(directory, "state.json")
    private val lock = Any()
    private var cached: PersistedState = load()
    private val stateLiveData = MutableLiveData(cached.toChangeState())

    fun observeState(): LiveData<TimetableChangeState> = stateLiveData

    fun currentState(): TimetableChangeState = cached.toChangeState()

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
                ignoredFingerprints = (state.ignoredFingerprints + fingerprints).distinct()
            )
        }
    }

    /**
     * 清空「记住忽略」指纹：重新登录后上游现状应重新提示一次，
     * 避免用户曾忽略的变更永远不再出现。vetoes/挂起批/待确认不受影响。
     */
    fun clearIgnoredFingerprints() = synchronized(lock) {
        if (cached.ignoredFingerprints.isEmpty()) return@synchronized
        mutate { state -> state.copy(ignoredFingerprints = emptyList()) }
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
        cached = transform(cached)
        persist(cached)
        stateLiveData.postValue(cached.toChangeState())
    }

    private fun load(): PersistedState {
        if (!stateFile.isFile) return PersistedState()
        val parsed = runCatching {
            gson.fromJson(stateFile.readText(), PersistedState::class.java)
        }.getOrNull() ?: return PersistedState()
        // 旧版落盘的 info/decisions/heldBatch 字段结构与新版不兼容（Gson 会填出 null 列表），
        // 直接丢弃这些瞬时数据；vetoes 结构未变，保留防骚扰记忆。
        if (parsed.schemaVersion != CURRENT_SCHEMA_VERSION) {
            return parsed.copy(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                info = null,
                decisions = emptyList(),
                heldBatch = null,
                pendingUpdates = emptyList(),
            )
        }
        return parsed
    }

    private fun persist(state: PersistedState) {
        directory.mkdirs()
        val temporary = File(directory, "state.json.tmp")
        temporary.writeText(gson.toJson(state))
        if (!temporary.renameTo(stateFile)) {
            stateFile.writeText(temporary.readText())
            temporary.delete()
        }
    }

    companion object {
        /** 落盘结构版本：info/decisions/heldBatch/pending 的形状变更后 +1，旧数据整体作废。 */
        private const val CURRENT_SCHEMA_VERSION = 3
    }
}
