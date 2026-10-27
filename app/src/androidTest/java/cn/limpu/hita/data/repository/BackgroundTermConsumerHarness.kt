package cn.limpu.hita.data.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.os.Handler
import androidx.lifecycle.Observer
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import cn.limpu.hita.agent.core.AgentProvider
import cn.limpu.hita.agent.core.AgentSession
import cn.limpu.hita.agent.timetable.TimetableAgentInput
import cn.limpu.hita.agent.timetable.TimetableAgentOutput
import cn.limpu.hita.agent.tools.ReActToolInput
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.data.model.eas.CourseScoreItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.source.preference.EasCredentialStore
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.data.source.preference.ScoreReminderStore
import cn.limpu.hita.data.source.preference.TimetablePreferenceSource
import cn.limpu.hita.data.source.web.service.EASService
import com.limpu.component.data.DataState
import java.io.Closeable
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertTrue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Real repository/Room/preferences with scripted remote results, never a campus account. */
internal class BackgroundTermConsumerHarness : Closeable {
    val application = IsolatedApplication(ApplicationProvider.getApplicationContext())
    val preferences = EasPreferenceSource(application).also { it.saveEasToken(account()) }
    val database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
    val reminderStore = ScoreReminderStore(application).also { it.setEnabled(true) }
    val calls = CopyOnWriteArrayList<String>()
    val tokens = CopyOnWriteArrayList<EASToken>()
    val notifications = CopyOnWriteArrayList<List<CourseScoreItem>>()
    var terms: suspend (EASToken) -> DataState<List<TermItem>> = { DataState(listOf(term())) }
    var buildings: suspend (EASToken) -> DataState<List<BuildingItem>> = { DataState(emptyList()) }
    var classrooms: suspend (EASToken, TermItem, BuildingItem, List<String>) -> DataState<List<ClassroomItem>> =
        { _, _, _, _ -> DataState(emptyList()) }
    var scores: (TermItem, EASToken, EASService.TestType) -> LiveData<DataState<List<CourseScoreItem>>> =
        { _, _, _ -> MutableLiveData(DataState(emptyList())) }
    /**
     * Once-style score seam used by background consumers.  The default bridges
     * the legacy LiveData fixture so the existing session tests keep their
     * request/observer identities while newer tests can provide a cancellable
     * suspend result directly.
     */
    var scoresOnce: suspend (TermItem, EASToken, EASService.TestType) -> DataState<List<CourseScoreItem>> =
        { term, token, type -> awaitScoreLiveData(scores(term, token, type)) }
    private val executor = Executors.newSingleThreadExecutor()

    @Suppress("UNCHECKED_CAST")
    private val service = Proxy.newProxyInstance(
        EASService::class.java.classLoader, arrayOf(EASService::class.java),
    ) { proxy, method, arguments ->
        val args = arguments.orEmpty()
        when (method.name) {
            "getAllTermsOnce" -> {
                val token = args[0] as EASToken
                calls += "terms"; tokens += token
                (suspend { terms(token) }).startCoroutineUninterceptedOrReturn(
                    args.last() as Continuation<DataState<List<TermItem>>>
                )
            }
            "getTeachingBuildingsOnce" -> {
                val token = args[0] as EASToken
                calls += "buildings"; tokens += token
                (suspend { buildings(token) }).startCoroutineUninterceptedOrReturn(
                    args.last() as Continuation<DataState<List<BuildingItem>>>
                )
            }
            "queryEmptyClassroomOnce" -> {
                val token = args[0] as EASToken
                val building = args[2] as BuildingItem
                calls += "classrooms:${building.id}"; tokens += token
                (suspend { classrooms(token, args[1] as TermItem, building, args[3] as List<String>) })
                    .startCoroutineUninterceptedOrReturn(
                        args.last() as Continuation<DataState<List<ClassroomItem>>>
                    )
            }
            "getPersonalScores" -> {
                check(Looper.myLooper() == Looper.getMainLooper())
                val term = args[0] as TermItem
                val token = args[1] as EASToken
                calls += "scores:${term.id}"; tokens += token
                scores(term, token, args[2] as EASService.TestType)
            }
            "getPersonalScoresOnce" -> {
                val term = args[0] as TermItem
                val token = args[1] as EASToken
                calls += "scores:${term.id}"; tokens += token
                (suspend {
                    scoresOnce(term, token, args[2] as EASService.TestType)
                }).startCoroutineUninterceptedOrReturn(
                    args.last() as Continuation<DataState<List<CourseScoreItem>>>
                )
            }
            "toString" -> "BackgroundTermConsumerService"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args.single()
            else -> error("Unexpected remote method ${method.name}")
        }
    } as EASService

    val repository = EASRepository(
        application, preferences, EasCredentialStore(application),
        TimetablePreferenceSource(application, preferences), TimetableChangeStore(application),
        TimetableMutationLock(), database,
        EasRepositoryServiceFactories(benbu = { service }, weihai = { service }),
    )

    fun input(actionInput: String = "{\"week\":2}") = ReActToolInput(
        actionInput, "query", application, repository, null,
        object : AgentProvider<TimetableAgentInput, TimetableAgentOutput> {
            override fun createSession(): AgentSession<TimetableAgentInput, TimetableAgentOutput> =
                error("The classroom query must not launch a typed agent")
        }, onTrace = {},
    )

    fun workerParameters(input: Data = Data.EMPTY) = WorkerParameters(
        UUID.randomUUID(), input, setOf("persisted-score-reminder"), WorkerParameters.RuntimeExtras(),
        2, 0, executor, WorkManagerTaskExecutor(executor), WorkerFactory.getDefaultWorkerFactory(),
        ProgressUpdater { _, _, _ -> error("No progress update expected") },
        ForegroundUpdater { _, _, _ -> error("No foreground transition expected") },
    )

    fun switchAccount() = preferences.saveEasToken(account("other-student", 802L))

    private suspend fun awaitScoreLiveData(
        liveData: LiveData<DataState<List<CourseScoreItem>>>,
    ): DataState<List<CourseScoreItem>> = suspendCancellableCoroutine { continuation ->
        val observer = object : Observer<DataState<List<CourseScoreItem>>> {
            override fun onChanged(value: DataState<List<CourseScoreItem>>) {
                val state = value
                if (state.state == DataState.STATE.NOTHING || state.state == DataState.STATE.LOADING) {
                    return
                }
                liveData.removeObserver(this)
                if (continuation.isActive) continuation.resume(state)
            }
        }
        val mainHandler = Handler(Looper.getMainLooper())
        continuation.invokeOnCancellation {
            mainHandler.post { liveData.removeObserver(observer) }
        }
        mainHandler.post { liveData.observeForever(observer) }
    }

    override fun close() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        database.close()
        application.close()
        executor.shutdown()
        assertTrue("Worker executor did not stop", executor.awaitTermination(10, TimeUnit.SECONDS))
    }

    class ObservedValue<T> : MutableLiveData<T>() {
        val active = CompletableDeferred<Unit>()
        val inactive = CompletableDeferred<Unit>()
        override fun onActive() { active.complete(Unit) }
        override fun onInactive() { inactive.complete(Unit) }
    }

    class IsolatedApplication(context: Context) : Application(), Closeable {
        private val prefix = "background-term-${UUID.randomUUID()}-"
        private val preferenceNames = mutableSetOf<String>()
        private val directory = File(context.cacheDir, prefix).apply { mkdirs() }
        init { attachBaseContext(context) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            synchronized(preferenceNames) { preferenceNames += name }
            return super.getSharedPreferences(prefix + name, mode)
        }
        override fun deleteSharedPreferences(name: String): Boolean {
            synchronized(preferenceNames) { preferenceNames += name }
            return super.deleteSharedPreferences(prefix + name)
        }
        override fun close() {
            synchronized(preferenceNames) { preferenceNames.forEach { super.deleteSharedPreferences(prefix + it) } }
            directory.deleteRecursively()
        }
    }

    companion object {
        fun account(id: String = "student", generation: Long = 801L) = EASToken().apply {
            campus = EASToken.Campus.BENBU
            stuId = id
            username = "local-login"
            sessionGeneration = generation
            cookies["JSESSIONID"] = "local-fixture"
        }
        fun term(code: String = "1", year: String = "2025-2026") = TermItem(year, year, code, code)
        fun score(name: String = "Algorithms", grade: Int = 90) = CourseScoreItem().apply {
            courseCode = "CS101"
            courseName = name
            finalScores = grade
        }
        fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }
}
