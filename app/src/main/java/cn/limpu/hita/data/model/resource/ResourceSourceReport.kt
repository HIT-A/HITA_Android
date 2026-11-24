package cn.limpu.hita.data.model.resource

enum class SearchSource {
    HOA, HITCS, FIREWORKS;

    /** 与结果卡片上的来源标签一致，便于用户把失败提示对回具体来源。 */
    val displayTag: String
        get() = when (this) {
            HOA -> "HOA"
            HITCS -> "HITCS"
            FIREWORKS -> "薪火"
        }
}

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
