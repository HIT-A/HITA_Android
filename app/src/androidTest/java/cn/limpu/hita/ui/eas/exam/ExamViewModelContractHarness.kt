package cn.limpu.hita.ui.eas.exam

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.ExamImportRepository
import cn.limpu.hita.data.repository.TimetableChangeStore
import cn.limpu.hita.data.repository.TimetableMutationLock
import cn.limpu.hita.data.source.preference.EasCredentialStore
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.data.source.preference.ExamMemoStore
import cn.limpu.hita.data.source.preference.TimetablePreferenceSource
import com.limpu.component.data.DataState
import java.util.UUID

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
                MutableLiveData<DataState<List<TermItem>>>().apply {
                    termsInitialState?.let { value = it }
                    terms += this
                }
            },
            getExams = { term ->
                requestedTerms += term
                MutableLiveData<DataState<List<ExamItem>>>().apply {
                    examsInitialState?.let { value = it }
                    exams += this
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
    fun close() {
        main {
            owner.registry.currentState = Lifecycle.State.DESTROYED
            store.clear()
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
