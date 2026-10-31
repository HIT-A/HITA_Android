package cn.limpu.hita.ui.eas.classroom

import androidx.lifecycle.Observer
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.repository.EmptyClassroomQueryRequest
import cn.limpu.hita.ui.eas.classroom.EmptyClassroomViewModelContractHarness.Companion.building
import cn.limpu.hita.ui.eas.classroom.EmptyClassroomViewModelContractHarness.Companion.success
import cn.limpu.hita.ui.eas.classroom.EmptyClassroomViewModelContractHarness.Companion.term
import com.limpu.component.data.DataState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmptyClassroomViewModelCoordinationInstrumentedTest {
    private var harness: EmptyClassroomViewModelContractHarness? = null
    private fun create() = EmptyClassroomViewModelContractHarness().also { harness = it }
    @After fun tearDown() { harness?.close() }

    @Test fun sameTermMetadataDoesNotRestartDefaultWeekOrClassroomQueries() {
        val h = create()
        h.start()
        h.select()
        h.main {
            h.model.selectTerm(term("1").apply { name = "refreshed term metadata"; id = "different display id" })
            h.model.selectBuilding(building("A", "refreshed building metadata"))
            h.model.selectWeek(3)
        }
        h.flush()
        assertEquals(1, h.weeks.size)
        assertEquals(1, h.requestedQueries.size)
        assertFalse(requireNotNull(h.jobs[0]).isCancelled)
    }

    @Test fun termIdentityUsesYearAndTermFieldsEvenWhenIdsAreEqual() {
        val h = create()
        h.start()
        h.select()
        val replacement = term("2").apply { id = term("1").id }
        h.main { h.model.selectTerm(replacement) }
        h.flush()
        assertTrue(requireNotNull(h.jobs[0]).isCancelled)
        assertEquals(1, h.requestedQueries.size)
        h.completeWeek(1, 8)
        assertEquals(listOf("1", "2"), h.requestedQueries.map { it.term.termCode })
        assertEquals(listOf(3, 8), h.requestedQueries.map { it.week })
    }

    @Test fun oldTermDefaultWeekCannotChangeTheNewSelection() {
        val h = create()
        h.start()
        h.select()
        h.main { h.model.selectTerm(term("2")) }
        h.completeWeek(1, 8)
        h.completeWeek(0, 19)
        h.main { assertEquals(8, h.model.selectedWeekLiveData.value) }
        assertEquals(listOf(3, 8), h.requestedQueries.map { it.week })
    }

    @Test fun manualWeekBeforeActivationSurvivesAnAlreadyValuedDefaultSource() {
        val h = create()
        h.initialWeek = 2
        h.main {
            h.model.selectTerm(term("1"))
            h.model.selectBuilding(building("A"))
            h.model.selectWeek(7)
        }
        h.start()
        h.main { assertEquals(7, h.model.selectedWeekLiveData.value) }
        assertEquals(listOf(7), h.requestedQueries.map { it.week })
    }

    @Test fun manualWeekBeforeFirstDefaultResultRemainsSelected() {
        val h = create()
        h.start()
        h.main {
            h.model.selectTerm(term("1"))
            h.model.selectBuilding(building("A"))
            h.model.selectWeek(7)
        }
        h.completeWeek(0, 2)
        h.main { assertEquals(7, h.model.selectedWeekLiveData.value) }
        assertEquals(listOf(7), h.requestedQueries.map { it.week })
    }

    @Test fun selectingTheCurrentDefaultWeekMakesItAManualChoice() {
        val h = create()
        h.start()
        h.select(week = 3)
        h.main { h.model.selectWeek(3) }
        h.completeWeek(0, 4)
        h.main { assertEquals(3, h.model.selectedWeekLiveData.value) }
        assertEquals(listOf(3), h.requestedQueries.map { it.week })
    }

    @Test fun fastBuildingAndWeekChangesCancelEachReplacedJob() {
        val h = create()
        h.start()
        h.select()
        h.main {
            h.model.selectBuilding(building("B"))
            h.model.selectWeek(9)
        }
        h.flush()
        assertTrue(requireNotNull(h.jobs[0]).isCancelled)
        assertTrue(requireNotNull(h.jobs[1]).isCancelled)
        assertTrue(requireNotNull(h.jobs[2]).isActive)
        val current = success("B week nine")
        h.complete(2, current)
        h.complete(0, success("obsolete building"))
        h.complete(1, success("obsolete week"))
        assertEquals(listOf(current), h.states)
    }

    @Test fun inactiveSelectionChangeCancelsImmediatelyAndDefersReplacement() {
        val h = create()
        h.nonCancellableQueries = true
        h.start()
        h.select()
        h.stop()
        h.main { h.model.selectBuilding(building("B")) }
        assertTrue(requireNotNull(h.jobs[0]).isCancelled)
        assertEquals(1, h.requestedQueries.size)
        h.completeNonCancellable(0, success("obsolete while inactive"))
        h.main { assertNull(h.model.classroomLiveData.value) }
        h.start()
        assertEquals(2, h.requestedQueries.size)
        h.completeNonCancellable(1, success("latest"))
        assertEquals(listOf("latest"), h.states.single().data?.map { it.name })
    }

    @Test fun inactiveTermChangeDoesNotReuseThePreviousWeekOnActivation() {
        val h = create()
        h.start()
        h.select()
        h.stop()
        h.main { h.model.selectTerm(term("2")) }
        assertTrue(requireNotNull(h.jobs[0]).isCancelled)
        h.completeWeek(0, 19)
        h.start()
        assertEquals(1, h.requestedQueries.size)
        h.completeWeek(1, 8)
        assertEquals(listOf(3, 8), h.requestedQueries.map { it.week })
    }

    @Test fun inactiveQueryCompletesWithoutCancellationAndReplaysOnlyTheLatestValue() {
        val h = create()
        h.start()
        h.select()
        h.stop()
        assertFalse(requireNotNull(h.jobs[0]).isCancelled)
        h.complete(0, success("cache").setFromCache(true))
        val remote = success("remote")
        h.complete(0, remote)
        assertTrue(h.states.isEmpty())
        h.start()
        assertEquals(listOf(remote), h.states)
        assertEquals(1, h.requestedQueries.size)
    }

    @Test fun refreshWithUnchangedMetadataForcesExactlyOneAdditionalQuery() {
        val h = create()
        h.buildingResult = DataState(listOf(building("A")))
        h.main {
            h.model.termsLiveData.observe(h.owner) { state -> state.data?.firstOrNull()?.let(h.model::selectTerm) }
            h.model.buildingsLiveData.observe(h.owner) { state -> state.data?.firstOrNull()?.let(h.model::selectBuilding) }
        }
        h.start()
        h.select()
        h.main { h.model.startRefresh() }
        h.flush()
        assertTrue(requireNotNull(h.jobs[0]).isCancelled)
        assertEquals(2, h.requestedQueries.size)
        h.complete(1, success("refreshed"))
        h.main { h.terms.last().value = DataState(listOf(term("1"))) }
        h.flush()
        assertEquals(2, h.requestedQueries.size)
        assertEquals(1, h.weeks.size)
        assertEquals(1, h.buildingRequests)
    }

    @Test fun refreshWithMissingSelectionRunsOnceAfterTheSelectionBecomesComplete() {
        val h = create()
        h.start()
        h.main { repeat(3) { h.model.startRefresh() } }
        assertTrue(h.requestedQueries.isEmpty())
        h.select()
        assertEquals(1, h.requestedQueries.size)
        h.main { h.model.selectBuilding(building("A")); h.model.selectWeek(3) }
        h.flush()
        assertEquals(1, h.requestedQueries.size)
    }

    @Test fun repeatedInactiveRefreshesCancelTheOldJobAndCoalesceOnActivation() {
        val h = create()
        h.start()
        h.select()
        h.stop()
        h.main { repeat(3) { h.model.startRefresh() } }
        assertTrue(requireNotNull(h.jobs[0]).isCancelled)
        assertEquals(1, h.requestedQueries.size)
        h.start()
        assertEquals(2, h.requestedQueries.size)
        h.stop()
        h.start()
        assertEquals(2, h.requestedQueries.size)
    }

    @Test fun retryWhileInactivePreservesSelectionAndRunsOnceOnActivation() {
        val h = create()
        h.start()
        h.select()
        h.main { h.model.selectWeek(9) }
        h.stop()
        h.main { assertTrue(h.model.retryCurrentQuery()) }
        assertTrue(requireNotNull(h.jobs[1]).isCancelled)
        assertEquals(2, h.requestedQueries.size)
        h.start()
        assertEquals(3, h.requestedQueries.size)
        assertEquals(9, h.requestedQueries.last().week)
        assertEquals(1, h.weeks.size)
    }

    @Test fun immediateCacheAndRemoteBothDeliverAfterTheirFlowHasCompleted() {
        val h = create()
        val cached = success("cache").setFromCache(true)
        val remote = success("remote")
        h.queryOverride = { _, _, _ -> EmptyClassroomQueryRequest(flowOf(cached, remote)) }
        h.start()
        h.select()
        assertTrue(requireNotNull(h.jobs[0]).isCompleted)
        assertFalse(requireNotNull(h.jobs[0]).isCancelled)
        assertEquals(listOf(cached, remote), h.states)
        assertEquals(1, h.analytics.count { it.event == UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_SUCCEEDED })
    }

    @Test fun queuedResultCannotPublishOrFinishAnalyticsAfterSelectionChanges() {
        val h = create()
        h.queryOverride = { _, selectedBuilding, _ -> EmptyClassroomQueryRequest(flowOf(success(selectedBuilding.id))) }
        h.start()
        h.main {
            h.model.selectTerm(term("1"))
            h.model.selectBuilding(building("A"))
            h.model.selectWeek(3)
            h.model.selectBuilding(building("B"))
            assertTrue(h.states.isEmpty())
        }
        h.flush()
        assertEquals(listOf("B"), h.states.single().data?.map { it.name })
        assertEquals(1, h.analytics.count { it.event == UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_SUCCEEDED })
        assertEquals(h.analytics[1].operationId, h.analytics.last().operationId)
    }

    @Test fun queuedResultCannotPublishOrFinishAnalyticsAfterClear() {
        val h = create()
        h.queryOverride = { _, _, _ -> EmptyClassroomQueryRequest(flowOf(success("queued"))) }
        h.start()
        h.main {
            h.model.selectTerm(term("1"))
            h.model.selectBuilding(building("A"))
            h.model.selectWeek(3)
            h.store.clear()
        }
        h.flush()
        assertTrue(h.states.isEmpty())
        assertEquals(listOf(UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_STARTED), h.analytics.map { it.event })
    }

    @Test fun immediateAuthenticationResultArrivesAfterTheCallerSetsItsRefreshFlag() {
        val h = create()
        h.start()
        h.select()
        var refreshing = false
        var receivedAfterFlag = false
        h.onState = {
            receivedAfterFlag = refreshing
            refreshing = false
        }
        h.queryOverride = { _, _, _ -> EmptyClassroomQueryRequest(flowOf(DataState(DataState.STATE.NOT_LOGGED_IN))) }
        h.main {
            assertTrue(h.model.retryCurrentQuery())
            refreshing = true
        }
        h.flush()
        assertTrue(receivedAfterFlag)
        assertFalse(refreshing)
        assertEquals(DataState.STATE.NOT_LOGGED_IN, h.states.single().state)
    }

    @Test fun cacheObserverCanReplaceAQueryBeforeItsQueuedRemoteResultIsDelivered() {
        val h = create()
        h.queryOverride = { _, selectedBuilding, _ ->
            if (selectedBuilding.id == "A") {
                EmptyClassroomQueryRequest(flowOf(success("cache A").setFromCache(true), success("remote A")))
            } else {
                EmptyClassroomQueryRequest(flow { kotlinx.coroutines.awaitCancellation() })
            }
        }
        h.onState = { state -> if (state.fromCache) h.model.selectBuilding(building("B")) }
        h.start()
        h.select()
        assertEquals(listOf("A", "B"), h.requestedQueries.map { it.building.id })
        assertEquals(listOf("cache A"), h.states.single().data?.map { it.name })
        assertTrue(requireNotNull(h.jobs[1]).isActive)
        h.main { h.store.clear() }
        assertTrue(requireNotNull(h.jobs[1]).isCancelled)
    }

    @Test fun authenticationObserverRetryKeepsOwnershipOfTheReplacementJob() {
        val h = create()
        h.initialClassroomState = DataState(DataState.STATE.NOT_LOGGED_IN)
        var retried = false
        h.onState = { state ->
            if (!retried && state.state == DataState.STATE.NOT_LOGGED_IN) {
                retried = true
                h.initialClassroomState = null
                h.model.retryCurrentQuery()
            }
        }
        h.start()
        h.select()
        h.flush()
        assertTrue(retried)
        assertEquals(2, h.requestedQueries.size)
        assertTrue(requireNotNull(h.jobs[0]).isCancelled)
        assertTrue(requireNotNull(h.jobs[1]).isActive)
        h.main { h.store.clear() }
        assertTrue(requireNotNull(h.jobs[1]).isCancelled)
        assertFalse(h.classroomSources[1].hasObservers())
    }

    @Test fun requestFactoryReentryCannotReplaceTheNewerJob() {
        val h = create()
        var reentered = false
        h.onCreateRequest = {
            if (!reentered) {
                reentered = true
                h.model.selectBuilding(building("B"))
            }
        }
        h.start()
        h.select()
        assertEquals(listOf("A", "B"), h.requestedQueries.map { it.building.id })
        assertFalse(h.jobs.containsKey(0))
        assertTrue(requireNotNull(h.jobs[1]).isActive)
        h.main { h.store.clear() }
        assertTrue(requireNotNull(h.jobs[1]).isCancelled)
    }

    @Test fun analyticsBeginReentryCannotStartTheObsoleteRequest() {
        val h = create()
        var reentered = false
        h.onBegin = {
            if (!reentered) {
                reentered = true
                h.model.selectBuilding(building("B"))
            }
        }
        h.start()
        h.select()
        assertEquals(listOf("B"), h.requestedQueries.map { it.building.id })
        assertTrue(requireNotNull(h.jobs[0]).isActive)
    }

    @Test fun selectionObserverReentryKeepsTheLatestTermAndDefaultWeekSource() {
        val h = create()
        val observer = Observer<TermItem?> { selected ->
            if (selected?.termCode == "2") h.model.selectTerm(term("3"))
        }
        h.main { h.model.selectedTermLiveData.observe(h.owner, observer) }
        h.start()
        h.select()
        h.main { h.model.selectTerm(term("2")) }
        h.flush()
        assertEquals(listOf("1", "3"), h.weekTerms.map { it?.termCode })
        h.completeWeek(1, 8)
        assertEquals(listOf("1", "3"), h.requestedQueries.map { it.term.termCode })
    }

    @Test fun nonCancellableOldQueryCannotDeliverOrCompleteAnalytics() {
        val h = create()
        h.nonCancellableQueries = true
        h.start()
        h.select()
        h.main { h.model.selectBuilding(building("B")) }
        h.flush()
        h.completeNonCancellable(1, success("current"))
        h.completeNonCancellable(0, success("obsolete"))
        assertEquals(listOf("current"), h.states.single().data?.map { it.name })
        assertEquals(2, h.analytics.count { it.event == UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_STARTED })
        assertEquals(1, h.analytics.count { it.event == UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_SUCCEEDED })
        assertEquals(0, h.analytics.count { it.event == UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_FAILED })
    }

    @Test fun clearCancelsANonCancellableQueryAndDropsItsLateCompletion() {
        val h = create()
        h.nonCancellableQueries = true
        h.start()
        h.select()
        val stateAtClear = h.model.classroomLiveData.value
        h.main { h.store.clear() }
        assertTrue(requireNotNull(h.jobs[0]).isCancelled)
        h.completeNonCancellable(0, success("after clear"))
        h.main { assertSame(stateAtClear, h.model.classroomLiveData.value) }
        assertEquals(listOf(UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_STARTED), h.analytics.map { it.event })
    }

    @Test fun cancellationIsNotPublishedAsFailureAndExplicitRetryCanRecover() {
        val h = create()
        h.queryOverride = { _, _, _ -> EmptyClassroomQueryRequest(flow { throw CancellationException("synthetic cancellation") }) }
        h.start()
        h.select()
        assertTrue(h.states.isEmpty())
        assertEquals(listOf(UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_STARTED), h.analytics.map { it.event })
        val result = success("retry succeeded")
        h.queryOverride = { _, _, _ -> EmptyClassroomQueryRequest(flowOf(result)) }
        h.main { assertTrue(h.model.retryCurrentQuery()) }
        h.flush()
        assertEquals(listOf(result), h.states)
        assertEquals(0, h.analytics.count { it.event == UsageAnalyticsEvent.EMPTY_ROOM_SEARCH_FAILED })
    }
}
