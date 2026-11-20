package cn.limpu.hita.ui.main.timeline

import cn.limpu.hita.data.repository.grader.GraderError
import cn.limpu.hita.data.repository.grader.GraderState
import cn.limpu.hita.data.repository.homework.HomeworkState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

enum class TodayHomeworkSource { LOCAL, GRADER }
enum class TodaySourceStatus { LOADING, READY, FAILED, DISCONNECTED, UNAVAILABLE }

data class TodayHomeworkDeadline(
    val source: TodayHomeworkSource,
    val accountId: String?,
    val id: String,
    val title: String,
    val course: String,
    val dueAt: Long,
)

data class TodayHomeworkSourceSummary(
    val source: TodayHomeworkSource,
    val status: TodaySourceStatus,
    val accountId: String? = null,
    /** Null is unknown; zero is known empty. Failure may retain counts labelled as local records. */
    val dueToday: Int? = null,
    val overdue: Int? = null,
    val undated: Int? = null,
    val invalidDeadline: Int? = null,
    val earliestDeadline: TodayHomeworkDeadline? = null,
    /** Every pending item with a resolvable deadline, earliest first. */
    val pending: List<TodayHomeworkDeadline> = emptyList(),
    val error: GraderError? = null,
)

data class TodayHomeworkSummary(val local: TodayHomeworkSourceSummary, val grader: TodayHomeworkSourceSummary)

object TodayHomeworkProjection {
    private val graderZone = ZoneId.of("Asia/Shanghai")
    private val localTime = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)

    fun project(local: HomeworkState, grader: GraderState, graderAvailable: Boolean, now: Long, zone: ZoneId): TodayHomeworkSummary {
        val localStatus = when {
            local.failed -> TodaySourceStatus.FAILED
            !local.ready -> TodaySourceStatus.LOADING
            else -> TodaySourceStatus.READY
        }
        var undated = 0
        var invalid = 0
        val localDeadlines = local.items.filterNot { it.completed }.mapNotNull { item ->
            if (item.plannedDate == null) { undated++; return@mapNotNull null }
            val due = try {
                LocalDate.parse(item.plannedDate).atTime(LocalTime.parse(item.plannedTime ?: "23:59", localTime))
                    .atZone(zone).toInstant().toEpochMilli()
            } catch (_: java.time.DateTimeException) { invalid++; return@mapNotNull null }
            TodayHomeworkDeadline(TodayHomeworkSource.LOCAL, null, item.id, item.title, item.subject, due)
        }
        val localSummary = summarize(TodayHomeworkSource.LOCAL, localStatus, null, localDeadlines,
            undated, invalid, local.ready, now, zone)
        val graderStatus = when {
            !graderAvailable -> TodaySourceStatus.UNAVAILABLE
            grader.error != null -> TodaySourceStatus.FAILED
            !grader.ready -> TodaySourceStatus.LOADING
            grader.needsLogin || grader.accountId == null -> TodaySourceStatus.DISCONNECTED
            else -> TodaySourceStatus.READY
        }
        val published = if (graderAvailable && grader.accountId != null) grader.summaryItems.filter {
            it.accountId == grader.accountId && !it.completed
        } else emptyList()
        val deadlines = published.mapNotNull { item -> item.dueAt?.let {
            TodayHomeworkDeadline(TodayHomeworkSource.GRADER, item.accountId, item.id, item.title, item.course, it)
        } }
        val graderSummary = summarize(TodayHomeworkSource.GRADER, graderStatus, grader.accountId.takeIf { graderAvailable },
            deadlines, published.count { it.dueAt == null }, 0,
            graderAvailable && grader.ready && grader.accountId != null, now, graderZone).copy(error = grader.error.takeIf { graderAvailable })
        return TodayHomeworkSummary(localSummary, graderSummary)
    }

    private fun summarize(source: TodayHomeworkSource, status: TodaySourceStatus, accountId: String?,
        deadlines: List<TodayHomeworkDeadline>, undated: Int, invalid: Int, known: Boolean, now: Long, zone: ZoneId,
    ): TodayHomeworkSourceSummary {
        if (!known) return TodayHomeworkSourceSummary(source, status, accountId)
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val upcoming = deadlines.filter { it.dueAt >= now }
        return TodayHomeworkSourceSummary(source, status, accountId,
            dueToday = upcoming.count { Instant.ofEpochMilli(it.dueAt).atZone(zone).toLocalDate() == date },
            overdue = deadlines.count { it.dueAt < now }, undated = undated, invalidDeadline = invalid,
            earliestDeadline = upcoming.minWithOrNull(compareBy<TodayHomeworkDeadline> { it.dueAt }.thenBy { it.id }),
            pending = deadlines.sortedWith(compareBy<TodayHomeworkDeadline> { it.dueAt }.thenBy { it.id }))
    }
}
