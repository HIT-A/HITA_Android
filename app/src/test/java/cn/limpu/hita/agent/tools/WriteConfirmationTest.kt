package cn.limpu.hita.agent.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写操作确认的决策必须只有一处解释：
 * 批准才执行、拒绝不执行、无 UI 时不执行。
 */
class WriteConfirmationTest {

    private val request = ConfirmationRequest(
        title = "添加日程",
        detailLines = listOf("名称：小组会议"),
    )

    @Test
    fun `approved decision proceeds`() = runBlocking {
        val decision = WriteConfirmation.decide(
            gate = ConfirmationGate { ConfirmationGate.Decision.APPROVED },
            request = request,
            pendingText = "pending",
            cancelledText = "cancelled",
        )
        assertEquals(WriteConfirmation.Decision.Proceed, decision)
    }

    @Test
    fun `rejected decision cancels instead of proceeding`() = runBlocking {
        val decision = WriteConfirmation.decide(
            gate = ConfirmationGate { ConfirmationGate.Decision.REJECTED },
            request = request,
            pendingText = "pending",
            cancelledText = "cancelled",
        )
        assertTrue(decision is WriteConfirmation.Decision.Halt)
        val outcome = (decision as WriteConfirmation.Decision.Halt).outcome
        assertTrue(outcome is ReActToolOutcome.Cancelled)
        assertEquals("cancelled", outcome.observation)
    }

    @Test
    fun `missing ui never proceeds and reports pending`() = runBlocking {
        val decision = WriteConfirmation.decide(
            gate = ConfirmationGate.unavailable,
            request = request,
            pendingText = "pending",
            cancelledText = "cancelled",
        )
        assertTrue(decision is WriteConfirmation.Decision.Halt)
        val outcome = (decision as WriteConfirmation.Decision.Halt).outcome
        assertTrue(outcome is ReActToolOutcome.NeedsConfirmation)
        assertEquals("pending", outcome.observation)
        assertEquals(request, (outcome as ReActToolOutcome.NeedsConfirmation).request)
    }

    @Test
    fun `network uncertainty is treated as unknown not retryable failure`() {
        assertTrue(WriteConfirmation.isUncertainFailure("timeout while reading response"))
        assertTrue(WriteConfirmation.isUncertainFailure("HTTP 503: service unavailable"))
        assertTrue(WriteConfirmation.isUncertainFailure("connection reset by peer"))
    }

    @Test
    fun `definite business failure is not uncertain`() {
        assertFalse(WriteConfirmation.isUncertainFailure("HTTP 400: invalid course code"))
        assertFalse(WriteConfirmation.isUncertainFailure("课程代码不存在"))
        assertFalse(WriteConfirmation.isUncertainFailure(null))
        assertFalse(WriteConfirmation.isUncertainFailure(""))
    }
}
