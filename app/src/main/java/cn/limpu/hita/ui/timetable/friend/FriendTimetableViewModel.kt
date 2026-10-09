package cn.limpu.hita.ui.timetable.friend

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.share.FriendTimetableEntity
import cn.limpu.hita.data.repository.FriendTimetableRepository
import cn.limpu.hita.feature.timetableshare.protocol.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FriendTimetableViewModel @Inject constructor(private val repository: FriendTimetableRepository) : ViewModel() {
    var friends by mutableStateOf<List<FriendTimetableEntity>>(emptyList()); private set
    var listLoading by mutableStateOf(true); private set
    var listError by mutableStateOf(false); private set
    var form by mutableStateOf(FriendImportUiState()); private set
    var importOpen by mutableStateOf(false); private set
    var openShareId by mutableStateOf<String?>(null); private set
    var renameRow by mutableStateOf<FriendTimetableEntity?>(null); private set
    var remark by mutableStateOf(""); private set
    var deleteRow by mutableStateOf<FriendTimetableEntity?>(null); private set
    var managing by mutableStateOf(false); private set
    var managementError by mutableStateOf(false); private set
    private var parsing: Job? = null
    private var observing: Job? = null
    init { retryList() }
    fun retryList() {
        observing?.cancel(); listLoading = true; listError = false
        observing = viewModelScope.launch {
            try {
                repository.observeFriendsFlow().collect { friends = it; listLoading = false }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { listLoading = false; listError = true }
        }
    }
    fun showImport() { importOpen = true }
    fun dismissImport() { if (!form.saving) { parsing?.cancel(); form = form.edit(form.input); importOpen = false } }
    fun editInput(text: String) { if (!form.saving) { parsing?.cancel(); form = form.edit(text) } }
    fun parse() {
        if (form.loading || form.saving || form.input.isBlank()) return
        form = form.begin(); val current = form
        parsing = viewModelScope.launch {
            try { form = form.accept(current.revision, repository.preview(current.input))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { form = form.fail(current.revision, importError(e)) }
        }
    }
    fun confirm() {
        if (!form.canConfirm) return
        val current = form; val preview = current.preview ?: return
        if (preview.kind == ImportKind.SAME) { openShareId = preview.snapshot.shareId; importOpen = false; return }
        form = current.beginSave()
        viewModelScope.launch {
            try {
                when (val result = repository.confirm(preview)) {
                    is FriendSaveResult.NeedsConfirmation -> {
                        form = form.accept(current.revision, result.preview)
                        form = form.copy(errorResource = R.string.friend_import_changed)
                    }
                    is FriendSaveResult.Saved -> saved(result.shareId, current.revision)
                    is FriendSaveResult.Unchanged -> saved(result.shareId, current.revision)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { form = form.fail(current.revision, importError(e)) }
        }
    }
    private fun saved(id: String, revision: Long) {
        if (form.revision != revision) return
        form = FriendImportUiState(); importOpen = false; openShareId = id
    }
    fun consumedNavigation() { openShareId = null }
    fun open(id: String) { openShareId = id }
    fun showRename(row: FriendTimetableEntity) { renameRow = row; remark = row.remark.orEmpty(); managementError = false }
    fun editRemark(text: String) { remark = text; managementError = false }
    fun dismissRename() { if (!managing) renameRow = null }
    fun showDelete(row: FriendTimetableEntity) { deleteRow = row; managementError = false }
    fun dismissDelete() { if (!managing) deleteRow = null }
    fun rename() {
        val row = renameRow ?: return
        if (managing || !friendRemarkValid(remark)) return
        val normalized = normalizeFriendRemark(remark)
        manage({ repository.rename(row.shareId, normalized) }) { renameRow = null }
    }
    fun delete() {
        val row = deleteRow ?: return
        if (!managing) manage({ repository.delete(row.shareId) }) { deleteRow = null }
    }
    private fun manage(action: suspend () -> Unit, success: () -> Unit) {
        managing = true; managementError = false
        viewModelScope.launch {
            try { action(); success()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { managementError = true
            } finally { managing = false }
        }
    }
}
private fun importError(e: Exception): Int = when ((e as? ShareFormatException)?.error) {
    ShareError.NO_TOKEN -> R.string.timetable_share_error_no_token
    ShareError.MULTIPLE_TOKENS -> R.string.timetable_share_error_multiple
    ShareError.UNSUPPORTED_VERSION -> R.string.timetable_share_error_version
    ShareError.TOO_LONG -> R.string.timetable_share_error_too_long
    ShareError.CORRUPT_DATA -> R.string.timetable_share_error_corrupt
    ShareError.DECOMPRESSED_TOO_LARGE -> R.string.timetable_share_error_decompressed
    ShareError.INVALID_FIELDS -> R.string.timetable_share_error_fields
    ShareError.EMPTY_SCHEDULE -> R.string.timetable_share_empty_schedule
    null -> R.string.timetable_share_error_io
}
