package cn.limpu.hita.ui.main.timetable.compare

import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.model.timetable.share.FriendTimetableEntity
import cn.limpu.hita.feature.timetablecompare.ComparisonPeriod
import cn.limpu.hita.feature.timetablecompare.PeriodStructure
import cn.limpu.hita.feature.timetableshare.protocol.ShareLimits
import cn.limpu.hita.feature.timetableshare.protocol.TimetableShareCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Value snapshot produced BEFORE Compose equality filtering; Timetable.equals ignores structure/term dates. */
data class ComparisonPersonalChoice(val id: String, val name: String, val startMillis: Long,
    val endMillis: Long, val periods: List<ComparisonPeriod>, val valid: Boolean)
fun comparisonPersonalChoice(table: Timetable): ComparisonPersonalChoice {
    val periods = table.scheduleStructure.map {
        ComparisonPeriod(it.from.hour * 60 + it.from.minute, it.to.hour * 60 + it.to.minute)
    }
    return ComparisonPersonalChoice(table.id, table.name.orEmpty(), table.startTime.time, table.endTime.time,
        periods, table.startTime.time < table.endTime.time && periods.size <= ShareLimits.PERIODS &&
            table.scheduleStructure.all { validComparisonTime(it.from) && validComparisonTime(it.to) } &&
            PeriodStructure.isValid(periods))
}
data class ComparisonCandidate(val personal: ComparisonPersonalChoice, val friend: FriendTimetableEntity,
    val ownPeriods: List<ComparisonPeriod>, val friendPeriods: List<ComparisonPeriod>, val valid: Boolean)

/** Decode only the selected persisted snapshot; never decode JSON in a Composable/main-thread callback. */
suspend fun readComparisonCandidate(personal: ComparisonPersonalChoice, friend: FriendTimetableEntity): ComparisonCandidate =
    withContext(Dispatchers.Default) {
        try {
            val snapshot = TimetableShareCodec().decodeJson(friend.snapshotJson)
            ComparisonCandidate(personal, friend, personal.periods,
                snapshot.periods.map { ComparisonPeriod(it.startMinute, it.endMinute) },
                snapshot.shareId == friend.shareId && personal.valid)
        } catch (error: kotlinx.coroutines.CancellationException) { throw error
        } catch (error: Exception) { ComparisonCandidate(personal, friend, personal.periods, emptyList(), false) }
    }
private fun validComparisonTime(time: TimeInDay) =
    (time.hour in 0..23 && time.minute in 0..59) || (time.hour == 24 && time.minute == 0)
