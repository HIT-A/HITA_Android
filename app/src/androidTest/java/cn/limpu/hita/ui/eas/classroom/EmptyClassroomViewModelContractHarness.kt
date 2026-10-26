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
import cn.limpu.hita.data.repository.LegacyEmptyClassroomQuery
import cn.limpu.hita.data.repository.TeachingBuildingsRequest
import cn.limpu.hita.data.repository.TimetableChangeStore
import cn.limpu.hita.data.repository.TimetableMutationLock
import cn.limpu.hita.data.source.preference.EasCredentialStore
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.data.source.preference.TimetablePreferenceSource
import com.limpu.component.data.DataState
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** The production ViewModel with controlled data sources and isolated inherited login inputs. */
internal class EmptyClassroomViewModelContractHarness {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val context = IsolatedPreferencesContext(app)
    private val repositoryWorkers = CopyOnWriteArrayList<Thread>()
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
    var buildingRequests = 0
    var initialWeek: Int? = null
    var initialClassroomState: DataState<List<ClassroomItem>>? = null
    var onState: (DataState<List<ClassroomItem>>) -> Unit = {}
    var queryOverride: ((TermItem, BuildingItem, Int) -> LiveData<DataState<List<ClassroomItem>>>)? = null
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
                TeachingBuildingsRequest(null) { DataState(emptyList()) }
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
            queryClassrooms = { term, building, week ->
                requestedQueries += Query(term, building, week)
                queryOverride?.invoke(term, building, week)
                    ?: MutableLiveData<DataState<List<ClassroomItem>>>().apply {
                        initialClassroomState?.let { value = it }
                        classroomSources += this
                    }
            },
            beginOperation = { event ->
                UsageAnalyticsClient.Operation(generation = 0, session = null).also {
                    analytics += AnalyticsEvent(it.id, event, emptyMap())
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

    /** Only this adapter changes when the page adopts the production Flow entry point. */
    fun useRepositoryQuery(
        token: EASToken,
        readCache: (TermItem, BuildingItem, Int) -> List<ClassroomCacheEntity>,
        saveCache: (TermItem, BuildingItem, Int, List<ClassroomItem>) -> Unit,
        remote: LiveData<DataState<List<ClassroomItem>>>,
    ) {
        val query = LegacyEmptyClassroomQuery(
            readCache = { term, building, week ->
                repositoryWorkers.addIfAbsent(Thread.currentThread())
                readCache(term, building, week)
            },
            saveCache = { term, building, week, classrooms ->
                repositoryWorkers.addIfAbsent(Thread.currentThread())
                saveCache(term, building, week, classrooms)
            },
            queryRemote = { _, _, _, _ -> remote },
        )
        queryOverride = { selectedTerm, selectedBuilding, selectedWeek ->
            query.query(token, selectedTerm, selectedBuilding, selectedWeek)
        }
    }

    fun close() {
        main {
            owner.registry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
        flush()
        repositoryWorkers.forEach { worker ->
            worker.join(5_000)
            check(!worker.isAlive) { "Repository test worker did not finish: ${worker.name}" }
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
