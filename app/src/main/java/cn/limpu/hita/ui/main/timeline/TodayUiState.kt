package cn.limpu.hita.ui.main.timeline

import cn.limpu.hita.data.model.timetable.EventItem
import java.time.LocalDate

/** Values derived from one successful two-day snapshot and one explicit clock reading. */
data class TodayAgendaState(
    val allToday: List<EventItem>,
    val active: List<EventItem>,
    val nextGroup: List<EventItem>,
    val ended: List<EventItem>,
    val remaining: List<EventItem>,
    val completedCount: Int,
    val invalidEvents: List<EventItem>,
    /** Connected groups of overlapping events; adjacent intervals do not conflict. */
    val conflictGroups: List<List<EventItem>>,
    val allFinished: Boolean,
    val tomorrow: TomorrowPreparationState?,
)

data class TomorrowPreparationState(
    val date: LocalDate,
    val firstGroup: List<EventItem>,
    val invalidEvents: List<EventItem>,
)

enum class TodayPageStatus { LOADING, NO_TARGET, READY, FAILED }
enum class TodayRetrySource { AGENDA, HOMEWORK, GRADER }

data class TodaySession(
    val ownerKey: String? = null,
    val generation: Long = 0,
    val graderAvailable: Boolean = false,
)

data class TodayUiState(
    val now: Long,
    val window: TodayDateWindow,
    val session: TodaySession = TodaySession(),
    val scopeKey: String = "unresolved",
    val scopeName: String? = null,
    val status: TodayPageStatus = TodayPageStatus.LOADING,
    val agenda: TodayAgendaState? = null,
    val header: TodayHeaderState = TodayHeaderState(),
    val homework: TodayHomeworkSummary? = null,
    val exams: TodayExamSummary? = null,
    val memberIds: List<String> = emptyList(),
    val scopeOptions: List<TodayScopeOption> = emptyList(),
)

data class TodayScopeOption(
    val scope: cn.limpu.hita.data.model.timetable.DisplayScope,
    val name: String,
    val memberCount: Int,
    val selected: Boolean,
)
