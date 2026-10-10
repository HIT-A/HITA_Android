package cn.limpu.hita.data.repository

import androidx.lifecycle.asFlow
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.model.timetable.share.FriendTimetableEntity
import cn.limpu.hita.feature.timetablecompare.*
import cn.limpu.hita.feature.timetableshare.protocol.ShareLimits
import cn.limpu.hita.feature.timetableshare.protocol.SharedTimetable
import cn.limpu.hita.feature.timetableshare.protocol.TimetableShareCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.Callable
import javax.inject.Inject

data class ComparisonSource(
    val ownTimetable: Timetable,
    val ownCourses: List<EventItem>,
    val friendRow: FriendTimetableEntity,
    val friendSnapshot: SharedTimetable,
    val own: ComparisonSchedule,
    val friend: ComparisonSchedule,
)

sealed interface ComparisonSourceState {
    data class Ready(val source: ComparisonSource) : ComparisonSourceState
    data object MissingOwn : ComparisonSourceState
    data object MissingFriend : ComparisonSourceState
    data object Invalid : ComparisonSourceState
    data object Incompatible : ComparisonSourceState
}

/** Observations invalidate a complete, read-only transaction; their partial payloads are never mixed. */
class TimetableComparisonRepository @Inject constructor(private val db: AppDatabase) {
    private val codec = TimetableShareCodec()

    fun observe(personalId: String, friendId: String): Flow<ComparisonSourceState> = combine(
        db.timetableDao().getTimetables().asFlow(),
        db.eventItemDao().observeClassesFlow(personalId),
        db.friendTimetableDao().observe(friendId).asFlow(),
    ) { _, _, _ ->
        try {
            db.runInTransaction(Callable { readSource(personalId, friendId) })
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // A later database invalidation must be able to recover from a malformed snapshot/read.
            ComparisonSourceState.Invalid
        }
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(ComparisonSourceState.Invalid)
    }.flowOn(Dispatchers.IO)

    private fun readSource(personalId: String, friendId: String): ComparisonSourceState {
        val ownTable = db.timetableDao().getTimetableByIdSync(personalId)
            ?: return ComparisonSourceState.MissingOwn
        val friendRow = db.friendTimetableDao().getSync(friendId)
            ?: return ComparisonSourceState.MissingFriend
        val courses = db.eventItemDao().getEventsOfTimetableSync(personalId).filter {
            it.timetableId == personalId && it.type == EventItem.TYPE.CLASS
        }
        if (ownTable.startTime.time >= ownTable.endTime.time ||
            ownTable.scheduleStructure.size > ShareLimits.PERIODS || courses.size > ShareLimits.OCCURRENCES ||
            courses.any { it.from.time >= it.to.time } || ownTable.scheduleStructure.any {
                !validTime(it.from) || !validTime(it.to)
            }) return ComparisonSourceState.Invalid
        val ownPeriods = ownTable.scheduleStructure.map {
            ComparisonPeriod(it.from.hour * 60 + it.from.minute, it.to.hour * 60 + it.to.minute)
        }
        if (!PeriodStructure.isValid(ownPeriods)) return ComparisonSourceState.Invalid
        val snapshot = codec.decodeJson(friendRow.snapshotJson)
        if (snapshot.shareId != friendId) return ComparisonSourceState.Invalid
        val friendPeriods = snapshot.periods.map { ComparisonPeriod(it.startMinute, it.endMinute) }
        if (!PeriodStructure.isValid(friendPeriods)) return ComparisonSourceState.Invalid
        if (!PeriodStructure.isCompatible(ownPeriods, friendPeriods)) return ComparisonSourceState.Incompatible
        val own = ComparisonSchedule(TimeSpan(ownTable.startTime.time, ownTable.endTime.time), ownPeriods,
            courses.map { TimeSpan(it.from.time, it.to.time) })
        val friend = ComparisonSchedule(TimeSpan(snapshot.term.startMillis, snapshot.term.endMillis), friendPeriods,
            snapshot.occurrences.map { TimeSpan(it.startMillis, it.endMillis) })
        return ComparisonSourceState.Ready(ComparisonSource(ownTable, courses, friendRow, snapshot, own, friend))
    }

    private fun validTime(time: TimeInDay): Boolean =
        (time.hour in 0..23 && time.minute in 0..59) || (time.hour == 24 && time.minute == 0)
}
