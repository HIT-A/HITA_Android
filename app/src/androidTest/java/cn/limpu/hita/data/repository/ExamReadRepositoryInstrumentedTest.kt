package cn.limpu.hita.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.repository.today.TodayReadState
import cn.limpu.hita.data.source.preference.EasCredentialStore
import com.limpu.component.data.DataState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual repository/session wiring with isolated Room/preferences and scripted service responses. */
@RunWith(AndroidJUnit4::class)
class ExamReadRepositoryInstrumentedTest {
    private fun repository(fixture: TimetableImportFlowHarness) = EASRepository(
        fixture.application, fixture.preferences, EasCredentialStore(fixture.application),
        fixture.timetablePreferences, fixture.changes, TimetableMutationLock(), fixture.database,
        EasRepositoryServiceFactories(benbu = { fixture.service }, weihai = { fixture.service }),
    )
    @Test fun standaloneQueryRecordsUnboundResultAndRejectsOlderSameTermResponse() = runBlocking {
        withTimeout(10_000) {
            TimetableImportFlowHarness().use { fixture ->
                val firstReached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                var calls = 0
                fixture.fetchExams = {
                    val index = ++calls
                    if (index == 1) { firstReached.complete(Unit); release.await() }
                    DataState(listOf(ExamItem().apply { courseName = if (index == 1) "old" else "new" }))
                }
                val repo = repository(fixture)
                val old = async { repo.getExamInfoOnce(TimetableImportFlowHarness.term()) }
                firstReached.await()
                assertEquals(DataState.STATE.SUCCESS, repo.getExamInfoOnce(TimetableImportFlowHarness.term()).state)
                release.complete(Unit); old.await()
                val result = repo.examReadState.value.terms[TimetableImportFlowHarness.term().id] as TodayReadState.Ready
                assertEquals("new", result.value.items.single().courseName)
                assertNull(result.value.targetTimetableId)
                assertTrue(fixture.database.eventItemDao().getExamEventsSync().isEmpty())
            }
        }
    }
    @Test fun switchingAccountCancelsRepositoryReadAndClearsRawResults() = runBlocking {
        withTimeout(10_000) {
            TimetableImportFlowHarness().use { fixture ->
                val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                fixture.fetchExams = {
                    entered.complete(Unit); release.await()
                    DataState(listOf(ExamItem().apply { courseName = "old account" }))
                }
                val repo = repository(fixture)
                val read = async { repo.getExamInfoOnce(TimetableImportFlowHarness.term()) }
                entered.await(); fixture.switchAccount(); release.complete(Unit)
                try { read.await(); fail("Superseded read must cancel") }
                catch (_: kotlinx.coroutines.CancellationException) { }
                assertTrue(repo.examReadState.value.terms.isEmpty())
                assertTrue(fixture.database.eventItemDao().getExamEventsSync().isEmpty())
            }
        }
    }
}
