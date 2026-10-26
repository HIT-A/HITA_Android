package cn.limpu.hita.ui.eas.classroom

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.repository.TeachingBuildingsRequest
import com.limpu.component.data.DataState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Keep switchMap's observation-driven refresh timing while owning requests in the ViewModel scope.
 * Each refresh gets a fresh source: Shenzhen retains its previous display until a terminal result,
 * while the legacy campuses keep their initial NOTHING state.
 */
internal fun <T> LiveData<T>.loadTeachingBuildings(
    scope: CoroutineScope,
    createRequest: () -> TeachingBuildingsRequest,
): LiveData<DataState<List<BuildingItem>>> {
    var currentJob: Job? = null
    var requestGeneration = 0L
    return switchMap {
        val generation = ++requestGeneration
        currentJob?.cancel()
        val request = createRequest()
        MutableLiveData<DataState<List<BuildingItem>>>().apply {
            request.initialState?.let { value = it }
            currentJob = scope.launch {
                val state = request.execute()
                ensureActive()
                if (generation == requestGeneration) value = state
            }
        }
    }
}
