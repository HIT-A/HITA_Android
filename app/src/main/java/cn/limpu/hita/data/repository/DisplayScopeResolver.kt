package cn.limpu.hita.data.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import cn.limpu.hita.R
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.DisplayScope
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.model.timetable.TimetableGroupEntity
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 显示目标解析结果：主课表/时间线/小组件/提醒/Agent 统一经此过滤课表事件。
 */
data class ResolvedDisplayScope(
    val scope: DisplayScope,
    val name: String,
    /** 目标内的课表 id 集合；事件查询按它过滤。 */
    val timetableIds: List<String>,
    /** 目标内课表（startTime 升序），周数与作息结构计算用。 */
    val timetables: List<Timetable>,
)

/**
 * 显示目标的唯一解析入口：持久化选择、默认回落、名称与成员解析。
 *
 * 持久化值失效（课表/组已删除、组已无成员）时回落到默认并清除持久化值。
 * 默认 = 当前时间落在学期内的课表中 startTime 最小者；无命中取最新学期；无课表返回 null。
 */
@Singleton
class DisplayScopeResolver internal constructor(
    private val application: Application,
    private val database: AppDatabase,
) {
    @Inject constructor(application: Application) : this(application, AppDatabase.getDatabase(application))

    companion object {
        private const val SP_NAME = "timetable_display_scope"
        private const val KEY_TYPE = "display_scope_type"
        private const val KEY_ID = "display_scope_id"
        private val selectionLock = Any()
        private val fallbackExecutor = Executors.newSingleThreadExecutor()
    }

    private val sp: SharedPreferences =
        application.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
    private val timetableDao = database.timetableDao()
    private val groupDao = database.timetableGroupDao()

    private val spTrigger = MutableLiveData(Unit)
    private val spListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_TYPE || key == KEY_ID) spTrigger.postValue(Unit)
    }

    /** 顶栏下拉的全量可选项：组合课表与独立课表。 */
    data class ScopeOption(
        val scope: DisplayScope,
        val name: String,
        val memberCount: Int,
        /** 目标整体学期范围：单课表即其自身;组合为成员最早开始/最晚结束。 */
        val startMillis: Long,
        val endMillis: Long,
    )

    private var latestTimetables: List<Timetable>? = null
    private var latestGroups: List<TimetableGroupEntity>? = null

    // Synchronous readers must not register permanent preference listeners or LiveData sources.
    private val inputs by lazy {
        MediatorLiveData<Unit>().apply {
            sp.registerOnSharedPreferenceChangeListener(spListener)
            addSource(groupDao.observeDisplayTimetables()) { rows ->
                latestTimetables = rows.map { it.timetable }
                latestGroups = rows.flatMap { it.groups }.distinctBy { it.id }.sortedBy { it.createdAt }
                value = Unit
            }
            addSource(spTrigger) { value = Unit }
        }
    }

    private val _resolved by lazy {
        MediatorLiveData<ResolvedDisplayScope?>().apply {
            addSource(inputs) { value = computeResolved() }
        }
    }
    val resolved: LiveData<ResolvedDisplayScope?> get() = _resolved

    private val _options by lazy {
        MediatorLiveData<List<ScopeOption>>().apply {
            addSource(inputs) { value = computeOptions() }
        }
    }
    val options: LiveData<List<ScopeOption>> get() = _options

    /** 用户切换显示目标；持久化，重启后保持。同时刷新桌面小组件。 */
    fun select(scope: DisplayScope) {
        synchronized(selectionLock) {
            sp.edit()
                .putString(KEY_TYPE, DisplayScope.typeOf(scope))
                .putString(KEY_ID, scope.id)
                .apply()
        }
        cn.limpu.hita.ui.widgets.WidgetUtils.sendRefreshToAll(application)
        cn.limpu.hita.feature.livecourse.scheduler.LiveCourseScheduler.autoSchedule(application)
    }

    private fun computeResolved(): ResolvedDisplayScope? {
        val timetables = latestTimetables ?: return null
        val groups = latestGroups ?: return null
        val persisted = DisplayScope.of(sp.getString(KEY_TYPE, null), sp.getString(KEY_ID, null))
        val resolvedPersisted = persisted?.let { resolveScope(it, timetables, groups) }
        if (persisted != null && resolvedPersisted == null) {
            // A preference change can precede the Room emission for its committed group.
            // Only an authoritative transactional read may invalidate the persisted selection.
            fallbackExecutor.execute { currentSync() }
        }
        resolvedPersisted?.let { return it }
        val fallback = defaultDisplayScope(timetables, System.currentTimeMillis(), groups) ?: return null
        return resolveScope(fallback, timetables, groups)
    }

    private fun computeOptions(): List<ScopeOption> {
        val timetables = latestTimetables ?: return emptyList()
        val groups = latestGroups ?: return emptyList()
        val membersByGroup = timetables.filter { it.groupId != null }.groupBy { it.groupId!! }
        val groupOptions = groups.mapNotNull { group ->
            val members = membersByGroup[group.id].orEmpty()
            if (members.isEmpty()) null
            else ScopeOption(
                scope = DisplayScope.Group(group.id),
                name = group.name,
                memberCount = members.size,
                startMillis = members.minOf { it.startTime.time },
                endMillis = members.maxOf { it.endTime.time },
            )
        }
        val groupIds = groups.mapTo(hashSetOf()) { it.id }
        val singles = timetables.filter { it.groupId !in groupIds }.map { tt ->
            ScopeOption(
                scope = DisplayScope.Single(tt.id),
                name = tt.name ?: application.getString(R.string.default_timetable_name),
                memberCount = 1,
                startMillis = tt.startTime.time,
                endMillis = tt.endTime.time,
            )
        }
        // 组合按创建时间升序在前，独立课表按学期最新在前。
        return groupOptions + singles.sortedByDescending { it.startMillis }
    }

    private fun resolveScope(
        scope: DisplayScope,
        timetables: List<Timetable>,
        groups: List<TimetableGroupEntity>,
    ): ResolvedDisplayScope? {
        return when (scope) {
            is DisplayScope.Single -> {
                val tt = timetables.firstOrNull { it.id == scope.id } ?: return null
                val groupId = tt.groupId
                if (groupId != null && groups.any { it.id == groupId }) {
                    return resolveScope(DisplayScope.Group(groupId), timetables, groups)
                }
                ResolvedDisplayScope(
                    scope = scope,
                    name = tt.name ?: application.getString(R.string.default_timetable_name),
                    timetableIds = listOf(tt.id),
                    timetables = listOf(tt),
                )
            }
            is DisplayScope.Group -> {
                val group = groups.firstOrNull { it.id == scope.id } ?: return null
                val members = timetables.filter { it.groupId == group.id }
                    .sortedBy { it.startTime.time }
                if (members.isEmpty()) return null
                ResolvedDisplayScope(
                    scope = scope,
                    name = group.name,
                    timetableIds = members.map { it.id },
                    timetables = members,
                )
            }
        }
    }


    /** Worker / 小组件 / Agent 等无 LiveData 场景使用。 */
    @WorkerThread
    fun currentSync(defaultExcludedId: String? = null): ResolvedDisplayScope? = database.runInTransaction(Callable {
        synchronized(selectionLock) {
            val timetables = timetableDao.getTimetablesSync()
            val groups = groupDao.getGroupsSync()
            val persisted = DisplayScope.of(sp.getString(KEY_TYPE, null), sp.getString(KEY_ID, null))
            val resolvedPersisted = persisted?.let { resolveScope(it, timetables, groups) }
            if (persisted != null && resolvedPersisted == null) {
                sp.edit().remove(KEY_TYPE).remove(KEY_ID).apply()
            }
            resolvedPersisted ?: defaultDisplayScope(
                timetables.filter { it.id != defaultExcludedId }, System.currentTimeMillis(), groups)
                ?.let { resolveScope(it, timetables, groups) }
        }
    })
}

/** 默认显示目标：当前时间落在学期内的课表中 startTime 最小者;无命中取最新学期;无课表 null。 */
internal fun defaultDisplayScope(
    timetables: List<Timetable>,
    now: Long,
    groups: List<TimetableGroupEntity> = emptyList(),
): DisplayScope? {
    if (timetables.isEmpty()) return null
    val inTerm = timetables.filter { now in it.startTime.time..it.endTime.time }
    val chosen = inTerm.minByOrNull { it.startTime.time }
        ?: timetables.maxByOrNull { it.startTime.time }
        ?: return null
    return chosen.groupId?.takeIf { id -> groups.any { it.id == id } }
        ?.let(DisplayScope::Group) ?: DisplayScope.Single(chosen.id)
}

/** 持久化目标有效性：课表存在;组存在且仍有成员。 */
internal fun displayScopeValid(
    scope: DisplayScope,
    timetables: List<Timetable>,
    groups: List<TimetableGroupEntity>,
): Boolean = when (scope) {
    is DisplayScope.Single -> timetables.any { it.id == scope.id }
    is DisplayScope.Group -> groups.any { it.id == scope.id } && timetables.any { it.groupId == scope.id }
}
