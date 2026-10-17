package cn.limpu.hita.data.repository

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.WorkerThread
import javax.inject.Inject
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.limpu.component.data.DataState
import com.limpu.component.data.MTransformations
import cn.limpu.hita.R
import cn.limpu.hita.utils.LogUtils
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogItem
import cn.limpu.hita.data.model.timetable.DisplayScope
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.SubjectColor
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.model.timetable.TimetableGroupEntity
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.ui.main.timetable.TimetableFragment.Companion.WEEK_MILLS
import cn.limpu.hita.utils.TimeTools
import cn.limpu.hita.utils.ColorTools
import java.lang.NumberFormatException
import java.util.*
import java.util.concurrent.Executors

import net.fortuna.ical4j.model.component.VAlarm

import net.fortuna.ical4j.model.component.VEvent

import net.fortuna.ical4j.data.CalendarOutputter

import net.fortuna.ical4j.model.Dur

import net.fortuna.ical4j.util.UidGenerator

import net.fortuna.ical4j.model.DateTime

import net.fortuna.ical4j.model.property.*
import net.fortuna.ical4j.data.CalendarBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream


class TimetableRepository internal constructor(
    val application: Application,
    private val database: AppDatabase,
) {
    @Inject constructor(application: Application) : this(application, AppDatabase.getDatabase(application))

    companion object {
        private const val EXAM_REMINDER_DAYS = 30
    }

    private val manualEventFallbackColor by lazy {
        ContextCompat.getColor(application, R.color.subject8)
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val eventItemDao = database.eventItemDao()
    private val timetableDao = database.timetableDao()
    private val timetableGroupDao = database.timetableGroupDao()
    private val subjectDao = database.subjectDao()
    private val easPreferenceSource by lazy {
        EasPreferenceSource(application.applicationContext)
    }
    private val followedTeachingSectionStore by lazy {
        FollowedTeachingSectionStore(application.applicationContext)
    }
    private val courseSelectionDraftStore by lazy {
        CourseSelectionDraftStore(application.applicationContext)
    }

    /**
     * 获取[from,to)内的事件
     */
    fun getEventsDuring(from: Long, to: Long): LiveData<List<EventItem>> {
        return MTransformations.map(eventItemDao.getEventsDuring(from, to), ::dedupeDisplayEvents)
    }

    /** 显示目标过滤版：今日时间线使用。空集合返回空列表。 */
    fun getEventsDuring(from: Long, to: Long, timetableIds: List<String>): LiveData<List<EventItem>> {
        if (timetableIds.isEmpty()) return MutableLiveData(emptyList())
        return MTransformations.map(
            eventItemDao.getEventsDuringForTimetables(from, to, timetableIds), ::dedupeDisplayEvents)
    }

    /**
     * 获取[from,...)内的至多limit个事件
     */
    fun getEventsAfter(from: Long,limit:Int): LiveData<List<EventItem>> {
        return MTransformations.map(eventItemDao.getEventsAfter(from, limit * 3)) { events ->
            dedupeDisplayEvents(events).sortedBy { it.from.time }.take(limit)
        }
    }

    /** 显示目标过滤版：时间线「本周后续」使用。 */
    fun getEventsAfter(from: Long, limit: Int, timetableIds: List<String>): LiveData<List<EventItem>> {
        if (timetableIds.isEmpty()) return MutableLiveData(emptyList())
        return MTransformations.map(eventItemDao.getEventsAfterForTimetables(from, limit * 3, timetableIds)) { events ->
            dedupeDisplayEvents(events).sortedBy { it.from.time }.take(limit)
        }
    }

    fun getUpcomingExamsWithinReminderWindow(from: Long): LiveData<List<EventItem>> {
        return eventItemDao.getExamsDuring(from, from + EXAM_REMINDER_DAYS * 24L * 60L * 60L * 1000L)
    }

    fun getUpcomingExamsWithinReminderWindow(from: Long, timetableIds: List<String>): LiveData<List<EventItem>> {
        if (timetableIds.isEmpty()) return MutableLiveData(emptyList())
        return eventItemDao.getExamsDuringForTimetables(
            from, from + EXAM_REMINDER_DAYS * 24L * 60L * 60L * 1000L, timetableIds)
    }

    @WorkerThread
    fun getUpcomingExamsWithinReminderWindowSync(from: Long, timetableIds: List<String>): List<EventItem> {
        if (timetableIds.isEmpty()) return emptyList()
        return eventItemDao.getExamsDuringForTimetablesSync(
            from, from + EXAM_REMINDER_DAYS * 24L * 60L * 60L * 1000L, timetableIds)
    }

    @WorkerThread
    fun getUpcomingExamsWithinReminderWindowSync(from: Long): List<EventItem> {
        return eventItemDao.getExamsDuringSync(
            from,
            from + EXAM_REMINDER_DAYS * 24L * 60L * 60L * 1000L
        )
    }


    /**
     * 获取今日事件
     */
    @WorkerThread
    fun getTodayEventsSync(): List<EventItem> {
        val now = Calendar.getInstance()
        now.set(Calendar.HOUR_OF_DAY,0)
        now.set(Calendar.MINUTE,0)
        val from = now.timeInMillis
        now.add(Calendar.DATE,1)
        val to = now.timeInMillis
        return dedupeDisplayEvents(eventItemDao.getEventsDuringSync(from, to))
            .sortedBy { it.from.time }
    }

    /** 显示目标过滤版今日事件：桌面小组件使用。 */
    @WorkerThread
    fun getTodayEventsSync(timetableIds: List<String>): List<EventItem> {
        if (timetableIds.isEmpty()) return emptyList()
        val now = Calendar.getInstance()
        now.set(Calendar.HOUR_OF_DAY,0)
        now.set(Calendar.MINUTE,0)
        val from = now.timeInMillis
        now.add(Calendar.DATE,1)
        val to = now.timeInMillis
        return dedupeDisplayEvents(eventItemDao.getEventsDuringForTimetablesSync(from, to, timetableIds))
            .sortedBy { it.from.time }
    }

    @WorkerThread
    fun getUpcomingEventsSync(from: Long, to: Long): List<EventItem> {
        return dedupeDisplayEvents(eventItemDao.getEventsDuringSync(from, to))
            .sortedBy { it.from.time }
    }

    /** 显示目标过滤版：课程提醒 Worker 使用。 */
    @WorkerThread
    fun getUpcomingEventsSync(from: Long, to: Long, timetableIds: List<String>): List<EventItem> {
        if (timetableIds.isEmpty()) return emptyList()
        return dedupeDisplayEvents(eventItemDao.getEventsDuringForTimetablesSync(from, to, timetableIds))
            .sortedBy { it.from.time }
    }

    @WorkerThread
    fun getTodayEventsWithUpcomingExamSync(): List<EventItem> {
        return appendUpcomingExam(getTodayEventsSync(), System.currentTimeMillis())
    }

    fun appendUpcomingExam(todayEvents: List<EventItem>, now: Long): List<EventItem> {
        val exams = getUpcomingExamsWithinReminderWindowSync(now)
        if (exams.isEmpty()) return todayEvents
        val todayEventIds = todayEvents.mapTo(mutableSetOf()) { it.id }
        return (todayEvents + exams.filter { it.id !in todayEventIds }).sortedBy { it.from.time }
    }
    /**
     * 获取[from,to)内的事件，包含颜色
     *
     * 这是主课表显示入口，底层 DAO 会返回所有课表的事件。
     * 为兼容历史版本产生的重复默认课表，这里对完全相同的 EAS 课程做显示层去重；
     * 手动活动、考试、ICS 导入等非 EAS 课程不在这里合并。
     */
    fun getEventsDuringWithColor(from: Long, to: Long): LiveData<List<EventItem>> {
        return MTransformations.switchMap(eventItemDao.getEventsDuring(from, to)) { events ->
            attachSubjectColors(events)
        }
    }

    /** 显示目标过滤版：只取目标课表集合内与窗口相交的事件，其余逻辑与全量版一致。 */
    fun getEventsDuringWithColor(from: Long, to: Long, timetableIds: List<String>): LiveData<List<EventItem>> {
        if (timetableIds.isEmpty()) return MutableLiveData(emptyList())
        return MTransformations.switchMap(
            eventItemDao.getEventsDuringForTimetables(from, to, timetableIds)
        ) { events ->
            attachSubjectColors(events)
        }
    }

    private fun attachSubjectColors(events: List<EventItem>): LiveData<List<EventItem>> {
        val displayEvents = dedupeDisplayEvents(events)
        val subjects = mutableSetOf<String>()
        for (e in displayEvents) {
            if (e.subjectId.isNotBlank()) {
                subjects.add(e.subjectId)
            }
        }
        val colorSource = subjectDao.getSubjectColorsWithId(subjects)
        return MTransformations.map(colorSource) { colors ->
            val map = mutableMapOf<String, Int>()
            for (color in colors) {
                map[color.id] = color.color
            }
            for (e in displayEvents) {
                map[e.subjectId]?.let {
                    e.color = it
                } ?: run {
                    if (e.subjectId.isBlank()) {
                        e.color = manualEventFallbackColor
                    }
                }
            }
            displayEvents
        }
    }

    fun getClassesOfSubject(subjectId: String): LiveData<List<EventItem>> {

        return eventItemDao.getClassesOfSubject(subjectId)
    }

    /** 显示目标内全部课程的颜色（分配器输入）。 */
    fun getSubjectColorsOfTimetables(timetableIds: List<String>): LiveData<List<SubjectColor>> {
        if (timetableIds.isEmpty()) return MutableLiveData(emptyList())
        return subjectDao.getSubjectColorsOfTimetables(timetableIds)
    }

    @WorkerThread
    fun getClassesOfSubjectSync(subjectId: String): List<EventItem> {
        return eventItemDao.getClassesOfSubjectSync(subjectId)
    }

    fun getEventsOfTimetable(timetableId: String): LiveData<List<EventItem>> {
        return eventItemDao.getEventsOfTimetable(timetableId)
    }

    /**
     * 获取所有课表
     */
    fun getTimetables(): LiveData<List<Timetable>> {
        return timetableDao.getTimetables()
    }

    fun getTimetablesById(id: String): LiveData<Timetable> {
        return timetableDao.getTimetableById(id)
    }

    @WorkerThread
    fun getRecentTimetableSync(): Timetable? {
        return timetableDao.getTimetableClosestToTimestampSync(System.currentTimeMillis())
    }

    @WorkerThread
    fun getTimetableByIdSync(id: String): Timetable? {
        return timetableDao.getTimetableByIdSync(id)
    }

    @WorkerThread
    fun getEventsOfTimetableSync(
        timetableId: String,
        fromMs: Long? = null,
        toMs: Long? = null
    ): List<EventItem> {
        if (fromMs != null && toMs != null) {
            return eventItemDao.getEventsOfTimetableDuringSync(timetableId, fromMs, toMs)
        }
        return eventItemDao.getEventsOfTimetableSync(timetableId)
    }

    @WorkerThread
    fun getEventsOfAllTimetablesSync(fromMs: Long?, toMs: Long?): List<EventItem> {
        if (fromMs != null && toMs != null) {
            return eventItemDao.getEventsOfAllTimetablesDuringSync(fromMs, toMs)
        }
        return emptyList()
    }

    /** 显示目标过滤版：Agent 课表查询使用。 */
    @WorkerThread
    fun getEventsOfTimetablesSync(timetableIds: List<String>, fromMs: Long?, toMs: Long?): List<EventItem> {
        if (timetableIds.isEmpty()) return emptyList()
        if (fromMs != null && toMs != null) {
            return eventItemDao.getEventsOfTimetablesDuringSync(timetableIds, fromMs, toMs)
        }
        return emptyList()
    }

    fun getTimetableByEasCode(code: String): LiveData<Timetable?> {
        return timetableDao.getTimetableByEASCode(code)
    }

    /**
     * 获得某学期（本地可能没有）的当前周数
     */
    fun getCurrentWeekOfTimetable(termItem: TermItem?): LiveData<Int> {
        if (termItem == null) {
            return MTransformations.map(timetableDao.getTimetableByEASCode("")) {
                1
            }
        }
        val campus = easPreferenceSource.getEasToken().campus
        val preferredCode = EASTimetableCode.of(campus, termItem)
        val legacyCode = termItem.getCode()
        return MTransformations.map(
            timetableDao.getTimetableByEASCodeCandidates(
                EASTimetableCode.candidates(termItem, campus),
                preferredCode,
                legacyCode
            )
        ) {
            it?.getWeekNumber(System.currentTimeMillis()) ?: 1
        }
    }

    fun getRecentTimetable(): LiveData<Timetable?> {
        return timetableDao.getTimetableClosestToTimestamp(System.currentTimeMillis())
    }

    fun getTimetableCount(): LiveData<Int> {
        return timetableDao.geeTimetableCount()
    }

    fun searchLocation(str: String): LiveData<List<String>> {
        return timetableDao.searchLocation("%$str%")
    }

    @WorkerThread
    fun searchEventsByKeywordSync(keyword: String): List<EventItem> {
        return eventItemDao.searchEventsByNameSync(keyword)
    }

    /** 显示目标过滤版：Agent 课表搜索使用。 */
    @WorkerThread
    fun searchEventsByKeywordSync(keyword: String, timetableIds: List<String>): List<EventItem> {
        if (timetableIds.isEmpty()) return emptyList()
        return eventItemDao.searchEventsByNameInTimetablesSync(keyword, timetableIds)
    }

    fun actionDeleteTimetables(timetables: List<Timetable>) {
        val ids = timetables.map { it.id }
        executor.execute {
            database.runInTransaction {
                // Caller objects may predate a move; use current membership for cleanup.
                val groups = timetableDao.getTimetablesInIdsSync(ids).mapNotNull { it.groupId }.distinct()
                eventItemDao.deleteEventsFromTimetablesSync(ids)
                subjectDao.deleteSubjectsFromTimetablesSync(ids)
                timetableDao.deleteTimetablesInIdsSync(ids)
                groups.forEach(::deleteGroupIfEmptySync)
            }
        }
    }

    // ---------- 组合课表 ----------

    private val displayScopeResolver by lazy { DisplayScopeResolver(application, database) }

    /**
     * 教务/登录导入产生新课表后的自动同屏：
     * 当前显示目标是组合 → 直接入组；是独立课表 → 新建组合课表(默认命名)含两者并切过去。
     * 新课表已在组内、或当前无课表、或显示目标就是它自己时不动作。
     */
    @WorkerThread
    fun autoGroupAfterEasImportSync(newTimetableId: String) {
        var selected: DisplayScope? = null
        var groupName: String? = null
        database.runInTransaction {
            val newTimetable = timetableDao.getTimetableByIdSync(newTimetableId) ?: return@runInTransaction
            if (newTimetable.groupId != null) return@runInTransaction
            val scope = displayScopeResolver.currentSync(defaultExcludedId = newTimetableId) ?: return@runInTransaction
            when (val target = scope.scope) {
                is DisplayScope.Group -> {
                    if (newTimetableId in scope.timetableIds) return@runInTransaction
                    timetableGroupDao.setTimetableGroupSync(newTimetableId, target.id)
                    groupName = scope.name
                }
                is DisplayScope.Single -> {
                    if (target.id == newTimetableId) return@runInTransaction
                    val group = TimetableGroupEntity().apply { name = nextDefaultGroupNameSync() }
                    timetableGroupDao.saveGroupSync(group)
                    timetableGroupDao.setTimetableGroupSync(target.id, group.id)
                    timetableGroupDao.setTimetableGroupSync(newTimetableId, group.id)
                    selected = DisplayScope.Group(group.id)
                    groupName = group.name
                }
            }
        }
        selected?.let(displayScopeResolver::select)
        groupName?.let(::notifyGroupedInto)
    }

    private fun notifyGroupedInto(groupName: String) {
        handler.post {
            Toast.makeText(
                application,
                application.getString(R.string.timetable_grouped_into, groupName),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun getGroups() = timetableGroupDao.getGroups()

    @WorkerThread
    fun getGroupsSync(): List<TimetableGroupEntity> = timetableGroupDao.getGroupsSync()

    @WorkerThread
    fun getGroupMembersSync(groupId: String): List<Timetable> = timetableGroupDao.getMembersSync(groupId)

    /** 重命名我的课表（含教务/ICS/口令导入）。返回 null 表示非法名字。 */
    fun actionRenameTimetable(timetableId: String, rawName: String, onResult: ((Boolean) -> Unit)? = null) {
        val name = normalizeDisplayName(rawName)
        if (name == null) {
            handler.post { onResult?.invoke(false) }
            return
        }
        executor.execute {
            val renamed = runCatching { timetableDao.renameTimetableSync(timetableId, name) == 1 }.getOrDefault(false)
            handler.post { onResult?.invoke(renamed) }
        }
    }

    fun actionRenameGroup(groupId: String, rawName: String, onResult: ((Boolean) -> Unit)? = null) {
        val name = normalizeDisplayName(rawName)
        if (name == null) {
            handler.post { onResult?.invoke(false) }
            return
        }
        executor.execute {
            val renamed = runCatching { timetableGroupDao.renameGroupSync(groupId, name) == 1 }.getOrDefault(false)
            handler.post { onResult?.invoke(renamed) }
        }
    }

    /** Returns null if a requested member disappeared or persistence failed. */
    fun actionCreateGroupWithMembers(memberIds: List<String>, onResult: (String?) -> Unit) {
        executor.execute {
            val groupId = runCatching {
                database.runInTransaction(java.util.concurrent.Callable {
                    val ids = memberIds.distinct()
                    if (ids.isEmpty()) return@Callable null
                    val members = timetableDao.getTimetablesInIdsSync(ids)
                    if (members.size != ids.size) return@Callable null
                    val oldGroups = members.mapNotNull { it.groupId }.distinct()
                    val group = TimetableGroupEntity().apply { name = nextDefaultGroupNameSync() }
                    timetableGroupDao.saveGroupSync(group)
                    ids.forEach { timetableGroupDao.setTimetableGroupSync(it, group.id) }
                    oldGroups.forEach(::deleteGroupIfEmptySync)
                    group.id
                })
            }.getOrNull()
            handler.post { onResult(groupId) }
        }
    }

    fun actionAddToGroup(timetableId: String, groupId: String, onResult: ((Boolean) -> Unit)? = null) {
        executor.execute {
            val added = runCatching {
                database.runInTransaction(java.util.concurrent.Callable {
                    val timetable = timetableDao.getTimetableByIdSync(timetableId) ?: return@Callable false
                    if (timetableGroupDao.getGroupByIdSync(groupId) == null) return@Callable false
                    timetableGroupDao.setTimetableGroupSync(timetableId, groupId)
                    timetable.groupId?.takeIf { it != groupId }?.let(::deleteGroupIfEmptySync)
                    true
                })
            }.getOrDefault(false)
            handler.post { onResult?.invoke(added) }
        }
    }

    /** 移出组合；组因此为空时自动删组。 */
    fun actionRemoveFromGroup(timetableId: String) {
        executor.execute {
            database.runInTransaction {
                val groupId = timetableDao.getTimetableByIdSync(timetableId)?.groupId ?: return@runInTransaction
                timetableGroupDao.setTimetableGroupSync(timetableId, null)
                deleteGroupIfEmptySync(groupId)
            }
        }
    }

    /** 解散组合：全部子课表变回独立课表，组删除。 */
    fun actionDissolveGroup(groupId: String) {
        executor.execute {
            database.runInTransaction {
                timetableGroupDao.clearGroupMembersSync(groupId)
                timetableGroupDao.deleteGroupByIdSync(groupId)
            }
        }
    }

    @WorkerThread
    private fun deleteGroupIfEmptySync(groupId: String) {
        if (timetableGroupDao.countMembersSync(groupId) == 0) {
            timetableGroupDao.deleteGroupByIdSync(groupId)
        }
    }

    @WorkerThread
    private fun nextDefaultGroupNameSync(): String {
        val prefix = application.getString(R.string.default_timetable_group_name)
        var max = 0
        for (name in timetableGroupDao.getGroupNamesWithPrefixSync(prefix)) {
            val i = name.removePrefix(prefix).trim().toIntOrNull()
            if (i != null && i > max) max = i
        }
        return "$prefix ${max + 1}"
    }

    private fun normalizeDisplayName(rawName: String): String? {
        val name = rawName.trim()
        if (name.isEmpty() || name.codePointCount(0, name.length) > 40) return null
        return name
    }

    fun actionDeleteEvents(courses: Collection<EventItem>) {
        val ids = mutableListOf<String>()
        for (tt in courses) {
            ids.add(tt.id)
        }
        executor.execute {
            eventItemDao.deleteEventsInIdsSync(ids)
        }
    }

    fun actionNewTimetable() {
        executor.execute {
            val newTable = buildNextDefaultTimetableSync()
            timetableDao.saveTimetableSync(newTable)
        }
    }

    fun followedSchoolSectionIds(term: TermItem): Set<String> {
        val ownerKey = FollowedTeachingSectionStore.ownerKey(easPreferenceSource.getEasToken())
        return followedTeachingSectionStore.snapshots(ownerKey, term.id).mapTo(mutableSetOf()) { it.id }
    }

    fun setSchoolSectionFollowed(
        course: ShenzhenCourseCatalogItem,
        term: TermItem,
        termStartMillis: Long,
        schedule: List<TimePeriodInDay>,
        followed: Boolean
    ): LiveData<DataState<Boolean>> {
        val result = MutableLiveData<DataState<Boolean>>(DataState(DataState.STATE.NOTHING))
        executor.execute {
            runCatching {
                val token = easPreferenceSource.getEasToken()
                check(token.campus == EASToken.Campus.SHENZHEN) { "仅深圳校区支持关注全校教学班" }
                val ownerKey = FollowedTeachingSectionStore.ownerKey(token)
                val stableId = course.taskId.ifBlank { course.id }
                if (followed) {
                    check(course.isFollowable) { "该教学班缺少完整周次或节次，暂不能关注" }
                    check(schedule.isNotEmpty()) { "未获取到本学期作息时间" }
                    followedTeachingSectionStore.save(
                        FollowedTeachingSectionSnapshot.create(
                            ownerKey = ownerKey,
                            term = term,
                            course = course,
                            termStartMillis = TimeTools.getMonday(termStartMillis).timeInMillis,
                            schedule = schedule
                        )
                    )
                } else {
                    followedTeachingSectionStore.remove(ownerKey, term.id, stableId)
                }
                syncFollowedProjection(ownerKey, term.id)
                true
            }.onSuccess {
                result.postValue(DataState(it, DataState.STATE.SUCCESS))
            }.onFailure { error ->
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }
        return result
    }

    fun courseSelectionDraft(term: TermItem): CourseSelectionDraft {
        val ownerKey = FollowedTeachingSectionStore.ownerKey(easPreferenceSource.getEasToken())
        return courseSelectionDraftStore.draft(ownerKey, term.id)
    }

    data class CoursePlanProjectionResult(
        val draft: CourseSelectionDraft,
        val timetableId: String?
    )

    fun setCoursePlanCourse(
        course: ShenzhenCourseCatalogItem,
        term: TermItem,
        termStartMillis: Long?,
        schedule: List<TimePeriodInDay>?,
        selectedCourses: List<ShenzhenCourseCatalogItem>,
        included: Boolean
    ): LiveData<DataState<CourseSelectionDraft>> = updateCourseSelectionDraft(term) { ownerKey ->
        var draft = courseSelectionDraftStore.setCourse(ownerKey, term.id, course, included)
        if (draft.projectionEnabled && termStartMillis != null && !schedule.isNullOrEmpty()) {
            if (
                CoursePlanProjectionPolicy.projectableCourses(
                    selectedCourses + draft.courses,
                    schedule.size
                ).isEmpty()
            ) {
                draft = courseSelectionDraftStore.setProjectionEnabled(ownerKey, term.id, false)
            }
            syncCoursePlanProjection(ownerKey, term, termStartMillis, schedule, selectedCourses, draft)
        }
        draft
    }

    fun setCoursePlanProjectionEnabled(
        term: TermItem,
        termStartMillis: Long?,
        schedule: List<TimePeriodInDay>?,
        selectedCourses: List<ShenzhenCourseCatalogItem>,
        enabled: Boolean
    ): LiveData<DataState<CoursePlanProjectionResult>> {
        val result = MutableLiveData<DataState<CoursePlanProjectionResult>>(
            DataState(DataState.STATE.NOTHING)
        )
        executor.execute {
            runCatching {
                val ownerKey = FollowedTeachingSectionStore.ownerKey(easPreferenceSource.getEasToken())
                val current = courseSelectionDraftStore.draft(ownerKey, term.id)
                if (enabled) {
                    val effectiveCourses = selectedCourses + current.courses
                    val validationError = when {
                        effectiveCourses.isEmpty() -> "本学期没有已选课程，请先加入要预览的课程"
                        termStartMillis == null ->
                            "尚未获取到本学期第一教学周日期，暂时无法生成课表预览"
                        schedule.isNullOrEmpty() ->
                            "尚未获取到本学期作息时间，暂时无法生成课表预览"
                        CoursePlanProjectionPolicy.projectableCourses(
                            effectiveCourses,
                            schedule.size
                        ).isEmpty() ->
                            "已加入课程没有可解析的上课时间，暂时只能查看草稿清单，无法生成课表"
                        else -> null
                    }
                    if (validationError != null) {
                        if (current.projectionEnabled) {
                            val disabled = current.copy(projectionEnabled = false)
                            syncCoursePlanProjection(
                                ownerKey,
                                term,
                                termStartMillis ?: 0L,
                                schedule.orEmpty(),
                                selectedCourses,
                                disabled
                            )
                            courseSelectionDraftStore.setProjectionEnabled(ownerKey, term.id, false)
                        }
                        error(validationError)
                    }
                }

                val proposed = current.copy(projectionEnabled = enabled)
                val timetableId = syncCoursePlanProjection(
                    ownerKey,
                    term,
                    termStartMillis ?: 0L,
                    schedule.orEmpty(),
                    selectedCourses,
                    proposed
                )
                val persisted = runCatching {
                    courseSelectionDraftStore.setProjectionEnabled(ownerKey, term.id, enabled)
                }.getOrElse { error ->
                    runCatching {
                        syncCoursePlanProjection(
                            ownerKey,
                            term,
                            termStartMillis ?: 0L,
                            schedule.orEmpty(),
                            selectedCourses,
                            current
                        )
                    }
                    throw error
                }
                CoursePlanProjectionResult(persisted, timetableId)
            }.onSuccess {
                result.postValue(DataState(it, DataState.STATE.SUCCESS))
            }.onFailure { error ->
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }
        return result
    }

    fun clearCourseSelectionDraft(
        term: TermItem,
        termStartMillis: Long?,
        schedule: List<TimePeriodInDay>?,
        selectedCourses: List<ShenzhenCourseCatalogItem>
    ): LiveData<DataState<CourseSelectionDraft>> = updateCourseSelectionDraft(term) { ownerKey ->
        var draft = courseSelectionDraftStore.clear(ownerKey, term.id)
        if (
            draft.projectionEnabled &&
            CoursePlanProjectionPolicy.projectableCourses(
                selectedCourses,
                schedule.orEmpty().size
            ).isEmpty()
        ) {
            draft = courseSelectionDraftStore.setProjectionEnabled(ownerKey, term.id, false)
        }
        syncCoursePlanProjection(
            ownerKey,
            term,
            termStartMillis ?: 0L,
            schedule.orEmpty(),
            selectedCourses,
            draft
        )
        draft
    }

    private fun updateCourseSelectionDraft(
        @Suppress("UNUSED_PARAMETER") term: TermItem,
        block: (String) -> CourseSelectionDraft
    ): LiveData<DataState<CourseSelectionDraft>> {
        val result = MutableLiveData<DataState<CourseSelectionDraft>>(DataState(DataState.STATE.NOTHING))
        executor.execute {
            runCatching {
                val ownerKey = FollowedTeachingSectionStore.ownerKey(easPreferenceSource.getEasToken())
                block(ownerKey)
            }.onSuccess {
                result.postValue(DataState(it, DataState.STATE.SUCCESS))
            }.onFailure { error ->
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }
        return result
    }

    @WorkerThread
    private fun syncCoursePlanProjection(
        ownerKey: String,
        term: TermItem,
        termStartMillis: Long,
        schedule: List<TimePeriodInDay>,
        selectedCourses: List<ShenzhenCourseCatalogItem>,
        draft: CourseSelectionDraft
    ): String? {
        val timetableCode = "COURSE_PLAN:$ownerKey:${term.id}"
        val existing = timetableDao.getTimetableByEASCodeSync(timetableCode)
        val courses = CoursePlanProjectionPolicy.projectableCourses(
            selectedCourses + draft.courses,
            schedule.size
        )
        if (!draft.projectionEnabled || courses.isEmpty()) {
            existing?.let {
                timetableDao.deleteTimetablesSync(listOf(it))
                eventItemDao.deleteEventsFromTimetablesSync(listOf(it.id))
                subjectDao.deleteSubjectsFromTimetablesSync(listOf(it.id))
            }
            return null
        }

        val projection = existing ?: Timetable()
        projection.code = timetableCode
        projection.name = "选课预览 · ${term.yearCode}-${term.termCode}"
        projection.startTime = java.sql.Timestamp(TimeTools.getMonday(termStartMillis).timeInMillis)
        projection.scheduleStructure = schedule
        val subjects = mutableListOf<cn.limpu.hita.data.model.timetable.TermSubject>()
        val events = mutableListOf<EventItem>()
        courses.forEach { course ->
            val courseId = course.taskId.ifBlank { course.id }
            val subjectId = stableUuid("$timetableCode:subject:$courseId")
            subjects += cn.limpu.hita.data.model.timetable.TermSubject().apply {
                id = subjectId
                name = course.courseName
                timetableId = projection.id
                code = course.courseCode.ifBlank { null }
                key = courseId
                credit = course.credits.toFloatOrNull() ?: 0f
                school = course.offeringCollege.ifBlank { null }
                nature = course.courseNature.ifBlank { null }
                selectCategory = course.selectionPoolName.ifBlank { null }
                color = ColorTools.colorForName(course.courseName)
            }
            course.meetings.forEachIndexed { meetingIndex, meeting ->
                if (!meeting.isStructurallyComplete() ||
                    meeting.beginPeriod - 1 !in schedule.indices ||
                    meeting.endPeriod - 1 !in schedule.indices
                ) return@forEachIndexed
                meeting.weeks.forEach { week ->
                    val timestamps = projection.getTimestamps(
                        week,
                        meeting.weekday,
                        meeting.beginPeriod,
                        meeting.endPeriod
                    )
                    events += EventItem().apply {
                        id = stableUuid("$timetableCode:event:$courseId:$meetingIndex:$week")
                        type = EventItem.TYPE.CLASS
                        source = "${EventItem.SOURCE_COURSE_PLAN}:$ownerKey"
                        name = course.courseName
                        place = meeting.location
                        teacher = meeting.teacher.ifBlank { course.teacher }
                        this.subjectId = subjectId
                        timetableId = projection.id
                        from.time = timestamps[0]
                        to.time = timestamps[1]
                        fromNumber = meeting.beginPeriod
                        lastNumber = meeting.endPeriod - meeting.beginPeriod + 1
                    }
                }
            }
        }
        projection.endTime = java.sql.Timestamp(
            events.maxOfOrNull { it.to.time } ?: (projection.startTime.time + 18L * WEEK_MILLS)
        )
        eventItemDao.deleteEventsFromTimetablesSync(listOf(projection.id))
        subjectDao.deleteSubjectsFromTimetablesSync(listOf(projection.id))
        timetableDao.saveTimetableSync(projection)
        subjectDao.saveSubjectsSync(subjects)
        eventItemDao.saveEvents(events)
        return projection.id
    }

    @WorkerThread
    private fun syncFollowedProjection(ownerKey: String, termId: String) {
        val snapshots = followedTeachingSectionStore.snapshots(ownerKey, termId)
        val timetableCode = "FOLLOWED:$ownerKey:$termId"
        var timetable = timetableDao.getTimetableByEASCodeSync(timetableCode)

        if (snapshots.isEmpty()) {
            timetable?.let {
                timetableDao.deleteTimetablesSync(listOf(it))
                eventItemDao.deleteEventsFromTimetablesSync(listOf(it.id))
                subjectDao.deleteSubjectsFromTimetablesSync(listOf(it.id))
            }
            return
        }

        val first = snapshots.first()
        val projection = timetable ?: Timetable()
        projection.code = timetableCode
        projection.name = "关注课程 · ${first.yearCode}-${first.termCode}"
        projection.startTime = java.sql.Timestamp(first.termStartMillis)
        projection.scheduleStructure = first.scheduleStructure()

        val subjects = mutableListOf<cn.limpu.hita.data.model.timetable.TermSubject>()
        val events = mutableListOf<EventItem>()
        snapshots.forEach { snapshot ->
            val subjectId = stableUuid("$timetableCode:subject:${snapshot.id}")
            val subject = cn.limpu.hita.data.model.timetable.TermSubject().apply {
                id = subjectId
                name = snapshot.courseName
                timetableId = projection.id
                code = snapshot.courseCode.ifBlank { null }
                key = snapshot.taskId.ifBlank { snapshot.id }
                credit = snapshot.credits.toFloatOrNull() ?: 0f
                school = snapshot.offeringCollege.ifBlank { null }
                color = ColorTools.colorForName(snapshot.courseName)
            }
            subjects += subject

            snapshot.meetings.forEachIndexed { meetingIndex, meeting ->
                val startIndex = meeting.beginPeriod - 1
                val endIndex = meeting.endPeriod - 1
                if (startIndex !in projection.scheduleStructure.indices ||
                    endIndex !in projection.scheduleStructure.indices
                ) return@forEachIndexed
                meeting.weeks.forEach { week ->
                    val from = projection.getTimestamps(
                        week,
                        meeting.weekday,
                        meeting.beginPeriod,
                        meeting.endPeriod
                    )
                    if (from.size != 2) return@forEach
                    events += EventItem().apply {
                        id = stableUuid("$timetableCode:event:${snapshot.id}:$meetingIndex:$week")
                        type = EventItem.TYPE.CLASS
                        source = "${EventItem.SOURCE_FOLLOWED_SCHOOL}:$ownerKey"
                        name = snapshot.courseName
                        place = meeting.location
                        teacher = meeting.teacher.ifBlank { snapshot.teacher }
                        this.subjectId = subjectId
                        timetableId = projection.id
                        this.from.time = from[0]
                        to.time = from[1]
                        fromNumber = meeting.beginPeriod
                        lastNumber = meeting.endPeriod - meeting.beginPeriod + 1
                    }
                }
            }
        }

        projection.endTime = java.sql.Timestamp(
            events.maxOfOrNull { it.to.time }
                ?: (projection.startTime.time + 18L * WEEK_MILLS)
        )
        eventItemDao.deleteEventsFromTimetablesSync(listOf(projection.id))
        subjectDao.deleteSubjectsFromTimetablesSync(listOf(projection.id))
        timetableDao.saveTimetableSync(projection)
        subjectDao.saveSubjectsSync(subjects)
        eventItemDao.saveEvents(events)
    }

    private fun stableUuid(value: String): String = UUID.nameUUIDFromBytes(
        value.toByteArray(Charsets.UTF_8)
    ).toString()

    /**
     * 进入主页/课表管理页前的轻量维护动作。
     *
     * 顺序很重要：
     * 1. 先清理历史遗留的纯重复默认课表；
     * 2. 如果数据库完全没有课表，再创建一个默认自定义课表。
     *
     * 这样不会在已有 EAS 学期课表时额外造一张空“默认课表”。
     */
    fun actionPrepareTimetableList() {
        executor.execute {
            cleanupDefaultDuplicateTimetablesSync()
            ensureDefaultCustomTimetableSync()
        }
    }

    fun ensureDefaultCustomTimetableAsync() {
        executor.execute {
            ensureDefaultCustomTimetableSync()
        }
    }

    @WorkerThread
    fun ensureDefaultCustomTimetableSync(): Timetable {
        val existing = timetableDao.getFirstCustomTimetableSync()
        if (existing != null) return existing

        val firstTimetable = timetableDao.getTimetablesSync().firstOrNull()
        if (firstTimetable != null) return firstTimetable

        val newTable = buildNextDefaultTimetableSync()
        timetableDao.saveTimetableSync(newTable)
        return newTable
    }

    @WorkerThread
    fun cleanupDefaultDuplicateTimetablesSync() {
        val defaultPrefix = application.getString(R.string.default_timetable_name)
        val defaults = timetableDao.getDefaultNamedCustomTimetablesSync("$defaultPrefix%")
        if (defaults.isEmpty()) return

        val easTables = timetableDao.getTimetablesSync()
            .filter { !it.code.isNullOrBlank() }
        if (easTables.isEmpty()) return

        val easEventKeys = easTables.associate { timetable ->
            timetable.id to eventItemDao.getImportedClassEventsOfTimetableSync(timetable.id)
                .mapTo(mutableSetOf()) { importedClassEventIdentityKey(it) }
        }

        val deleteIds = defaults.mapNotNull { timetable ->
            val eventCount = eventItemDao.countEventsOfTimetableSync(timetable.id)
            if (eventCount == 0) {
                return@mapNotNull timetable.id
            }

            val nonImportedClassCount = eventItemDao.countNonImportedClassEventsOfTimetableSync(timetable.id)
            if (nonImportedClassCount > 0) {
                return@mapNotNull null
            }

            val defaultKeys = eventItemDao.getImportedClassEventsOfTimetableSync(timetable.id)
                .mapTo(mutableSetOf()) { importedClassEventIdentityKey(it) }
            val duplicatedByEas = defaultKeys.isNotEmpty() && easEventKeys.values.any { easKeys ->
                easKeys.isNotEmpty() && easKeys.containsAll(defaultKeys)
            }
            if (duplicatedByEas) timetable.id else null
        }
        if (deleteIds.isEmpty()) return

        LogUtils.d("cleanupDefaultDuplicateTimetables: deleting defaults=$deleteIds")
        database.runInTransaction {
            val groups = timetableDao.getTimetablesInIdsSync(deleteIds).mapNotNull { it.groupId }.distinct()
            eventItemDao.deleteEventsFromTimetablesSync(deleteIds)
            subjectDao.deleteSubjectsFromTimetablesSync(deleteIds)
            timetableDao.deleteTimetablesInIdsSync(deleteIds)
            groups.forEach(::deleteGroupIfEmptySync)
        }
    }

    @WorkerThread
    private fun buildNextDefaultTimetableSync(): Timetable {
        val defaultPrefix = application.getString(R.string.default_timetable_name)
        val defaultTables = timetableDao.getTimetableNamesWithDefaultSync("$defaultPrefix%")
        var max = 0
        for (tt in defaultTables) {
            val i = try {
                tt.replace(defaultPrefix, "").toInt()
            } catch (e: NumberFormatException) {
                null
            }
            if (i != null && i > max) {
                max = i
            }
        }
        val newTable = Timetable()
        val c = TimeTools.getMonday(System.currentTimeMillis())
        newTable.startTime.time = c.timeInMillis
        newTable.endTime.time = c.timeInMillis + WEEK_MILLS
        newTable.name = defaultPrefix + (max + 1).toString()
        return newTable
    }

    fun actionSaveTimetable(timetable: Timetable) {
        executor.execute {
            timetableDao.saveTimetableSync(timetable)
        }
    }

    @WorkerThread
    fun saveTimetableSync(timetable: Timetable) {
        timetableDao.saveTimetableSync(timetable)
    }

    fun actionChangeTimetableStartDate(timetable: Timetable, startTime: Long) {
        val calendar = TimeTools.getMonday(startTime)
        val offset = calendar.timeInMillis - timetable.startTime.time
        timetable.endTime.time = timetable.endTime.time + offset
        timetable.startTime.time = calendar.timeInMillis
        executor.execute {
            timetableDao.saveTimetableSync(timetable)
            eventItemDao.updateClassesAddOffset(timetableId = timetable.id, offset)
        }
    }

    fun actionChangeTimetableStructure(timetable: Timetable, tp: TimePeriodInDay, position: Int) {
        timetable.setScheduleStructure(tp, position)
        executor.execute {
            timetableDao.saveTimetableSync(timetable)
            val fromToChange = eventItemDao.getClassAtFromNumberSync(timetable.id, position + 1)
            val tmp = Calendar.getInstance()
            val ids = mutableListOf<String>()
            for (e in fromToChange) {
                ids.add(e.id)
                tmp.timeInMillis = e.from.time
                tmp.set(Calendar.HOUR_OF_DAY, tp.from.hour)
                tmp.set(Calendar.MINUTE, tp.from.minute)
                e.from.time = tmp.timeInMillis
            }
            eventItemDao.saveEvents(fromToChange)
            val endToChange = eventItemDao.getClassAtToNumberSync(timetable.id, position + 1)
            for (e in endToChange) {
                ids.add(e.id)
                tmp.timeInMillis = e.to.time
                tmp.set(Calendar.HOUR_OF_DAY, tp.to.hour)
                tmp.set(Calendar.MINUTE, tp.to.minute)
                e.to.time = tmp.timeInMillis
            }
            eventItemDao.saveEvents(endToChange)
        }
    }

    fun actionAddEvents(data:List<EventItem>) {
        executor.execute {
            eventItemDao.addEvents(data)
        }
    }

    fun actionAddCourseEvents(subject: TermSubject, events: List<EventItem>) {
        executor.execute { addCourseEventsSync(subject, events) }
    }

    @WorkerThread
    fun addCourseEventsSync(subject: TermSubject, events: List<EventItem>) {
        database.runInTransaction {
            subjectDao.saveSubjectSync(subject)
            eventItemDao.addEvents(events)
        }
    }

    fun actionUpdateEvent(event: EventItem) {
        executor.execute {
            eventItemDao.updateEventSync(event)
        }
    }

    fun actionUpdateEvents(events: Collection<EventItem>) {
        if (events.isEmpty()) return
        executor.execute {
            eventItemDao.updateEventsSync(events.toList())
        }
    }

    @WorkerThread
    fun addEventsSync(data: List<EventItem>) {
        eventItemDao.addEvents(data)
    }


    fun exportToICS(timetableName: String, timetableId: String): LiveData<DataState<String>> {
        val res = MutableLiveData<DataState<String>>();
        executor.execute {
            val filename = "HITSZ助手：$timetableName.ics"
            try {
                val timetable = timetableDao.getTimetableByIdSync(timetableId)
                val events = eventItemDao.getEventsOfTimetableSync(timetableId).map { ei ->
                    IcsExportEvent(ei.name, ei.from.time, ei.to.time, ei.place, ei.teacher,
                        timetable?.getWeekNumber(ei.from.time) ?: -1, ei.type.name.lowercase())
                }
                val dir = application.getExternalFilesDir("ics") ?: application.filesDir
                val path = TimetableIcsWriter.write(dir, filename, events).absolutePath
                res.postValue(DataState(path))
            } catch (e: Exception) {
                LogUtils.e("Failed to export timetable to ICS", e)
                res.postValue(DataState(DataState.STATE.FETCH_FAILED))
            }
        }
        return res;
    }

    /**
     * 从 ICS 文件导入课表
     * @param inputStream ICS 文件输入流
     * @param timetableId 目标课表 ID
     * @return 导入结果，包含导入的课程数量
     */
    fun importFromICS(inputStream: InputStream, timetableId: String): LiveData<DataState<Int>> {
        val res = MutableLiveData<DataState<Int>>()
        executor.execute {
            try {
                val builder = CalendarBuilder()
                val calendar = builder.build(inputStream)
                val events = calendar.components.filterIsInstance<VEvent>()
                var importedCount = 0
                
                for (event in events) {
                    val ei = IcsImportEventMapper.map(event, timetableId) ?: continue
                    eventItemDao.insertEventSync(ei)
                    importedCount++
                }
                
                res.postValue(DataState(importedCount))
            } catch (e: Exception) {
                LogUtils.e("Failed to import events from ICS", e)
                res.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }
        return res
    }

    fun importFromICSAsNewTimetable(
        inputStream: InputStream,
        sourceName: String?
    ): LiveData<DataState<IcsImportResult>> {
        val res = MutableLiveData<DataState<IcsImportResult>>()
        executor.execute {
            try {
                val calendar = inputStream.use { CalendarBuilder().build(it) }
                val bundle = IcsImportBundleBuilder.build(
                    events = calendar.components.filterIsInstance<VEvent>(),
                    sourceName = sourceName
                )
                database.runInTransaction {
                    timetableDao.saveTimetableSync(bundle.timetable)
                    subjectDao.saveSubjectsSync(bundle.subjects)
                    eventItemDao.saveEvents(bundle.events)
                }

                res.postValue(
                    DataState(
                        IcsImportResult(
                            timetableId = bundle.timetable.id,
                            timetableName = bundle.timetable.name ?: application.getString(R.string.default_timetable_name),
                            importedCount = bundle.events.size
                        )
                    )
                )
            } catch (e: Exception) {
                LogUtils.e("Failed to import ICS as new timetable", e)
                val message = if (e is IllegalArgumentException) e.message else e.message
                res.postValue(DataState(DataState.STATE.FETCH_FAILED, message))
            }
        }
        return res
    }

    /** 课表口令(FULL 快照)导入为我的课表。BUSY 模式由 UI 层拦截,这里防御性报错。 */
    fun importShareSnapshotAsTimetable(snapshot: cn.limpu.hita.feature.timetableshare.protocol.SharedTimetable): LiveData<DataState<IcsImportResult>> {
        val res = MutableLiveData<DataState<IcsImportResult>>()
        executor.execute {
            try {
                val bundle = SharedTimetableImporter.buildBundleFromShare(snapshot)
                database.runInTransaction {
                    timetableDao.saveTimetableSync(bundle.timetable)
                    subjectDao.saveSubjectsSync(bundle.subjects)
                    eventItemDao.saveEvents(bundle.events)
                }
                res.postValue(
                    DataState(
                        IcsImportResult(
                            timetableId = bundle.timetable.id,
                            timetableName = bundle.timetable.name ?: application.getString(R.string.default_timetable_name),
                            importedCount = bundle.events.size
                        )
                    )
                )
            } catch (e: Exception) {
                LogUtils.e("Failed to import shared timetable", e)
                res.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }
        return res
    }

    fun actionClearData() {
        executor.execute {
            database.runInTransaction {
                eventItemDao.clear()
                subjectDao.clear()
                timetableDao.clear()
                timetableGroupDao.getGroupsSync().forEach { timetableGroupDao.deleteGroupByIdSync(it.id) }
            }
        }
    }

    private fun dedupeDisplayEvents(events: List<EventItem>): List<EventItem> {
        val seenImportedClasses = mutableSetOf<String>()
        val currentOwnerKey = FollowedTeachingSectionStore.ownerKey(easPreferenceSource.getEasToken())
        return events.filter { event ->
            when {
                event.source.startsWith("${EventItem.SOURCE_FOLLOWED_SCHOOL}:") ||
                    event.source.startsWith("${EventItem.SOURCE_COURSE_PLAN}:") ->
                    event.source.substringAfterLast(':') == currentOwnerKey
                else -> true
            }
        }.sortedBy { event ->
            when {
                event.source == EventItem.SOURCE_EAS_IMPORT -> 0
                event.source.startsWith(EventItem.SOURCE_FOLLOWED_SCHOOL) -> 1
                event.source.startsWith(EventItem.SOURCE_COURSE_PLAN) -> 2
                else -> 3
            }
        }.filter { event ->
            val shouldDedupe = event.type == EventItem.TYPE.CLASS && (
                event.source == EventItem.SOURCE_EAS_IMPORT ||
                    event.source.startsWith(EventItem.SOURCE_FOLLOWED_SCHOOL) ||
                    event.source.startsWith(EventItem.SOURCE_COURSE_PLAN)
                )
            !shouldDedupe || seenImportedClasses.add(importedClassEventIdentityKey(event))
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

}
