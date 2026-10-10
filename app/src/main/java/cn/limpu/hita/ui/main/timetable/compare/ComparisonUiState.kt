package cn.limpu.hita.ui.main.timetable.compare

import androidx.lifecycle.SavedStateHandle
import cn.limpu.hita.data.repository.ComparisonSource
import cn.limpu.hita.feature.timetablecompare.ComparisonResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ComparisonConfig(val personalId: String, val friendId: String,
    val startMinute: Int = 510, val endMinute: Int = 1350,
    val showFree: Boolean = true, val showConflicts: Boolean = true) {
    fun isValid() = personalId.isNotBlank() && friendId.isNotBlank() &&
        startMinute in 0..1440 && endMinute in 0..1440 && startMinute < endMinute
}
sealed interface ComparisonUiState {
    data object Off : ComparisonUiState
    data object Loading : ComparisonUiState
    data class Ready(val config: ComparisonConfig, val source: ComparisonSource,
        val result: ComparisonResult) : ComparisonUiState
    data class Error(val messageResource: Int) : ComparisonUiState
}

/** Shared request guard used by the ViewModel; every reload clears the prior result immediately. */
internal class ComparisonSession {
    data class Request(val revision: Long, val config: ComparisonConfig, val monday: Long)
    private var revision = 0L
    private var monday: Long? = null
    private val mutableConfig = MutableStateFlow<ComparisonConfig?>(null)
    private val mutableState = MutableStateFlow<ComparisonUiState>(ComparisonUiState.Off)
    val config = mutableConfig.asStateFlow()
    val state = mutableState.asStateFlow()
    fun reload(config: ComparisonConfig, monday: Long): Request {
        require(config.isValid())
        revision++
        this.monday = monday
        mutableConfig.value = config
        mutableState.value = ComparisonUiState.Loading
        return Request(revision, config, monday)
    }
    fun accept(request: Request, state: ComparisonUiState): Boolean {
        if (request.revision != revision || request.config != config.value || request.monday != monday) return false
        mutableState.value = state
        return true
    }
    fun disable(state: ComparisonUiState = ComparisonUiState.Off) {
        revision++
        mutableConfig.value = null
        mutableState.value = state
    }
}

internal fun saveComparisonConfig(handle: SavedStateHandle, config: ComparisonConfig?) {
    handle["comparison.enabled"] = config != null
    if (config == null) return
    handle["comparison.personal"] = config.personalId
    handle["comparison.friend"] = config.friendId
    handle["comparison.start"] = config.startMinute
    handle["comparison.end"] = config.endMinute
    handle["comparison.free"] = config.showFree
    handle["comparison.conflicts"] = config.showConflicts
}
internal fun restoreComparisonConfig(handle: SavedStateHandle): ComparisonConfig? {
    if (handle.get<Boolean>("comparison.enabled") != true) return null
    return ComparisonConfig(handle.get<String>("comparison.personal") ?: return null,
        handle.get<String>("comparison.friend") ?: return null,
        handle.get<Int>("comparison.start") ?: 510, handle.get<Int>("comparison.end") ?: 1350,
        handle.get<Boolean>("comparison.free") ?: true,
        handle.get<Boolean>("comparison.conflicts") ?: true).takeIf { it.isValid() }
}

/** Ready supplies selected CLASS records; loading/error deliberately have no aggregate fallback. */
fun <T> comparisonDisplayEvents(state: ComparisonUiState, original: List<T>,
    selected: (ComparisonUiState.Ready) -> List<T>): List<T> = when (state) {
    ComparisonUiState.Off -> original
    is ComparisonUiState.Ready -> selected(state)
    else -> emptyList()
}
