package cn.limpu.hita.data.repository

import androidx.lifecycle.Observer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import com.limpu.component.data.DataState
import java.sql.Timestamp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimetableSnapshotFlowInstrumentedTest {
    @Test fun cancellationWhileRestoreWaitsForSQLiteKeepsCurrentLessons() = runBlocking {
        TimetableImportFlowHarness().use { fixture ->
            fixture.import()
            // New imports no longer write the legacy snapshot file; seed the compatibility backup explicitly.
            fixture.snapshots.capture(FollowedTeachingSectionStore.ownerKey(fixture.token),
                TimetableImportFlowHarness.term(), fixture.tables().single(), TimetableSnapshotKind.IMPORTED)
            val snapshot = fixture.importer.getTimetableSnapshots(fixture.session(), TimetableImportFlowHarness.term()).data!!.single()
            fixture.fetchCourses = { DataState(listOf(TimetableImportFlowHarness.course("Changed"))) }
            fixture.import()
            val held = CountDownLatch(1); val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val holder = executor.submit { fixture.database.runInTransaction { held.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) } }
            assertTrue(held.await(5, TimeUnit.SECONDS))
            val thread = AtomicReference<Thread>()
            val task = launch(Dispatchers.IO) {
                thread.set(Thread.currentThread())
                fixture.importer.restoreTimetableSnapshot(fixture.session(), snapshot.id)
            }
            try {
                withTimeout(5_000) { while (thread.get()?.stackTrace?.none {
                    it.className.contains("SQLiteConnectionPool") || (it.className.contains("RoomDatabase") && it.methodName.contains("beginTransaction"))
                } != false) delay(5) }
                task.cancel(); release.countDown(); holder.get(5, TimeUnit.SECONDS); task.join()
                assertTrue(task.isCancelled)
                assertEquals(2, fixture.events().size)
                assertTrue("No stale restore after acquiring SQLite", fixture.events().all { it.name == "Changed" })
            } finally { release.countDown(); task.cancelAndJoin(); holder.get(5, TimeUnit.SECONDS); executor.shutdownNow() }
        }
    }

    @Test fun cancellationAfterRestoreCommitBoundaryStillRestoresWholeSnapshot() = runBlocking {
        TimetableImportFlowHarness().use { fixture ->
            fixture.import()
            fixture.snapshots.capture(FollowedTeachingSectionStore.ownerKey(fixture.token),
                TimetableImportFlowHarness.term(), fixture.tables().single(), TimetableSnapshotKind.IMPORTED)
            val snapshot = fixture.importer.getTimetableSnapshots(fixture.session(), TimetableImportFlowHarness.term()).data!!.single()
            fixture.fetchCourses = { DataState(listOf(TimetableImportFlowHarness.course("Changed"))) }; fixture.import()
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val once = AtomicBoolean()
            fixture.onSql = { sql ->
                if (sql.startsWith("DELETE FROM events", true) && once.compareAndSet(false, true)) {
                    entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS))
                }
            }
            var delivered: DataState<TimetableVersionSnapshot>? = null
            val task = launch(Dispatchers.IO) { delivered = fixture.importer.restoreTimetableSnapshot(fixture.session(), snapshot.id) }
            try {
                assertTrue("Actual restore DELETE is reached after the guard", entered.await(5, TimeUnit.SECONDS))
                task.cancel(); release.countDown(); task.join()
                assertNull(delivered)
                assertEquals(2, fixture.events().size)
                assertTrue(fixture.events().all { it.name == "Algorithms" })
            } finally { release.countDown(); task.cancelAndJoin() }
        }
    }

    @Test fun actualViewModelRestoreRefreshesSnapshotsOnceWithoutReloadingMetadata() = ImportMetadataHarness().use { fixture ->
        val term = ImportMetadataHarness.term()
        val table = Timetable().apply { id = "snapshot-table"; name = "Local title"; code = EASTimetableCode.of(fixture.preferences.getEasToken().campus, term) }
        fixture.database.timetableDao().saveTimetableSync(table)
        fixture.database.subjectDao().saveSubjectSync(TermSubject().apply { id = "subject"; timetableId = table.id; name = "Original" })
        fixture.database.eventItemDao().insertEventSync(EventItem().apply {
            id = "event"; timetableId = table.id; subjectId = "subject"; name = "Original"; place = "A101"
            from = Timestamp(1_800_000_000_000); to = Timestamp(from.time + 3_600_000)
        })
        val snapshot = requireNotNull(TimetableSnapshotStore(fixture.application, fixture.database).capture(
            FollowedTeachingSectionStore.ownerKey(fixture.preferences.getEasToken()), term, table, TimetableSnapshotKind.IMPORTED))
        fixture.database.eventItemDao().getEventsOfTimetableSync(table.id).single().let {
            it.name = "Changed"; it.place = "B202"; fixture.database.eventItemDao().updateEventSync(it)
        }
        val arrivals = LinkedBlockingQueue<DataState<List<TimetableVersionSnapshot>>>()
        val seen = CopyOnWriteArrayList<DataState<List<TimetableVersionSnapshot>>>()
        val restored = CountDownLatch(1)
        val observer = Observer<DataState<List<TimetableVersionSnapshot>>> { if (it.state == DataState.STATE.SUCCESS) { seen += it; arrivals.offer(it) } }
        val resultObserver = Observer<DataState<TimetableVersionSnapshot>> { if (it.state == DataState.STATE.SUCCESS) restored.countDown() }
        ImportMetadataHarness.main {
            fixture.model.changeSelectedTerm(term)
            fixture.model.snapshotsLiveData.observeForever(observer)
            fixture.model.restoreSnapshotLiveData.observeForever(resultObserver)
        }
        try {
            assertEquals(1, requireNotNull(arrivals.poll(5, TimeUnit.SECONDS)).data!!.size)
            ImportMetadataHarness.main { fixture.model.restoreSnapshot(snapshot.id) }
            assertTrue(restored.await(5, TimeUnit.SECONDS))
            // Legacy restore rewrites courses only; the version rows are the durable signal. The recovery
            // transaction always records the pre-image as INITIAL and the committed content as SOURCE.
            // Read the table directly: TimetableVersionStore.list() would fabricate an INITIAL row.
            val summaries = fixture.database.timetableVersionDao()
                .summaries(FollowedTeachingSectionStore.ownerKey(fixture.preferences.getEasToken()), table.id)
            assertEquals("One pre-image plus exactly one restore commit",
                listOf("SOURCE", "INITIAL"), summaries.map { it.reason })
            assertNull("A snapshot restore is a source commit, not a version restore",
                summaries.first().restoredFrom)
            assertEquals("Changed", versionNames(fixture, table.id, "INITIAL").single())
            assertEquals("Original", versionNames(fixture, table.id, "SOURCE").single())
            assertEquals("The compatibility file is not appended by a restore", 1,
                TimetableSnapshotStore(fixture.application, fixture.database).snapshots(
                    FollowedTeachingSectionStore.ownerKey(fixture.preferences.getEasToken()), term.id).size)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals("One initial list plus exactly one successful restore refresh", 2, seen.size)
            assertTrue(fixture.dates.isEmpty()); assertTrue(fixture.schedules.isEmpty())
            assertEquals("Original", fixture.database.eventItemDao().getEventsOfTimetableSync(table.id).single().name)
            assertEquals("Local title", fixture.database.timetableDao().getTimetableByIdSync(table.id)!!.name)
        } finally { ImportMetadataHarness.main { fixture.model.snapshotsLiveData.removeObserver(observer); fixture.model.restoreSnapshotLiveData.removeObserver(resultObserver) } }
    }

    private fun versionNames(fixture: ImportMetadataHarness, tableId: String, reason: String): List<String> {
        val owner = FollowedTeachingSectionStore.ownerKey(fixture.preferences.getEasToken())
        val dao = fixture.database.timetableVersionDao()
        val summary = dao.summaries(owner, tableId).single { it.reason == reason }
        val stored = requireNotNull(dao.get(owner, tableId, summary.id))
        return EventEditHistoryStore.eventCodec()
            .fromJson(stored.payload, CourseVersionContent::class.java).events.map { it.name }
    }
}
