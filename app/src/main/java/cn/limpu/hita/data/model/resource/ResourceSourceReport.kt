package cn.limpu.hita.data.model.resource

enum class SearchSource { HOA, HITCS, FIREWORKS }

enum class ResourceSourceStatus { OK, EMPTY, FAILED }

data class ResourceSourceReport(
    val source: SearchSource,
    val status: ResourceSourceStatus,
    val count: Int,
)

data class ExternalSearchResult(
    val items: List<ExternalCourseItem>,
    val reports: List<ResourceSourceReport>,
)

data class UnifiedSearchResult(
    val items: List<UnifiedResourceItem>,
    val reports: List<ResourceSourceReport>,
)
