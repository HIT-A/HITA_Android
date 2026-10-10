package cn.limpu.hita.data.timetablecompare

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.*
import cn.limpu.hita.data.model.timetable.share.FriendTimetableEntity
import cn.limpu.hita.data.model.timetable.share.ShareIdentityEntity
import cn.limpu.hita.data.repository.*
import cn.limpu.hita.feature.timetablecompare.*
import cn.limpu.hita.feature.timetableshare.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.sql.Timestamp

@RunWith(AndroidJUnit4::class)
class TimetableComparisonRepositoryTest {
    private val monday = 1767542400000L // 2026-01-05 00:00 Asia/Shanghai
    private val friendId = "5fb93366-071b-4bd6-a658-ff402a25311b"
    private val codec = TimetableShareCodec()
    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    private fun table(id: String) = Timetable().apply {
        this.id = id; name = id; startTime = Timestamp(monday); endTime = Timestamp(monday + 7 * 86400000L)
        scheduleStructure = listOf(TimePeriodInDay(TimeInDay(8, 30), TimeInDay(10, 15)))
    }
    private fun event(id: String, tableId: String, type: EventItem.TYPE = EventItem.TYPE.CLASS) = EventItem().apply {
        this.id = id; timetableId = tableId; this.type = type; source = EventItem.SOURCE_MANUAL
        from = Timestamp(monday + 9 * 3600000L); to = Timestamp(monday + 10 * 3600000L)
    }
    private fun snapshot(mode: ShareMode = ShareMode.FULL) = SharedTimetable(friendId, "friend", mode,
        SharedTerm("term", "term", monday, monday + 7 * 86400000L), listOf(SharedPeriod(510, 615)),
        if (mode == ShareMode.FULL) listOf("math") else emptyList(),
        listOf(SharedOccurrence(monday + 9 * 3600000L, monday + 10 * 3600000L,
            if (mode == ShareMode.FULL) 0 else null)))
    private fun row(s: SharedTimetable = snapshot()) = FriendTimetableEntity(s.shareId, s.nickname, null,
        s.term.termName, s.term.startMillis, s.term.endMillis, s.mode.name, codec.canonicalJson(s), codec.digest(s), 1, 1)
    private fun seed(db: AppDatabase) {
        db.timetableDao().saveTimetablesSync(listOf(table("A"), table("B")))
        db.eventItemDao().saveEvents(listOf(event("a", "A"), event("b", "B"),
            event("exam", "A", EventItem.TYPE.EXAM), event("other", "A", EventItem.TYPE.OTHER)))
        db.friendTimetableDao().insertSync(row())
    }
    private fun rows(db: AppDatabase): Map<String, List<List<String?>>> =
        listOf("timetable", "subject", "events", "timetable_share_identity", "friend_timetable").associateWith { name ->
            db.openHelper.writableDatabase.query("SELECT * FROM `$name` ORDER BY 1").use { c ->
                buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
            }
        }
    private suspend fun read(db: AppDatabase) = withTimeout(5000) {
        TimetableComparisonRepository(db).observe("A", friendId).first()
    }
    @Test fun selectedClassesReadDoesNotModifyAnyStoredRows() = runBlocking {
        val db = database()
        try {
            seed(db)
            db.subjectDao().saveSubjectSync(TermSubject().apply { id = "subject"; timetableId = "A"; name = "math" })
            db.timetableShareDao().insertIgnoreSync(ShareIdentityEntity("B", "existing-term", "existing-share"))
            val before = rows(db)
            val source = (read(db) as ComparisonSourceState.Ready).source
            assertEquals(listOf("a"), source.ownCourses.map { it.id })
            assertEquals(listOf(TimeSpan(monday + 9 * 3600000L, monday + 10 * 3600000L)), source.own.occupied)
            assertEquals(before, rows(db))
        } finally { db.close() }
    }
    @Test fun emptyPersonalClassesAreValidAndFullBusyOccupancyIsEquivalent() = runBlocking {
        val db = database()
        try {
            seed(db)
            val full = (read(db) as ComparisonSourceState.Ready).source
            db.friendTimetableDao().updateSync(row(snapshot(ShareMode.BUSY)))
            val busy = (read(db) as ComparisonSourceState.Ready).source
            val engine = TimetableComparisonEngine()
            assertEquals(engine.compare(full.own, full.friend, ComparisonRequest(monday)),
                engine.compare(busy.own, busy.friend, ComparisonRequest(monday)))
            assertTrue(busy.friendSnapshot.courses.isEmpty())
            db.eventItemDao().deleteEventsInIdsSync(listOf("a"))
            val empty = (read(db) as ComparisonSourceState.Ready).source
            assertTrue(empty.ownCourses.isEmpty()); assertTrue(empty.own.occupied.isEmpty())
        } finally { db.close() }
    }
    @Test fun observesCourseChangesRemarksSnapshotUpdatesAndBothObjectDeletes() = runBlocking {
        val db = database()
        try {
            seed(db)
            val updates = Channel<ComparisonSourceState>(Channel.UNLIMITED)
            val job = launch { TimetableComparisonRepository(db).observe("A", friendId).collect { updates.send(it) } }
            suspend fun awaitState(predicate: (ComparisonSourceState) -> Boolean): ComparisonSourceState = withTimeout(5000) {
                var result = updates.receive()
                while (!predicate(result)) result = updates.receive()
                result
            }
            try {
                awaitState { it is ComparisonSourceState.Ready }
                val changed = event("a", "A").apply { to = Timestamp(monday + 11 * 3600000L) }
                db.eventItemDao().updateEventSync(changed)
                awaitState { it is ComparisonSourceState.Ready && it.source.own.occupied.single().endMillis == changed.to.time }
                db.friendTimetableDao().renameSync(friendId, "new remark")
                awaitState { it is ComparisonSourceState.Ready && it.source.friendRow.remark == "new remark" }
                db.friendTimetableDao().updateSync(row(snapshot().copy(occurrences = listOf(
                    SharedOccurrence(monday + 8 * 3600000L, monday + 9 * 3600000L, 0)))))
                awaitState { it is ComparisonSourceState.Ready && it.source.friend.occupied.single().startMillis == monday + 8 * 3600000L }
                db.friendTimetableDao().updateSync(row().copy(snapshotJson = "{}"))
                awaitState { it == ComparisonSourceState.Invalid }
                db.friendTimetableDao().updateSync(row())
                awaitState { it is ComparisonSourceState.Ready }
                db.timetableDao().saveTimetableSync(table("A").apply {
                    scheduleStructure = listOf(TimePeriodInDay(TimeInDay(8, 30), TimeInDay(10, 0)))
                })
                awaitState { it == ComparisonSourceState.Incompatible }
                db.timetableDao().saveTimetableSync(table("A"))
                awaitState { it is ComparisonSourceState.Ready }
                db.friendTimetableDao().updateSync(row(snapshot().copy(periods = listOf(SharedPeriod(510, 600)))))
                awaitState { it == ComparisonSourceState.Incompatible }
                db.friendTimetableDao().updateSync(row())
                awaitState { it is ComparisonSourceState.Ready }
                db.friendTimetableDao().deleteSync(friendId)
                awaitState { it == ComparisonSourceState.MissingFriend }
                db.friendTimetableDao().insertSync(row())
                awaitState { it is ComparisonSourceState.Ready }
                db.timetableDao().deleteTimetablesInIdsSync(listOf("A"))
                awaitState { it == ComparisonSourceState.MissingOwn }
            } finally { job.cancelAndJoin(); updates.close() }
        } finally { db.close() }
    }
    @Test fun rejectsInvalidRecordsValidityJsonAndEntirePeriodStructure() = runBlocking {
        val db = database()
        try {
            seed(db)
            db.eventItemDao().updateEventSync(event("a", "A").apply { to = from })
            assertEquals(ComparisonSourceState.Invalid, read(db))
            db.eventItemDao().updateEventSync(event("a", "A"))
            db.timetableDao().saveTimetableSync(table("A").apply { endTime = startTime })
            assertEquals(ComparisonSourceState.Invalid, read(db))
            db.timetableDao().saveTimetableSync(table("A"))
            db.friendTimetableDao().updateSync(row().copy(snapshotJson = "{}"))
            assertEquals(ComparisonSourceState.Invalid, read(db))
            db.friendTimetableDao().updateSync(row().copy(snapshotJson = codec.canonicalJson(snapshot())
                .replace((monday + 7 * 86400000L).toString(), monday.toString())))
            assertEquals(ComparisonSourceState.Invalid, read(db))
            db.friendTimetableDao().updateSync(row())
            db.timetableDao().saveTimetableSync(table("A").apply { scheduleStructure = emptyList() })
            assertEquals(ComparisonSourceState.Invalid, read(db))
            db.timetableDao().saveTimetableSync(table("A").apply {
                scheduleStructure.single().from.minute = 60
                assertEquals(60, scheduleStructure.single().from.minute)
            })
            assertEquals(ComparisonSourceState.Invalid, read(db))
            db.timetableDao().saveTimetableSync(table("A").apply {
                scheduleStructure = listOf(TimePeriodInDay(TimeInDay(8, 30), TimeInDay(10, 0)))
            })
            assertEquals(ComparisonSourceState.Incompatible, read(db))
            db.timetableDao().saveTimetableSync(table("A"))
            db.friendTimetableDao().updateSync(row(snapshot().copy(periods = listOf(SharedPeriod(510, 600)))))
            assertEquals(ComparisonSourceState.Incompatible, read(db))
        } finally { db.close() }
    }
    @Test fun oversizedPersonalInputIsRejectedWithoutTruncation() = runBlocking {
        val db = database()
        try {
            seed(db)
            db.timetableDao().saveTimetableSync(table("A").apply { scheduleStructure = (0..48).map {
                TimePeriodInDay(TimeInDay(it / 60, it % 60), TimeInDay((it + 1) / 60, (it + 1) % 60))
            } })
            assertEquals(ComparisonSourceState.Invalid, read(db))
            db.timetableDao().saveTimetableSync(table("A"))
            db.eventItemDao().saveEvents((0..4095).map { event("extra-$it", "A") })
            assertEquals(ComparisonSourceState.Invalid, read(db))
        } finally { db.close() }
    }
}
