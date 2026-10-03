package cn.limpu.hita.data.repository

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.map
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
import cn.limpu.hita.data.source.web.service.EASService
import cn.limpu.hita.utils.ColorTools
import cn.limpu.hita.utils.CourseCodeUtils
import cn.limpu.hita.utils.CourseNameUtils
import cn.limpu.hita.utils.LogUtils
import cn.limpu.hita.utils.TermNameFormatter
import cn.limpu.hita.utils.TimeTools.getDateAtWOT
import com.limpu.component.data.DataState
import java.sql.Timestamp
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

internal class EASTimetableImportRepository(
    private val getService: (EASToken.Campus) -> EASService,
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
    private val autoImportInProgress: AtomicBoolean,
) {
    private var timetableWebLiveData: LiveData<DataState<List<CourseItem>>>? = null
    fun startImportTimetableOfTerm(
        term: TermItem,
        startDate: Calendar,
        schedule: List<TimePeriodInDay>,//课表结构
        importTimetableLiveData: MediatorLiveData<DataState<Boolean>>,
        importMode: TimetableImportMode = TimetableImportMode.REPLACE
    ) {
        startDate.set(Calendar.HOUR_OF_DAY, 0)
        startDate.set(Calendar.MINUTE, 0)
        startDate.firstDayOfWeek = Calendar.MONDAY
        startDate.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        startDate.set(Calendar.SECOND, 0)
        startDate.set(Calendar.MILLISECOND, 0)
        val easToken = easPreferenceSource.getEasToken()
        val timetableCode = EASTimetableCode.of(easToken.campus, term)
        val legacyTimetableCode = term.getCode()
        LogUtils.d("startImport: term=${term.getCode()}, campus=${easToken.campus}, code=$timetableCode, isLogin=${easToken.isLogin()}")
        if (easToken.isLogin()) {
            val finished = AtomicBoolean(false)
            val timeoutHandler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (finished.compareAndSet(false, true)) {
                    importTimetableLiveData.value =
                        DataState(DataState.STATE.FETCH_FAILED, "导入超时，请重试")
                }
            }
            timeoutHandler.postDelayed(timeoutRunnable, 90_000L)
            timetableWebLiveData?.let { importTimetableLiveData.removeSource(it) }
            timetableWebLiveData =
                getService(easToken.campus).getTimetableOfTerm(term, easToken)
            importTimetableLiveData.addSource(timetableWebLiveData!!) {
                when (it.state) {
                    DataState.STATE.SUCCESS -> {
                        val courseItems = it.data
                        LogUtils.d( "import: timetable response state=${it.state} term=${term.getCode()} courseCount=${courseItems?.size ?: -1}")
                        if (courseItems.isNullOrEmpty()) {
                            if (finished.compareAndSet(false, true)) {
                                timeoutHandler.removeCallbacks(timeoutRunnable)
                                importTimetableLiveData.value =
                                    DataState(DataState.STATE.FETCH_FAILED, "empty timetable")
                            }
                            return@addSource
                        }
                        Thread {
                            try {
                                val meta = if (easToken.campus == EASToken.Campus.SHENZHEN) {
                                    fetchSelectedSubjectMeta(term, easToken)
                                } else {
                                    SelectedSubjectMeta(
                                        emptyMap(),
                                        emptyMap(),
                                        emptyMap(),
                                        emptyMap(),
                                        emptyMap(),
                                        emptyMap()
                                    )
                                }
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

                                    for (week in item.weeks) {
                                        val from = getDateAtWOT(startDate, week, item.dow)
                                        val to = getDateAtWOT(startDate, week, item.dow)

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
                                if (events.isEmpty()) {
                                    LogUtils.w("import: empty events for term=${term.getCode()}")
                                    if (finished.compareAndSet(false, true)) {
                                        timeoutHandler.removeCallbacks(timeoutRunnable)
                                        importTimetableLiveData.postValue(
                                            DataState(DataState.STATE.FETCH_FAILED, "empty events")
                                        )
                                    }
                                    return@Thread
                                }
                                LogUtils.d( "import: saving ${events.size} events for term=${term.getCode()}")
                                val snapshotOwnerKey = FollowedTeachingSectionStore.ownerKey(easToken)
                                if (importMode == TimetableImportMode.MERGE) {
                                    applyMergedTimetable(
                                        term = term,
                                        easToken = easToken,
                                        timetable = timetable,
                                        pendingSubjects = pendingSubjects,
                                        events = events,
                                        maxTs = maxTs,
                                        timetableCode = timetableCode,
                                        timetableName = buildTimetableName(term, easToken.campus),
                                        startMillis = startDate.timeInMillis,
                                        schedule = safeSchedule
                                    )
                                } else {
                                    timetableSnapshotStore.capture(
                                        snapshotOwnerKey,
                                        term,
                                        timetable,
                                        TimetableSnapshotKind.BEFORE_REFRESH
                                    )
                                    eventItemDao.deleteCourseFromTimetable(timetable.id)
                                    subjectDao.saveSubjectsSync(pendingSubjects.values.toList())
                                    eventItemDao.saveEvents(events)

                                    //更新timetable对象
                                    timetable.name = buildTimetableName(term, easToken.campus)
                                    timetable.startTime = Timestamp(startDate.timeInMillis)
                                    timetable.endTime = Timestamp(maxTs)
                                    timetable.code = timetableCode
                                    timetable.scheduleStructure = safeSchedule
                                    timetableDao.saveTimetableSync(timetable)
                                    timetableSnapshotStore.capture(
                                        snapshotOwnerKey,
                                        term,
                                        timetable,
                                        TimetableSnapshotKind.IMPORTED
                                    )
                                }
                                cleanupDefaultDuplicateTimetablesAfterImport(timetable.id)

                                if (finished.compareAndSet(false, true)) {
                                    timeoutHandler.removeCallbacks(timeoutRunnable)
                                    LogUtils.success("import: term=${term.getCode()} events=${events.size}")
                                    importTimetableLiveData.postValue(DataState(true, DataState.STATE.SUCCESS))
                                }
                            } catch (e: Exception) {
                                LogUtils.e( "import: failed for term=${term.getCode()}", e)
                                if (finished.compareAndSet(false, true)) {
                                    timeoutHandler.removeCallbacks(timeoutRunnable)
                                    importTimetableLiveData.postValue(
                                        DataState(DataState.STATE.FETCH_FAILED, e.message)
                                    )
                                }
                            }
                        }.start()
                    }
                    DataState.STATE.FETCH_FAILED, DataState.STATE.NOT_LOGGED_IN -> {
                        LogUtils.w( "import: timetable fetch failed for term=${term.getCode()} message=${it.message}")
                        if (finished.compareAndSet(false, true)) {
                            timeoutHandler.removeCallbacks(timeoutRunnable)
                            importTimetableLiveData.value =
                                DataState(DataState.STATE.FETCH_FAILED, it.message)
                        }
                    }
                    else -> Unit
                }
            }
        } else {
            LogUtils.e("startImport: not logged in, cannot import")
            importTimetableLiveData.value = DataState(DataState.STATE.NOT_LOGGED_IN)
        }
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
        easToken: EASToken,
        timetable: Timetable,
        pendingSubjects: Map<String, TermSubject>,
        events: List<EventItem>,
        maxTs: Long,
        timetableCode: String,
        timetableName: String,
        startMillis: Long,
        schedule: List<TimePeriodInDay>
    ) = timetableMutationLock.exclusive {
        val snapshotOwnerKey = FollowedTeachingSectionStore.ownerKey(easToken)
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

        // 本地没有任何已导入课程 = 首次导入（新设备/新装/刚导入课表）：
        // 此时"全部课程"只是初始数据，不是变更，不产生变更提示。
        val firstImport = localCourses.isEmpty()

        val plan = TimetableRefreshMergePolicy.plan(
            local = localCourses,
            incoming = incomingCourses,
            vetoes = timetableChangeStore.getVetoes(term.id),
            nowMillis = System.currentTimeMillis()
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

        val replacedSubjectIds = plan.replacedLocal.mapTo(HashSet()) { it.subjectId }
        if (replacedSubjectIds.isNotEmpty()) {
            eventItemDao.deleteEventsFromSubjectsSync(replacedSubjectIds.toList())
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

            timetable.name = timetableName
            timetable.startTime = Timestamp(startMillis)
            timetable.code = timetableCode
            timetable.scheduleStructure = schedule
            val keptMax = localEvents
                .filterNot { it.subjectId in replacedSubjectIds }
                .maxOfOrNull { it.to.time }
                ?: 0L
            timetable.endTime = Timestamp(maxOf(maxTs, keptMax))
            timetableDao.saveTimetableSync(timetable)
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
        timetableDao.deleteTimetablesInIdsSync(deleteIds)
        eventItemDao.deleteEventsFromTimetablesSync(deleteIds)
        subjectDao.deleteSubjectsFromTimetablesSync(deleteIds)
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
        val codeMap: Map<String, String>,
        val teacherMap: Map<String, String>,
        val creditMap: Map<String, Float>,
        val fieldMap: Map<String, String>,
        val selectCategoryMap: Map<String, String>,
        val natureMap: Map<String, String>
    )

    private fun fetchSelectedSubjectMeta(term: TermItem, token: EASToken): SelectedSubjectMeta {
        val codeMap = mutableMapOf<String, String>()
        val teacherMap = mutableMapOf<String, String>()
        val creditMap = mutableMapOf<String, Float>()
        val fieldMap = mutableMapOf<String, String>()
        val selectCategoryMap = mutableMapOf<String, String>()
        val natureMap = mutableMapOf<String, String>()
        val latch = CountDownLatch(1)
        val live = getService(token.campus).getSubjectsOfTerm(token, term)
        val observer = Observer<DataState<MutableList<TermSubject>>> { state ->
            if (state.state == DataState.STATE.SUCCESS || state.state == DataState.STATE.FETCH_FAILED) {
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
                latch.countDown()
            }
        }
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post { live.observeForever(observer) }
        latch.await(8, TimeUnit.SECONDS)
        mainHandler.post { live.removeObserver(observer) }
        return SelectedSubjectMeta(
            codeMap,
            teacherMap,
            creditMap,
            fieldMap,
            selectCategoryMap,
            natureMap
        )
    }

    fun startAutoImportCurrentTimetable(
        isUndergraduate: Boolean,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        val token = easPreferenceSource.getEasToken()
        if (!token.isLogin()) {
            onResult?.invoke(false)
            return
        }
        if (!autoImportInProgress.compareAndSet(false, true)) {
            LogUtils.d("autoImport: ignored duplicate request while another import is running")
            onResult?.invoke(false)
            return
        }
        val expectedEpoch = authEpoch.get()
        Thread {
            var importedSuccessfully = false
            try {
                if (!isCurrentAuthOperation(expectedEpoch)) return@Thread
                val service = getService(token.campus)
                val termsState = awaitLiveData(service.getAllTerms(token), 6)
                LogUtils.d("autoImport: terms state=${termsState.state} count=${termsState.data?.size ?: -1}")
                if (!isCurrentAuthOperation(expectedEpoch)) return@Thread
                val term = termsState.data?.firstOrNull { it.isCurrent }
                    ?: termsState.data?.firstOrNull()
                    ?: return@Thread

                val startState = awaitLiveData(service.getStartDate(token, term), 6)
                val startDate = startState.data
                LogUtils.d("autoImport: startDate state=${startState.state}")
                if (!isCurrentAuthOperation(expectedEpoch)) return@Thread
                val scheduleState = awaitLiveData(
                    service.getScheduleStructure(term, isUndergraduate, token),
                    6
                )
                val schedule = scheduleState.data
                    ?: timetablePreferenceSource.getSchedule(isUndergraduate)
                LogUtils.d("autoImport: schedule state=${scheduleState.state} size=${schedule.size}")
                if (startDate == null || !isCurrentAuthOperation(expectedEpoch)) return@Thread

                val importLive = MediatorLiveData<DataState<Boolean>>()
                val latch = CountDownLatch(1)
                var timetableImported = false
                val observer = Observer<DataState<Boolean>> { state ->
                    if (state.state == DataState.STATE.SUCCESS ||
                        state.state == DataState.STATE.FETCH_FAILED
                    ) {
                        timetableImported = state.state == DataState.STATE.SUCCESS
                        latch.countDown()
                    }
                }
                val mainHandler = Handler(Looper.getMainLooper())
                mainHandler.post {
                    if (!isCurrentAuthOperation(expectedEpoch)) {
                        latch.countDown()
                        return@post
                    }
                    importLive.observeForever(observer)
                    startImportTimetableOfTerm(
                        term,
                        startDate,
                        schedule,
                        importLive,
                        TimetableImportMode.MERGE
                    )
                }
                latch.await(25, TimeUnit.SECONDS)
                mainHandler.post { importLive.removeObserver(observer) }
                if (!isCurrentAuthOperation(expectedEpoch)) return@Thread

                val examState = awaitLiveData(service.getExamItems(token, term), 8)
                if (!isCurrentAuthOperation(expectedEpoch)) return@Thread
                val timetableCode = EASTimetableCode.of(token.campus, term)
                val timetable = timetableDao.getTimetableByEASCodeCandidatesSync(
                    EASTimetableCode.candidates(term, token.campus),
                    timetableCode,
                    term.getCode()
                )
                val importedExamCount = if (timetable == null) {
                    LogUtils.w("autoImport: skip exams, timetable not found code=$timetableCode")
                    0
                } else {
                    importExamItemsSync(examState.data.orEmpty(), timetable)
                }
                LogUtils.d(
                    "autoImport: exam state=${examState.state} " +
                        "total=${examState.data?.size ?: -1} imported=$importedExamCount"
                )
                importedSuccessfully = timetableImported || importedExamCount > 0
            } catch (error: Exception) {
                LogUtils.e("autoImport: failed, error=${error.message}", error)
            } finally {
                autoImportInProgress.set(false)
                onResult?.invoke(importedSuccessfully)
            }
        }.start()
    }

    private fun isCurrentAuthOperation(expectedEpoch: Long): Boolean {
        return authEpoch.get() == expectedEpoch && easPreferenceSource.getEasToken().isLogin()
    }

    @WorkerThread
    private fun importExamItemsSync(exams: List<ExamItem>, timetable: Timetable): Int {
        if (exams.isEmpty()) return 0
        val existingKeys = eventItemDao.getExamEventsSync()
            .mapTo(mutableSetOf()) { ExamEventMapper.identityKey(it) }
        var importedCount = 0

        for (exam in exams) {
            val examEvent = ExamEventMapper.toEvent(exam, timetable.id, "EASRepository") ?: continue
            val key = ExamEventMapper.identityKey(examEvent)
            if (!existingKeys.add(key)) continue
            eventItemDao.insertEventSync(examEvent)
            importedCount++
        }
        return importedCount
    }

    private fun <T> awaitLiveData(
        live: LiveData<DataState<T>>,
        timeoutSeconds: Long
    ): DataState<T> {
        val latch = CountDownLatch(1)
        var result = DataState<T>(DataState.STATE.FETCH_FAILED)
        val observer = Observer<DataState<T>> { state ->
            if (state.state == DataState.STATE.NOTHING || state.state == DataState.STATE.LOADING) {
                return@Observer
            }
            result = state
            latch.countDown()
        }
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post { live.observeForever(observer) }
        latch.await(timeoutSeconds, TimeUnit.SECONDS)
        mainHandler.post { live.removeObserver(observer) }
        return result
    }
    fun getTimetableSnapshots(term: TermItem): LiveData<DataState<List<TimetableVersionSnapshot>>> {
        val result = MutableLiveData<DataState<List<TimetableVersionSnapshot>>>(
            DataState(DataState.STATE.NOTHING)
        )
        thread(name = "timetable-snapshot-list") {
            runCatching {
                val token = easPreferenceSource.getEasToken()
                val ownerKey = FollowedTeachingSectionStore.ownerKey(token)
                timetableSnapshotStore.snapshots(ownerKey, term.id)
            }.onSuccess {
                result.postValue(DataState(it, DataState.STATE.SUCCESS))
            }.onFailure { error ->
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }
        return result
    }

    fun restoreTimetableSnapshot(snapshotId: String): LiveData<DataState<TimetableVersionSnapshot>> {
        val result = MutableLiveData<DataState<TimetableVersionSnapshot>>(
            DataState(DataState.STATE.NOTHING)
        )
        thread(name = "timetable-snapshot-restore") {
            runCatching {
                val token = easPreferenceSource.getEasToken()
                val ownerKey = FollowedTeachingSectionStore.ownerKey(token)
                val restored = timetableMutationLock.exclusive {
                    val snapshot = timetableSnapshotStore.restore(ownerKey, snapshotId)
                    timetableChangeStore.pruneResolvedDecisions(
                        snapshot.termId,
                        snapshot.subjects.map {
                            TimetableRefreshMergePolicy.courseKey(it.name, it.code)
                        }.toSet()
                    )
                    snapshot
                }
                restored
            }.onSuccess {
                result.postValue(DataState(it, DataState.STATE.SUCCESS))
            }.onFailure { error ->
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }
        return result
    }
}
