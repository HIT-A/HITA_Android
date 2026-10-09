package cn.limpu.hita.data.timetableshare

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.*
import cn.limpu.hita.data.repository.*
import cn.limpu.hita.feature.timetableshare.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.sql.Timestamp
import java.util.UUID

internal fun fullFixture(nickname: String = "朋友"): SharedTimetable {
    val start = 1767225600000L
    return SharedTimetable("5fb93366-071b-4bd6-a658-ff402a25311b", nickname, ShareMode.FULL,
        SharedTerm("春季课表", "2026年春季", start, start + 86400000L * 120),
        listOf(SharedPeriod(480, 540)), listOf("数学"),
        listOf(SharedOccurrence(start + 28800000, start + 32400000, 0, "A101")))
}

internal fun personalRows(db: androidx.sqlite.db.SupportSQLiteDatabase): Map<String, List<List<String?>>> =
    listOf("timetable", "subject", "events").associateWith { table ->
        db.query("SELECT * FROM `$table` ORDER BY id").use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
        }
    }

@RunWith(AndroidJUnit4::class)
class FriendTimetableInstrumentedTest {
    private fun database(): AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()

    private fun seed(db: AppDatabase): Timetable {
        val snapshot = fullFixture()
        val table = Timetable().apply { id = "personal"; name = "本人"; code = "2025-2026-2"; startTime = Timestamp(snapshot.term.startMillis); endTime = Timestamp(snapshot.term.endMillis) }
        db.timetableDao().saveTimetableSync(table)
        db.subjectDao().saveSubjectSync(TermSubject().apply { id = "subject"; timetableId = table.id; name = "数学"; credit = 3f; color = 123 })
        db.eventItemDao().insertEventSync(EventItem().apply {
            id = "event"; timetableId = table.id; subjectId = "subject"; name = "数学"; teacher = "老师"; place = "A101"
            from = Timestamp(snapshot.occurrences.single().startMillis); to = Timestamp(snapshot.occurrences.single().endMillis)
        })
        return table
    }

    @Test fun importsRenamesDuplicatesUpdatesAndDeletesWithoutPersonalWrites() = runBlocking {
        val db = database()
        try {
            seed(db)
            val before = personalRows(db.openHelper.writableDatabase)
            val repo = FriendTimetableRepository(db)
            val codec = TimetableShareCodec()
            val first = repo.preview(codec.encode(fullFixture()))
            assertEquals(ImportKind.NEW, first.kind)
            assertNull(db.friendTimetableDao().getSync(first.snapshot.shareId))
            assertTrue(repo.confirm(first) is FriendSaveResult.Saved)
            assertEquals(before, personalRows(db.openHelper.writableDatabase))
            val inserted = db.friendTimetableDao().getSync(first.snapshot.shareId)!!
            repo.rename(inserted.shareId, "室友")
            assertEquals(before, personalRows(db.openHelper.writableDatabase))
            assertTrue(repo.confirm(repo.preview(codec.encode(fullFixture()))) is FriendSaveResult.Unchanged)
            assertEquals(before, personalRows(db.openHelper.writableDatabase))
            assertEquals(inserted.updatedAt, db.friendTimetableDao().getSync(inserted.shareId)!!.updatedAt)
            val update = repo.preview(codec.encode(fullFixture("新昵称")))
            assertEquals(ImportKind.UPDATE, update.kind)
            assertTrue(repo.confirm(update) is FriendSaveResult.Saved)
            assertEquals(before, personalRows(db.openHelper.writableDatabase))
            val changed = db.friendTimetableDao().getSync(inserted.shareId)!!
            assertEquals("室友", changed.remark)
            assertEquals(inserted.importedAt, changed.importedAt)
            repo.rename(inserted.shareId, null)
            val cleared = db.friendTimetableDao().getSync(inserted.shareId)!!
            assertNull(cleared.remark)
            assertEquals("新昵称", cleared.nickname)
            assertEquals(changed.updatedAt, cleared.updatedAt)
            assertEquals(before, personalRows(db.openHelper.writableDatabase))
            repo.delete(inserted.shareId)
            assertNull(db.friendTimetableDao().getSync(inserted.shareId))
            assertEquals(before, personalRows(db.openHelper.writableDatabase))
        } finally { db.close() }
    }

    @Test fun stalePreviewsRequireConfirmationAndLatestRemarkSurvives() = runBlocking {
        val db = database()
        try {
            val repo = FriendTimetableRepository(db)
            val codec = TimetableShareCodec()
            fun token(name: String) = codec.encode(fullFixture(name))
            val newA = repo.preview(token("A"))
            assertTrue(repo.confirm(repo.preview(token("B"))) is FriendSaveResult.Saved)
            assertTrue(repo.confirm(newA) is FriendSaveResult.NeedsConfirmation)
            val updateA = repo.preview(token("A"))
            repo.rename(updateA.snapshot.shareId, "最新备注")
            assertTrue(repo.confirm(updateA) is FriendSaveResult.Saved)
            assertEquals("最新备注", db.friendTimetableDao().getSync(updateA.snapshot.shareId)!!.remark)
            val staleSame = repo.preview(token("A"))
            assertTrue(repo.confirm(repo.preview(token("C"))) is FriendSaveResult.Saved)
            assertTrue(repo.confirm(staleSame) is FriendSaveResult.NeedsConfirmation)
            val staleUpdate = repo.preview(token("D"))
            repo.delete(staleUpdate.snapshot.shareId)
            val deleted = repo.confirm(staleUpdate) as FriendSaveResult.NeedsConfirmation
            assertEquals(ImportKind.NEW, deleted.preview.kind)
            assertNull(deleted.preview.expectedDigest)
            val simultaneous = repo.preview(token("A"))
            repo.confirm(repo.preview(token("A")))
            val conflicted = repo.confirm(simultaneous) as FriendSaveResult.NeedsConfirmation
            assertEquals(ImportKind.SAME, conflicted.preview.kind)
        } finally { db.close() }
    }

    @Test fun generatesStableIdentityAcrossNicknameAndModeButNotTerm() = runBlocking {
        val db = database()
        try {
            val table = seed(db)
            val repo = TimetableSharingRepository(db)
            val codec = TimetableShareCodec()
            val (first, second) = coroutineScope {
                val full = async { codec.decodeMessage(repo.generate(table.id, "甲", ShareMode.FULL)) }
                val busy = async { codec.decodeMessage(repo.generate(table.id, "乙", ShareMode.BUSY)) }
                full.await() to busy.await()
            }
            assertEquals(first.shareId, second.shareId)
            table.code = "2026-2027-1"
            db.timetableDao().saveTimetableSync(table)
            assertNotEquals(first.shareId, codec.decodeMessage(repo.generate(table.id, "甲", ShareMode.FULL)).shareId)
        } finally { db.close() }
    }

    @Test fun invalidTimetableDoesNotCreateIdentity() = runBlocking {
        val db = database()
        try {
            val table = Timetable().apply { id = "empty"; name = "空"; startTime = Timestamp(fullFixture().term.startMillis); endTime = Timestamp(fullFixture().term.endMillis) }
            db.timetableDao().saveTimetableSync(table)
            try { TimetableSharingRepository(db).generate(table.id, "甲", ShareMode.FULL); fail("must reject empty") }
            catch (e: ShareFormatException) { assertEquals(ShareError.EMPTY_SCHEDULE, e.error) }
            assertNull(db.timetableShareDao().getSync(table.id, ShareTermResolver.resolve(table.code, table.startTime.time).key))
        } finally { db.close() }
    }

    @Test fun sharingReadsOnlySelectedClassesAndNeverWritesPersonalRows() = runBlocking {
        val db = database()
        try {
            val table = seed(db)
            db.eventItemDao().insertEventSync(EventItem().apply {
                id = "other"; timetableId = table.id; type = EventItem.TYPE.OTHER; name = "私人事项"
                from = Timestamp(fullFixture().occurrences.single().startMillis); to = Timestamp(from.time + 3600000)
            })
            db.eventItemDao().insertEventSync(EventItem().apply {
                id = "foreign"; timetableId = "another"; name = "另一课表"
                from = Timestamp(fullFixture().occurrences.single().startMillis); to = Timestamp(from.time + 3600000)
            })
            val before = personalRows(db.openHelper.writableDatabase)
            val snapshot = TimetableShareCodec().decodeMessage(TimetableSharingRepository(db).generate(table.id, "甲", ShareMode.FULL))
            assertEquals(listOf("数学"), snapshot.courses)
            assertEquals(1, snapshot.occurrences.size)
            assertEquals(before, personalRows(db.openHelper.writableDatabase))
        } finally { db.close() }
    }

    @Test fun malformedPreviewDoesNotWriteAnyFriendRows() = runBlocking {
        val db = database()
        try {
            val repo = FriendTimetableRepository(db)
            try { repo.preview("HITA1:broken"); fail("must reject malformed") }
            catch (_: ShareFormatException) { }
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM friend_timetable").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally { db.close() }
    }
}
