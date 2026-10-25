package cn.limpu.hita.data.repository

import android.app.Application
import androidx.annotation.WorkerThread
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.utils.ColorTools
import cn.limpu.hita.utils.CourseNameUtils
import java.sql.Timestamp
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 课表源数据的用户决策应用层。
 *
 * 职责边界：
 * - [EASRepository] 负责"拉取 → 生成 → 按 [TimetableRefreshMergePolicy] 合并"的自动刷新链路；
 * - 本类负责"用户对挂起数据做出决策后的落库"：采用某门课的源端版本、删除某门课、
 *   或整批采用挂起的源端数据。写库前统一捕获 [TimetableSnapshotKind.BEFORE_USER_ADOPT] 快照兜底。
 *
 * 与自动刷新解耦：这里只处理用户在课表变更详情里显式确认过的数据。
 */
@Singleton
class TimetableSourceApplier internal constructor(
    application: Application,
    private val easPreferenceSource: EasPreferenceSource,
    private val changeStore: TimetableChangeStore,
    private val timetableMutationLock: TimetableMutationLock,
    private val database: AppDatabase,
) {
    @Inject constructor(
        application: Application,
        easPreferenceSource: EasPreferenceSource,
        changeStore: TimetableChangeStore,
        timetableMutationLock: TimetableMutationLock,
    ) : this(application, easPreferenceSource, changeStore, timetableMutationLock,
        AppDatabase.getDatabase(application))

    private val appContext = application.applicationContext
    private val eventItemDao = database.eventItemDao()
    private val timetableDao = database.timetableDao()
    private val subjectDao = database.subjectDao()
    private val snapshotStore = TimetableSnapshotStore(appContext, database)

    /** 采用某门待确认课程的源端版本（源端整门缺失时即删除本地该课）。 */
    fun adoptCourse(termId: String, courseKey: String) {
        timetableMutationLock.exclusive {
            // 在锁内重读。刷新若已确认该课回归并清掉决策，这里必须放弃删除。
            val item = changeStore.currentState().decisions.firstOrNull {
                it.termId == termId && it.courseKey == courseKey
            } ?: error("待确认项不存在或已处理")
            database.runInTransaction { applyCourseDecision(item) }
            changeStore.consumeDecision(termId, courseKey)
        }
    }

    /** 继续保留某门待确认课程：保留本地，并重置观察窗口。 */
    fun dismissCourse(termId: String, courseKey: String) {
        timetableMutationLock.exclusive {
            changeStore.consumeDecision(termId, courseKey)
            changeStore.resetVeto(termId, courseKey)
        }
    }

    /** 采用整批挂起的源端数据：全量替换当前课表。 */
    fun adoptHeldBatch() {
        timetableMutationLock.exclusive {
            val batch = changeStore.currentState().heldBatch ?: error("没有待处理的课表源数据")
            database.runInTransaction { applyHeldBatch(batch) }
            changeStore.consumeHeldBatch()
        }
    }

    /** 保留当前课表：丢弃整批挂起的源端数据，并为缺失课建立 veto 追踪避免反复询问。 */
    fun dismissHeldBatch() {
        timetableMutationLock.exclusive {
            val batch = changeStore.consumeHeldBatch() ?: return
            changeStore.trackHeldBatchMissing(
                batch.termId,
                batch.unmatchedLocal,
                System.currentTimeMillis()
            )
        }
    }

    /**
     * 用户对时间/地点/教师/新增课的多选确认。
     * [adopted] 为勾选采纳的指纹；[remember] 为忽略且要求记住的指纹。
     * 所有课表同一事务落库，提交成功后才清除待确认项、记住忽略；失败时两者原样保留可重试。
     */
    fun applyPendingConfirmations(adopted: Set<String>, remember: Collection<String>) {
        timetableMutationLock.exclusive {
            val state = changeStore.currentState()
            if (state.pendingUpdates.isNotEmpty()) {
                database.runInTransaction {
                    for ((timetableId, group) in state.pendingUpdates.groupBy { it.timetableId }) {
                        applyPendingGroup(timetableId, group, adopted)
                    }
                }
                changeStore.clearPendingUpdates(state.pendingRevision)
            }
            changeStore.rememberIgnored(remember)
        }
    }

    @WorkerThread
    private fun applyPendingGroup(
        timetableId: String,
        updates: List<PendingCourseUpdate>,
        adopted: Set<String>,
    ) {
        val timetable = timetableDao.getTimetableByIdSync(timetableId) ?: return
        val termId = updates.firstOrNull()?.termId.orEmpty()
        if (termId.isNotEmpty()) captureSnapshot(timetable, termId)
        val localSubjects = subjectDao.getSubjectsSync(timetable.id).associateBy { it.id }
        var maxTo = timetable.endTime.time
        for (update in updates) {
            if (update.rows.none { it.fingerprint in adopted }) continue
            val patched = CourseChangeConfirm.applyRows(
                local = update.local,
                incoming = update.incoming,
                adopted = adopted,
                rows = update.rows,
            ) ?: continue
            if (patched.lessons.isEmpty() && !update.isAdded) continue
            val existing = localSubjects[patched.subjectId]
            val subject = existing ?: TermSubject().apply {
                this.id = patched.subjectId
                this.timetableId = timetable.id
                color = ColorTools.colorForName(CourseNameUtils.normalize(patched.name) ?: patched.name)
            }
            subject.name = patched.name
            subject.timetableId = timetable.id
            if (!patched.code.isNullOrBlank()) subject.code = patched.code
            eventItemDao.deleteImportedClassesFromSubjectsSync(timetable.id, listOf(subject.id))
            subjectDao.saveSubjectsSync(listOf(subject))
            val events = patched.lessons.map { it.toEventItem(subject.id, timetable.id) }
            if (events.isNotEmpty()) eventItemDao.saveEvents(events)
            maxTo = maxOf(maxTo, events.maxOfOrNull { it.to.time } ?: 0L)
        }
        timetable.endTime = Timestamp(maxTo)
        timetableDao.saveTimetableSync(timetable)
    }

    @WorkerThread
    private fun applyCourseDecision(item: TimetableDecisionItem) {
        val timetable = timetableDao.getTimetableByIdSync(item.timetableId)
            ?: error("课表不存在")
        captureSnapshot(timetable, item.termId)

        val incoming = item.incoming
        if (incoming == null) {
            // 用户确认该课真实取消：仅删除教务导入课次，无事件引用时清理科目
            eventItemDao.deleteImportedClassesFromSubjectsSync(timetable.id, listOf(item.subjectId))
            subjectDao.deleteUnusedSubjectsInTimetableSync(timetable.id, listOf(item.subjectId))
            return
        }
        val localSubject = subjectDao.getSubjectsSync(timetable.id)
            .firstOrNull { it.id == item.subjectId }
        val subject = localSubject ?: TermSubject().apply {
            id = incoming.subjectId
            timetableId = timetable.id
            color = ColorTools.colorForName(CourseNameUtils.normalize(incoming.name) ?: incoming.name)
        }
        subject.name = incoming.name
        subject.timetableId = timetable.id
        if (!incoming.code.isNullOrBlank()) subject.code = incoming.code
        eventItemDao.deleteImportedClassesFromSubjectsSync(timetable.id, listOf(subject.id))
        subjectDao.saveSubjectsSync(listOf(subject))
        eventItemDao.saveEvents(
            incoming.lessons.map { it.toEventItem(subject.id, timetable.id) }
        )
        timetable.endTime = Timestamp(
            maxOf(timetable.endTime.time, incoming.lessons.maxOfOrNull { it.toMillis } ?: 0L)
        )
        timetableDao.saveTimetableSync(timetable)
    }

    @WorkerThread
    private fun applyHeldBatch(batch: TimetableHeldBatch) {
        val timetable = timetableDao.getTimetableByIdSync(batch.timetableId)
            ?: error("课表不存在")
        captureSnapshot(timetable, batch.termId)

        val localSubjects = subjectDao.getSubjectsSync(timetable.id).associateBy { it.id }
        val subjects = batch.courses.map { course ->
            val existing = localSubjects[course.subjectId]
            if (existing != null) {
                existing.name = course.name
                if (!course.code.isNullOrBlank()) existing.code = course.code
                existing
            } else {
                TermSubject().apply {
                    id = course.subjectId
                    name = course.name
                    timetableId = timetable.id
                    code = course.code
                    color = ColorTools.colorForName(
                        CourseNameUtils.normalize(course.name) ?: course.name
                    )
                }
            }
        }
        val events = batch.courses.flatMap { course ->
            course.lessons.map { it.toEventItem(course.subjectId, timetable.id) }
        }
        eventItemDao.deleteCourseFromTimetable(timetable.id)
        subjectDao.saveSubjectsSync(subjects)
        eventItemDao.saveEvents(events)
        timetable.code = batch.timetableCode
        timetable.startTime = Timestamp(batch.startMillis)
        timetable.scheduleStructure = batch.schedule
        timetable.endTime = Timestamp(events.maxOfOrNull { it.to.time } ?: batch.startMillis)
        timetableDao.saveTimetableSync(timetable)
        // 本地已被源端整体替换：该学期的 veto 记忆与待确认项作废
        changeStore.clearTerm(batch.termId)
    }

    private fun captureSnapshot(timetable: Timetable, termId: String) {
        val token: EASToken = easPreferenceSource.getEasToken()
        snapshotStore.capture(
            FollowedTeachingSectionStore.ownerKey(token),
            termOf(termId),
            timetable,
            TimetableSnapshotKind.BEFORE_USER_ADOPT
        )
    }

    /** termId 形如 "yearCode-termCode"（见 [TermItem.id]），快照捕获需要 TermItem。 */
    private fun termOf(termId: String): TermItem {
        val parts = termId.split('-')
        val yearCode = parts.take(2).joinToString("-")
        return TermItem(
            yearCode = yearCode,
            yearName = yearCode,
            termCode = parts.lastOrNull().orEmpty(),
            termName = ""
        )
    }

    private fun MergeLesson.toEventItem(subjectId: String, timetableId: String): EventItem {
        return EventItem().apply {
            type = EventItem.TYPE.CLASS
            source = EventItem.SOURCE_EAS_IMPORT
            name = this@toEventItem.name
            place = this@toEventItem.place
            teacher = this@toEventItem.teacher
            this.subjectId = subjectId
            this.timetableId = timetableId
            from = Timestamp(this@toEventItem.fromMillis)
            to = Timestamp(this@toEventItem.toMillis)
            fromNumber = this@toEventItem.fromNumber
            lastNumber = this@toEventItem.lastNumber
        }
    }
}
