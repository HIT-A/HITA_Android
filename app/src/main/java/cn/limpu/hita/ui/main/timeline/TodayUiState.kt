package cn.limpu.hita.ui.main.timeline

import cn.limpu.hita.data.model.timetable.EventItem
import java.time.LocalDate

/** Values derived from one successful two-day snapshot and one explicit clock reading. */
data class TodayAgendaState(
    val allToday: List<EventItem>,
    val active: List<EventItem>,
    val nextGroup: List<EventItem>,
    val defaultVisible: List<EventItem>,
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
