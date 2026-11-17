package cn.limpu.hita.ui.main.timeline

import androidx.lifecycle.asFlow
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.EasQueryScope
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.repository.*
import cn.limpu.hita.data.repository.grader.GraderRepository
import cn.limpu.hita.data.repository.grader.GraderState
import cn.limpu.hita.data.repository.homework.HomeworkRepository
import cn.limpu.hita.data.repository.homework.HomeworkState
import cn.limpu.hita.data.repository.today.TodayReadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Small read seam for controlled clock/source tests; production continues to use repositories. */
internal interface TodayReadSources {
    val scopes: Flow<TodayReadState<ResolvedDisplayScope?>>
    val homework: Flow<HomeworkState> get() = flowOf(HomeworkState())
    val grader: Flow<GraderState> get() = flowOf(GraderState())
    val exams: Flow<ExamReadSnapshot> get() = flowOf(ExamReadSnapshot())
    val session: Flow<TodaySession> get() = flowOf(TodaySession())
    fun currentSession(): TodaySession = TodaySession()
    fun currentGraderAccount(): String? = null
    fun currentScope(): ResolvedDisplayScope?
    fun read(query: TodayTimelineQuery): Flow<TodayReadState<List<EventItem>>>
    suspend fun load(source: TodayRetrySource, retry: Boolean = false) {}
    fun visible(visible: Boolean) {}
    suspend fun findEvent(query: TodayTimelineQuery, id: String): EventItem?
}

internal open class TimetableTodaySources(
    private val timetable: TimetableRepository,
    protected val resolver: DisplayScopeResolver,
) : TodayReadSources {
    override val scopes get() = resolver.readState.asFlow()
    override fun currentScope() = resolver.resolved.value
    override fun read(query: TodayTimelineQuery) = timetable.observeTimelineWindow(query.window.start, query.window.dayAfterStart, query.timetableIds)
    override suspend fun findEvent(query: TodayTimelineQuery, id: String): EventItem? = withContext(Dispatchers.IO) {
        val scope = resolver.currentSync() ?: return@withContext null
        if (TodayTimelineQuery.from(scope, query.window) != query) return@withContext null
        timetable.getUpcomingEventsSync(query.window.start, query.window.dayAfterStart, query.timetableIds).firstOrNull { it.id == id }
    }
}

internal class RepositoryTodaySources(
    timetable: TimetableRepository, resolver: DisplayScopeResolver,
    private val local: HomeworkRepository, private val remote: GraderRepository, private val eas: EASRepository,
) : TimetableTodaySources(timetable, resolver) {
    private val consumer = "today:${System.identityHashCode(this)}"
    override val homework get() = local.state.asFlow()
    override val grader get() = remote.state.asFlow()
    override val exams get() = eas.examReadState
    override val session get() = eas.observeEasToken().asFlow().map(::sessionFrom)
    override fun currentSession() = sessionFrom(eas.getEasToken())
    override fun currentGraderAccount() = remote.state.value?.accountId
    override fun visible(visible: Boolean) = remote.readCoordinator.setVisible(consumer, visible)
    override suspend fun load(source: TodayRetrySource, retry: Boolean) {
        when (source) {
            TodayRetrySource.HOMEWORK -> local.reload()
            TodayRetrySource.GRADER -> if (currentSession().graderAvailable) remote.readCoordinator.load(consumer, retry)
            TodayRetrySource.AGENDA -> Unit
        }
    }
    private fun sessionFrom(token: EASToken) = TodaySession(
        EasQueryScope.termCacheOwnerKey(token), token.sessionGeneration,
        token.isLogin() && token.campus == EASToken.Campus.SHENZHEN,
    )
}
