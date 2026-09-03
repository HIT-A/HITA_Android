package cn.limpu.hita.ui.credit

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ShenzhenCreditProgress
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.SubjectRepository
import cn.limpu.hita.data.source.preference.CreditGoalStore
import cn.limpu.hita.ui.eas.EASViewModel
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

    val termsLiveData: LiveData<DataState<List<TermItem>>> = if (officialSupported) {
        easRepo.getAllTerms().map { state ->
            if (state.state == DataState.STATE.SUCCESS && state.data != null) {
                DataState(
                    TermUtils.filterTermsForStudent(
                        state.data.orEmpty(),
                        easRepo.getEasToken().grade
                    ),
                    state.state
                ).apply { message = state.message }
            } else {
                state
            }
        }
    } else {
        MutableLiveData(DataState<List<TermItem>>(DataState.STATE.NOTHING))
    }
    val selectedTermLiveData = MutableLiveData<TermItem>()

    val shenzhenProgress: LiveData<DataState<ShenzhenCreditProgress>> =
        officialRefreshTrigger.switchMap {
            easRepo.getShenzhenCreditProgress(selectedTermLiveData.value)
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
        val selected = selectedTermLiveData.value
            ?.let { current -> terms.firstOrNull { it.id == current.id } }
            ?: terms.firstOrNull { it.isCurrent }
            ?: terms.firstOrNull()
            ?: return
        if (selectedTermLiveData.value?.id != selected.id) {
            selectedTermLiveData.value = selected
            officialRefreshTrigger.value = true
        }
    }

    fun selectTerm(term: TermItem) {
        if (selectedTermLiveData.value?.id != term.id) {
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
