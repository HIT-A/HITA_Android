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

internal data class CourseVersionsUiState(
    val timetableId: String? = null,
    val versions: List<cn.limpu.hita.data.model.timetable.TimetableVersionSummary> = emptyList(),
    val comparison: cn.limpu.hita.data.repository.TimetableVersionComparison? = null,
    val loading: Boolean = false,
    val restoring: Boolean = false,
    val failed: Boolean = false,
    val restored: Boolean = false,
    val hasLegacy: Boolean = false,
)

@HiltViewModel
class TimetableHistoryViewModel @Inject constructor(
    private val application: android.app.Application,
    private val timetableRepository: TimetableRepository,
    subjectsRepository: SubjectRepository,
    writeUseCaseFactory: TimetableWriteUseCaseFactory,
) : ViewModel() {
    private val writes = writeUseCaseFactory.create(timetableRepository, subjectsRepository::enqueueWrite)
    private val _versions = MutableStateFlow(CourseVersionsUiState())
    internal val versions = _versions.asStateFlow()
    private var job: Job? = null
    private val _deleted = MutableStateFlow(DeletionHistoryUiState())
    internal val deleted = _deleted.asStateFlow()
    private var deletionJob: Job? = null

    fun loadDeleted(id: String) {
        if (_deleted.value.restoringId != null) return
        deletionJob?.cancel()
        val sameTable = _deleted.value.timetableId == id
        _deleted.value = DeletionHistoryUiState(timetableId = id, loading = true,
            restoreSucceeded = _deleted.value.restoreSucceeded.takeIf { sameTable })
        deletionJob = viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { timetableRepository.personalDeletionHistorySync(id) }
                _deleted.value = _deleted.value.copy(entries = entries, loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _deleted.value = _deleted.value.copy(loading = false, loadFailed = true)
            }
        }
    }

    fun restoreDeleted(historyId: String) {
        val state = _deleted.value
        val id = state.timetableId ?: return
        if (state.loading || state.restoringId != null || state.entries.none {
            it.id == historyId && it.status == cn.limpu.hita.data.repository.DeletedEventStatus.AVAILABLE
        }) return
        _deleted.value = state.copy(restoringId = historyId, restoreSucceeded = null)
        writes.restoreDeleted(id, historyId) { result ->
            _deleted.value = _deleted.value.copy(restoringId = null, restoreSucceeded = result.isSuccess)
            loadDeleted(id)
        }
    }
    private fun store() = cn.limpu.hita.data.repository.TimetableVersionStore.forApplication(
        application, cn.limpu.hita.data.AppDatabase.getDatabase(application))

    fun loadVersions(id: String) {
        if (_versions.value.restoring) return
        job?.cancel()
        _versions.value = CourseVersionsUiState(timetableId = id, loading = true, restored = _versions.value.restored)
        job = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val owner = cn.limpu.hita.data.repository.FollowedTeachingSectionStore.ownerKey(
                        cn.limpu.hita.data.source.preference.EasPreferenceSource(application).getEasToken())
                    store().list(id) to cn.limpu.hita.data.repository.TimetableSnapshotStore(application,
                        cn.limpu.hita.data.AppDatabase.getDatabase(application)).hasSnapshots(owner, id)
                }
                _versions.value = _versions.value.copy(versions = result.first, hasLegacy = result.second, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _versions.value = _versions.value.copy(loading = false, failed = true) }
        }
    }

    fun compareVersion(versionId: String) {
        val id = _versions.value.timetableId ?: return
        if (_versions.value.restoring) return
        job?.cancel()
        _versions.value = _versions.value.copy(loading = true, failed = false, restored = false)
        job = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { store().compare(id, versionId) }
                _versions.value = _versions.value.copy(comparison = result, loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _versions.value = _versions.value.copy(loading = false, failed = true) }
        }
    }

    fun restoreVersion() {
        val state = _versions.value
        val comparison = state.comparison ?: return
        val id = state.timetableId ?: return
        if (state.loading || state.restoring) return
        _versions.value = state.copy(restoring = true, failed = false)
        writes.restoreVersion(id, comparison.version.id, comparison.currentRevision) { outcome ->
            _versions.value = _versions.value.copy(restoring = false, failed = outcome.isFailure,
                restored = outcome.isSuccess)
            if (outcome.isSuccess) loadVersions(id)
            // On stale preview, keep the comparison visible but require a new comparison before retry.
            else _versions.value = _versions.value.copy(comparison = null)
        }
    }
}
