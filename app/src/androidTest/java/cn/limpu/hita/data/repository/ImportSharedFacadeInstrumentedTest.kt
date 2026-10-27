package cn.limpu.hita.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportSharedFacadeInstrumentedTest : TermRepositoryContractHarness() {
    @Test fun automaticImportGateIsSharedByIndependentRepositoryFacades() = runBlocking {
        val fixture = fixture()
        val first = fixture.repository.startAutoImportCurrentTimetable(true)!!
        var second: kotlinx.coroutines.Job? = null
        try {
            fixture.scripted.awaitCall()
            var next: AutoImportResult? = null
            second = fixture.newFacade().startAutoImportCurrentTimetable(true) { next = it }
            assertNull(second)
            assertEquals(AutoImportResult.SKIPPED_IN_PROGRESS, next)
            assertEquals(1, fixture.scripted.calls.size)
        } finally { second?.cancelAndJoin(); first.cancelAndJoin() }
    }
}
