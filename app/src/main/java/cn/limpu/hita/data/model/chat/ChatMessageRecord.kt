package cn.limpu.hita.data.model.chat

import cn.limpu.hita.data.model.resource.AgentResourceCard

/** Persisted chat content; presentation state such as placeholders belongs to the UI. */
data class ChatMessageRecord(
    val role: Role,
    val text: String,
    val resourceCards: List<AgentResourceCard> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis(),
) {
    enum class Role { USER, ASSISTANT, TRACE }
}
