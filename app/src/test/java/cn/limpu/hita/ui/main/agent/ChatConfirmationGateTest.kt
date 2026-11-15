package cn.limpu.hita.ui.main.agent

import cn.limpu.hita.agent.tools.ConfirmationGate
import cn.limpu.hita.agent.tools.ConfirmationRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 确认门的行为契约：有界、可取消、会话隔离。
 *
 * 这些是最容易写出「挂起泄漏」的地方，因此即使没有 UI 也要在 JVM 上验证。
 */
class ChatConfirmationGateTest {

    private val request = ConfirmationRequest(
        title = "添加到课表",
        detailLines = listOf("课程：高等数学"),
    )

    @Test
    fun `approve resolves the pending request with APPROVED`() = runBlocking {
        val gate = ChatConfirmationGate()
        val decision = async { gate.request(request) }
        // 等 gate 真正登记待确认，再模拟用户点击。
        while (gate.state.value == null) kotlinx.coroutines.yield()
        assertNotNull(gate.state.value)
        gate.approve()

        assertEquals(ConfirmationGate.Decision.APPROVED, decision.await())
        assertNull(gate.state.value)
    }

    @Test
    fun `reject resolves with REJECTED and clears the dialog state`() = runBlocking {
        val gate = ChatConfirmationGate()
        val decision = async { gate.request(request) }
        while (gate.state.value == null) kotlinx.coroutines.yield()
        gate.reject()

        assertEquals(ConfirmationGate.Decision.REJECTED, decision.await())
        assertNull(gate.state.value)
    }

    @Test
    fun `a second request while one is pending is refused immediately`() = runBlocking {
        val gate = ChatConfirmationGate()
        val first = async { gate.request(request) }
        while (gate.state.value == null) kotlinx.coroutines.yield()

        // 不排队、不覆盖：第二个写工具直接拿到 UNAVAILABLE。
        assertEquals(ConfirmationGate.Decision.UNAVAILABLE, gate.request(request))
        gate.approve()
        assertEquals(ConfirmationGate.Decision.APPROVED, first.await())
    }

    @Test
    fun `cancelAll releases a waiting tool instead of hanging`() = runBlocking {
        val gate = ChatConfirmationGate()
        gate.sessionIdProvider = { "session-a" }
        val decision = async { gate.request(request) }
        while (gate.state.value == null) kotlinx.coroutines.yield()

        gate.cancelAll()
        assertEquals(ConfirmationGate.Decision.REJECTED, decision.await())
    }

    @Test
    fun `cancelAll for another session leaves the pending request alone`() = runBlocking {
        val gate = ChatConfirmationGate()
        gate.sessionIdProvider = { "session-a" }
        val decision = async { gate.request(request) }
        while (gate.state.value == null) kotlinx.coroutines.yield()

        gate.cancelAll(sessionId = "session-b")
        assertNotNull(gate.state.value)

        gate.approve()
        assertEquals(ConfirmationGate.Decision.APPROVED, decision.await())
    }
}
