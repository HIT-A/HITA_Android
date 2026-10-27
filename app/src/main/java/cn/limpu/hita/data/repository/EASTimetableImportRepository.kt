package cn.limpu.hita.data.repository

import android.content.Context
import android.os.SystemClock
import androidx.annotation.WorkerThread
import cn.limpu.hita.R
import cn.limpu.hita.data.model.eas.*
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.source.dao.EventItemDao
import cn.limpu.hita.data.source.dao.SubjectDao
import cn.limpu.hita.data.source.dao.TimetableDao
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.data.source.preference.TimetablePreferenceSource
import cn.limpu.hita.utils.ColorTools
import cn.limpu.hita.utils.CourseCodeUtils
import cn.limpu.hita.utils.CourseNameUtils
import cn.limpu.hita.utils.LogUtils
import cn.limpu.hita.utils.TermNameFormatter
import com.limpu.component.data.DataState
import java.sql.Timestamp
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 自动刷新课表的结果：会话失效单独上报，供上层引导重登而不是静默吞掉。 */
enum class AutoImportResult {
    SUCCESS,
    FAILED,
    NOT_LOGGED_IN,
    /** 已有自动导入在跑，本次请求被去重跳过。 */
    SKIPPED_IN_PROGRESS,
}

internal class EASTimetableImportRepository(
    private val easPreferenceSource: EasPreferenceSource,
    private val eventItemDao: EventItemDao,
    private val timetableDao: TimetableDao,
    private val subjectDao: SubjectDao,
    private val timetableSnapshotStore: TimetableSnapshotStore,
    private val timetableMutationLock: TimetableMutationLock,
    private val timetableChangeStore: TimetableChangeStore,
    private val timetablePreferenceSource: TimetablePreferenceSource,
    private val appContext: Context,
    private val authEpoch: AtomicLong,
    private val tokenStateLock: Any,
    private val autoImportInProgress: AtomicBoolean,
    private val captureSession: () -> EasQuerySession,
    private val queryTerms: suspend (EasQuerySession) -> DataState<List<TermItem>>,
    private val applicationScope: CoroutineScope,
    private val database: cn.limpu.hita.data.AppDatabase,
    private val autoGroupImportedTimetable: (String) -> Unit,
) {
    private val examImportRepository by lazy { ExamImportRepository(database) }

    /** Remote work is cancellable; after the Room transaction owns commit, it finishes atomically. */
    suspend fun importTimetableOfTerm(
        session: EasQuerySession,
        requestedTerm: TermItem,
        requestedDate: Calendar,
        requestedSchedule: List<TimePeriodInDay>,
        importMode: TimetableImportMode = TimetableImportMode.REPLACE,
        importOperation: EasTimetableImportGuard? = null,
        timeoutMillis: Long = 90_000L,
    ): DataState<Boolean> {
        require(timeoutMillis > 0)
        val term = requestedTerm.copyForQuery()
        val startDate = (requestedDate.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val schedule = requestedSchedule.map { it.clone() }
        val snapshotOwnerKey = FollowedTeachingSectionStore.ownerKey(session.token)
        if (!session.loggedIn) return DataState(DataState.STATE.NOT_LOGGED_IN)
        session.ensureCurrent()
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        val operation = importOperation ?: synchronized(tokenStateLock) {
            EasTimetableImportGuard(session.token, authEpoch.get(), deadline)
        }
        return try {
            session.whileCurrent {
                withContext(Dispatchers.IO) {
                    val prepared = withTimeoutOrNull(timeoutMillis) {
                        val response = session.service.getTimetableOfTermOnce(term, session.token)
                        currentCoroutineContext().ensureActive()
                        session.ensureCurrent()
                        if (response.state != DataState.STATE.SUCCESS) {
                            return@withTimeoutOrNull PreparedResult.Failed(DataState(response.state, response.message))
                        }
                        val courses = response.data
                        if (courses.isNullOrEmpty()) {
                            return@withTimeoutOrNull PreparedResult.Failed(DataState(DataState.STATE.FETCH_FAILED, "empty timetable"))
                        }
                        check(operation.tryStartProcessing()) { "Import operation already completed" }
                        val meta = if (session.campus == EASToken.Campus.SHENZHEN) {
                            withTimeoutOrNull(8_000L) { fetchSelectedSubjectMeta(term, session) }
                                ?: SelectedSubjectMeta()
                        } else SelectedSubjectMeta()
                        currentCoroutineContext().ensureActive()
                        session.ensureCurrent()
                        PreparedResult.Ready(courses, meta)
                    } ?: return@withContext DataState(DataState.STATE.FETCH_FAILED, "导入超时，请重试")
                    if (prepared is PreparedResult.Failed) return@withContext prepared.state
                    val remote = prepared as PreparedResult.Ready
                    val coroutine = currentCoroutineContext()
                    // Keep token -> shared timetable -> SQLite acquisition order. Cancellation is
                    // checked inside Room too, because acquiring its connection can block.
                    synchronized(tokenStateLock) {
                        timetableMutationLock.exclusive {
                            coroutine.ensureActive()
                            session.ensureCurrent()
                            var committed: PreparedImport? = null
                            var emptyEvents = false
                            database.runInTransaction {
                                coroutine.ensureActive()
                                session.ensureCurrent()
                                // Preparation reads the latest local subjects/timetable under the same
                                // serialization boundary as the write, including concurrent first imports.
                                val data = prepareImport(term, session.token, startDate, schedule, remote.courses, remote.meta)
                                if (data.events.isEmpty()) { emptyEvents = true; return@runInTransaction }
                                coroutine.ensureActive()
                                if (SystemClock.elapsedRealtime() >= deadline ||
                                    !operation.tryBeginCommit(authEpoch.get(), easPreferenceSource.getEasToken(), SystemClock.elapsedRealtime())) {
                                    return@runInTransaction
                                }
                                val timetable = data.timetable
                                if (importMode == TimetableImportMode.MERGE) {
                                    applyMergedTimetable(
                                        term, snapshotOwnerKey, timetable, data.pendingSubjects, data.events,
                                        data.maxTs, data.timetableCode, buildTimetableName(term, session.campus),
                                        startDate.timeInMillis, data.schedule, data.isNewTimetable,
                                    )
                                } else {
                                    timetableSnapshotStore.capture(snapshotOwnerKey, term, timetable, TimetableSnapshotKind.BEFORE_REFRESH)
                                    eventItemDao.deleteCourseFromTimetable(timetable.id)
                                    subjectDao.saveSubjectsSync(data.pendingSubjects.values.toList())
                                    eventItemDao.saveEvents(data.events)
                                    val current = timetableDao.getTimetableByIdSync(timetable.id)
                                    timetable.name = current?.name ?: buildTimetableName(term, session.campus)
                                    timetable.groupId = current?.groupId
                                    timetable.startTime = Timestamp(startDate.timeInMillis)
                                    timetable.endTime = Timestamp(data.maxTs)
                                    timetable.code = data.timetableCode
                                    timetable.scheduleStructure = data.schedule
                                    timetableDao.saveTimetableSync(timetable)
                                }
                                cleanupDefaultDuplicateTimetablesAfterImport(timetable.id)
                                committed = data
                            }
                            val data = committed
                            if (data == null) {
                                DataState(DataState.STATE.FETCH_FAILED, if (emptyEvents) "empty events" else "导入超时，请重试")
                            } else {
                                // Post-commit bookkeeping must not report an already persisted import as failed.
                                runCatching {
                                    timetableSnapshotStore.capture(snapshotOwnerKey, term, data.timetable, TimetableSnapshotKind.IMPORTED)
                                }.onFailure { LogUtils.e("import: snapshot capture after commit failed", it) }
                                if (data.isNewTimetable) {
                                    runCatching { autoGroupImportedTimetable(data.timetable.id) }
                                        .onFailure { LogUtils.e("import: auto-group failed", it) }
                                }
                                DataState(true, DataState.STATE.SUCCESS)
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LogUtils.e("import: failed for term=${term.getCode()}", error)
            DataState(DataState.STATE.FETCH_FAILED, error.message)
        } finally {
            operation.finishProcessing()
        }
    }

    private sealed interface PreparedResult {
        data class Ready(val courses: List<CourseItem>, val meta: SelectedSubjectMeta) : PreparedResult
        data class Failed(val state: DataState<Boolean>) : PreparedResult
    }

    private data class PreparedImport(
        val timetable: Timetable,
        val isNewTimetable: Boolean,
        val timetableCode: String,
        val pendingSubjects: Map<String, TermSubject>,
        val events: List<EventItem>,
        val maxTs: Long,
        val schedule: List<TimePeriodInDay>,
    )

    private fun prepareImport(
        term: TermItem,
        easToken: EASToken,
        startDate: Calendar,
        schedule: List<TimePeriodInDay>,
        courseItems: List<CourseItem>,
        meta: SelectedSubjectMeta,
    ): PreparedImport {
        val timetableCode = EASTimetableCode.of(easToken.campus, term)
        val legacyTimetableCode = term.getCode()
        val teacherMap = meta.teacherMap
        val creditMap = meta.creditMap
        val maxPeriod = courseItems.maxOfOrNull { item ->
            (item.begin + item.last - 1).coerceAtLeast(item.begin)
        } ?: 0
        val safeSchedule = buildSafeSchedule(schedule, maxPeriod)
        LogUtils.d(
            "import: processing term=${term.getCode()} campus=${easToken.campus} code=$timetableCode courseCount=${courseItems.size} maxPeriod=$maxPeriod schedule=${describeSchedule(safeSchedule)}"
        )
        //更新timetable信息
        var timetable = timetableDao.getTimetableByEASCodeCandidatesSync(
            EASTimetableCode.candidates(term, easToken.campus),
            timetableCode,
            legacyTimetableCode
        )
        val isNewTimetable = timetable == null
        if (timetable == null) {
            timetable = Timetable()
        }
        //记录最后的时间戳，作为学期结束的标志
        var maxTs: Long = 0
        //添加时间表
        val events = mutableListOf<EventItem>()
        val pendingSubjects = linkedMapOf<String, TermSubject>()
        val subjectsByKey = mutableMapOf<String, TermSubject>()
        subjectDao.getSubjectsSync(timetable.id).forEach { subject ->
            EasImportIdentity.registerSubject(
                subjectsByKey,
                subject,
                CourseNameUtils.normalize(subject.name) ?: subject.name,
                subject.name,
            )
        }
        val generatedClassKeys = mutableSetOf<String>()

        // Count free time courses before processing
        val freeTimeCount = courseItems.count { item ->
            !item.startTime.isNullOrBlank() && !item.endTime.isNullOrBlank() && item.begin == -1 && item.last == -1
        }
        LogUtils.d("import: courses=${courseItems.size} freeTime=$freeTimeCount period=${courseItems.size - freeTimeCount}")

        for (item in courseItems) {
            // Check if this is a free time course (has startTime/EndTime)
            val isFreeTimeCourse = !item.startTime.isNullOrBlank() && !item.endTime.isNullOrBlank() && item.begin == -1 && item.last == -1

            // Debug log for experiment courses
            // Skip period-based courses with invalid indices
            if (!isFreeTimeCourse) {
                val startIndex = item.begin - 1
                val endIndex = item.begin + item.last - 2
                if (startIndex !in safeSchedule.indices || endIndex !in safeSchedule.indices) {
                    continue
                }
            }

            val rawName = item.name?.toString().orEmpty().trim()
            if (rawName.isBlank()) {
                continue
            }
            val normalizedName = CourseNameUtils.normalize(rawName) ?: rawName
            val code = (
                CourseCodeUtils.normalize(item.code)
                    ?: item.code?.trim().orEmpty()
                ).ifBlank {
                meta.codeMap[rawName]
                    ?: meta.codeMap[normalizedName]
                    ?: ""
            }

            //添加科目
            var subject = EasImportIdentity.findReusableSubject(
                subjectsByKey,
                code,
                normalizedName,
                rawName,
            )
            if (subject == null) {//不存在，新建
                subject = TermSubject()
                // 优先保存完整的原始名称
                subject.name = rawName
                subject.timetableId = timetable.id
                subject.id = UUID.randomUUID().toString()
                subject.color = ColorTools.colorForName(normalizedName)
            } else {
                // 科目已存在，总是尝试更新为更完整的名称
                // 优先选择包含更多信息（括号、方括号）的名称
                val oldHasBrackets = subject.name.contains("（") || subject.name.contains("(") ||
                                               subject.name.contains("[") || subject.name.contains("【")
                val newHasBrackets = rawName.contains("（") || rawName.contains("(") ||
                                               rawName.contains("[") || rawName.contains("【")

                // 如果新名称包含括号信息（通常更完整），或者新名称明显更长，则更新
                if (newHasBrackets && !oldHasBrackets) {
                    subject.name = rawName
                } else if (rawName.length > subject.name.length + 2) {
                    // 只有新名称明显更长时才更新（避免因细微差异反复更新）
                    subject.name = rawName
                }
            }
            if (code.isNotBlank() && subject.code.isNullOrBlank()) {
                subject.code = code
            }
            if (subject.credit <= 0f) {
                val mappedCredit = creditMap[code]
                    ?: creditMap[rawName]
                    ?: creditMap[normalizedName]
                if (mappedCredit != null && mappedCredit > 0f) {
                    subject.credit = mappedCredit
                }
            }
            if (subject.field.isNullOrBlank()) {
                val mappedField = meta.fieldMap[code]
                    ?: meta.fieldMap[rawName]
                    ?: meta.fieldMap[normalizedName]
                if (!mappedField.isNullOrBlank()) {
                    subject.field = mappedField
                }
            }
            if (subject.selectCategory.isNullOrBlank()) {
                val mappedSelect = meta.selectCategoryMap[code]
                    ?: meta.selectCategoryMap[rawName]
                    ?: meta.selectCategoryMap[normalizedName]
                if (!mappedSelect.isNullOrBlank()) {
                    subject.selectCategory = mappedSelect
                }
            }
            if (subject.nature.isNullOrBlank()) {
                val mappedNature = meta.natureMap[code]
                    ?: meta.natureMap[rawName]
                    ?: meta.natureMap[normalizedName]
                if (!mappedNature.isNullOrBlank()) {
                    subject.nature = mappedNature
                }
            }
            EasImportIdentity.registerSubject(
                subjectsByKey,
                subject,
                normalizedName,
                subject.name,
            )
            var itemHasEvent = false

            for (date in resolveEasCourseDates(item, startDate)) {
                val from = date.clone() as Calendar
                val to = date.clone() as Calendar

                // Handle free time courses (experiment courses with custom times)
                if (isFreeTimeCourse) {
                    val startTime = item.startTime
                    val endTime = item.endTime
                    if (startTime != null && endTime != null) {
                        // Parse "HH:MM" format
                        val startParts = startTime.split(":")
                        val endParts = endTime.split(":")
                        from.set(Calendar.HOUR_OF_DAY, startParts[0].toInt())
                        from.set(Calendar.MINUTE, startParts[1].toInt())
                        to.set(Calendar.HOUR_OF_DAY, endParts[0].toInt())
                        to.set(Calendar.MINUTE, endParts[1].toInt())
                    }
                } else {
                    // Period-based courses
                    val spStart = safeSchedule[item.begin - 1]
                    val spEnd = safeSchedule[item.begin + item.last - 2]
                    from.set(Calendar.HOUR_OF_DAY, spStart.from.hour)
                    from.set(Calendar.MINUTE, spStart.from.minute)
                    to.set(Calendar.HOUR_OF_DAY, spEnd.to.hour)
                    to.set(Calendar.MINUTE, spEnd.to.minute)
                }

                val e = EventItem()
                e.source = EventItem.SOURCE_EAS_IMPORT
                // 使用原始完整名称而不是normalized
                e.name = rawName
                e.from.time = from.timeInMillis
                e.fromNumber = if (isFreeTimeCourse) 0 else item.begin
                e.subjectId = subject.id
                e.lastNumber = if (isFreeTimeCourse) 0 else item.last
                e.to.time = to.timeInMillis
                val itemTeacher = sanitizeImportedTeacher(rawName, item.teacher)
                val mappedTeacher = itemTeacher
                    ?: code.takeIf { it.isNotBlank() }?.let { teacherMap[it] }
                    ?: teacherMap[rawName]
                    ?: teacherMap[normalizedName]
                val teacherSource = when {
                    !itemTeacher.isNullOrBlank() -> "item"
                    !code.isNullOrBlank() && !teacherMap[code].isNullOrBlank() -> "meta_by_code"
                    !teacherMap[rawName].isNullOrBlank() -> "meta_by_name_raw"
                    !teacherMap[normalizedName].isNullOrBlank() -> "meta_by_name_normalized"
                    else -> "none"
                }
                e.teacher = mappedTeacher
                e.place = item.classroom
                e.timetableId = timetable.id
                if (!generatedClassKeys.add(EasImportIdentity.classEventIdentityKey(e))) {
                    continue
                }
                if (e.to.time > maxTs) maxTs = e.to.time
                events.add(e)
                itemHasEvent = true
            }
            if (itemHasEvent) {
                pendingSubjects[subject.id] = subject
            }
        }
        return PreparedImport(timetable, isNewTimetable, timetableCode, pendingSubjects, events, maxTs, safeSchedule)
    }

    /**
     * 打开应用时的自动刷新：按 [TimetableRefreshMergePolicy] 逐门合并，而不是整表替换。
     *
     * 与手动导入（REPLACE）的差别：
     * - 课表源漏课或课次减少的课保留本地，不被错误源覆盖；
     * - 时间/地点/教师变更与新增课挂起，等用户多选确认后才覆盖；
     * - 持续缺失（3 次且 48 小时）升级为待用户确认；
     * - 源端与本地匹配率过低时整批挂起，等用户决策。
     *
     * 必须在工作线程调用。
     */
    @WorkerThread
    private fun applyMergedTimetable(
        term: TermItem,
        snapshotOwnerKey: String,
        timetable: Timetable,
        pendingSubjects: Map<String, TermSubject>,
        events: List<EventItem>,
        maxTs: Long,
        timetableCode: String,
        timetableName: String,
        startMillis: Long,
        schedule: List<TimePeriodInDay>,
        isNewTimetable: Boolean
    ) = timetableMutationLock.exclusive {
        val localEvents = eventItemDao.getImportedClassEventsOfTimetableSync(timetable.id)
        val localSubjectsById = subjectDao.getSubjectsSync(timetable.id).associateBy { it.id }

        // 字段级信息保护：教师/地点「有→无」视为课表源字段抖动（信息减少不可信），
        // 沿用本地「同周次+同槽位」课次的值。在比对与落库前就地修正源端事件，
        // 使纯字段抖动不产生变更提示，课程因其他变更被采纳时也不清空本地字段。
        val localLessonsBySubject = localEvents.groupBy { it.subjectId }
            .mapValues { (_, subjectEvents) -> subjectEvents.map { it.toMergeLesson(startMillis) } }
        events.groupBy { it.subjectId }.forEach { (subjectId, courseEvents) ->
            val localLessons = localLessonsBySubject[subjectId] ?: return@forEach
            val protectedLessons = TimetableRefreshMergePolicy.inheritBlankLocalFields(
                local = localLessons,
                incoming = courseEvents.map { it.toMergeLesson(startMillis) }
            )
            courseEvents.forEachIndexed { index, event ->
                if (event.teacher.isNullOrBlank()) {
                    event.teacher = protectedLessons[index].teacher.ifBlank { null }
                }
                if (event.place.isNullOrBlank()) {
                    event.place = protectedLessons[index].place.ifBlank { null }
                }
            }
        }
        val localCourses = localEvents.groupBy { it.subjectId }.mapNotNull { (subjectId, lessons) ->
            val subject = localSubjectsById[subjectId] ?: return@mapNotNull null
            MergeCourse(subjectId, subject.name, subject.code, lessons.map { it.toMergeLesson(startMillis) })
        }
        val incomingCourses = events.groupBy { it.subjectId }.mapNotNull { (subjectId, lessons) ->
            val subject = pendingSubjects[subjectId] ?: return@mapNotNull null
            MergeCourse(subjectId, subject.name, subject.code, lessons.map { it.toMergeLesson(startMillis) })
        }

        // 已有课表可能被用户主动清空；只有新建课表才允许自动填入全部课程。
        val firstImport = isNewTimetable

        val plan = TimetableRefreshMergePolicy.plan(
            local = localCourses,
            incoming = incomingCourses,
            vetoes = timetableChangeStore.getVetoes(term.id),
            nowMillis = System.currentTimeMillis(),
            allowInitialImport = firstImport
        )
        timetableChangeStore.putVetoes(term.id, plan.vetoes)

        if (plan.holdBatch) {
            LogUtils.w(
                "merge: hold batch term=${term.id} " +
                    "local=${plan.holdLocalCount} incoming=${plan.holdIncomingCount} " +
                    "matched=${plan.holdMatchedCount}"
            )
            timetableChangeStore.pruneResolvedDecisions(
                term.id,
                incomingCourses.map {
                    TimetableRefreshMergePolicy.courseKey(it.name, it.code)
                }.toSet()
            )
            timetableChangeStore.recordHeldBatch(
                TimetableHeldBatch(
                    createdAtMillis = System.currentTimeMillis(),
                    termId = term.id,
                    timetableId = timetable.id,
                    timetableName = timetableName,
                    timetableCode = timetableCode,
                    startMillis = startMillis,
                    schedule = schedule,
                    localCount = plan.holdLocalCount,
                    incomingCount = plan.holdIncomingCount,
                    matchedCount = plan.holdMatchedCount,
                    courses = incomingCourses,
                    unmatchedLocal = plan.holdUnmatchedLocal
                )
            )
            return
        }

        // 本次刷新匹配正常：源端已恢复，丢弃此前挂起的过期异常批
        timetableChangeStore.clearHeldBatch()
        // 课重新在源端出现后，清理它的过期待确认项（否则"采用课表源"会误删已回归的课）
        timetableChangeStore.pruneResolvedDecisions(term.id, plan.matchedCourseKeys)

        val ignored = timetableChangeStore.ignoredFingerprints()
        val pending = plan.pendingUpdates.mapNotNull { update ->
            val filtered = update.withTerm(term.id, timetable.id).filterRows(ignored)
            filtered.takeIf { it.rows.isNotEmpty() }
        }
        timetableChangeStore.recordPendingUpdates(pending)

        // 只有真实落库（有采纳的课）才捕获刷新前快照；纯保留/无变化不改数据，不打快照
        if (plan.adopt.isNotEmpty()) {
            timetableSnapshotStore.capture(
                snapshotOwnerKey,
                term,
                timetable,
                TimetableSnapshotKind.BEFORE_REFRESH
            )
        }

        database.runInTransaction {
            val replacedSubjectIds = plan.replacedLocal.mapTo(HashSet()) { it.subjectId }
            if (replacedSubjectIds.isNotEmpty()) {
                eventItemDao.deleteImportedClassesFromSubjectsSync(timetable.id, replacedSubjectIds.toList())
            }
            if (plan.adopt.isNotEmpty()) {
                val adoptSubjectIds = plan.adopt.mapTo(HashSet()) { it.subjectId }
                subjectDao.saveSubjectsSync(plan.adopt.mapNotNull { pendingSubjects[it.subjectId] })
                val adoptEvents = events.filter { it.subjectId in adoptSubjectIds }
                val keptEvents = mutableListOf<EventItem>()
                plan.partialMerges.forEach { partial ->
                    localEvents
                        .filter { it.subjectId == partial.subjectId }
                        .filterTo(keptEvents) {
                            TimetableRefreshMergePolicy.slotKeyOf(it) !in partial.incomingSlotKeys
                        }
                }
                eventItemDao.saveEvents(adoptEvents + keptEvents)

                val current = timetableDao.getTimetableByIdSync(timetable.id)
                timetable.name = current?.name ?: timetableName
                timetable.groupId = current?.groupId
                timetable.startTime = Timestamp(startMillis)
                timetable.code = timetableCode
                timetable.scheduleStructure = schedule
                val keptMax = localEvents
                    .filterNot { it.subjectId in replacedSubjectIds }
                    .maxOfOrNull { it.to.time }
                    ?: 0L
                timetable.endTime = Timestamp(maxOf(maxTs, keptMax))
                timetableDao.saveTimetableSync(timetable)
            }
        }
        if (plan.adopt.isNotEmpty()) {
            timetableSnapshotStore.capture(
                snapshotOwnerKey,
                term,
                timetable,
                TimetableSnapshotKind.IMPORTED
            )
        }

        if ((plan.hasChanges || pending.isNotEmpty()) && !firstImport) {
            timetableChangeStore.recordApplied(
                TimetableChangeInfo(
                    updatedAtMillis = System.currentTimeMillis(),
                    updated = emptyList(),
                    added = emptyList(),
                    keptCourses = plan.kept
                ),
                plan.decisions.map { decision ->
                    TimetableDecisionItem(
                        termId = term.id,
                        timetableId = timetable.id,
                        courseKey = decision.courseKey,
                        subjectId = decision.subjectId,
                        name = decision.name,
                        reason = decision.reason,
                        localLessonCount = decision.localLessonCount,
                        firstSeenMillis = decision.firstSeenMillis,
                        observationCount = decision.observationCount,
                        incoming = decision.incoming
                    )
                }
            )
        }
        LogUtils.d(
            "merge: term=${term.id} adopt=${plan.adopt.size} " +
                "updated=${plan.updated.size} added=${plan.added.size} " +
                "kept=${plan.kept.size} decisions=${plan.decisions.size}"
        )
    }

    /**
     * 清理历史遗留的“默认课表”重复数据。
     *
     * 早期版本可能把 EAS 导入课程放入默认课表，升级后同一学期会同时存在：
     * - 带 EAS code 的正式学期课表；
     * - code 为空、名字像“默认课表”的历史表。
     *
     * 只清理纯 EAS 课程且内容完全被正式课表覆盖的默认表；
     * 如果里面有手动活动、考试、ICS 或 AI 创建内容，一律保留。
     */
    private fun cleanupDefaultDuplicateTimetablesAfterImport(importedTimetableId: String) {
        val defaultPrefix = appContext.getString(R.string.default_timetable_name)
        val defaults = timetableDao.getDefaultNamedCustomTimetablesSync("$defaultPrefix%")
        if (defaults.isEmpty()) return

        val importedKeys = eventItemDao.getImportedClassEventsOfTimetableSync(importedTimetableId)
            .mapTo(mutableSetOf()) { importedClassEventIdentityKey(it) }
        if (importedKeys.isEmpty()) return

        val deleteIds = defaults.mapNotNull { timetable ->
            val eventCount = eventItemDao.countEventsOfTimetableSync(timetable.id)
            if (eventCount == 0) return@mapNotNull timetable.id

            val nonImportedClassCount = eventItemDao.countNonImportedClassEventsOfTimetableSync(timetable.id)
            if (nonImportedClassCount > 0) return@mapNotNull null

            val defaultKeys = eventItemDao.getImportedClassEventsOfTimetableSync(timetable.id)
                .mapTo(mutableSetOf()) { importedClassEventIdentityKey(it) }
            if (defaultKeys.isNotEmpty() && importedKeys.containsAll(defaultKeys)) {
                timetable.id
            } else {
                null
            }
        }
        if (deleteIds.isEmpty()) return

        LogUtils.d("import: cleanup duplicate default timetables=$deleteIds")
        database.runInTransaction {
            val groups = timetableDao.getTimetablesInIdsSync(deleteIds).mapNotNull { it.groupId }.distinct()
            eventItemDao.deleteEventsFromTimetablesSync(deleteIds)
            subjectDao.deleteSubjectsFromTimetablesSync(deleteIds)
            timetableDao.deleteTimetablesInIdsSync(deleteIds)
            val groupDao = database.timetableGroupDao()
            groups.forEach { id ->
                if (groupDao.countMembersSync(id) == 0) groupDao.deleteGroupByIdSync(id)
            }
        }
    }

    private fun importedClassEventIdentityKey(event: EventItem): String {
        return listOf(
            event.name.trim(),
            event.place.orEmpty().trim(),
            event.teacher.orEmpty().trim(),
            event.from.time.toString(),
            event.to.time.toString(),
            event.fromNumber.toString(),
            event.lastNumber.toString(),
        ).joinToString("|")
    }

    private fun sanitizeImportedTeacher(courseName: String?, teacherRaw: String?): String? {
        val source = teacherRaw?.trim().orEmpty()
        if (source.isBlank()) return null

        val name = courseName?.trim().orEmpty()
        val normalized = source.replace(" ", "")
        val looksLikeCoursePayload = normalized.startsWith("【") ||
            (name.isNotBlank() && (source.startsWith(name) || normalized.contains(name.replace(" ", ""))))
        if (looksLikeCoursePayload) return null

        val cleaned = source
            .replace(Regex("^第[一二三四五六七八九十0-9]+批"), "")
            .trimStart('/', '／', ' ', '\t')
            .trim()
        return cleaned.ifBlank { null }
    }

    private fun buildSafeSchedule(
        schedule: List<TimePeriodInDay>,
        requiredMaxPeriod: Int
    ): List<TimePeriodInDay> {
        if (requiredMaxPeriod <= 0) return schedule
        if (schedule.size >= requiredMaxPeriod) return schedule
        val defaults = Timetable().getDefaultTimeStructure()
        val size = maxOf(requiredMaxPeriod, defaults.size, schedule.size)
        return List(size) { idx ->
            schedule.getOrNull(idx) ?: defaults.getOrNull(idx) ?: defaults.last()
        }
    }

    private fun describeSchedule(schedule: List<TimePeriodInDay>): String {
        if (schedule.isEmpty()) return "empty"
        return "size=${schedule.size}, first=${schedule.first()}, last=${schedule.last()}"
    }

    private fun buildTimetableName(term: TermItem, campus: EASToken.Campus): String {
        val campusName = when (campus) {
            EASToken.Campus.SHENZHEN -> appContext.getString(R.string.eas_campus_shenzhen)
            EASToken.Campus.BENBU -> appContext.getString(R.string.eas_campus_benbu)
            EASToken.Campus.WEIHAI -> appContext.getString(R.string.eas_campus_weihai)
        }
        return "$campusName ${TermNameFormatter.shortTermName(term.termName, term.name)}"
    }

    private data class SelectedSubjectMeta(
        val codeMap: Map<String, String> = emptyMap(),
        val teacherMap: Map<String, String> = emptyMap(),
        val creditMap: Map<String, Float> = emptyMap(),
        val fieldMap: Map<String, String> = emptyMap(),
        val selectCategoryMap: Map<String, String> = emptyMap(),
        val natureMap: Map<String, String> = emptyMap(),
    )

    private suspend fun fetchSelectedSubjectMeta(term: TermItem, session: EasQuerySession): SelectedSubjectMeta {
        val codeMap = mutableMapOf<String, String>()
        val teacherMap = mutableMapOf<String, String>()
        val creditMap = mutableMapOf<String, Float>()
        val fieldMap = mutableMapOf<String, String>()
        val selectCategoryMap = mutableMapOf<String, String>()
        val natureMap = mutableMapOf<String, String>()
        val state = session.service.getSubjectsOfTermOnce(session.token, term)
        currentCoroutineContext().ensureActive()
        session.ensureCurrent()
        state.data?.forEach { subject ->
            val code = CourseCodeUtils.normalize(subject.code)
                ?: subject.code?.trim().orEmpty()
            val nameKeys = listOfNotNull(
                subject.name.trim().takeIf { it.isNotEmpty() },
                CourseNameUtils.normalize(subject.name)?.trim()?.takeIf { it.isNotEmpty() }
            ).distinct()
            if (code.isNotBlank()) {
                nameKeys.forEach { name -> codeMap[name] = code }
            }
            val teacher = subject.teacher?.trim()
            if (!teacher.isNullOrEmpty()) {
                if (code.isNotBlank()) teacherMap[code] = teacher
                nameKeys.forEach { name -> teacherMap[name] = teacher }
            }
            val credit = subject.credit
            if (credit > 0f) {
                if (code.isNotBlank()) creditMap[code] = credit
                nameKeys.forEach { name -> creditMap[name] = credit }
            }
            subject.field?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
                if (code.isNotBlank()) fieldMap[code] = value
                nameKeys.forEach { name -> fieldMap[name] = value }
            }
            subject.selectCategory?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
                if (code.isNotBlank()) selectCategoryMap[code] = value
                nameKeys.forEach { name -> selectCategoryMap[name] = value }
            }
            subject.nature?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
                if (code.isNotBlank()) natureMap[code] = value
                nameKeys.forEach { name -> natureMap[name] = value }
            }
        }
        return SelectedSubjectMeta(codeMap, teacherMap, creditMap, fieldMap, selectCategoryMap, natureMap)
    }

    fun startAutoImportCurrentTimetable(
        isUndergraduate: Boolean,
        onResult: ((AutoImportResult) -> Unit)? = null,
    ): Job? {
        val session = captureSession()
        val token = session.token
        val expectedEpoch = synchronized(tokenStateLock) { authEpoch.get() }
        val operation = EasTimetableImportGuard(token, expectedEpoch, SystemClock.elapsedRealtime() + 90_000L)
        if (!session.loggedIn) {
            onResult?.invoke(AutoImportResult.NOT_LOGGED_IN)
            return null
        }
        if (!autoImportInProgress.compareAndSet(false, true)) {
            onResult?.invoke(AutoImportResult.SKIPPED_IN_PROGRESS)
            return null
        }
        var result = AutoImportResult.FAILED
        val job = applicationScope.launch {
            try {
                session.whileCurrent {
                    val termsState = withTimeoutOrNull(6_000L) { queryTerms(session) }
                        ?: return@whileCurrent
                    if (termsState.state == DataState.STATE.NOT_LOGGED_IN) {
                        result = AutoImportResult.NOT_LOGGED_IN
                        return@whileCurrent
                    }
                    val term = (termsState.data?.firstOrNull { it.isCurrent }
                        ?: termsState.data?.firstOrNull())?.copyForQuery() ?: return@whileCurrent
                    val startState = withTimeoutOrNull(6_000L) {
                        session.service.getStartDateOnce(token, term)
                    } ?: DataState(DataState.STATE.FETCH_FAILED)
                    session.ensureCurrent()
                    if (startState.state == DataState.STATE.NOT_LOGGED_IN) {
                        result = AutoImportResult.NOT_LOGGED_IN
                        return@whileCurrent
                    }
                    val startDate = (startState.data?.clone() as? Calendar) ?: return@whileCurrent
                    if (session.campus == EASToken.Campus.BENBU) {
                        cn.limpu.hita.data.source.preference.BenbuStartDatePreferenceSource(appContext)
                            .getStartDateMillis(term.getCode())?.let { startDate.timeInMillis = it }
                    }
                    val scheduleState = withTimeoutOrNull(6_000L) {
                        session.service.getScheduleStructureOnce(term, isUndergraduate, token)
                    } ?: DataState(DataState.STATE.FETCH_FAILED)
                    session.ensureCurrent()
                    if (scheduleState.state == DataState.STATE.NOT_LOGGED_IN) {
                        result = AutoImportResult.NOT_LOGGED_IN
                        return@whileCurrent
                    }
                    val schedule = if (timetablePreferenceSource.hasSavedSchedule()) {
                        timetablePreferenceSource.getSchedule(isUndergraduate)
                    } else scheduleState.data ?: timetablePreferenceSource.getSchedule(isUndergraduate)
                    val importState = importTimetableOfTerm(session, term, startDate, schedule, TimetableImportMode.MERGE, operation, 25_000L)
                    session.ensureCurrent()
                    if (importState.state == DataState.STATE.NOT_LOGGED_IN) {
                        result = AutoImportResult.NOT_LOGGED_IN
                        return@whileCurrent
                    }
                    val examState = withTimeoutOrNull(8_000L) { session.service.getExamItemsOnce(token, term) }
                        ?: DataState(DataState.STATE.FETCH_FAILED)
                    session.ensureCurrent()
                    if (examState.state == DataState.STATE.NOT_LOGGED_IN) {
                        result = AutoImportResult.NOT_LOGGED_IN
                        return@whileCurrent
                    }
                    val coroutine = currentCoroutineContext()
                    val timetableCode = EASTimetableCode.of(session.campus, term)
                    val importedExamCount = synchronized(tokenStateLock) {
                        timetableMutationLock.exclusive {
                            coroutine.ensureActive()
                            session.ensureCurrent()
                            database.runInTransaction<Int> {
                                // A request may have been cancelled while SQLite's writer was held.
                                // Once this guard passes, the complete exam batch commits atomically.
                                coroutine.ensureActive()
                                session.ensureCurrent()
                                if (!operation.isCurrentSession(authEpoch.get(), easPreferenceSource.getEasToken())) {
                                    return@runInTransaction 0
                                }
                                val timetable = timetableDao.getTimetableByEASCodeCandidatesSync(
                                    EASTimetableCode.candidates(term, session.campus), timetableCode, term.getCode()
                                ) ?: return@runInTransaction 0
                                examImportRepository.importAutomatically(examState.data.orEmpty(), timetable).importedCount
                            }
                        }
                    }
                    if (importState.state == DataState.STATE.SUCCESS || importedExamCount > 0) result = AutoImportResult.SUCCESS
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtils.e("autoImport: failed", error)
            }
        }
        // Completion also runs if the application scope was cancelled before launch entered.
        job.invokeOnCompletion { failure ->
            operation.finishWaiting()
            autoImportInProgress.set(false)
            onResult?.invoke(if (failure == null && session.isCurrent()) result else AutoImportResult.FAILED)
        }
        return job
    }

    /** 重新登录后调用：清空「记住忽略」指纹，让上游现状重新提示一次。 */
    fun clearIgnoredTimetableChangeFingerprints() {
        timetableChangeStore.clearIgnoredFingerprints()
    }

    suspend fun getTimetableSnapshots(
        session: EasQuerySession,
        term: TermItem,
    ): DataState<List<TimetableVersionSnapshot>> = session.whileCurrent {
        val termId = term.id
        val ownerKey = FollowedTeachingSectionStore.ownerKey(session.token)
        withContext(Dispatchers.IO) {
            try {
                currentCoroutineContext().ensureActive()
                DataState(timetableSnapshotStore.snapshots(ownerKey, termId), DataState.STATE.SUCCESS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DataState(DataState.STATE.FETCH_FAILED, error.message)
            }
        }
    }

    suspend fun restoreTimetableSnapshot(
        session: EasQuerySession,
        snapshotId: String,
    ): DataState<TimetableVersionSnapshot> = session.whileCurrent {
        val ownerKey = FollowedTeachingSectionStore.ownerKey(session.token)
        withContext(Dispatchers.IO) {
            try {
                val coroutine = currentCoroutineContext()
                val restored = synchronized(tokenStateLock) {
                    timetableMutationLock.exclusive {
                        coroutine.ensureActive()
                        session.ensureCurrent()
                        val snapshot = timetableSnapshotStore.restore(ownerKey, snapshotId) {
                            coroutine.ensureActive()
                            session.ensureCurrent()
                        }
                        runCatching {
                            timetableChangeStore.pruneResolvedDecisions(
                                snapshot.termId,
                                snapshot.subjects.map { TimetableRefreshMergePolicy.courseKey(it.name, it.code) }.toSet(),
                            )
                        }.onFailure { LogUtils.e("restore: decision cleanup after commit failed", it) }
                        snapshot
                    }
                }
                DataState(restored, DataState.STATE.SUCCESS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DataState(DataState.STATE.FETCH_FAILED, error.message)
            }
        }
    }
}
