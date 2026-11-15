package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.resource.ExternalCourseItem
import cn.limpu.hita.data.model.resource.ExternalSearchResult
import cn.limpu.hita.data.model.resource.ResourceSource
import cn.limpu.hita.data.model.resource.ResourceSourceReport
import cn.limpu.hita.data.model.resource.ResourceSourceStatus
import cn.limpu.hita.data.model.resource.SearchSource
import cn.limpu.hita.data.model.resource.UnifiedResourceItem
import cn.limpu.hita.data.model.resource.UnifiedSearchResult

data class ExternalSourceOutcome(
    val source: ResourceSource,
    val items: List<ExternalCourseItem>,
    val failure: Exception?,
) {
    val report: ResourceSourceReport
        get() = ResourceSourceReport(
            source = source.toSearchSource(),
            status = when {
                failure != null -> ResourceSourceStatus.FAILED
                items.isEmpty() -> ResourceSourceStatus.EMPTY
                else -> ResourceSourceStatus.OK
            },
            count = if (failure == null) items.size else 0,
        )
}

sealed class UnifiedSourceOutcome {
    abstract val reports: List<ResourceSourceReport>
    abstract val items: List<UnifiedResourceItem>

    data class Success(
        override val items: List<UnifiedResourceItem>,
        override val reports: List<ResourceSourceReport>,
    ) : UnifiedSourceOutcome()

    data class Failed(
        override val reports: List<ResourceSourceReport>,
    ) : UnifiedSourceOutcome() {
        override val items: List<UnifiedResourceItem> = emptyList()
    }
}

object ExternalResourceSearch {
    fun merge(outcomes: List<ExternalSourceOutcome>): ExternalSearchResult {
        val items = outcomes
            .filter { it.failure == null }
            .flatMap { it.items }
            .sortedBy { it.courseName }
        return ExternalSearchResult(items, outcomes.map { it.report })
    }
}

object UnifiedResourceSearch {
    fun merge(outcomes: List<UnifiedSourceOutcome>): UnifiedSearchResult {
        return UnifiedSearchResult(
            items = outcomes.flatMap { it.items }.sortedBy { it.displayName },
            reports = outcomes.flatMap { it.reports },
        )
    }
}

internal fun ResourceSource.toSearchSource(): SearchSource = when (this) {
    ResourceSource.HITCS -> SearchSource.HITCS
    ResourceSource.FIREWORKS -> SearchSource.FIREWORKS
}
