package cn.limpu.hita.ui.main.agent

import cn.limpu.hita.agent.tools.ConfirmationGate
import cn.limpu.hita.agent.tools.ConfirmationRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
    private val ownerA = ChatConfirmationGate.Owner("session-a", 1)
    private val ownerB = ChatConfirmationGate.Owner("session-b", 2)

    @Test
    fun `approve resolves the pending request with APPROVED`() = runBlocking {
        val gate = ChatConfirmationGate()
        val decision = async { gate.bind(ownerA).request(request) }
        // 等 gate 真正登记待确认，再模拟用户点击。
        while (gate.state.value == null) kotlinx.coroutines.yield()
        assertNotNull(gate.state.value)
        gate.resolve(checkNotNull(gate.state.value), "session-a", approved = true)

        assertEquals(ConfirmationGate.Decision.APPROVED, decision.await())
        assertNull(gate.state.value)
    }

    @Test
    fun `reject resolves with REJECTED and clears the dialog state`() = runBlocking {
        val gate = ChatConfirmationGate()
        val decision = async { gate.bind(ownerA).request(request) }
        while (gate.state.value == null) kotlinx.coroutines.yield()
        gate.resolve(checkNotNull(gate.state.value), "session-a", approved = false)

        assertEquals(ConfirmationGate.Decision.REJECTED, decision.await())
        assertNull(gate.state.value)
    }

    @Test
    fun `a second request while one is pending is refused immediately`() = runBlocking {
        val gate = ChatConfirmationGate()
        val first = async { gate.bind(ownerA).request(request) }
        while (gate.state.value == null) kotlinx.coroutines.yield()

        // 不排队、不覆盖：第二个写工具直接拿到 UNAVAILABLE。
        assertEquals(ConfirmationGate.Decision.UNAVAILABLE, gate.bind(ownerB).request(request))
        gate.resolve(checkNotNull(gate.state.value), "session-a", approved = true)
        assertEquals(ConfirmationGate.Decision.APPROVED, first.await())
    }

    @Test
    fun `cancelAll releases a waiting tool instead of hanging`() = runBlocking {
        val gate = ChatConfirmationGate()
        val decision = async { gate.bind(ownerA).request(request) }
        while (gate.state.value == null) kotlinx.coroutines.yield()

        gate.cancelAll()
        assertEquals(ConfirmationGate.Decision.REJECTED, decision.await())
    }

    @Test
    fun `cancelAll for another session leaves the pending request alone`() = runBlocking {
        val gate = ChatConfirmationGate()
        val decision = async { gate.bind(ownerA).request(request) }
        while (gate.state.value == null) kotlinx.coroutines.yield()

        gate.cancelAll(sessionId = "session-b")
        assertNotNull(gate.state.value)

        gate.resolve(checkNotNull(gate.state.value), "session-a", approved = true)
        assertEquals(ConfirmationGate.Decision.APPROVED, decision.await())
    }

    @Test
    fun `only the owning session and exact prompt can decide once`() = runBlocking {
        val gate = ChatConfirmationGate()
        val channel = gate.bind(ownerA)
        val first = async { channel.request(request) }
        while (gate.state.value == null) kotlinx.coroutines.yield()
        val old = checkNotNull(gate.state.value)
        assertEquals(ownerA, old.owner)
        assertEquals(false, gate.resolve(old, "session-b", approved = true))
        assertEquals(true, gate.resolve(old, "session-a", approved = true))
        assertEquals(ConfirmationGate.Decision.APPROVED, first.await())

        val second = async { channel.request(request) }
        while (gate.state.value == null) kotlinx.coroutines.yield()
        assertEquals(false, gate.resolve(old, "session-a", approved = true))
        assertEquals(true, gate.resolve(checkNotNull(gate.state.value), "session-a", approved = false))
        assertEquals(ConfirmationGate.Decision.REJECTED, second.await())
    }

    @Test
    fun `cancelled and expired prompts cannot be approved`() = runBlocking {
        val gate = ChatConfirmationGate()
        val call = async { gate.bind(ownerA).request(request) }
        while (gate.state.value == null) kotlinx.coroutines.yield()
        val pending = checkNotNull(gate.state.value)
        call.cancelAndJoin()
        assertNull(gate.state.value)
        assertEquals(false, gate.resolve(pending, "session-a", approved = true))

        val expiringGate = ChatConfirmationGate(timeoutMs = 20)
        assertEquals(ConfirmationGate.Decision.UNAVAILABLE, expiringGate.bind(ownerA).request(request))
        assertNull(expiringGate.state.value)
    }
}
