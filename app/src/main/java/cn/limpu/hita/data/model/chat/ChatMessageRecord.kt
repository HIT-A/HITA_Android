package cn.limpu.hita.data.model.chat

import cn.limpu.hita.data.model.resource.AgentResourceCard
import java.util.UUID

/** Persisted chat content; presentation state such as placeholders belongs to the UI. */
data class ChatMessageRecord(
    val role: Role,
    val text: String,
    val resourceCards: List<AgentResourceCard> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis(),
    val id: String = UUID.randomUUID().toString(),
) {
    enum class Role { USER, ASSISTANT, TRACE }
}
