package cn.limpu.hita.ui.eas.classroom

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.ui.eas.TermSelectionDisplay
import cn.limpu.hita.ui.eas.classroom.EmptyClassroomViewModelContractHarness.Companion.building
import cn.limpu.hita.ui.eas.classroom.EmptyClassroomViewModelContractHarness.Companion.term
import com.limpu.component.data.DataState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TermSelectionMetadataInstrumentedTest {
    @Test fun remoteMetadataUpdatesTheUiSnapshotWithoutRepeatingDependentQueries() {
        val h = EmptyClassroomViewModelContractHarness()
        val displayed = mutableListOf<TermSelectionDisplay?>()
        try {
            h.main {
                h.model.termsLiveData.observe(h.owner) {}
                h.model.selectedTermDisplay.observe(h.owner) { displayed += it }
                h.model.timetableStructureLiveData.observe(h.owner) {}
                h.model.startRefresh()
            }
            h.start()
            val cached = term("1")
            h.main {
                h.terms.single().value = DataState(listOf(cached)).setFromCache(true)
            }
            h.flush()
            h.main {
                h.model.selectBuilding(building("A"))
                h.model.selectWeek(7)
            }
            h.flush()
            assertEquals(1, h.requestedQueries.size)
            val snapshotBefore = displayed.last()
            val remote = term("1").apply {
                name = "remote display name"
                yearName = "remote year name"
                termName = "remote term name"
                id = "server-id"
                isCurrent = true
            }
            h.main {
                h.terms.single().value = DataState(listOf(term("2").apply { isCurrent = true }, remote))
            }
            h.flush()
            assertEquals(TermSelectionDisplay.from(remote), displayed.last())
            assertFalse(snapshotBefore == displayed.last())
            assertEquals("A metadata update must not repeat classroom IO", 1, h.requestedQueries.size)
            assertEquals("A metadata update must not reload the default week", 1, h.weeks.size)
            assertEquals("A metadata update must not reload schedule metadata", 1, h.structures.size)
            assertEquals(7, h.model.selectedWeekLiveData.value)
            assertEquals("1", h.model.selectedTermLiveData.value?.termCode)
            assertEquals("An in-flight request's input must remain unchanged", snapshotBefore?.id, h.requestedQueries.single().term.id)
            h.main { assertTrue(h.model.retryCurrentQuery()) }
            h.flush()
            assertEquals(2, h.requestedQueries.size)
            assertEquals("server-id", h.requestedQueries.last().term.id)
            assertEquals("remote display name", h.requestedQueries.last().term.name)
        } finally { h.close() }
    }

    @Test fun buildingRefreshPreservesManualNameWhenSeveralBuildingsHaveBlankIds() {
        val h = EmptyClassroomViewModelContractHarness()
        try {
            h.buildingResult = DataState(listOf(building("", "A"), building("", "B")))
            h.main { h.model.buildingsLiveData.observe(h.owner) {} }
            h.start()
            h.select(building = building("", "B"))
            h.main { h.model.startRefresh() }
            h.flush()
            assertEquals("B", h.model.selectedBuildingLiveData.value?.name)
            assertEquals("B", h.requestedQueries.last().building.name)
        } finally { h.close() }
    }
}
