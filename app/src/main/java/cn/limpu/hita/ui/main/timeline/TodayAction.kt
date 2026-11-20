package cn.limpu.hita.ui.main.timeline

sealed interface TodayAction {
    data class OpenEvent(val id: String) : TodayAction
    data class OpenHomework(val source: TodayHomeworkSource) : TodayAction
    data class Retry(val source: TodayRetrySource) : TodayAction
    data object OpenExams : TodayAction
    data class SelectScope(val scope: cn.limpu.hita.data.model.timetable.DisplayScope) : TodayAction
    data object SelectTimetable : TodayAction
    data object ImportTimetable : TodayAction
}
