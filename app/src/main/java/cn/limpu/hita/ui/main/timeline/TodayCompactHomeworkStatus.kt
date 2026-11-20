package cn.limpu.hita.ui.main.timeline

/** Chooses what fits in the compact card's single state line, before localized rendering. */
internal sealed interface TodayCompactHomeworkStatus {
    data class Source(val source: TodayHomeworkSource, val status: TodaySourceStatus) : TodayCompactHomeworkStatus
    data class Overdue(val count: Int) : TodayCompactHomeworkStatus
    data class Pending(val count: Int) : TodayCompactHomeworkStatus
    data object Empty : TodayCompactHomeworkStatus
    data object Due : TodayCompactHomeworkStatus
    data object Loading : TodayCompactHomeworkStatus
}

internal fun todayCompactHomeworkStatus(summary: TodayHomeworkSummary): TodayCompactHomeworkStatus {
    val model = todayHomeworkCard(summary)
    model.sources.firstOrNull { it.status != TodaySourceStatus.READY || it.dueToday == null }?.let {
        return TodayCompactHomeworkStatus.Source(it.source, it.status)
    }
    return when {
        model.overdue > 0 -> TodayCompactHomeworkStatus.Overdue(model.overdue)
        model.pendingDate > 0 -> TodayCompactHomeworkStatus.Pending(model.pendingDate)
        model.count == 0 -> TodayCompactHomeworkStatus.Empty
        model.count != null -> TodayCompactHomeworkStatus.Due
        else -> TodayCompactHomeworkStatus.Loading
    }
}
