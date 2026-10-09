package cn.limpu.hita.ui.timetable.share

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.TimetableSharingRepository
import cn.limpu.hita.feature.timetableshare.protocol.ShareError
import cn.limpu.hita.feature.timetableshare.protocol.ShareFormatException
import cn.limpu.hita.feature.timetableshare.protocol.ShareMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TimetableShareViewModel @Inject constructor(private val repository: TimetableSharingRepository) : ViewModel() {
    val timetables = repository.observePersonalTimetables()
    var form by mutableStateOf<ShareFormState?>(null)
        private set
    private var summaryState by mutableStateOf(ShareSummaryState())
    val summary get() = summaryState.summary
    val loading get() = summaryState.loading
    val summaryErrorResource get() = summaryState.errorResource
    private var generationErrorResource by mutableStateOf<Int?>(null)
    val errorResource get() = summaryErrorResource ?: generationErrorResource
    private var generationJob: Job? = null
    private var summaryJob: Job? = null

    fun initialize(id: String, nickname: String) {
        if (form != null) return
        form = ShareFormState(id, nickname)
        if (id.isNotEmpty()) loadSummary()
    }

    fun reconcile(list: List<Timetable>) {
        val current = form ?: return
        if (list.none { it.id == current.timetableId }) edit(id = list.firstOrNull()?.id.orEmpty())
        else loadSummary()
    }

    fun edit(id: String = form?.timetableId.orEmpty(), name: String = form?.nickname.orEmpty(), mode: ShareMode = form?.mode ?: ShareMode.FULL) {
        val current = form ?: return
        generationJob?.cancel()
        form = current.edit(id, name, mode)
        generationErrorResource = null
        if (id != current.timetableId || mode != current.mode) loadSummary()
    }

    fun retrySummary() {
        val current = form ?: return
        if (current.timetableId.isEmpty() || loading) return
        generationJob?.cancel()
        // Reloading counts invalidates any previous token and its outstanding generation.
        form = current.edit()
        loadSummary()
    }

    private fun loadSummary() {
        val id = form?.timetableId.orEmpty()
        val mode = form?.mode ?: ShareMode.FULL
        summaryJob?.cancel()
        summaryState = summaryState.reload(id, mode)
        val revision = summaryState.revision
        if (id.isEmpty()) return
        summaryJob = viewModelScope.launch {
            try {
                val result = repository.describe(id, mode)
                summaryState = summaryState.accept(revision, result)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                summaryState = summaryState.fail(revision, errorFor(e))
            }
        }
    }

    fun generate() {
        val current = form ?: return
        if (current.generating || !current.nicknameValid || loading || (summary?.occurrenceCount ?: 0) == 0) return
        form = current.copy(generating = true, token = null)
        generationErrorResource = null
        generationJob = viewModelScope.launch {
            try {
                val token = repository.generate(current.timetableId, current.nickname, current.mode)
                form = form?.accept(current.revision, token)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (form?.revision == current.revision) {
                    form = form?.copy(generating = false)
                    generationErrorResource = errorFor(e)
                }
            }
        }
    }

    fun dismissToken() { form = form?.copy(token = null) }
}

private fun errorFor(error: Exception): Int = when ((error as? ShareFormatException)?.error) {
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
