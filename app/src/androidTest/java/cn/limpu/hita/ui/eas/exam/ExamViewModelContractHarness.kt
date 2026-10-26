package cn.limpu.hita.ui.eas.exam

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.ExamQueryRequest
import cn.limpu.hita.data.repository.ExamImportRepository
import cn.limpu.hita.data.repository.TimetableChangeStore
import cn.limpu.hita.data.repository.TimetableMutationLock
import cn.limpu.hita.data.source.preference.EasCredentialStore
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.data.source.preference.ExamMemoStore
import cn.limpu.hita.data.source.preference.TimetablePreferenceSource
import com.limpu.component.data.DataState
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import cn.limpu.hita.ui.eas.termPageTestRequest

/** Real ViewModel and memo persistence; controlled source results, time, and analytics. */
internal class ExamViewModelContractHarness(initialMemos: List<ExamItem> = emptyList()) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val context = IsolatedPreferencesContext(app)
    val owner = Owner()
    val store = ViewModelStore()
    val memoStore = ExamMemoStore(context)
    val terms = mutableListOf<MutableLiveData<DataState<List<TermItem>>>>()
    val exams = mutableListOf<MutableLiveData<DataState<List<ExamItem>>>>()
    val requestedTerms = mutableListOf<TermItem>()
    val states = mutableListOf<DataState<List<ExamItem>>>()
    val analytics = mutableListOf<AnalyticsEvent>()
    var now = 10_000L
    var nonCancellableQueries = false
    val jobs = mutableMapOf<Int, Job>()
    private val pendingContinuations = mutableMapOf<Int, Continuation<DataState<List<ExamItem>>>>()
    var termsInitialState: DataState<List<TermItem>>? = null
    var examsInitialState: DataState<List<ExamItem>>? = null
    lateinit var model: ExamViewModel
        private set

    init {
        initialMemos.forEach(memoStore::upsert)
        val easPreferences = EasPreferenceSource(context)
        val repository = EASRepository(
            app, easPreferences, EasCredentialStore(context),
            TimetablePreferenceSource(context, easPreferences), TimetableChangeStore(app), TimetableMutationLock(),
        )
        val inputs = ExamPageInputs(
            getTerms = {
                val source = MutableLiveData<DataState<List<TermItem>>>().apply {
                    termsInitialState?.let { value = it }
                    terms += this
                }
                termPageTestRequest(repository.captureQuerySession(), source)
            },
            createExamQueryRequest = { term ->
                requestedTerms += requireNotNull(term)
                val initial = examsInitialState
                val index = exams.size
                val source = MutableLiveData<DataState<List<ExamItem>>>().apply {
                    initial?.let { value = it }
                    exams += this
                }
                ExamQueryRequest(initial?.takeIf {
                    it.state == DataState.STATE.NOTHING || it.state == DataState.STATE.LOADING
                }) {
                    jobs[index] = currentCoroutineContext().job
                    if (nonCancellableQueries) {
                        suspendCoroutine { pendingContinuations[index] = it }
                    } else {
                        awaitTerminal(source)
                    }
                }
            },
            nowMillis = { now },
            beginOperation = { event ->
                val operation = UsageAnalyticsClient.Operation(generation = 0, session = null)
                analytics += AnalyticsEvent(operation.id, event, emptyMap())
                operation
            },
            finishOperation = { operation, event, dimensions ->
                if (operation != null && operation.ended.compareAndSet(false, true)) {
                    analytics += AnalyticsEvent(operation.id, event, dimensions)
                }
            },
        )
        main {
            model = ExamViewModel(repository, context, ExamImportRepository(app), inputs)
            store.put("exam", model)
            owner.registry.currentState = Lifecycle.State.CREATED
            model.examInfoLiveData.observe(owner) { states += it }
        }
    }

    fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    fun flush() = instrumentation.waitForIdleSync()
    fun start() = main { owner.registry.currentState = Lifecycle.State.STARTED }
    fun stop() = main { owner.registry.currentState = Lifecycle.State.CREATED }
    fun selectAndRefresh(term: TermItem = term("1")) = main {
        model.selectedTermLiveData.value = term
        model.startRefresh()
    }
    fun complete(index: Int, state: DataState<List<ExamItem>>) {
        main { exams[index].value = state }
        flush()
    }
    fun completeNonCancellable(index: Int, state: DataState<List<ExamItem>>) {
        main { requireNotNull(pendingContinuations.remove(index)).resume(state) }
        flush()
    }

    private suspend fun awaitTerminal(
        source: MutableLiveData<DataState<List<ExamItem>>>,
    ): DataState<List<ExamItem>> = suspendCancellableCoroutine { continuation ->
        val observer = object : Observer<DataState<List<ExamItem>>> {
            override fun onChanged(state: DataState<List<ExamItem>>) {
                if (state.state == DataState.STATE.NOTHING || state.state == DataState.STATE.LOADING) return
                source.removeObserver(this)
                if (continuation.isActive) continuation.resume(state)
            }
        }
        continuation.invokeOnCancellation {
            if (Looper.myLooper() == Looper.getMainLooper()) source.removeObserver(observer)
            else Handler(Looper.getMainLooper()).post { source.removeObserver(observer) }
        }
        source.observeForever(observer)
    }

    fun close() {
        main {
            owner.registry.currentState = Lifecycle.State.DESTROYED
            store.clear()
            pendingContinuations.values.toList().forEach { it.resume(DataState(emptyList())) }
            pendingContinuations.clear()
        }
        flush()
        context.clear()
    }

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
        private val prefix = "exam-viewmodel-contract-${UUID.randomUUID()}-"
        private val names = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names += name
            return super.getSharedPreferences(prefix + name, mode)
        }
        fun clear() { names.forEach { super.getSharedPreferences(prefix + it, MODE_PRIVATE).edit().clear().commit() } }
    }

    companion object {
        fun term(code: String, current: Boolean = false) =
            TermItem("2026-2027", "2026-2027", code, code).apply { isCurrent = current }

        fun exam(
            name: String,
            term: TermItem? = term("1"),
            type: String = "期末",
            memo: Boolean = false,
        ) = ExamItem().apply {
            courseName = name
            termId = term?.id
            examType = type
            examDate = "2026-11-01"
            examTime = "09:00-11:00"
            if (memo) memoId = "memo-$name"
        }
    }
}
