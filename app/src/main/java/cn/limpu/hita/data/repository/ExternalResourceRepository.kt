package cn.limpu.hita.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.limpu.component.data.DataState
import cn.limpu.hita.data.model.resource.ExternalCourseItem
import cn.limpu.hita.data.model.resource.ExternalSearchResult
import cn.limpu.hita.data.model.resource.ExternalResourceEntry
import cn.limpu.hita.data.model.resource.ResourceSource
import cn.limpu.hita.data.source.web.FireworksWebSource
import cn.limpu.hita.data.source.web.HITCSWebSource
import cn.limpu.hita.utils.LogUtils
import javax.inject.Inject

class ExternalResourceRepository @Inject constructor() {

    fun searchSource(query: String, source: ResourceSource): LiveData<DataState<List<ExternalCourseItem>>> =
        when (source) {
            ResourceSource.HITCS -> HITCSWebSource.searchCourses(query)
            ResourceSource.FIREWORKS -> FireworksWebSource.searchCourses(query)
        }

    fun searchCourses(query: String): LiveData<DataState<ExternalSearchResult>> {
        val mediator = MediatorLiveData<DataState<ExternalSearchResult>>()
        val outcomes = mutableMapOf<ResourceSource, ExternalSourceOutcome>()
        for (source in ResourceSource.entries) {
            val request = searchSource(query, source)
            mediator.addSource(request) { state ->
                when (state.state) {
                    DataState.STATE.NOTHING, DataState.STATE.LOADING -> Unit
                    else -> {
                        mediator.removeSource(request)
                        outcomes[source] = ExternalSourceOutcome(
                            source,
                            state.data.orEmpty(),
                            if (state.state == DataState.STATE.SUCCESS) null
                            else IllegalStateException(state.message.orEmpty()),
                        )
                        if (outcomes.size == ResourceSource.entries.size) {
                            mediator.value = DataState(
                                ExternalResourceSearch.merge(ResourceSource.entries.map(outcomes::getValue)),
                                DataState.STATE.SUCCESS,
                            )
                        }
                    }
                }
            }
        }
        return mediator
    }

    fun searchCoursesSync(query: String): List<ExternalCourseItem> {
        val merged = mutableListOf<ExternalCourseItem>()
        try {
            merged.addAll(HITCSWebSource.searchCoursesSync(query))
        } catch (e: Exception) {
            LogUtils.w("HITCS sync search failed: ${e.message}")
        }
        try {
            merged.addAll(FireworksWebSource.searchCoursesSync(query))
        } catch (e: Exception) {
            LogUtils.w("Fireworks sync search failed: ${e.message}")
        }
        return merged.sortedBy { it.courseName }
    }

    fun listDirectory(
        path: String,
        source: ResourceSource,
    ): LiveData<DataState<List<ExternalResourceEntry>>> {
        return when (source) {
            ResourceSource.HITCS -> HITCSWebSource.listDirectory(path)
            ResourceSource.FIREWORKS -> FireworksWebSource.listDirectory(path)
        }
    }
}
