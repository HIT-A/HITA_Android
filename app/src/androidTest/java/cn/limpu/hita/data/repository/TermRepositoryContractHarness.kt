package cn.limpu.hita.data.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteConstraintException
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ScoreTermCacheEntity
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.source.preference.EasCredentialStore
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.data.source.preference.TimetablePreferenceSource
import cn.limpu.hita.data.source.web.eas.BenbuEASWebSource
import cn.limpu.hita.data.source.web.eas.EASWebSource
import cn.limpu.hita.data.source.web.eas.TermQueryContractHarness
import cn.limpu.hita.data.source.web.eas.WeihaiEASWebSource
import cn.limpu.hita.data.source.web.service.EASService
import com.limpu.component.data.DataState
import java.io.Closeable
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Executes the real legacy repository, Room cache and Main/worker scheduling. */
abstract class TermRepositoryContractHarness : TermQueryContractHarness() {
    private val fixtures = CopyOnWriteArrayList<RepositoryFixture>()

    @After
    fun closeTermRepositories() {
        var failure: Throwable? = null
        fixtures.reversed().forEach { fixture ->
            try {
                fixture.close()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        fixtures.clear()
        failure?.let { throw it }
    }

    protected fun fixture(
        token: EASToken = accountToken(),
        realHttp: Boolean = false,
    ): RepositoryFixture = RepositoryFixture(token, realHttp).also(fixtures::add)

    protected fun accountToken(campus: EASToken.Campus = EASToken.Campus.BENBU) = EASToken().apply {
        this.campus = campus
        stuId = "student"
        username = "login-name"
        sessionGeneration = 211L
        if (campus == EASToken.Campus.SHENZHEN) accessToken = "local-api-token"
        else cookies["JSESSIONID"] = "local-term-session"
    }

    protected fun cachedRow(
        owner: String = "BENBU:student",
        year: String = "2025-2026",
        code: String = "1",
        label: String = "cached",
        current: Boolean = false,
        cachedAt: Long = System.currentTimeMillis(),
    ) = ScoreTermCacheEntity(owner, year, "year-$year", code, label, current, cachedAt)

    protected fun remoteTerm(
        code: String = "2",
        label: String = "remote",
        year: String = "2025-2026",
    ) = TermItem(year, "year-$year", code, label)

    protected fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    protected fun flushMain() = instrumentation.waitForIdleSync()

    protected inner class RepositoryFixture(initialToken: EASToken, realHttp: Boolean) : Closeable {
        private val threadsAtCreation = Thread.getAllStackTraces().keys.toSet()
        private val application = IsolatedApplication(ApplicationProvider.getApplicationContext())
        private val observations = CopyOnWriteArrayList<TermObservation>()
        private val realTermSources = CopyOnWriteArrayList<LiveData<DataState<List<TermItem>>>>()
        private val workers = ConcurrentHashMap.newKeySet<Thread>()
        private val loadStarts = LinkedBlockingQueue<Thread>()
        private val saveStarts = LinkedBlockingQueue<Thread>()
        private val workerFailures = CopyOnWriteArrayList<Pair<String, Throwable>>()
        private var allowReplacementTriggerFailure = false
        private val roomQueryExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "term-repository-room")
        }
        val sql = CopyOnWriteArrayList<SqlCall>()
        @Volatile var onSql: (SqlCall) -> Unit = {}
        val preferences = EasPreferenceSource(application).also { it.saveEasToken(initialToken) }
        val database: AppDatabase = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .setQueryExecutor(roomQueryExecutor)
            .setQueryCallback({ statement, arguments -> recordSql(statement, arguments) }, { it.run() })
            .build()
        val scripted = ScriptedTermService()
        val repository: EASRepository

        init {
            val factories = EasRepositoryServiceFactories(
                shenzhen = { callback, credentials ->
                    EASWebSource(
                        onTokenRefreshed = callback,
                        credentialProvider = credentials,
                        apiHostOverride = api.url("/").toString(),
                        webHostOverride = web.url("/").toString(),
                    )
                },
                benbu = { callback ->
                    if (realHttp) trackRealTermSource(BenbuEASWebSource(
                        teachingBuildingsHostOverride = null,
                        onCookiesUpdated = callback,
                        termsHostOverride = api.url("/").toString(),
                        graduateTermsHostOverride = api.url("/").toString(),
                    )) else scripted.service
                },
                weihai = { callback ->
                    if (realHttp) trackRealTermSource(WeihaiEASWebSource(
                        teachingBuildingsHostOverride = null,
                        onCookiesUpdated = callback,
                        termsHostOverride = api.url("/").toString(),
                    )) else scripted.service
                },
            )
            repository = EASRepository(
                application, preferences, EasCredentialStore(application),
                TimetablePreferenceSource(application, preferences), TimetableChangeStore(application),
                TimetableMutationLock(), database, factories,
            )
        }

        private fun trackRealTermSource(actual: EASService): EASService = object : EASService by actual {
            override fun getAllTerms(token: EASToken): LiveData<DataState<List<TermItem>>> =
                actual.getAllTerms(token).also(realTermSources::add)
        }

        fun seed(vararg rows: ScoreTermCacheEntity) {
            database.scoreCacheDao().saveTermsSync(rows.toList())
            awaitRoomIdle()
        }

        fun rows(owner: String = "BENBU:student") = database.scoreCacheDao().getTermsSync(owner)

        fun clearSql() {
            awaitRoomIdle()
            sql.clear()
        }

        private fun awaitRoomIdle() {
            // Room 2.8 schedules invalidation after the seeding transaction returns. Its
            // synchronous refresh alone can race an already-started asynchronous refresh.
            // The fixture's serial executor barrier also waits for that earlier task, so
            // clearing the log never misattributes seeding SQL to the subsequent query.
            database.invalidationTracker.refreshVersionsSync()
            roomQueryExecutor.submit {}.get(20, TimeUnit.SECONDS)
        }

        fun query(useCache: Boolean = true, observe: Boolean = true): TermObservation =
            createObservation(observe) { repository.getAllTerms(useCache) }

        fun queryWeb(observe: Boolean = true): TermObservation =
            createObservation(observe) { repository.getShenzhenWebTerms() }

        private fun createObservation(
            observe: Boolean,
            query: () -> LiveData<DataState<List<TermItem>>>,
        ): TermObservation {
            lateinit var observation: TermObservation
            main {
                observation = TermObservation(query())
                if (observe) observation.attachOnMain()
            }
            observations += observation
            return observation
        }

        fun awaitCacheRead() {
            joinWorker(requireNotNull(loadStarts.poll(20, TimeUnit.SECONDS)) { "Cache reader did not start" })
            flushMain()
        }

        fun awaitCacheSave() {
            joinWorker(requireNotNull(saveStarts.poll(20, TimeUnit.SECONDS)) { "Cache writer did not start" })
            flushMain()
        }

        fun assertWorkersHealthy() {
            val injected = workerFailures.filter { (worker, error) ->
                allowReplacementTriggerFailure && worker == "score-term-cache-save" &&
                    error.javaClass == SQLiteConstraintException::class.java &&
                    error.message?.substringBefore(" (code ") == "injected term replacement failure"
            }
            injected.forEach { (_, error) ->
                Log.e("TermRepositoryContract", "Recorded the explicitly injected term replacement failure", error)
            }
            assertTrue("Unexpected duplicate injected failures: $injected", injected.size <= 1)
            val unexpected = workerFailures - injected.toSet()
            assertTrue("Repository worker failures: $unexpected", unexpected.isEmpty())
        }

        fun allowInjectedTermReplacementFailure() {
            allowReplacementTriggerFailure = true
        }

        private fun recordSql(statement: String, arguments: List<Any?>) {
            val thread = Thread.currentThread()
            if (thread.name in setOf("score-term-cache-load", "score-term-cache-save", "score-cache-clear") &&
                workers.add(thread)
            ) {
                // Keep an unexpected legacy worker failure attributable to its testcase.
                // DAO exceptions still escape the original worker; no production catch is added.
                thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { worker, error ->
                    workerFailures += worker.name to error
                }
                when (thread.name) {
                    "score-term-cache-load" -> loadStarts.offer(thread)
                    "score-term-cache-save" -> saveStarts.offer(thread)
                }
            }
            val call = SqlCall(statement, arguments.toList(), thread)
            sql += call
            onSql(call)
        }

        private fun joinWorker(thread: Thread) {
            assertTrue("Cannot join the testcase thread", thread !== Thread.currentThread())
            thread.join(20_000)
            assertFalse("Repository worker did not finish: ${thread.name}", thread.isAlive)
        }

        private fun joinStartedWorkers(includeRawHttpThreads: Boolean = false) {
            // Thread.start runs before getAllTerms/the Main callback returns. After Main is
            // detached and drained, this also sees workers that have not reached their first
            // SQL callback. Multiple fixtures in this one testcase may share this drain;
            // pre-existing application threads are excluded.
            val newlyStarted = Thread.getAllStackTraces().keys.filter { thread ->
                thread !in threadsAtCreation && thread.isAlive &&
                    (thread.name in setOf("score-term-cache-load", "score-term-cache-save", "score-cache-clear") ||
                        (includeRawHttpThreads && thread.name.matches(Regex("Thread-\\d+"))))
            }
            (workers + newlyStarted).forEach(::joinWorker)
        }

        private fun awaitRealTermSources() {
            // Only teardown observes the original source. The repository is already detached,
            // so this cannot activate its Mediator or manufacture a cache write. A failed HTTP
            // result is visible here even when the repository would suppress it on a cache hit.
            realTermSources.forEach { source ->
                val finished = CountDownLatch(1)
                val observer = Observer<DataState<List<TermItem>>> { state ->
                    if (state.state != DataState.STATE.NOTHING && state.state != DataState.STATE.LOADING) {
                        finished.countDown()
                    }
                }
                try {
                    main { source.observeForever(observer) }
                    assertTrue("The actual term HTTP source did not finish", finished.await(20, TimeUnit.SECONDS))
                } finally {
                    main { source.removeObserver(observer) }
                }
            }
            // These are cached-pool threads: wait for the completed term task to return,
            // never join the pool thread itself. The raw source result above guarantees
            // the task has started, avoiding a not-yet-entered-lambda false completion.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (true) {
                val active = Thread.getAllStackTraces().filterValues { stack ->
                    stack.any { frame ->
                        frame.className == "cn.limpu.hita.data.source.web.eas.AbstractEASWebSource" &&
                            (frame.methodName.startsWith("requestAllTerms") || frame.methodName.startsWith("getAllTerms"))
                    }
                }.keys
                if (active.isEmpty()) return
                assertTrue("Term tasks did not return: ${active.map { it.name }}", System.nanoTime() < deadline)
                Thread.sleep(10)
            }
        }

        override fun close() {
            onSql = {}
            try {
                observations.forEach { it.close() }
                flushMain()
                joinStartedWorkers()
                // A completed cache reader may just have posted source creation to Main.
                flushMain()
                awaitRealTermSources()
                // Shenzhen terms use finite unnamed Threads, rather than the legacy pool.
                joinStartedWorkers(includeRawHttpThreads = true)
                flushMain()
                assertWorkersHealthy()
            } finally {
                try {
                    database.close()
                } finally {
                    roomQueryExecutor.shutdown()
                    application.close()
                    assertTrue("Room executor did not stop", roomQueryExecutor.awaitTermination(20, TimeUnit.SECONDS))
                }
            }
        }
    }

    protected inner class TermObservation(val source: LiveData<DataState<List<TermItem>>>) : Closeable {
        val states = CopyOnWriteArrayList<DataState<List<TermItem>>>()
        private val arrivals = LinkedBlockingQueue<DataState<List<TermItem>>>()
        private val observer = Observer<DataState<List<TermItem>>> {
            states += it
            arrivals.offer(it)
        }
        private var attached = false

        fun attach() = main { attachOnMain() }

        fun attachOnMain() {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!attached) {
                source.observeForever(observer)
                attached = true
            }
        }

        fun await(predicate: (DataState<List<TermItem>>) -> Boolean): DataState<List<TermItem>> {
            states.lastOrNull(predicate)?.let { return it }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw AssertionError("No matching repository state: $states")
                val state = arrivals.poll(remaining, TimeUnit.NANOSECONDS)
                    ?: throw AssertionError("No matching repository state: $states")
                if (predicate(state)) return state
            }
        }

        override fun close() = main {
            if (attached) source.removeObserver(observer)
            attached = false
        }
    }

    protected inner class ScriptedTermService {
        val calls = CopyOnWriteArrayList<RemoteCall>()
        private val arrivals = LinkedBlockingQueue<RemoteCall>()
        var initialState: DataState<List<TermItem>>? = null
        val service: EASService = Proxy.newProxyInstance(
            EASService::class.java.classLoader, arrayOf(EASService::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getAllTerms" -> {
                    check(Looper.myLooper() == Looper.getMainLooper())
                    val source = MutableLiveData<DataState<List<TermItem>>>()
                    initialState?.let { source.value = it }
                    val call = RemoteCall(arguments?.single() as EASToken, source, Thread.currentThread())
                    calls += call
                    arrivals.offer(call)
                    source
                }
                "toString" -> "ScriptedTermService"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.single()
                else -> throw AssertionError("Unexpected service method ${method.name}")
            }
        } as EASService

        fun awaitCall(): RemoteCall = requireNotNull(arrivals.poll(20, TimeUnit.SECONDS)) {
            "The actual repository did not call its term service"
        }

        fun complete(call: RemoteCall, state: DataState<List<TermItem>>) = main { call.source.value = state }
    }

    protected data class RemoteCall(
        val token: EASToken,
        val source: MutableLiveData<DataState<List<TermItem>>>,
        val thread: Thread,
    )

    protected data class SqlCall(val sql: String, val arguments: List<Any?>, val thread: Thread)

    private class IsolatedApplication(context: Context) : Application(), Closeable {
        private val prefix = "term-repository-${UUID.randomUUID()}-"
        private val names = ConcurrentHashMap.newKeySet<String>()
        private val directory = File(context.cacheDir, prefix).apply { mkdirs() }

        init { attachBaseContext(context) }

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names += name
            return super.getSharedPreferences(prefix + name, mode)
        }

        override fun deleteSharedPreferences(name: String): Boolean {
            names += name
            return super.deleteSharedPreferences(prefix + name)
        }

        override fun close() {
            names.forEach { super.deleteSharedPreferences(prefix + it) }
            directory.deleteRecursively()
        }
    }
}
