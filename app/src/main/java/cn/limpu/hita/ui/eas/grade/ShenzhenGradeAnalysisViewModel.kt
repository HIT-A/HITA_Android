package cn.limpu.hita.ui.eas.grade

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.data.model.eas.ShenzhenGradeAnalysis
import cn.limpu.hita.data.model.eas.ShenzhenGradeCourse
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogItem
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogSource
import cn.limpu.hita.data.model.eas.ShenzhenHistoricalFailureReport
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.ui.eas.EASViewModel
import cn.limpu.hita.ui.eas.TermQueryLiveData
import cn.limpu.hita.ui.eas.mapTerms
import cn.limpu.hita.ui.eas.sessionBoundData
import cn.limpu.hita.ui.eas.TermSelectionDisplaySource
import cn.limpu.hita.ui.eas.reconcileSelectedTerm
import cn.limpu.hita.ui.eas.sameTermKey
import cn.limpu.hita.utils.TermUtils
import com.limpu.component.data.DataState
import com.limpu.component.data.Trigger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ShenzhenGradeAnalysisViewModel @Inject constructor(
    easRepo: EASRepository
) : EASViewModel(easRepo) {
    private val termQueries = TermQueryLiveData(viewModelScope, easRepo.querySessions,
        createRequest = { easRepo.createTermQueryRequest() },
        transform = { session, state -> state.mapTerms {
            TermUtils.filterTermsForStudent(it, session.grade, session.calendarYear, session.calendarMonth)
        } },
        onSessionChanged = {
            selectedTerm.value = null
            selectedTermDisplay.updateOptions(emptyList())
            selectedCourse.value = null
            peerTeacherRequest.value = null
            courseRefresh.value = Trigger.actioning
        },
        onResult = { _, state -> if (state.state == DataState.STATE.SUCCESS) reconcileTerms(state.data.orEmpty()) },
    )
    val terms: LiveData<DataState<List<TermItem>>> = termQueries

    val selectedTerm = MutableLiveData<TermItem>()
    internal val selectedTermDisplay = TermSelectionDisplaySource(selectedTerm)
    private val courseRefresh = MutableLiveData<Trigger>()
    val courses: LiveData<DataState<List<ShenzhenGradeCourse>>> = courseRefresh.switchMap {
        selectedTermDisplay.current()?.let { term -> easRepo.sessionBoundData { easRepo.getShenzhenGradeCourses(term) } }
            ?: MutableLiveData(DataState(DataState.STATE.NOTHING))
    }

    val selectedCourse = MutableLiveData<ShenzhenGradeCourse?>()
    val analysis: LiveData<DataState<ShenzhenGradeAnalysis>> = selectedCourse.switchMap { course ->
        course?.let { selected -> easRepo.sessionBoundData { easRepo.getShenzhenGradeAnalysis(selected) } }
            ?: MutableLiveData(DataState(DataState.STATE.NOTHING))
    }
    private val peerTeacherRequest = MutableLiveData<Pair<TermItem, ShenzhenGradeCourse>>()
    val peerTeacherComparison: LiveData<DataState<ShenzhenHistoricalFailureReport>> =
        peerTeacherRequest.switchMap { request ->
            if (request == null) return@switchMap MutableLiveData(DataState(DataState.STATE.NOTHING))
            val (term, course) = request
            easRepo.sessionBoundData { session -> easRepo.getShenzhenHistoricalTeacherFailureRates(
                referenceTerm = term,
                studentType = session.token.getStudentType(),
                referenceCourse = ShenzhenCourseCatalogItem(
                    id = course.taskId,
                    taskId = course.taskId,
                    taskNumber = course.taskNumber,
                    courseCode = course.courseCode,
                    courseName = course.courseName,
                    teacher = course.teacher,
                    source = ShenzhenCourseCatalogSource.SCHOOL
                ),
                yearsBack = 0
            ) }
        }

    fun refreshTerms() {
        termQueries.refresh()
    }

    fun selectTerm(term: TermItem) {
        selectedTermDisplay.updateTerm(term)
        if (sameTermKey(selectedTerm.value, term)) return
        selectedCourse.value = null
        peerTeacherRequest.value = null
        selectedTerm.value = term
        courseRefresh.value = Trigger.actioning
    }

    fun reconcileTerms(values: List<TermItem>) {
        selectedTermDisplay.updateOptions(values)
        val current = selectedTerm.value
        if (values.isEmpty()) {
            selectedTerm.value = null
            selectedCourse.value = null
            peerTeacherRequest.value = null
            courseRefresh.value = Trigger.actioning
            return
        }
        selectTerm(reconcileSelectedTerm(values, current) ?: return)
    }

    fun openCourse(course: ShenzhenGradeCourse) {
        selectedCourse.value = course
    }

    fun closeCourse() {
        selectedCourse.value = null
    }

    fun retryCourses(): Boolean {
        if (selectedTerm.value == null) return false
        courseRefresh.value = Trigger.actioning
        return true
    }

    fun retryAnalysis(): Boolean {
        val course = selectedCourse.value ?: return false
        selectedCourse.value = course.copy()
        return true
    }

    fun loadPeerTeacherComparison(): Boolean {
        val term = selectedTermDisplay.current() ?: return false
        val course = selectedCourse.value ?: return false
        peerTeacherRequest.value = term to course
        return true
    }

    fun retryPeerTeacherComparison(): Boolean {
        val request = peerTeacherRequest.value ?: return false
        val term = selectedTermDisplay.current()?.takeIf { sameTermKey(it, request.first) } ?: return false
        peerTeacherRequest.value = request.copy(first = term)
        return true
    }
}
