package cn.limpu.hita.ui.eas.classroom

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.ui.eas.classroom.EmptyClassroomViewModelContractHarness.Companion.building
import cn.limpu.hita.ui.eas.classroom.EmptyClassroomViewModelContractHarness.Companion.success
import cn.limpu.hita.ui.eas.classroom.EmptyClassroomViewModelContractHarness.Companion.term
import com.limpu.component.data.DataState
import java.util.Calendar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Preserved page contracts, measured against the pre-coroutine production implementation. */
@RunWith(AndroidJUnit4::class)
class EmptyClassroomViewModelBaselineInstrumentedTest {
    private var harness: EmptyClassroomViewModelContractHarness? = null
    private fun create() = EmptyClassroomViewModelContractHarness().also { harness = it }
    @After fun tearDown() { harness?.close() }

    @Test fun activationWithoutSelectionStartsNoQueriesAndPublishesNoState() {
        val h = create()
        h.start()
        assertTrue(h.requestedQueries.isEmpty())
        assertTrue(h.weeks.isEmpty())
        assertTrue(h.states.isEmpty())
        assertTrue(h.analytics.isEmpty())
    }

    @Test fun completeSelectionBeforeActivationWaitsForTheResultObserver() {
        val h = create()
        h.main {
            h.model.selectTerm(term("1"))
            h.model.selectBuilding(building("A"))
            h.model.selectWeek(7)
        }
        assertTrue(h.requestedQueries.isEmpty())
        h.start()
        assertEquals(listOf(7), h.requestedQueries.map { it.week })
        assertEquals(1, h.analytics.size)
    }

    @Test fun queryWaitsUntilTermBuildingAndWeekAreAllAvailable() {
        val h = create()
        h.start()
        h.main { h.model.selectTerm(term("1")) }
        assertTrue(h.requestedQueries.isEmpty())
        h.completeWeek(0, 4)
        assertTrue(h.requestedQueries.isEmpty())
        h.main { h.model.selectBuilding(building("A")) }
        h.flush()
        assertEquals(1, h.requestedQueries.size)
        assertEquals(4, h.requestedQueries.single().week)
    }

    @Test fun sameBuildingAndWeekSelectionsDoNotRepeatTheQuery() {
        val h = create()
        h.start()
        h.select()
        h.main {
            h.model.selectBuilding(building("A", "renamed label"))
            h.model.selectWeek(3)
        }
        h.flush()
        assertEquals(1, h.requestedQueries.size)
    }

    @Test fun buildingsWithoutIdsUseTheirNamesForQueryDeduplication() {
        val h = create()
        h.start()
        h.select(building = building("", "A"))
        h.main { h.model.selectBuilding(building("", "A")) }
        h.flush()
        assertEquals(1, h.requestedQueries.size)
        h.main { h.model.selectBuilding(building("", "B")) }
        h.flush()
        assertEquals(listOf("A", "B"), h.requestedQueries.map { it.building.name })
    }

    @Test fun changedBuildingDropsAnOldSourceResult() {
        val h = create()
        h.start()
        h.select()
        h.main { h.model.selectBuilding(building("B")) }
        h.flush()
        val current = success("B101")
        h.complete(1, current)
        h.complete(0, success("obsolete A101"))
        assertEquals(listOf("A", "B"), h.requestedQueries.map { it.building.id })
        assertEquals(listOf(current), h.states)
        assertEquals(0, h.analytics.count { it.event == UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_FAILED })
    }

    @Test fun changedWeekDropsAnOldSourceResult() {
        val h = create()
        h.start()
        h.select(week = 3)
        h.main { h.model.selectWeek(8) }
        h.flush()
        val current = success("week eight")
        h.complete(1, current)
        h.complete(0, success("obsolete week three"))
        assertEquals(listOf(3, 8), h.requestedQueries.map { it.week })
        assertEquals(listOf(current), h.states)
    }

    @Test fun sourceWithoutInitialValueAddsNoSyntheticLoadingState() {
        val h = create()
        h.start()
        h.select()
        assertTrue(h.states.isEmpty())
        h.main { assertNull(h.model.classroomLiveData.value) }
        val remote = success("remote")
        h.complete(0, remote)
        assertEquals(listOf(remote), h.states)
    }

    @Test fun existingNothingInitialStateIsPreservedWithoutAddingLoading() {
        val h = create()
        h.initialClassroomState = DataState(DataState.STATE.NOTHING)
        h.start()
        h.select()
        h.complete(0, success("remote"))
        assertEquals(listOf(DataState.STATE.NOTHING, DataState.STATE.SUCCESS), h.states.map { it.state })
        assertFalse(h.states.any { it.state == DataState.STATE.LOADING })
    }

    @Test fun cacheAndRemoteSuccessKeepTheirFieldsAndFinishAnalyticsOnlyOnce() {
        val h = create()
        h.start()
        h.select()
        val cached = success("cached").setFromCache(true).apply { message = "cached message" }
        val remote = success("remote").apply { message = "remote message" }
        h.complete(0, cached)
        h.complete(0, remote)
        assertEquals(listOf(cached, remote), h.states)
        assertTrue(h.states.first().fromCache)
        assertFalse(h.states.last().fromCache)
        assertEquals("remote message", h.states.last().message)
        assertEquals(
            listOf(UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_STARTED, UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_SUCCEEDED),
            h.analytics.map { it.event },
        )
        assertEquals(h.analytics.first().operationId, h.analytics.last().operationId)
    }

    @Test fun intermediateNothingAndLoadingDoNotFinishAnalytics() {
        val h = create()
        h.start()
        h.select()
        h.complete(0, DataState(DataState.STATE.NOTHING))
        h.complete(0, DataState(DataState.STATE.LOADING))
        assertEquals(listOf(UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_STARTED), h.analytics.map { it.event })
        assertEquals(listOf(DataState.STATE.NOTHING, DataState.STATE.LOADING), h.states.map { it.state })
    }

    @Test fun authenticationFailuresKeepTheirStateAndAnalyticsCategory() {
        val h = create()
        h.start()
        h.select()
        val statuses = listOf(DataState.STATE.NOT_LOGGED_IN, DataState.STATE.TOKEN_INVALID)
        statuses.forEachIndexed { index, status ->
            if (index > 0) {
                h.main { h.model.selectWeek(4) }
                h.flush()
            }
            val failure = DataState<List<ClassroomItem>>(status, "session expired")
            h.complete(index, failure)
            assertSame(failure, h.states.last())
            assertEquals(UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_FAILED, h.analytics.last().event)
            assertEquals(mapOf("error_category" to "authentication"), h.analytics.last().dimensions)
        }
    }

    @Test fun ordinaryFailureKeepsItsMessageAndUnknownAnalyticsCategory() {
        val h = create()
        h.start()
        h.select()
        val failure = DataState<List<ClassroomItem>>(DataState.STATE.FETCH_FAILED, "offline")
        h.complete(0, failure)
        assertSame(failure, h.states.single())
        assertEquals("offline", h.states.single().message)
        assertEquals(mapOf("error_category" to "unknown"), h.analytics.last().dimensions)
    }

    @Test fun emptySuccessRemainsSuccessAndCompletesAnalytics() {
        val h = create()
        h.start()
        h.select()
        h.complete(0, DataState(emptyList()))
        assertEquals(DataState.STATE.SUCCESS, h.states.single().state)
        assertTrue(h.states.single().data.orEmpty().isEmpty())
        assertEquals(UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_SUCCEEDED, h.analytics.last().event)
    }

    @Test fun reactivationWithUnchangedSelectionDoesNotStartAnotherQuery() {
        val h = create()
        h.start()
        h.select()
        val result = success("existing")
        h.complete(0, result)
        h.stop()
        h.start()
        assertEquals(1, h.requestedQueries.size)
        assertEquals(listOf(result), h.states)
    }

    @Test fun completionWhileInactiveReplaysOnActivationWithoutRestarting() {
        val h = create()
        h.start()
        h.select()
        h.stop()
        val result = success("completed while inactive")
        h.complete(0, result)
        assertTrue(h.states.isEmpty())
        h.start()
        assertEquals(1, h.requestedQueries.size)
        assertEquals(listOf(result), h.states)
    }

    @Test fun buildingChangeWhileInactiveDefersTheReplacementUntilActivation() {
        val h = create()
        h.start()
        h.select()
        h.stop()
        h.main { h.model.selectBuilding(building("B")) }
        h.flush()
        assertEquals(1, h.requestedQueries.size)
        h.start()
        assertEquals(listOf("A", "B"), h.requestedQueries.map { it.building.id })
        val result = success("B101")
        h.complete(1, result)
        h.complete(0, success("obsolete A101"))
        assertEquals(listOf(result), h.states)
    }

    @Test fun retryWithMissingSelectionReturnsFalseWithoutStartingAQuery() {
        val h = create()
        h.start()
        h.main {
            assertFalse(h.model.retryCurrentQuery())
            h.model.selectTerm(term("1"))
            assertFalse(h.model.retryCurrentQuery())
            h.model.selectBuilding(building("A"))
            assertFalse(h.model.retryCurrentQuery())
        }
        assertTrue(h.requestedQueries.isEmpty())
    }

    @Test fun explicitRetryCanRepeatACompleteSelection() {
        val h = create()
        h.start()
        h.select()
        h.main { assertTrue(h.model.retryCurrentQuery()) }
        h.flush()
        assertEquals(2, h.requestedQueries.size)
        assertEquals(h.requestedQueries.first(), h.requestedQueries.last())
        val result = success("retried")
        h.complete(1, result)
        assertEquals(listOf(result), h.states)
    }

    @Test fun metadataRefreshBeforeActivationStartsOnlyOnceWhenObserved() {
        val h = create()
        h.main {
            h.model.termsLiveData.observe(h.owner) { }
            h.model.buildingsLiveData.observe(h.owner) { }
            repeat(3) { h.model.startRefresh() }
        }
        assertTrue(h.terms.isEmpty())
        assertEquals(0, h.buildingRequests)
        h.start()
        assertEquals(1, h.terms.size)
        assertEquals(1, h.buildingRequests)
        assertTrue(h.requestedQueries.isEmpty())
    }

    @Test fun termListKeepsCurrentYearMatchesAndFallsBackWhenThereAreNone() {
        val h = create()
        h.main { h.model.termsLiveData.observe(h.owner) { } }
        h.start()
        h.main { h.model.startRefresh() }
        val year = Calendar.getInstance().get(Calendar.YEAR).toString()
        val matching = TermItem(year, year, "1", "fall")
        val other = TermItem("1900", "1900", "2", "spring")
        h.main { h.terms.last().value = DataState(listOf(other, matching)) }
        h.main { assertEquals(listOf(matching), h.model.termsLiveData.value?.data) }
        h.main { h.terms.last().value = DataState(listOf(other)) }
        h.main { assertEquals(listOf(other), h.model.termsLiveData.value?.data) }
    }

    @Test fun scheduleStructureTracksTheSelectedTermIndependently() {
        val h = create()
        h.main { h.model.timetableStructureLiveData.observe(h.owner) { } }
        h.start()
        h.main { h.model.selectTerm(term("1")) }
        h.main { h.model.selectTerm(term("2")) }
        assertEquals(listOf(term("1"), term("2")), h.structures.map { it.first })
        assertTrue(h.requestedQueries.isEmpty())
    }
}
