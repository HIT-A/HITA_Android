package cn.limpu.hita.agent.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReActToolOutcomeTest {

    @Test
    fun `observation is exposed for every variant`() {
        assertEquals("ok", ReActToolOutcome.Succeeded("ok").observation)
        assertEquals("bad", ReActToolOutcome.Failed("bad", retryable = true).observation)
        assertEquals("maybe", ReActToolOutcome.OutcomeUnknown("maybe").observation)
        assertEquals("stop", ReActToolOutcome.Cancelled("stop").observation)
        assertEquals(
            "confirm",
            ReActToolOutcome.NeedsConfirmation(
                observation = "confirm",
                request = ConfirmationRequest(title = "t", detailLines = emptyList()),
            ).observation,
        )
    }

    @Test
    fun `status names are stable for analytics`() {
        assertEquals("succeeded", ReActToolOutcome.statusName(ReActToolOutcome.Succeeded("x")))
        assertEquals("failure", ReActToolOutcome.statusName(ReActToolOutcome.Failed("x")))
        assertEquals("unknown", ReActToolOutcome.statusName(ReActToolOutcome.OutcomeUnknown("x")))
        assertEquals("cancelled", ReActToolOutcome.statusName(ReActToolOutcome.Cancelled("x")))
        assertEquals(
            "pending_confirmation",
            ReActToolOutcome.statusName(
                ReActToolOutcome.NeedsConfirmation("x", ConfirmationRequest("t", emptyList()))
            ),
        )
    }

    @Test
    fun `legacy string results are never reported as success`() {
        assertTrue(ReActToolOutcome.fromLegacy("评价提交成功") is ReActToolOutcome.OutcomeUnknown)
    }

    @Test
    fun `legacy null becomes empty unknown`() {
        val outcome = ReActToolOutcome.fromLegacy(null)
        assertEquals("", outcome.observation)
        assertTrue(outcome is ReActToolOutcome.OutcomeUnknown)
    }

    @Test
    fun `unavailable gate rejects writes when no ui subscribes`() = runBlocking {
        assertEquals(
            ConfirmationGate.Decision.UNAVAILABLE,
            ConfirmationGate.unavailable.request(ConfirmationRequest("t", emptyList())),
        )
    }
}
