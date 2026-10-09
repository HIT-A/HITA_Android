package cn.limpu.hita.ui.timetable.friend

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.share.FriendTimetableEntity
import cn.limpu.hita.data.repository.FriendTimetableRepository
import cn.limpu.hita.feature.timetableshare.protocol.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*

sealed interface FriendDetailState {
    data object Loading : FriendDetailState
    data object Deleted : FriendDetailState
    data class Error(val resource: Int) : FriendDetailState
    data class Ready(val row: FriendTimetableEntity, val snapshot: SharedTimetable) : FriendDetailState
}

@HiltViewModel
class FriendTimetableDetailViewModel @Inject constructor(
    repository: FriendTimetableRepository, savedStateHandle: SavedStateHandle
) : ViewModel() {
    val friend = repository.observeFriend(savedStateHandle.get<String>(FriendTimetableDetailActivity.EXTRA_SHARE_ID).orEmpty())
    var state: FriendDetailState by mutableStateOf(FriendDetailState.Loading)
        private set
    private var request: Job? = null
    fun accept(row: FriendTimetableEntity?) {
        request?.cancel()
        if (row == null) { state = FriendDetailState.Deleted; return }
        request = viewModelScope.launch {
            state = FriendDetailState.Loading
            try {
                val snapshot = withContext(Dispatchers.IO) { TimetableShareCodec().decodeJson(row.snapshotJson) }
                if (snapshot.shareId != row.shareId || snapshot.mode.name != row.mode) {
                    state = FriendDetailState.Error(R.string.friend_timetable_corrupt)
                } else state = FriendDetailState.Ready(row, snapshot)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: ShareFormatException) { state = FriendDetailState.Error(R.string.friend_timetable_corrupt) }
            catch (_: Exception) { state = FriendDetailState.Error(R.string.friend_timetable_load_failed) }
        }
    }
}
