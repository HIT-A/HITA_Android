package cn.limpu.hita.ui.main.timetable.compare

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.R
import cn.limpu.hita.data.repository.ComparisonSourceState
import cn.limpu.hita.data.repository.TimetableComparisonRepository
import cn.limpu.hita.feature.timetablecompare.ComparisonRequest
import cn.limpu.hita.feature.timetablecompare.IncompatiblePeriodsException
import cn.limpu.hita.feature.timetablecompare.TimetableComparisonEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TimetableComparisonViewModel @Inject constructor(
    private val repository: TimetableComparisonRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val session = ComparisonSession()
    val state = session.state
    val config = session.config
    private var monday: Long? = null
    private var job: Job? = null
    private val engine = TimetableComparisonEngine()
    private var restored = restoreComparisonConfig(savedStateHandle)

    init {
        restored?.let { session.reload(it, 0L) }
            ?: saveComparisonConfig(savedStateHandle, null)
    }

    fun enable(config: ComparisonConfig) {
        if (!config.isValid()) {
            disableWithError(R.string.comparison_invalid_range)
            return
        }
        restored = config
        saveComparisonConfig(savedStateHandle, config)
        // The host supplies the actual displayed campus Monday before calculation.
        val week = monday
        if (week != null) reload(config, week)
        else { job?.cancel(); session.reload(config, 0L) }
    }

    fun changeWeek(mondayMillis: Long) {
        monday = mondayMillis
        (config.value ?: restored)?.let { reload(it, mondayMillis) }
    }

    fun disable() {
        job?.cancel()
        restored = null
        saveComparisonConfig(savedStateHandle, null)
        session.disable()
    }

    fun retry() { (config.value ?: restored)?.let { enable(it) } }

    private fun disableWithError(resource: Int) {
        disable()
        session.disable(ComparisonUiState.Error(resource))
    }

    private fun reload(config: ComparisonConfig, week: Long) {
        job?.cancel()
        val request = session.reload(config, week)
        job = viewModelScope.launch {
            try {
                repository.observe(config.personalId, config.friendId).collectLatest { source ->
                    // A continuous emission replaces the prior result even while computing.
                    if (!session.accept(request, ComparisonUiState.Loading)) return@collectLatest
                    when (source) {
                        ComparisonSourceState.MissingOwn, ComparisonSourceState.MissingFriend -> {
                            if (session.accept(request, ComparisonUiState.Error(R.string.comparison_missing_source))) {
                                disableWithError(R.string.comparison_missing_source)
                            }
                        }
                        ComparisonSourceState.Invalid -> session.accept(request,
                            ComparisonUiState.Error(R.string.comparison_read_error))
                        ComparisonSourceState.Incompatible -> session.accept(request,
                            ComparisonUiState.Error(R.string.comparison_incompatible_periods))
                        is ComparisonSourceState.Ready -> {
                            val result = withContext(Dispatchers.Default) {
                                engine.compare(source.source.own, source.source.friend,
                                    ComparisonRequest(week, config.startMinute, config.endMinute))
                            }
                            val next = when {
                                result.days.all { it.slots.isEmpty() } ->
                                    ComparisonUiState.Error(R.string.comparison_no_complete_period)
                                result.days.all { it.windows.isEmpty() } ->
                                    ComparisonUiState.Error(R.string.comparison_no_common_validity)
                                else -> ComparisonUiState.Ready(config, source.source, result)
                            }
                            session.accept(request, next)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                session.accept(request, ComparisonUiState.Error(if (error is IncompatiblePeriodsException)
                    R.string.comparison_incompatible_periods else R.string.comparison_read_error))
            }
        }
    }
}
