package cn.limpu.hita.ui.main.timeline

/** A display adapter over already-counted sources; an unavailable source is never counted as zero. */
internal data class TodayHomeworkCardModel(
    val count: Int?,
    val singleSource: TodayHomeworkSource?,
    val sources: List<TodayHomeworkSourceSummary>,
    val known: List<TodayHomeworkSourceSummary>,
) {
    val overdue: Int get() = known.sumOf { it.overdue ?: 0 }
    val pendingDate: Int get() = known.sumOf { (it.undated ?: 0) + (it.invalidDeadline ?: 0) }
    val retained: Boolean get() = known.any { it.status == TodaySourceStatus.FAILED }
    val items: List<TodayHomeworkDeadline>
        get() = known.flatMap { it.pending }
            .sortedWith(compareBy<TodayHomeworkDeadline> { it.dueAt }.thenBy { it.id })
}

internal fun todayHomeworkCard(summary: TodayHomeworkSummary): TodayHomeworkCardModel {
    val sources = listOf(summary.local, summary.grader).filter { it.status != TodaySourceStatus.UNAVAILABLE }
    val known = sources.filter { it.dueToday != null }
    return TodayHomeworkCardModel(
        count = known.takeIf { it.isNotEmpty() }?.sumOf { it.dueToday ?: 0 },
        singleSource = known.singleOrNull()?.source,
        sources = sources,
        known = known,
    )
}
