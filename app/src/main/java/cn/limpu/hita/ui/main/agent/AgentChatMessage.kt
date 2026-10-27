package cn.limpu.hita.ui.main.agent

import cn.limpu.hita.data.model.resource.AgentResourceCard

data class AgentChatMessage(
    val role: Role,
    val text: String,
    val thinking: String? = null,
    val isThinkingExpanded: Boolean = false,
    val areResourceCardsExpanded: Boolean = true,
    val isPlaceholder: Boolean = false,
    val resourceCards: List<AgentResourceCard> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis(),
) {
    enum class Role {
        USER,
        ASSISTANT,
        TRACE,
    }
}

internal fun AgentChatMessage.toRecord() = cn.limpu.hita.data.model.chat.ChatMessageRecord(
    role = cn.limpu.hita.data.model.chat.ChatMessageRecord.Role.valueOf(role.name),
    text = text,
    resourceCards = resourceCards.toList(),
    timestampMs = timestampMs,
)

internal fun cn.limpu.hita.data.model.chat.ChatMessageRecord.toUiMessage() = AgentChatMessage(
    role = AgentChatMessage.Role.valueOf(role.name),
    text = text,
    resourceCards = resourceCards,
    timestampMs = timestampMs,
)
