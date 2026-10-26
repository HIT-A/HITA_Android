package cn.limpu.hita.ui.eas.classroom

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomCacheEntity
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.EmptyClassroomQueryRequest
import cn.limpu.hita.data.repository.TeachingBuildingsRequest
import cn.limpu.hita.data.repository.TimetableChangeStore
import cn.limpu.hita.data.repository.TimetableMutationLock
import cn.limpu.hita.data.source.preference.EasCredentialStore
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.data.source.preference.TimetablePreferenceSource
import com.limpu.component.data.DataState
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** The production ViewModel with controlled data sources and isolated inherited login inputs. */
internal class EmptyClassroomViewModelContractHarness {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val context = IsolatedPreferencesContext(app)
    val owner = Owner()
    val store = ViewModelStore()
    val terms = mutableListOf<MutableLiveData<DataState<List<TermItem>>>>()
    val weekTerms = mutableListOf<TermItem?>()
    val weeks = mutableListOf<MutableLiveData<Int>>()
    val structures = mutableListOf<Pair<TermItem, MutableLiveData<DataState<MutableList<TimePeriodInDay>>>>>()
    val requestedQueries = mutableListOf<Query>()
    val classroomSources = mutableListOf<MutableLiveData<DataState<List<ClassroomItem>>>>()
    val states = mutableListOf<DataState<List<ClassroomItem>>>()
    val analytics = mutableListOf<AnalyticsEvent>()
    val jobs = mutableMapOf<Int, Job>()
    private val pendingContinuations = mutableMapOf<Int, Continuation<DataState<List<ClassroomItem>>>>()
    var buildingRequests = 0
    var buildingResult: DataState<List<BuildingItem>> = DataState(emptyList())
    var initialWeek: Int? = null
    var initialClassroomState: DataState<List<ClassroomItem>>? = null
    var onState: (DataState<List<ClassroomItem>>) -> Unit = {}
    var onBegin: () -> Unit = {}
    var onCreateRequest: () -> Unit = {}
    var nonCancellableQueries = false
    var queryOverride: ((TermItem, BuildingItem, Int) -> EmptyClassroomQueryRequest)? = null
    lateinit var model: EmptyClassroomViewModel
        private set

    init {
        val easPreferences = EasPreferenceSource(context)
        val repository = EASRepository(
            app, easPreferences, EasCredentialStore(context),
            TimetablePreferenceSource(context, easPreferences), TimetableChangeStore(app), TimetableMutationLock(),
        )
        val inputs = EmptyClassroomPageInputs(
            getTerms = {
                MutableLiveData<DataState<List<TermItem>>>().also { terms += it }
            },
            createTeachingBuildingsRequest = {
                buildingRequests++
                TeachingBuildingsRequest(null) { buildingResult }
            },
            getCurrentWeek = { term ->
                weekTerms += term
                MutableLiveData<Int>().apply {
                    initialWeek?.let { value = it }
                    weeks += this
                }
            },
            getScheduleStructure = { term ->
                MutableLiveData<DataState<MutableList<TimePeriodInDay>>>().also { structures += term to it }
            },
            createClassroomQueryRequest = { term, building, week ->
                val index = requestedQueries.size
                requestedQueries += Query(term, building, week)
                val request = queryOverride?.invoke(term, building, week) ?: sourceRequest(index)
                onCreateRequest()
                EmptyClassroomQueryRequest(flow {
                    jobs[index] = currentCoroutineContext().job
                    emitAll(request.states())
                })
            },
            beginOperation = { event ->
                UsageAnalyticsClient.Operation(generation = 0, session = null).also {
                    analytics += AnalyticsEvent(it.id, event, emptyMap())
                    onBegin()
                }
            },
            finishOperation = { operation, event, dimensions ->
                if (operation != null && operation.ended.compareAndSet(false, true)) {
                    analytics += AnalyticsEvent(operation.id, event, dimensions)
                }
            },
        )
        main {
            model = EmptyClassroomViewModel(repository, inputs)
            store.put("empty-classroom", model)
            owner.registry.currentState = Lifecycle.State.CREATED
            model.classroomLiveData.observe(owner) { state ->
                states += state
                onState(state)
            }
        }
    }

    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun flush() = instrumentation.waitForIdleSync()
    fun start() { main { owner.registry.currentState = Lifecycle.State.STARTED }; flush() }
    fun stop() { main { owner.registry.currentState = Lifecycle.State.CREATED }; flush() }

    fun select(term: TermItem = term("1"), building: BuildingItem = building("A"), week: Int = 3) {
        main {
            model.selectTerm(term)
            model.selectBuilding(building)
        }
        flush()
        completeWeek(weeks.lastIndex, week)
    }

    fun completeWeek(index: Int, week: Int) {
        main { weeks[index].value = week }
        flush()
    }

    fun complete(index: Int, state: DataState<List<ClassroomItem>>) {
        main { classroomSources[index].value = state }
        flush()
    }

    private fun sourceRequest(index: Int): EmptyClassroomQueryRequest {
        val source = MutableLiveData<DataState<List<ClassroomItem>>>().apply {
            initialClassroomState?.let { value = it }
            classroomSources += this
        }
        return EmptyClassroomQueryRequest(flow {
            if (nonCancellableQueries) {
                emit(suspendCoroutine { pendingContinuations[index] = it })
            } else {
                val values = Channel<DataState<List<ClassroomItem>>>(Channel.UNLIMITED)
                val observer = Observer<DataState<List<ClassroomItem>>> { values.trySend(it) }
                source.observeForever(observer)
                try {
                    for (state in values) emit(state)
                } finally {
                    source.removeObserver(observer)
                    values.close()
                }
            }
        })
    }

    fun completeNonCancellable(index: Int, state: DataState<List<ClassroomItem>>) {
        main { requireNotNull(pendingContinuations.remove(index)).resume(state) }
        flush()
    }

    /** Same controlled boundaries as the legacy test, through the production request factory. */
    fun useRepositoryQuery(
        token: EASToken,
        readCache: (TermItem, BuildingItem, Int) -> List<ClassroomCacheEntity>,
        saveCache: (TermItem, BuildingItem, Int, List<ClassroomItem>) -> Unit,
        remote: LiveData<DataState<List<ClassroomItem>>>,
    ) {
        queryOverride = { selectedTerm, selectedBuilding, selectedWeek ->
            EmptyClassroomQueryRequest.forToken(
                token, selectedTerm, selectedBuilding, selectedWeek,
                readCache = readCache,
                saveCache = { term, building, week, rooms -> saveCache(term, building, week, rooms) },
                queryRemote = { _, _, _, _ ->
                    withContext(Dispatchers.Main.immediate) { requireNotNull(remote.value) }
                },
            )
        }
    }

    fun close() {
        main {
            owner.registry.currentState = Lifecycle.State.DESTROYED
            store.clear()
            pendingContinuations.values.toList().forEach { it.resume(DataState(emptyList())) }
            pendingContinuations.clear()
        }
        flush()
        runBlocking {
            withTimeout(5_000) { jobs.values.toList().forEach { it.join() } }
        }
        context.clear()
    }

    data class Query(val term: TermItem, val building: BuildingItem, val week: Int)
    data class AnalyticsEvent(
        val operationId: String,
        val event: UsageAnalyticsEvent,
        val dimensions: Map<String, String>,
    )

    class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class IsolatedPreferencesContext(base: Context) : ContextWrapper(base) {
        private val prefix = "empty-classroom-viewmodel-${UUID.randomUUID()}-"
        private val names = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names += name
            return super.getSharedPreferences(prefix + name, mode)
        }
        fun clear() { names.forEach { super.getSharedPreferences(prefix + it, MODE_PRIVATE).edit().clear().commit() } }
    }

    companion object {
        fun term(code: String) = TermItem("2026-2027", "2026-2027", code, code)
        fun building(code: String, label: String = code) = BuildingItem().apply { id = code; name = label }
        fun classroom(name: String) = ClassroomItem().apply { this.name = name; id = name; capacity = 40 }
        fun success(name: String): DataState<List<ClassroomItem>> = DataState(listOf(classroom(name)))
    }
}
