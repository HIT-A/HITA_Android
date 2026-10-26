package cn.limpu.hita.ui.eas.classroom

import androidx.lifecycle.LiveData
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.repository.TeachingBuildingsRequest
import cn.limpu.hita.data.repository.EmptyClassroomQueryRequest
import cn.limpu.hita.data.repository.TermQueryRequest
import com.limpu.component.data.DataState

/** Narrow dependencies for exercising the real page's selection and query behavior. */
internal class EmptyClassroomPageInputs(
    val getTerms: () -> TermQueryRequest,
    val createTeachingBuildingsRequest: () -> TeachingBuildingsRequest,
    val getCurrentWeek: (TermItem?) -> LiveData<Int>,
    val getScheduleStructure: (TermItem) -> LiveData<DataState<MutableList<TimePeriodInDay>>>,
    val createClassroomQueryRequest: (TermItem, BuildingItem, Int) -> EmptyClassroomQueryRequest,
    val beginOperation: (UsageAnalyticsEvent) -> UsageAnalyticsClient.Operation? = {
        UsageAnalyticsClient.begin(it)
    },
    val finishOperation: (
        UsageAnalyticsClient.Operation?, UsageAnalyticsEvent, Map<String, String>
    ) -> Unit = { operation, event, dimensions ->
        UsageAnalyticsClient.finish(operation, event, dimensions)
    },
)
