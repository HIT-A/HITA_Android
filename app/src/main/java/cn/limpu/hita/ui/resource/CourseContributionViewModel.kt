package cn.limpu.hita.ui.resource

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import com.limpu.component.data.DataState
import cn.limpu.hita.data.model.resource.CourseStructureSummary
import cn.limpu.hita.data.model.resource.CoursePreviewData
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.HoaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import org.json.JSONArray
import javax.inject.Inject

@HiltViewModel
class CourseContributionViewModel @Inject constructor(
    private val repository: HoaRepository,
    private val easRepository: EASRepository
) : ViewModel() {
    private val hoaCampus = easRepository.getHoaCampus()

    private val repoNameLiveData = MutableLiveData<String>()
    val structureLiveData: LiveData<DataState<CourseStructureSummary>> = repoNameLiveData.switchMap {
        repository.getCourseStructure(it, hoaCampus)
    }

    private val submitRequestLiveData = MutableLiveData<PreviewRequest>()
    private val submitting = MutableLiveData(false)
    internal val submitInFlight: LiveData<Boolean> = submitting
    val submitLiveData: LiveData<DataState<String>> = submitRequestLiveData.switchMap { request ->
        repository.submitOps(
            request.repoName, request.courseCode, request.courseName, request.repoType, JSONArray(request.ops), hoaCampus,
        ).map { result ->
            submitting.value = false
            result
        }
    }

    private val previewRequestLiveData = MutableLiveData<PreviewRequest?>()
    internal val previewLiveData: LiveData<PreviewResult?> = previewRequestLiveData.switchMap { request ->
        if (request == null) MutableLiveData<PreviewResult?>(null)
        else repository.previewOps(
            request.repoName, request.courseCode, request.courseName, JSONArray(request.ops), hoaCampus,
        ).map<DataState<CoursePreviewData>, PreviewResult?> { PreviewResult(request, it) }
    }

    fun load(repoName: String) {
        if (repoNameLiveData.value != repoName) repoNameLiveData.value = repoName
    }

    internal fun preview(
        repoName: String, courseCode: String, courseName: String, repoType: String, ops: JSONArray,
    ): PreviewRequest = PreviewRequest(repoName, courseCode, courseName, repoType, ops.toString()).also {
        previewRequestLiveData.value = it
    }

    internal fun dismissPreview(request: PreviewRequest) {
        if (previewRequestLiveData.value === request) previewRequestLiveData.value = null
    }

    internal fun confirmPreview(expected: PreviewResult): Boolean {
        val request = expected.request
        if (submitting.value == true || previewLiveData.value !== expected ||
            previewRequestLiveData.value !== request || expected.data.state != DataState.STATE.SUCCESS ||
            expected.data.data?.markdown.isNullOrBlank()
        ) return false

        submitting.value = true
        dismissPreview(request)
        submitRequestLiveData.value = request
        return true
    }

    internal data class PreviewRequest(
        val repoName: String,
        val courseCode: String,
        val courseName: String,
        val repoType: String,
        val ops: String,
    )

    internal data class PreviewResult(val request: PreviewRequest, val data: DataState<CoursePreviewData>)
}
