package cn.limpu.hita.ui.timetable.share

import cn.limpu.hita.data.repository.ShareSummary
import cn.limpu.hita.feature.timetableshare.protocol.ShareMode

/** Summary requests have their own revision; a same-table retry is always a new request. */
data class ShareSummaryState(
    val timetableId: String = "",
    val mode: ShareMode = ShareMode.FULL,
    val revision: Long = 0,
    val summary: ShareSummary? = null,
    val loading: Boolean = false,
    val errorResource: Int? = null,
) {
    fun reload(id: String, selectedMode: ShareMode = mode) = ShareSummaryState(
        timetableId = id, mode = selectedMode, revision = revision + 1, loading = id.isNotEmpty(),
    )

    fun accept(requestRevision: Long, result: ShareSummary) =
        if (requestRevision == revision && result.mode == mode) copy(summary = result, loading = false, errorResource = null) else this

    fun fail(requestRevision: Long, error: Int) =
        if (requestRevision == revision) copy(summary = null, loading = false, errorResource = error) else this
}
