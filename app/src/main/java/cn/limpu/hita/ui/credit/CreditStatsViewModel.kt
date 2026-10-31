package cn.limpu.hita.ui.credit

import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ShenzhenCreditProgress
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.SubjectRepository
import cn.limpu.hita.data.source.preference.CreditGoalStore
import cn.limpu.hita.ui.eas.EASViewModel
import cn.limpu.hita.ui.eas.TermQueryLiveData
import cn.limpu.hita.ui.eas.mapTerms
import cn.limpu.hita.ui.eas.sessionBoundData
import cn.limpu.hita.ui.eas.TermSelectionDisplaySource
import cn.limpu.hita.ui.eas.reconcileSelectedTerm
import cn.limpu.hita.ui.eas.sameTermKey
import cn.limpu.hita.utils.TermUtils
import com.limpu.component.data.DataState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CreditStatsViewModel @Inject constructor(
    private val subjectRepository: SubjectRepository,
    private val creditGoalStore: CreditGoalStore,
    easRepository: EASRepository
) : EASViewModel(easRepository) {

    private val refreshTrigger = MutableLiveData(true)
    private val officialRefreshTrigger = MutableLiveData<Boolean>()

    val officialSupported: Boolean
        get() = easRepo.getEasToken().campus == EASToken.Campus.SHENZHEN

    private val termQueries = TermQueryLiveData(viewModelScope, easRepo.querySessions,
        createRequest = {
            val session = easRepo.captureQuerySession()
            if (session.campus == EASToken.Campus.SHENZHEN) easRepo.createTermQueryRequest(session) else null
        },
        initiallyRequested = true,
        transform = { session, state -> state.mapTerms {
            TermUtils.filterTermsForStudent(it, session.grade, session.calendarYear, session.calendarMonth)
        } },
        onSessionChanged = {
            selectedTermLiveData.value = null
            selectedTermDisplay.updateOptions(emptyList())
            officialRefreshTrigger.value = true
        },
        onResult = { _, state -> if (state.state == DataState.STATE.SUCCESS) reconcileTerms(state.data.orEmpty()) },
    )
    val termsLiveData: LiveData<DataState<List<TermItem>>> = termQueries
    val selectedTermLiveData = MutableLiveData<TermItem?>()
    internal val selectedTermDisplay = TermSelectionDisplaySource(selectedTermLiveData)

    val shenzhenProgress: LiveData<DataState<ShenzhenCreditProgress>> =
        officialRefreshTrigger.switchMap {
            if (!officialSupported || selectedTermLiveData.value == null) {
                return@switchMap MutableLiveData(DataState(DataState.STATE.NOTHING))
            }
            val operation = UsageAnalyticsClient.begin(UsageAnalyticsEvent.CREDIT_SUMMARY_LOAD_STARTED)
            easRepo.sessionBoundData { easRepo.getShenzhenCreditProgress(selectedTermDisplay.current()) }.map { result ->
                when (result.state) {
                    DataState.STATE.SUCCESS -> UsageAnalyticsClient.finish(operation, UsageAnalyticsEvent.CREDIT_SUMMARY_LOAD_SUCCEEDED)
                    DataState.STATE.NOTHING, DataState.STATE.LOADING -> Unit
                    else -> UsageAnalyticsClient.finish(operation, UsageAnalyticsEvent.CREDIT_SUMMARY_LOAD_FAILED, mapOf("error_category" to "unknown"))
                }
                result
            }
        }

    val creditStats: LiveData<CreditStatsState> = refreshTrigger.switchMap {
        subjectRepository.getAllSubjects().map { subjects ->
            aggregate(subjects, creditGoalStore)
        }
    }

    fun refresh() {
        if (officialSupported) {
            officialRefreshTrigger.value = true
        } else {
            refreshTrigger.value = true
        }
    }

    fun retryOfficial(): Boolean {
        if (!officialSupported) return false
        officialRefreshTrigger.value = true
        return true
    }

    fun reconcileTerms(terms: List<TermItem>) {
        selectedTermDisplay.updateOptions(terms)
        if (terms.isEmpty()) {
            selectedTermLiveData.value = null
            officialRefreshTrigger.value = true
            return
        }
        selectTerm(reconcileSelectedTerm(terms, selectedTermLiveData.value) ?: return)
    }

    fun selectTerm(term: TermItem) {
        selectedTermDisplay.updateTerm(term)
        if (!sameTermKey(selectedTermLiveData.value, term)) {
            selectedTermLiveData.value = term
            officialRefreshTrigger.value = true
        }
    }

    fun setGoal(type: TermSubject.TYPE, credits: Float) {
        creditGoalStore.setGoal(type, credits)
        refresh()
    }

    fun removeGoal(type: TermSubject.TYPE) {
        creditGoalStore.removeGoal(type)
        refresh()
    }

    private fun aggregate(
        subjects: List<TermSubject>,
        goalStore: CreditGoalStore
    ): CreditStatsState {
        // 去重：同一门课可能在多个课表中，按 code+name 去重
        val deduped = subjects
            .filter { it.type != TermSubject.TYPE.TAG }
            .distinctBy { it.code?.takeIf { c -> c.isNotBlank() } ?: it.name }

        if (deduped.isEmpty()) {
            return CreditStatsState(isEmpty = true)
        }

        val typeOrder = listOf(
            TermSubject.TYPE.COM_A,
            TermSubject.TYPE.COM_B,
            TermSubject.TYPE.OPT_A,
            TermSubject.TYPE.OPT_B,
            TermSubject.TYPE.MOOC
        )

        val grouped = deduped.groupBy { it.type }
        val categories = typeOrder.mapNotNull { type ->
            val list = grouped[type] ?: return@mapNotNull null
            val totalCredits = list.sumOf { it.credit.toDouble() }.toFloat()
            val fieldGroup = list.groupBy { it.field?.takeIf { b -> b.isNotBlank() } ?: "未分类" }
            val fieldBreakdown = fieldGroup.map { (field, items) ->
                FieldCreditSummary(
                    fieldName = field,
                    totalCredits = items.sumOf { it.credit.toDouble() }.toFloat()
                )
            }.sortedByDescending { it.totalCredits }

            CreditCategorySummary(
                type = type,
                totalCredits = totalCredits,
                goalCredits = goalStore.getGoal(type),
                subjectCount = list.size,
                subjects = list.map { s ->
                    SubjectCreditItem(
                        name = s.name,
                        credit = s.credit,
                        field = s.field,
                        countInSpa = s.countInSPA
                    )
                },
                fieldBreakdown = fieldBreakdown
            )
        }

        val totalCredits = categories.sumOf { it.totalCredits.toDouble() }.toFloat()
        val totalSubjects = categories.sumOf { it.subjectCount }
        val spaCredits = deduped.filter { it.countInSPA }.sumOf { it.credit.toDouble() }.toFloat()
        val nonSpaCredits = deduped.filter { !it.countInSPA }.sumOf { it.credit.toDouble() }.toFloat()

        return CreditStatsState(
            totalCredits = totalCredits,
            totalSubjects = totalSubjects,
            spaCredits = spaCredits,
            nonSpaCredits = nonSpaCredits,
            categories = categories,
            isEmpty = false
        )
    }
}
