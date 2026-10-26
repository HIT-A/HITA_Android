package cn.limpu.hita.ui.eas.exam

import androidx.lifecycle.LiveData
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.repository.ExamQueryRequest
import cn.limpu.hita.data.repository.TermQueryRequest
import cn.limpu.hita.data.model.eas.TermItem
import com.limpu.component.data.DataState

/** Narrow inputs for exercising the real page's request and lifecycle behavior. */
internal class ExamPageInputs(
    val getTerms: () -> TermQueryRequest,
    val createExamQueryRequest: (TermItem?) -> ExamQueryRequest,
    val nowMillis: () -> Long = System::currentTimeMillis,
    val beginOperation: (UsageAnalyticsEvent) -> UsageAnalyticsClient.Operation? = {
        UsageAnalyticsClient.begin(it)
    },
    val finishOperation: (
        UsageAnalyticsClient.Operation?, UsageAnalyticsEvent, Map<String, String>
    ) -> Unit = { operation, event, dimensions ->
        UsageAnalyticsClient.finish(operation, event, dimensions)
    },
)
