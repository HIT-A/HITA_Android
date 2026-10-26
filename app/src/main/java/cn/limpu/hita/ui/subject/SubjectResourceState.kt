package cn.limpu.hita.ui.subject

import cn.limpu.hita.data.model.resource.CourseResourceItem
import cn.limpu.hita.data.model.resource.ExternalCourseItem
import cn.limpu.hita.data.model.resource.ExternalResourceEntry

sealed interface SubjectReadmeUiState {
    data object Loading : SubjectReadmeUiState
    data class Message(val text: String) : SubjectReadmeUiState
    data class Success(val markdown: String, val source: String) : SubjectReadmeUiState
}

data class CandidateReadmeState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val markdown: String = "",
    val source: String = "",
    val message: String = "",
)

data class ExternalBrowseUiState(
    val breadcrumb: String,
    val loading: Boolean,
    val entries: List<ExternalResourceEntry>,
    val message: String,
    val loaded: Boolean = false,
)

data class SubjectResourceState(
    val key: SubjectResourceKey? = null,
    val readme: SubjectReadmeUiState = SubjectReadmeUiState.Loading,
    val candidates: List<CourseResourceItem> = emptyList(),
    val candidateExpanded: Map<String, Boolean> = emptyMap(),
    val candidateReadmes: Map<String, CandidateReadmeState> = emptyMap(),
    val externalCourses: List<ExternalCourseItem> = emptyList(),
    val externalExpanded: Map<String, Boolean> = emptyMap(),
    val directories: Map<String, ExternalBrowseUiState> = emptyMap(),
    val selectedCandidate: CourseResourceItem? = null,
)

data class SubjectResourceText(
    val missing: String,
    val chooseCard: String,
    val failed: String,
    val emptyDirectory: String,
    val detailsFallbackTitle: String,
)

internal fun itemKey(item: CourseResourceItem): String =
    listOf(item.repoType, item.repoName, item.courseCode, item.courseName).joinToString("|")

internal fun itemKey(item: ExternalCourseItem): String =
    listOf(item.source.name, item.path, item.courseName).joinToString("|")
