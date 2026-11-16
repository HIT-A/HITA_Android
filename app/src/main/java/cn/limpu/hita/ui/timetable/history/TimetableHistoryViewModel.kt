package cn.limpu.hita.ui.timetable.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.data.repository.DeletedEventHistory
import cn.limpu.hita.data.repository.SubjectRepository
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.feature.timetable.write.TimetableWriteUseCaseFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DeletionHistoryUiState(
    val timetableId: String? = null,
    val entries: List<DeletedEventHistory> = emptyList(),
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val restoringId: String? = null,
    val restoreSucceeded: Boolean? = null,
)

@HiltViewModel
class TimetableHistoryViewModel @Inject constructor(
    private val timetableRepository: TimetableRepository,
    subjectsRepository: SubjectRepository,
    writeUseCaseFactory: TimetableWriteUseCaseFactory,
) : ViewModel() {
    private val subjectWrites = writeUseCaseFactory.create(timetableRepository, subjectsRepository::enqueueWrite)
    private val _deletionHistory = MutableStateFlow(DeletionHistoryUiState())
    val deletionHistory = _deletionHistory.asStateFlow()
    private var historyJob: Job? = null

    fun loadDeletionHistory(id: String) {
        if (id.isBlank()) return
        historyJob?.cancel()
        _deletionHistory.value = if (_deletionHistory.value.timetableId == id) {
            _deletionHistory.value.copy(loading = true, loadFailed = false)
        } else DeletionHistoryUiState(timetableId = id, loading = true)
        historyJob = viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { timetableRepository.deletedEventHistorySync(id) }
                _deletionHistory.value = _deletionHistory.value.copy(entries = entries, loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _deletionHistory.value = _deletionHistory.value.copy(loading = false, loadFailed = true)
            }
        }
    }

    fun restoreDeletedEvent(historyId: String) {
        val state = _deletionHistory.value
        val id = state.timetableId ?: return
        if (state.loading || state.restoringId != null) return
        _deletionHistory.value = state.copy(restoringId = historyId, restoreSucceeded = null)
        // The same queue as course deletion preserves ordering; the use case refreshes widgets after commit.
        subjectWrites.restoreDeleted(id, historyId) { result ->
            if (_deletionHistory.value.timetableId != id) return@restoreDeleted
            _deletionHistory.value = _deletionHistory.value.copy(
                restoringId = null, restoreSucceeded = result.isSuccess,
            )
            loadDeletionHistory(id)
        }
    }

}
