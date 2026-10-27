package cn.limpu.hita.ui.main.agent

import android.app.Application
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.R
import cn.limpu.hita.agent.attachment.ChatAttachmentLoader
import cn.limpu.hita.agent.attachment.ChatAttachmentResult
import cn.limpu.hita.agent.core.AgentProvider
import cn.limpu.hita.agent.core.AgentTraceEvent
import cn.limpu.hita.agent.llm.ChatMediaContent
import cn.limpu.hita.agent.llm.ChatMessage
import cn.limpu.hita.agent.llm.ChatRequestRunner
import cn.limpu.hita.agent.llm.LlmChatResult
import cn.limpu.hita.agent.timetable.TimetableAgentInput
import cn.limpu.hita.agent.timetable.TimetableAgentOutput
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.model.chat.ChatSession
import cn.limpu.hita.data.repository.chat.ChatSessionRepository
import cn.limpu.hita.utils.LogUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal fun nextSessionIdAfterDeletion(
    deletedSessionId: String,
    currentSessionId: String?,
    remainingLatestSessionId: String?,
): String? = if (deletedSessionId == currentSessionId) remainingLatestSessionId else currentSessionId

/** Main-thread session selection and request delivery; stores and parsers own their work. */
@HiltViewModel
class AgentChatViewModel @Inject constructor(
    private val application: Application,
    private val repository: ChatSessionRepository,
    private val attachmentLoader: ChatAttachmentLoader,
    private val chatRunner: ChatRequestRunner,
) : ViewModel() {
    private val delivery = ChatSessionDelivery()
    private var restoringSessionId: String? = null
    private var initializingSession = false
    private var cleared = false
    private var nextRequestId = 0L
    private var messageList: List<AgentChatMessage> = emptyList()
    private val histories = mutableMapOf<String, MutableList<ChatMessage>>()
    private data class Request(val sessionId: String, val id: Long)
    private val jobs = mutableMapOf<Request, Job>()
    private val placeholders = mutableMapOf<Request, AgentChatMessage>()

    private val _messages = MutableLiveData<List<AgentChatMessage>>(emptyList())
    val messages: LiveData<List<AgentChatMessage>> = _messages
    private val _status = MutableLiveData("")
    val status: LiveData<String> = _status
    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading
    private val _pendingAttachment = MutableLiveData<Uri?>(null)
    val pendingAttachment: LiveData<Uri?> = _pendingAttachment
    val sessions = repository.sessions
    val currentSessionId: String? get() = delivery.currentSessionId

    private fun publishMessages() { if (!cleared) _messages.value = messageList }
    private fun publishStatus(text: String) { if (!cleared) _status.value = text }
    private fun publishLoading() {
        if (!cleared) _isLoading.value = initializingSession || restoringSessionId == currentSessionId && currentSessionId != null ||
            jobs.keys.any { it.sessionId == currentSessionId }
    }

    fun setPendingAttachment(uri: Uri?) { if (!cleared) _pendingAttachment.value = uri }
    fun clearPendingAttachment() { if (!cleared) _pendingAttachment.value = null }

    fun addMessage(message: AgentChatMessage, targetSessionId: String? = null) {
        val id = targetSessionId ?: currentSessionId ?: return
        if (cleared) return
        when (delivery.receive(id)) {
            ChatSessionDelivery.Destination.DELETED -> return
            ChatSessionDelivery.Destination.CURRENT -> { messageList = messageList + message; publishMessages() }
            ChatSessionDelivery.Destination.BACKGROUND -> Unit
        }
        watchWrite(repository.append(id, message.toRecord()), id)
    }

    private fun watchWrite(write: Deferred<*>, sessionId: String? = currentSessionId) {
        viewModelScope.launch {
            try {
                if (write.await() == false && sessionId == currentSessionId && sessionId != null && !delivery.isDeleted(sessionId)) {
                    publishStatus(application.getString(R.string.chat_save_failed))
                }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                LogUtils.e("Chat persistence failed", error)
                if (sessionId == currentSessionId && sessionId != null && !delivery.isDeleted(sessionId)) {
                    publishStatus(application.getString(R.string.chat_save_failed))
                }
            }
        }
    }

    fun createNewSession() {
        if (cleared) return
        val session = ChatSession()
        delivery.select(session.id)
        restoringSessionId = null
        initializingSession = false
        histories[session.id] = mutableListOf()
        messageList = emptyList()
        publishMessages()
        publishStatus("")
        publishLoading()
        // Enqueue synchronously before a subsequent addMessage can enqueue its insert.
        watchWrite(repository.create(session))
    }

    fun switchToSession(sessionId: String) {
        if (cleared || sessionId == currentSessionId || delivery.isDeleted(sessionId)) return
        delivery.select(sessionId)
        restoringSessionId = sessionId
        messageList = emptyList()
        publishMessages()
        publishStatus("")
        publishLoading()
        restoreSession(sessionId)
    }

    private fun restoreSession(sessionId: String) {
        val ticket = delivery.beginRestore(sessionId)
        val read = repository.restore(sessionId)
        viewModelScope.launch {
            try {
                val restored = read.await().map { it.toUiMessage() }
                when (delivery.restoration(ticket)) {
                    ChatSessionDelivery.RestoreDecision.OBSOLETE -> return@launch
                    ChatSessionDelivery.RestoreDecision.RELOAD -> {
                        // append was already enqueued; the next serial read includes the arriving reply.
                        restoreSession(sessionId)
                        return@launch
                    }
                    ChatSessionDelivery.RestoreDecision.APPLY -> Unit
                }
                restoringSessionId = null
                histories[sessionId] = restored.filter { it.role != AgentChatMessage.Role.TRACE }.map {
                    ChatMessage(if (it.role == AgentChatMessage.Role.USER) "user" else "assistant", it.text)
                }.toMutableList()
                messageList = restored + placeholders.filterKeys { it.sessionId == sessionId }.values
                publishMessages()
                publishLoading()
                publishStatus("")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (delivery.restoration(ticket) == ChatSessionDelivery.RestoreDecision.OBSOLETE) return@launch
                restoringSessionId = null
                publishLoading()
                publishStatus(application.getString(R.string.chat_restore_failed))
                LogUtils.e("Chat restoration failed", error)
            }
        }
    }

    fun deleteSession(session: ChatSession) {
        if (cleared) return
        delivery.delete(session.id)
        jobs.filterKeys { it.sessionId == session.id }.values.toList().forEach { it.cancel() }
        histories.remove(session.id)
        placeholders.keys.removeAll { it.sessionId == session.id }
        val deletion = repository.delete(session)
        viewModelScope.launch {
            try {
                val latestId = deletion.await()?.id
                val next = nextSessionIdAfterDeletion(session.id, currentSessionId, latestId)
                when {
                    next == null || delivery.isDeleted(next) -> createNewSession()
                    next != currentSessionId -> switchToSession(next)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                publishStatus(application.getString(R.string.chat_save_failed))
                LogUtils.e("Chat deletion failed", error)
            }
        }
    }

    fun ensureSession() {
        if (cleared || currentSessionId != null || initializingSession) return
        initializingSession = true
        publishLoading()
        val read = repository.latest()
        viewModelScope.launch {
            try {
                val latest = read.await()?.id
                initializingSession = false
                if (currentSessionId != null) { publishLoading(); return@launch }
                if (latest != null && !delivery.isDeleted(latest)) switchToSession(latest) else createNewSession()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                initializingSession = false
                publishLoading()
                publishStatus(application.getString(R.string.chat_restore_failed))
                LogUtils.e("Chat initialization failed", error)
            }
        }
    }

    fun sendToLlm(
        text: String,
        agentProvider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>,
        targetSessionId: String? = null,
    ) {
        ensureSession()
        val id = targetSessionId ?: currentSessionId ?: return
        if (cleared || delivery.isDeleted(id)) return
        val history = captureHistory(id, text)
        launchRequest(id) { request -> runLlm(request, history, null, agentProvider) }
    }

    fun sendWithAttachment(text: String, uri: Uri, agentProvider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>) {
        val id = currentSessionId ?: return
        if (cleared || delivery.isDeleted(id)) return
        launchRequest(id) { request ->
            val result = try { attachmentLoader.load(uri) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { ChatAttachmentResult.Failure(ChatAttachmentResult.Kind.READ, detail = error.message) }
            currentCoroutineContext().ensureActive()
            when (result) {
                is ChatAttachmentResult.Text -> {
                    val fullText = "$text\n\n[附件: ${result.fileName}]\n${result.content}"
                    addMessage(AgentChatMessage(AgentChatMessage.Role.USER, fullText), id)
                    runLlm(request, captureHistory(id, fullText), null, agentProvider)
                }
                is ChatAttachmentResult.Media -> {
                    addMessage(AgentChatMessage(AgentChatMessage.Role.USER, "$text\n\n[附件: ${result.fileName}]"), id)
                    runLlm(request, captureHistory(id, "$text\n\n[文件: ${result.fileName}]"), ChatMediaContent(result.base64, result.mimeType), agentProvider)
                }
                is ChatAttachmentResult.Failure -> addMessage(
                    AgentChatMessage(AgentChatMessage.Role.ASSISTANT, attachmentFailureText(application, result)), id,
                )
            }
        }
    }

    private fun captureHistory(sessionId: String, userText: String): List<ChatMessage> =
        histories.getOrPut(sessionId) { mutableListOf() }.also { it.add(ChatMessage("user", userText)) }.toList()

    private fun launchRequest(sessionId: String, block: suspend (Request) -> Unit) {
        val request = Request(sessionId, ++nextRequestId)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { block(request) }
            finally {
                jobs.remove(request)
                val placeholder = placeholders.remove(request)
                if (!cleared && sessionId == currentSessionId && !delivery.isDeleted(sessionId)) {
                    if (placeholder != null) { messageList = messageList.filterNot { it === placeholder }; publishMessages() }
                    publishLoading()
                }
            }
        }
        jobs[request] = job
        publishLoading()
        job.start()
    }

    private suspend fun runLlm(
        request: Request,
        history: List<ChatMessage>,
        media: ChatMediaContent?,
        provider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>,
    ) {
        val operation = UsageAnalyticsClient.begin(UsageAnalyticsEvent.AI_CHAT_STARTED)
        var outcome = "failure"
        try {
            val result = chatRunner.run(history, media, provider) { trace ->
                if (!cleared && jobs.containsKey(request) && !delivery.isDeleted(request.sessionId)) showTrace(request, trace, media != null)
            }
            currentCoroutineContext().ensureActive()
            if (cleared || delivery.isDeleted(request.sessionId)) return
            val final = when (result) {
                is LlmChatResult.Success -> {
                    outcome = "success"
                    histories.getOrPut(request.sessionId) { mutableListOf() }.add(ChatMessage("assistant", result.text))
                    AgentChatMessage(AgentChatMessage.Role.ASSISTANT, result.text, thinking = result.thinking, resourceCards = result.resourceCards)
                }
                is LlmChatResult.Error -> AgentChatMessage(
                    AgentChatMessage.Role.ASSISTANT,
                    if (media == null) "操作失败: ${result.error}" else "抱歉，处理过程中出现错误：${result.error}",
                )
            }
            deliverReply(request, final)
            if (request.sessionId == currentSessionId && media == null && jobs.keys.none { it != request && it.sessionId == request.sessionId }) publishStatus(if (result is LlmChatResult.Success) "完成" else "失败")
        } catch (cancelled: CancellationException) {
            outcome = "cancelled"
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            LogUtils.e("Chat request failed", error)
            deliverReply(request, AgentChatMessage(AgentChatMessage.Role.ASSISTANT, "操作失败: ${error.message}"))
        } finally {
            UsageAnalyticsClient.finish(operation, UsageAnalyticsEvent.AI_CHAT_FINISHED, mapOf("outcome" to outcome))
        }
    }

    private fun showTrace(request: Request, trace: AgentTraceEvent, attachment: Boolean) {
        if (request.sessionId != currentSessionId) return
        val existing = placeholders[request]
        val updated = (existing ?: AgentChatMessage(AgentChatMessage.Role.ASSISTANT, "", isPlaceholder = true)).copy(
            text = chatTraceText(trace, attachment),
            thinking = if (trace.stage == "react_step") trace.payload.ifBlank { trace.message } else existing?.thinking,
        )
        val index = existing?.let { old -> messageList.indexOfFirst { it === old } } ?: -1
        messageList = if (index >= 0) messageList.toMutableList().apply { set(index, updated) } else messageList + updated
        placeholders[request] = updated
        publishMessages()
    }

    private fun deliverReply(request: Request, message: AgentChatMessage) {
        if (cleared) return
        val destination = delivery.receive(request.sessionId)
        if (destination == ChatSessionDelivery.Destination.DELETED) return
        val placeholder = placeholders.remove(request)
        if (destination == ChatSessionDelivery.Destination.CURRENT) {
            val index = placeholder?.let { old -> messageList.indexOfFirst { it === old } } ?: -1
            messageList = if (index >= 0) messageList.toMutableList().apply { set(index, message) } else messageList + message
            publishMessages()
        }
        watchWrite(repository.append(request.sessionId, message.toRecord()), request.sessionId)
    }

    override fun onCleared() {
        cleared = true
        jobs.values.toList().forEach { it.cancel() }
        jobs.clear()
        placeholders.clear()
        // repository is application-owned: already accepted writes continue after UI cancellation.
        super.onCleared()
    }
}

private fun chatTraceText(trace: AgentTraceEvent, attachment: Boolean): String {
    val action = trace.message.substringAfter("→ ", "").trim()
    return when (trace.stage) {
    "react_start" -> if (attachment) "正在分析附件…" else "正在分析您的问题…"
    "react_step" -> when {
        action.contains("get_timetable") -> "正在查询课表…"
        action.contains("search_timetable") -> "正在搜索课表事件…"
        action.contains("search_empty_classroom") -> "正在查询空教室…"
        action.contains("search_external_resource") -> "正在搜索课程资料…"
        action.contains("search_course") -> "正在搜索课程信息…"
        action.contains("get_course_detail") -> "正在获取课程详情…"
        action.contains("search_teacher") -> "正在搜索教师信息…"
        action.contains("web_search") -> "正在搜索网页…"
        action.contains("rag_search") -> "正在搜索知识库…"
        action.contains("crawl_page") -> "正在爬取网页…"
        action.contains("crawl_site") -> "正在爬取网站…"
        action.contains("submit_review") -> "正在提交评价…"
        action.contains("add_activity") -> "正在添加活动…"
        else -> "正在思考…"
    }
    else -> "正在处理…"
}
}
