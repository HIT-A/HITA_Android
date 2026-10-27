package cn.limpu.hita.ui.main.agent

/** Main-thread routing of asynchronous work by its original session. */
internal class ChatSessionDelivery {
    enum class Destination { CURRENT, BACKGROUND, DELETED }
    enum class RestoreDecision { APPLY, RELOAD, OBSOLETE }
    data class RestoreTicket(val sessionId: String, val selection: Long, val revision: Long)

    var currentSessionId: String? = null
        private set
    private var selection = 0L
    private val revisions = mutableMapOf<String, Long>()
    private val deleted = mutableSetOf<String>()

    fun select(sessionId: String) {
        check(sessionId !in deleted)
        currentSessionId = sessionId
        selection++
    }

    fun isDeleted(sessionId: String): Boolean = sessionId in deleted

    fun receive(sessionId: String): Destination {
        if (isDeleted(sessionId)) return Destination.DELETED
        revisions[sessionId] = (revisions[sessionId] ?: 0L) + 1L
        return if (sessionId == currentSessionId) Destination.CURRENT else Destination.BACKGROUND
    }

    fun beginRestore(sessionId: String) = RestoreTicket(sessionId, selection, revisions[sessionId] ?: 0L)

    fun restoration(ticket: RestoreTicket): RestoreDecision = when {
        isDeleted(ticket.sessionId) || ticket.sessionId != currentSessionId || ticket.selection != selection ->
            RestoreDecision.OBSOLETE
        ticket.revision != (revisions[ticket.sessionId] ?: 0L) -> RestoreDecision.RELOAD
        else -> RestoreDecision.APPLY
    }

    fun delete(sessionId: String) {
        deleted.add(sessionId)
        revisions.remove(sessionId)
    }
}

/** Call in the same transaction as the session existence check and message insert. */
internal fun persistChatMessage(
    sessionId: String,
    message: AgentChatMessage,
    sessionExists: (String) -> Boolean,
    save: (String, AgentChatMessage) -> Unit,
    firstUserText: (String) -> String?,
    updateTitle: (String, String) -> Unit,
): Boolean {
    return cn.limpu.hita.data.repository.chat.persistChatRecord(
        sessionId, message.toRecord(), sessionExists,
        save = { id, _ -> save(id, message) },
        firstUserText = firstUserText,
        updateTitle = updateTitle,
    )
}
