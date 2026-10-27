package cn.limpu.hita.agent.llm

import android.app.Application
import cn.limpu.hita.agent.core.AgentProvider
import cn.limpu.hita.agent.core.AgentTraceEvent
import cn.limpu.hita.agent.timetable.TimetableAgentInput
import cn.limpu.hita.agent.timetable.TimetableAgentOutput
import cn.limpu.hita.data.repository.EASRepository
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ChatMediaContent(val base64: String, val mimeType: String)

interface ChatRequestRunner {
    /** Runs in the caller's Job; trace callbacks are delivered on Main. */
    suspend fun run(
        history: List<ChatMessage>,
        media: ChatMediaContent?,
        provider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>,
        onTrace: (AgentTraceEvent) -> Unit,
    ): LlmChatResult
}

class LlmChatRequestRunner @Inject constructor(
    private val application: Application,
    private val easRepository: EASRepository,
) : ChatRequestRunner {
    override suspend fun run(
        history: List<ChatMessage>,
        media: ChatMediaContent?,
        provider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>,
        onTrace: (AgentTraceEvent) -> Unit,
    ): LlmChatResult {
        var result: LlmChatResult? = null
        if (media == null) {
            LlmChatService.chat(history, null, application, easRepository, provider, onTrace) { result = it }
        } else {
            LlmChatService.chatWithAttachment(
                history, media.base64, media.mimeType, null, application, easRepository, provider,
                onTrace, onResult = { result = it },
            )
        }
        currentCoroutineContext().ensureActive()
        return result ?: LlmChatResult.Error("调用失败")
    }
}
