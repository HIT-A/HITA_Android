package cn.limpu.hita.feature.timetable.display

import cn.limpu.hita.data.model.timetable.DisplayScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class DisplayScopeCoordinatorTest {
    @Test
    fun selectionPersistsBeforeRefreshRequestsOnTheCallingThread() {
        val scope = DisplayScope.Single("table")
        val caller = Thread.currentThread()
        val calls = mutableListOf<String>()
        var persisted: DisplayScope? = null
        val coordinator = DisplayScopeCoordinator(
            persistSelection = {
                assertSame(caller, Thread.currentThread())
                assertSame(scope, it)
                persisted = it
                calls += "persist"
            },
            refreshWidgets = {
                assertSame(caller, Thread.currentThread())
                assertSame(scope, persisted)
                calls += "widgets"
            },
            scheduleLiveCourse = {
                assertSame(caller, Thread.currentThread())
                assertSame(scope, persisted)
                calls += "live-course"
            },
        )

        coordinator.select(scope)

        assertEquals(listOf("persist", "widgets", "live-course"), calls)
    }

    @Test
    fun selectingTheSameScopeAgainRepeatsTheEntireOperation() {
        val scope = DisplayScope.Group("group")
        val calls = mutableListOf<String>()
        val coordinator = DisplayScopeCoordinator(
            persistSelection = {
                assertSame(scope, it)
                calls += "persist"
            },
            refreshWidgets = { calls += "widgets" },
            scheduleLiveCourse = { calls += "live-course" },
        )

        coordinator.select(scope)
        coordinator.select(scope)

        assertEquals(
            listOf("persist", "widgets", "live-course", "persist", "widgets", "live-course"),
            calls,
        )
    }

    @Test
    fun persistenceFailureStopsBothRefreshRequestsAndPropagatesTheOriginalError() {
        assertFailureStopsAt("persist", listOf("persist"))
    }

    @Test
    fun widgetFailureStopsLiveCourseSchedulingAndPropagatesTheOriginalError() {
        assertFailureStopsAt("widgets", listOf("persist", "widgets"))
    }

    @Test
    fun liveCourseFailurePropagatesTheOriginalErrorWithoutRetryingEarlierSteps() {
        assertFailureStopsAt("live-course", listOf("persist", "widgets", "live-course"))
    }

    private fun assertFailureStopsAt(failingStep: String, expectedCalls: List<String>) {
        val failure = IllegalStateException("$failingStep failed")
        val calls = mutableListOf<String>()
        fun perform(step: String) {
            calls += step
            if (step == failingStep) throw failure
        }
        val coordinator = DisplayScopeCoordinator(
            persistSelection = { perform("persist") },
            refreshWidgets = { perform("widgets") },
            scheduleLiveCourse = { perform("live-course") },
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            coordinator.select(DisplayScope.Single("table"))
        }

        assertSame(failure, thrown)
        assertEquals(expectedCalls, calls)
    }
}
