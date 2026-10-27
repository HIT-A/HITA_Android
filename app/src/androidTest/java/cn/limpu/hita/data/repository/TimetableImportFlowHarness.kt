package cn.limpu.hita.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.eas.CourseItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.EasQueryScope
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.data.source.preference.TimetablePreferenceSource
import cn.limpu.hita.data.source.web.service.EASService
import com.limpu.component.data.DataState
import java.io.Closeable
import java.lang.reflect.Proxy
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin

internal class TimetableImportFlowHarness(campus: EASToken.Campus = EASToken.Campus.BENBU) : Closeable {
    val application = BackgroundTermConsumerHarness.IsolatedApplication(ApplicationProvider.getApplicationContext())
    val token = BackgroundTermConsumerHarness.account().apply {
        this.campus = campus
        if (campus == EASToken.Campus.SHENZHEN) accessToken = "test-token"
    }
    val preferences = EasPreferenceSource(application).also { it.saveEasToken(token) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val state = preferences.querySessionState
    val inProgress = AtomicBoolean()
    val calls = CopyOnWriteArrayList<String>()
    @Volatile var onSql: (String) -> Unit = {}
    @Volatile var importThread: Thread? = null
    val database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
        .setQueryCallback({ sql, _ -> onSql(sql) }, { it.run() }).build()
    val snapshots = TimetableSnapshotStore(application, database)
    val changes = TimetableChangeStore(application)
    val timetablePreferences = TimetablePreferenceSource(application, preferences)
    var fetchCourses: suspend (TermItem) -> DataState<List<CourseItem>> = { DataState(listOf(course())) }
    var fetchSubjects: suspend () -> DataState<MutableList<TermSubject>> = { DataState(mutableListOf()) }
    var fetchTerms: suspend () -> DataState<List<TermItem>> = { DataState(listOf(term())) }
    var fetchExams: suspend () -> DataState<List<ExamItem>> = { DataState(emptyList()) }

    @Suppress("UNCHECKED_CAST")
    val service = Proxy.newProxyInstance(EASService::class.java.classLoader, arrayOf(EASService::class.java)) { proxy, method, arguments ->
        val args = arguments.orEmpty()
        val request: (suspend () -> Any)? = when (method.name) {
            "getTimetableOfTermOnce" -> {
                importThread = Thread.currentThread()
                calls += "timetable"
                suspend { fetchCourses(args[0] as TermItem) }
            }
            "getSubjectsOfTermOnce" -> { calls += "subjects"; suspend { fetchSubjects() } }
            "getStartDateOnce" -> { calls += "date"; suspend { DataState(date()) } }
            "getScheduleStructureOnce" -> { calls += "schedule"; suspend { DataState(Timetable().getDefaultTimeStructure().toMutableList()) } }
            "getExamItemsOnce" -> { calls += "exams"; suspend { fetchExams() } }
            else -> null
        }
        if (request != null) request.startCoroutineUninterceptedOrReturn(args.last() as Continuation<Any>)
        else when (method.name) {
            "toString" -> "TimetableImportService"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args.single()
            else -> error("Unexpected import method ${method.name}")
        }
    } as EASService

    val importer = EASTimetableImportRepository(
        preferences, database.eventItemDao(), database.timetableDao(), database.subjectDao(),
        snapshots, TimetableMutationLock(), changes, timetablePreferences, application,
        state.authEpoch, state.lock, inProgress, ::session,
        queryTerms = { calls += "terms"; fetchTerms() }, applicationScope = scope,
        database = database, autoGroupImportedTimetable = {},
    )

    fun session(): EasQuerySession {
        val captured = preferences.getEasToken()
        return EasQuerySession(EasQueryScope.from(captured), captured, service, state::current, scopes = state.changes)
    }

    suspend fun import(
        mode: TimetableImportMode = TimetableImportMode.REPLACE,
        timeout: Long = 90_000L,
    ) = importer.importTimetableOfTerm(session(), term(), date(), Timetable().getDefaultTimeStructure(), mode, timeoutMillis = timeout)

    fun tables() = database.timetableDao().getTimetablesSync()
    fun events() = tables().flatMap { database.eventItemDao().getEventsOfTimetableSync(it.id) }
    fun switchAccount() = preferences.saveEasToken(BackgroundTermConsumerHarness.account("other", token.sessionGeneration + 1))

    override fun close() {
        scope.cancel()
        runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() }
        database.close()
        application.close()
    }

    companion object {
        fun term() = BackgroundTermConsumerHarness.term().apply { isCurrent = true }
        fun date() = Calendar.getInstance().apply { set(2026, Calendar.MARCH, 2, 13, 25, 17); set(Calendar.MILLISECOND, 13) }
        fun course(name: String = "Algorithms") = CourseItem().apply {
            this.name = name; code = "CS101"; weeks = mutableListOf(1, 2); dow = 1; begin = 1; last = 2
            teacher = "Teacher"; classroom = "A101"
        }
    }
}
