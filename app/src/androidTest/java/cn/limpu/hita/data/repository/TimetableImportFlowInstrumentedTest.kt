package cn.limpu.hita.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.model.timetable.TimetableGroupEntity
import cn.limpu.hita.data.repository.TimetableImportFlowHarness.Companion.course
import cn.limpu.hita.data.repository.TimetableImportFlowHarness.Companion.date
import cn.limpu.hita.data.repository.TimetableImportFlowHarness.Companion.term
import com.limpu.component.data.DataState
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimetableImportFlowInstrumentedTest {
    @Test fun rejectedImportDoesNotNormalizeTheCallersCalendar() = runBlocking {
        TimetableImportFlowHarness().use { fixture ->
            fixture.preferences.saveEasToken(fixture.token.apply { cookies.clear() })
            val input = date()
            val original = input.clone() as Calendar
            val state = fixture.importer.importTimetableOfTerm(fixture.session(), term(), input, Timetable().getDefaultTimeStructure())
            assertEquals(DataState.STATE.NOT_LOGGED_IN, state.state)
            assertEquals(original, input)
            assertTrue(fixture.tables().isEmpty())
        }
    }

    @Test fun replaceKeepsManualEventsAndLocalNameGroupAndDeduplicatesLessons() = runBlocking {
        TimetableImportFlowHarness().use { fixture ->
            assertEquals(DataState.STATE.SUCCESS, fixture.import().state)
            val table = fixture.tables().single()
            fixture.database.timetableGroupDao().saveGroupSync(TimetableGroupEntity().apply { id = "group"; name = "mine" })
            table.name = "Personal title"; table.groupId = "group"
            fixture.database.timetableDao().saveTimetableSync(table)
            fixture.database.eventItemDao().insertEventSync(EventItem().apply {
                id = "manual"; timetableId = table.id; source = EventItem.SOURCE_MANUAL; name = "Personal event"
            })
            fixture.fetchCourses = { DataState(listOf(course("New title").apply { classroom = "B202" }, course("New title").apply { classroom = "B202" })) }
            assertEquals(DataState.STATE.SUCCESS, fixture.import().state)
            val stored = fixture.tables().single()
            assertEquals("Personal title", stored.name); assertEquals("group", stored.groupId)
            assertEquals(3, fixture.events().size)
            assertEquals(2, fixture.events().count { it.name == "New title" })
            assertTrue(fixture.events().any { it.id == "manual" })
            assertEquals(2, fixture.snapshots.snapshots(FollowedTeachingSectionStore.ownerKey(fixture.token), term().id).size)
        }
    }

    @Test fun shenzhenMetadataAndFreeTimeExperimentsUseTheSameImportCommit() = runBlocking {
        TimetableImportFlowHarness(EASToken.Campus.SHENZHEN).use { fixture ->
            fixture.fetchCourses = { DataState(listOf(course().apply { teacher = null }, course("Experiment").apply {
                code = "EXP"; begin = -1; last = -1; startTime = "15:40"; endTime = "18:10"; weeks = mutableListOf(1)
            })) }
            fixture.fetchSubjects = { DataState(mutableListOf(TermSubject().apply {
                name = "Algorithms"; code = "CS101"; teacher = "Metadata teacher"; credit = 3.5f; field = "major"; nature = "required"
            })) }
            assertEquals(DataState.STATE.SUCCESS, fixture.import().state)
            val table = fixture.tables().single()
            val subject = fixture.database.subjectDao().getSubjectsSync(table.id).first { it.code == "CS101" }
            assertEquals(3.5f, subject.credit); assertEquals("major", subject.field); assertEquals("required", subject.nature)
            assertEquals(listOf("timetable", "subjects"), fixture.calls.toList())
            assertTrue(fixture.events().filter { it.name == "Algorithms" }.all { it.teacher == "Metadata teacher" })
            val experiment = fixture.events().single { it.name == "Experiment" }
            val calendar = Calendar.getInstance().apply { timeInMillis = experiment.from.time }
            assertEquals(15, calendar.get(Calendar.HOUR_OF_DAY)); assertEquals(40, calendar.get(Calendar.MINUTE))
            assertEquals(0, experiment.fromNumber)
        }
    }

    @Test fun sqliteFailureRollsBackReplacementAndRetainsOriginalLessons() = runBlocking {
        TimetableImportFlowHarness().use { fixture ->
            assertEquals(DataState.STATE.SUCCESS, fixture.import().state)
            val before = fixture.events().map { it.id to it.name }
            fixture.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_import BEFORE INSERT ON events BEGIN SELECT RAISE(ABORT, 'import injected failure'); END")
            fixture.fetchCourses = { DataState(listOf(course("Changed"))) }
            val result = fixture.import()
            assertEquals(DataState.STATE.FETCH_FAILED, result.state)
            assertTrue(result.message.orEmpty().contains("import injected failure"))
            assertEquals(before, fixture.events().map { it.id to it.name })
        }
    }

    @Test fun cancelledRemoteDoesNotWriteOrFetchMoreMetadata() = runBlocking {
        TimetableImportFlowHarness(EASToken.Campus.SHENZHEN).use { fixture ->
            val started = CompletableDeferred<Unit>()
            fixture.fetchCourses = { started.complete(Unit); CompletableDeferred<DataState<List<cn.limpu.hita.data.model.eas.CourseItem>>>().await() }
            val task = launch { fixture.import() }
            started.await(); task.cancelAndJoin()
            assertTrue(fixture.tables().isEmpty())
            assertEquals(listOf("timetable"), fixture.calls.toList())
        }
    }

    @Test fun remoteDeadlineIsAFailureWithoutWriting() = runBlocking {
        TimetableImportFlowHarness().use { fixture ->
            fixture.fetchCourses = { delay(10_000); DataState(listOf(course())) }
            assertEquals(DataState.STATE.FETCH_FAILED, fixture.import(timeout = 30).state)
            assertTrue(fixture.tables().isEmpty())
        }
    }

    @Test fun sessionChangeCancelsRequestBeforeLocalCommit() = runBlocking {
        TimetableImportFlowHarness().use { fixture ->
            val started = CompletableDeferred<Unit>()
            fixture.fetchCourses = { started.complete(Unit); delay(10_000); DataState(listOf(course())) }
            val task = launch { fixture.import() }
            started.await(); fixture.switchAccount(); withTimeout(5_000) { task.join() }
            assertTrue(task.isCancelled)
            assertTrue(fixture.tables().isEmpty())
        }
    }

    @Test fun simultaneousFirstImportsShareOneTimetableIdentity() = runBlocking {
        TimetableImportFlowHarness().use { fixture ->
            val both = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val count = java.util.concurrent.atomic.AtomicInteger()
            fixture.fetchCourses = { if (count.incrementAndGet() == 2) both.complete(Unit); release.await(); DataState(listOf(course())) }
            val first = async(Dispatchers.Default) { fixture.import() }
            val second = async(Dispatchers.Default) { fixture.import() }
            both.await(); release.complete(Unit)
            assertEquals(DataState.STATE.SUCCESS, first.await().state)
            assertEquals(DataState.STATE.SUCCESS, second.await().state)
            assertEquals(1, fixture.tables().size)
            assertEquals(2, fixture.events().size)
        }
    }

    @Test fun cancellationWhileWaitingForSQLitePreventsAllWrites() = runBlocking {
        TimetableImportFlowHarness().use { fixture ->
            // Open Room before holding its real write connection from another thread.
            fixture.tables()
            val held = CountDownLatch(1); val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val holder = executor.submit { fixture.database.runInTransaction { held.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) } }
            var task: kotlinx.coroutines.Job? = null
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS))
                task = launch(Dispatchers.Default) { fixture.import() }
                withTimeout(5_000) {
                    while (fixture.importThread?.stackTrace?.none {
                        it.className.contains("SQLiteConnectionPool") || (it.className.contains("RoomDatabase") && it.methodName.contains("beginTransaction"))
                    } != false) delay(5)
                }
                task.cancel(); release.countDown(); holder.get(5, TimeUnit.SECONDS); task.join()
                assertTrue(task.isCancelled)
                assertTrue(fixture.tables().isEmpty())
                assertTrue(fixture.events().isEmpty())
            } finally { release.countDown(); task?.cancelAndJoin(); holder.get(5, TimeUnit.SECONDS); executor.shutdownNow() }
        }
    }

    @Test fun cancellationAfterCommitBoundaryCompletesWholeTransactionWithoutFailureDelivery() = runBlocking {
        TimetableImportFlowHarness().use { fixture ->
            val atInsert = CountDownLatch(1); val release = CountDownLatch(1); val once = AtomicBoolean()
            fixture.onSql = { sql ->
                if (sql.contains("INSERT", true) && sql.contains("`events`", true) && once.compareAndSet(false, true)) {
                    atInsert.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS))
                }
            }
            var delivered: DataState<Boolean>? = null
            val task = launch(Dispatchers.Default) { delivered = fixture.import() }
            try {
                assertTrue("Actual event insert must be reached inside Room", atInsert.await(5, TimeUnit.SECONDS))
                task.cancel(); release.countDown(); task.join()
                assertNull("Cancelled UI must not receive a false failure or success", delivered)
                assertEquals(1, fixture.tables().size)
                assertEquals(2, fixture.events().size)
            } finally { release.countDown(); task.cancelAndJoin() }
        }
    }

    @Test fun automaticImportUsesSavedCalibrationScheduleAndDeduplicatesExams() = runBlocking {
        TimetableImportFlowHarness().use { fixture ->
            val calibrated = date().apply { add(Calendar.DAY_OF_MONTH, 7) }
            cn.limpu.hita.data.source.preference.BenbuStartDatePreferenceSource(fixture.application)
                .saveCalibration(term().getCode(), calibrated.timeInMillis)
            val schedule = Timetable().getDefaultTimeStructure().map { it.clone() }.toMutableList()
            schedule[0].from.hour = 9
            fixture.timetablePreferences.saveSchedules(schedule)
            fixture.fetchExams = { DataState(listOf(ExamItem().apply {
                courseName = "Algorithms"; examDate = "2026-03-30"; examTime = "09:00-11:00"; examLocation = "A101"
            })) }
            repeat(2) {
                val result = CompletableDeferred<AutoImportResult>()
                fixture.importer.startAutoImportCurrentTimetable(true) { result.complete(it) }!!.join()
                assertEquals(AutoImportResult.SUCCESS, result.await())
            }
            assertEquals(1, fixture.tables().size)
            assertEquals(1, fixture.events().count { it.type == EventItem.TYPE.EXAM })
            val table = fixture.tables().single()
            val monday = calibrated.clone() as Calendar
            monday.set(Calendar.HOUR_OF_DAY, 0); monday.set(Calendar.MINUTE, 0); monday.set(Calendar.SECOND, 0); monday.set(Calendar.MILLISECOND, 0)
            assertEquals(monday.timeInMillis, table.startTime.time)
            assertEquals(9, table.scheduleStructure.first().from.hour)
            assertFalse(fixture.inProgress.get())
        }
    }

    @Test fun automaticConcurrentRequestIsDeduplicatedAndCancellationReleasesGuard() = runBlocking {
        TimetableImportFlowHarness().use { fixture ->
            val started = CompletableDeferred<Unit>()
            fixture.fetchTerms = { started.complete(Unit); delay(10_000); DataState(listOf(term())) }
            val completed = CompletableDeferred<AutoImportResult>()
            val running = fixture.importer.startAutoImportCurrentTimetable(true) { completed.complete(it) }!!
            started.await()
            var second: AutoImportResult? = null
            assertNull(fixture.importer.startAutoImportCurrentTimetable(true) { second = it })
            assertEquals(AutoImportResult.SKIPPED_IN_PROGRESS, second)
            running.cancelAndJoin()
            assertEquals(AutoImportResult.FAILED, completed.await())
            assertFalse(fixture.inProgress.get())
            assertTrue(fixture.tables().isEmpty())
        }
    }

    @Test fun snapshotsRestoreCapturedOwnerAndPreserveLocalDisplayMetadata() = runBlocking {
        TimetableImportFlowHarness().use { fixture ->
            fixture.import()
            val listed = fixture.importer.getTimetableSnapshots(fixture.session(), term())
            val snapshot = listed.data!!.single()
            fixture.fetchCourses = { DataState(listOf(course("Changed"))) }; fixture.import()
            val table = fixture.tables().single().apply { name = "Local title" }
            fixture.database.timetableDao().saveTimetableSync(table)
            val result = fixture.importer.restoreTimetableSnapshot(fixture.session(), snapshot.id)
            assertEquals(DataState.STATE.SUCCESS, result.state)
            assertTrue(fixture.events().all { it.name == "Algorithms" })
            assertEquals("Local title", fixture.tables().single().name)
            fixture.switchAccount()
            assertTrue(fixture.importer.getTimetableSnapshots(fixture.session(), term()).data!!.isEmpty())
            assertEquals(DataState.STATE.FETCH_FAILED, fixture.importer.restoreTimetableSnapshot(fixture.session(), snapshot.id).state)
        }
    }
}
