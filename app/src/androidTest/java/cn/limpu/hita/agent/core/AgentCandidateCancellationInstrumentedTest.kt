package cn.limpu.hita.agent.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.model.resource.CourseResourceItem
import cn.limpu.hita.utils.CourseResourceLinker
import com.limpu.component.data.DataState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real resolver aggregation/ranking and main-queue cancellation with finite source fixtures. */
@RunWith(AndroidJUnit4::class)
class AgentCandidateCancellationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val answers = mutableMapOf<String, (DataState<List<CourseResourceItem>>) -> Unit>()
    private val results = mutableListOf<List<CourseResourceItem>>()
    private val exact = CourseResourceItem(repoName = "MATH1001", courseCode = "MATH1001", courseName = "高等数学")
    private val other = CourseResourceItem(repoName = "other", courseName = "高等数学")

    private fun start(onResult: (List<CourseResourceItem>) -> Unit = results::add) =
        CourseResourceLinker.resolveCandidatesWithSource("MATH1001", "高等数学", "SZ", { query, campus, callback ->
            assertEquals("SZ", campus)
            answers[query] = callback
        }, onResult)

    @Test fun outOfOrderAndDuplicateRepliesKeepRankingAndOneCompletion() {
        instrumentation.runOnMainSync {
            start()
            answers.getValue("高等数学")(DataState(listOf(other, exact)))
            answers.getValue("高等数学")(DataState(listOf(CourseResourceItem(repoName = "duplicate"))))
            assertTrue(results.isEmpty())
            answers.getValue("MATH1001")(DataState(listOf(exact)))
        }
        instrumentation.waitForIdleSync()
        assertEquals(listOf("MATH1001", "other"), results.single().map { it.repoName })
    }

    @Test fun partialFailureStillDeliversSuccessfulCandidates() {
        instrumentation.runOnMainSync {
            start()
            answers.getValue("MATH1001")(DataState(DataState.STATE.FETCH_FAILED))
            answers.getValue("高等数学")(DataState(listOf(other)))
        }
        instrumentation.waitForIdleSync()
        assertEquals(listOf(other), results.single())
    }

    @Test fun cancellingQueuedCompletionSuppressesItAndAllLateReplies() {
        instrumentation.runOnMainSync {
            val request = start()
            answers.getValue("MATH1001")(DataState(listOf(exact)))
            answers.getValue("高等数学")(DataState(listOf(other)))
            request.cancel()
            answers.values.forEach { it(DataState(listOf(exact))) }
        }
        instrumentation.waitForIdleSync()
        assertTrue(results.isEmpty())
    }

    @Test fun cancellationAfterSuccessfulDeliveryCannotEraseItsSnapshot() {
        var request = AgentCancellation.NONE
        instrumentation.runOnMainSync {
            request = start { result -> request.cancel(); results += result }
            answers.getValue("MATH1001")(DataState(listOf(exact)))
            answers.getValue("高等数学")(DataState(DataState.STATE.FETCH_FAILED))
        }
        instrumentation.waitForIdleSync()
        request.cancel()
        assertEquals(listOf(exact), results.single())
    }
}
