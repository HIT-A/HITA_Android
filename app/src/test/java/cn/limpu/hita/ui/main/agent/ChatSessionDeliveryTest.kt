package cn.limpu.hita.ui.main.agent

import org.junit.Assert.*
import org.junit.Test

class ChatSessionDeliveryTest {
    private class Store {
        val messages = mutableMapOf("a" to mutableListOf(user("question A")), "b" to mutableListOf(user("question B")))
        val titles = mutableMapOf<String, String>()
        fun persist(id: String, message: AgentChatMessage): Boolean = persistChatMessage(
            id, message, messages::containsKey,
            { sid, value -> messages.getValue(sid).add(value) },
            { sid -> messages.getValue(sid).firstOrNull { it.role == AgentChatMessage.Role.USER }?.text },
            { sid, title -> titles[sid] = title },
        )
    }

    @Test fun `successful and failed background results stay with their original session`() {
        for (text in listOf("answer A", "operation failed")) {
            val state = ChatSessionDelivery()
            val store = Store()
            state.select("a")
            val requestSession = state.currentSessionId!!
            state.select("b")
            assertEquals(ChatSessionDelivery.Destination.BACKGROUND, state.receive(requestSession))
            assertTrue(store.persist(requestSession, assistant(text)))
            assertEquals(listOf("question A", text), store.messages.getValue("a").map { it.text })
            assertEquals(listOf("question B"), store.messages.getValue("b").map { it.text })
            assertEquals("question A", store.titles["a"])
            assertNull(store.titles["b"])
        }
    }

    @Test fun `deleting session rejects late result and any already queued save`() {
        val state = ChatSessionDelivery()
        val store = Store()
        state.select("a")
        val pendingSave = { store.persist("a", assistant("late response")) }
        state.delete("a")
        store.messages.remove("a")
        assertEquals(ChatSessionDelivery.Destination.DELETED, state.receive("a"))
        assertFalse(pendingSave())
        assertFalse(store.messages.containsKey("a"))
        assertFalse(store.titles.containsKey("a"))
    }

    @Test fun `message after a database read forces reread instead of dropping the response`() {
        val state = ChatSessionDelivery()
        state.select("a")
        val staleRead = state.beginRestore("a")
        assertEquals(ChatSessionDelivery.Destination.CURRENT, state.receive("a"))
        assertEquals(ChatSessionDelivery.RestoreDecision.RELOAD, state.restoration(staleRead))
        val reread = state.beginRestore("a")
        assertEquals(ChatSessionDelivery.RestoreDecision.APPLY, state.restoration(reread))
    }

    @Test fun `old restore cannot replace new session even after switching back`() {
        val state = ChatSessionDelivery()
        state.select("a")
        val oldA = state.beginRestore("a")
        state.select("b")
        val oldB = state.beginRestore("b")
        state.select("a")
        assertEquals(ChatSessionDelivery.RestoreDecision.OBSOLETE, state.restoration(oldA))
        assertEquals(ChatSessionDelivery.RestoreDecision.OBSOLETE, state.restoration(oldB))
        assertEquals(ChatSessionDelivery.RestoreDecision.APPLY, state.restoration(state.beginRestore("a")))
    }

    @Test fun `unrelated background response does not invalidate current session read`() {
        val state = ChatSessionDelivery()
        state.select("b")
        val readB = state.beginRestore("b")
        state.receive("a")
        assertEquals(ChatSessionDelivery.RestoreDecision.APPLY, state.restoration(readB))
    }

    @Test fun `deleted session cannot be restored while another stays usable`() {
        val state = ChatSessionDelivery()
        state.select("a")
        val readA = state.beginRestore("a")
        state.delete("a")
        assertEquals(ChatSessionDelivery.RestoreDecision.OBSOLETE, state.restoration(readA))
        state.select("b")
        assertEquals(ChatSessionDelivery.Destination.CURRENT, state.receive("b"))
    }

    @Test fun `title is derived from target first user message including truncation`() {
        val store = Store()
        val longTitle = "01234567890123456789012345"
        store.messages["a"] = mutableListOf(user(longTitle), user("second question"))
        assertTrue(store.persist("a", assistant("answer")))
        assertEquals(longTitle.take(20) + "…", store.titles["a"])
    }

    companion object {
        private fun user(text: String) = AgentChatMessage(AgentChatMessage.Role.USER, text)
        private fun assistant(text: String) = AgentChatMessage(AgentChatMessage.Role.ASSISTANT, text)
    }
}
