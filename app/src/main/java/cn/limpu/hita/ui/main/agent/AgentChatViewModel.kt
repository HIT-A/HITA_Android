package cn.limpu.hita.ui.main.agent

import android.app.Application
import android.net.Uri
import androidx.compose.ui.text.input.TextFieldValue
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

internal data class ChatDraft(
    val text: TextFieldValue = TextFieldValue(),
    val attachment: Uri? = null,
    val retryRowId: String? = null,
) {
    val hasContent: Boolean get() = text.text.isNotBlank() || attachment != null
}

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
    private var lastRowTime = 0L
    private var messageList: List<AgentChatMessage> = emptyList()
    private class SessionUi {
        var draft = ChatDraft()
        var notice = ""
        val retries = mutableMapOf<String, Attempt>()
    }
    private data class Attempt(val input: ChatDraft, val user: AgentChatMessage, val reply: AgentChatMessage)
    private class Running(val owner: ChatConfirmationGate.Owner, val attempt: Attempt) {
        lateinit var job: Job
        var row = attempt.reply
        // Written by the Main-thread confirmation action, before the tool receives approval.
        var writeAuthorized = false
    }
    private val sessionUi = mutableMapOf<String, SessionUi>()
    private val jobs = mutableMapOf<ChatConfirmationGate.Owner, Running>()
    private var pickerSessionId: String? = null
    private var restoreFailed = false

    private val confirmationGate = ChatConfirmationGate()
    internal val pendingConfirmation = confirmationGate.state

    private val _messages = MutableLiveData<List<AgentChatMessage>>(emptyList())
    val messages: LiveData<List<AgentChatMessage>> = _messages
    private val _status = MutableLiveData("")
    val status: LiveData<String> = _status
    private val _selectedSessionId = MutableLiveData<String?>()
    val selectedSessionId: LiveData<String?> = _selectedSessionId
    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading
    private val _isRestoring = MutableLiveData(false)
    val isRestoring: LiveData<Boolean> = _isRestoring
    private val _activeRequest = MutableLiveData<ChatConfirmationGate.Owner?>()
    internal val activeRequest: LiveData<ChatConfirmationGate.Owner?> = _activeRequest
    private val _draft = MutableLiveData(ChatDraft())
    internal val draft: LiveData<ChatDraft> = _draft
    private val _deleting = MutableLiveData<Set<String>>(emptySet())
    val deleting: LiveData<Set<String>> = _deleting
    private val _deletionNotice = MutableLiveData("")
    val deletionNotice: LiveData<String> = _deletionNotice
    val sessions = repository.sessions
    val currentSessionId: String? get() = delivery.currentSessionId

    private fun ui(sessionId: String) = sessionUi.getOrPut(sessionId, ::SessionUi)

    private fun publishMessages() { if (!cleared) _messages.value = messageList }
    private fun publishStatus(text: String, sessionId: String? = currentSessionId) {
        if (cleared) return
        if (sessionId != null && !delivery.isDeleted(sessionId)) ui(sessionId).notice = text
        if (sessionId == currentSessionId) _status.value = text
    }
    private fun publishLoading() {
        if (cleared) return
        val id = currentSessionId
        val active = jobs.keys.lastOrNull { it.sessionId == id }
        val restoring = initializingSession || id != null && restoringSessionId == id
        _isRestoring.value = restoring
        _activeRequest.value = active
        _isLoading.value = restoring || active != null || id != null && id in _deleting.value.orEmpty()
    }

    private fun updateDraft(sessionId: String, draft: ChatDraft) {
        if (cleared || delivery.isDeleted(sessionId)) return
        ui(sessionId).draft = draft
        if (sessionId == currentSessionId) _draft.value = draft
    }

    internal fun editDraft(sessionId: String, text: TextFieldValue) {
        if (sessionId != currentSessionId || sessionId in _deleting.value.orEmpty()) return
        val draft = ui(sessionId).draft
        updateDraft(sessionId, draft.copy(text = text))
    }

    internal fun beginAttachmentPicker(sessionId: String): Boolean {
        if (sessionId != currentSessionId || sessionId in _deleting.value.orEmpty() || pickerSessionId != null) return false
        pickerSessionId = sessionId
        return true
    }

    internal fun finishAttachmentPicker(uri: Uri?) {
        val id = pickerSessionId ?: return
        pickerSessionId = null
        if (uri != null && !delivery.isDeleted(id)) updateDraft(id, ui(id).draft.copy(attachment = uri))
    }

    internal fun removeAttachment(sessionId: String) {
        if (sessionId != currentSessionId || sessionId in _deleting.value.orEmpty()) return
        updateDraft(sessionId, ui(sessionId).draft.copy(attachment = null))
    }

    private fun placeVisible(message: AgentChatMessage) {
        val index = messageList.indexOfFirst { it.id == message.id }
        messageList = when {
            index >= 0 -> messageList.toMutableList().apply { set(index, message) }
            messageList.lastOrNull()?.let { it.timestampMs > message.timestampMs } == true ->
                messageList.toMutableList().apply { add(indexOfFirst { it.timestampMs > message.timestampMs }, message) }
            else -> messageList + message
        }
        publishMessages()
    }

    private fun persistMessage(sessionId: String, message: AgentChatMessage) {
        if (cleared) return
        when (delivery.receive(sessionId)) {
            ChatSessionDelivery.Destination.DELETED -> return
            ChatSessionDelivery.Destination.CURRENT -> placeVisible(message)
            ChatSessionDelivery.Destination.BACKGROUND -> Unit
        }
        watchWrite(repository.append(sessionId, message.toRecord()), sessionId)
    }

    private fun watchWrite(write: Deferred<*>, sessionId: String? = currentSessionId) {
        viewModelScope.launch {
            try {
                if (write.await() == false) publishStatus(application.getString(R.string.chat_save_failed), sessionId)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                LogUtils.e("Chat persistence failed", error)
                publishStatus(application.getString(R.string.chat_save_failed), sessionId)
            }
        }
    }

    fun createNewSession() {
        if (cleared) return
        val session = ChatSession()
        select(session.id, restore = false)
        // Enqueue creation before the first accepted message can enqueue its insert.
        watchWrite(repository.create(session))
    }

    fun switchToSession(sessionId: String) {
        if (cleared || sessionId == currentSessionId || delivery.isDeleted(sessionId) ||
            sessionId in _deleting.value.orEmpty()) return
        select(sessionId, restore = true)
        restoreSession(sessionId)
    }

    private fun select(sessionId: String, restore: Boolean) {
        delivery.select(sessionId)
        restoringSessionId = sessionId.takeIf { restore }
        initializingSession = false
        restoreFailed = false
        messageList = emptyList()
        publishMessages()
        _selectedSessionId.value = sessionId
        _draft.value = ui(sessionId).draft
        _status.value = ui(sessionId).notice
        publishLoading()
    }

    internal fun retryRestore() {
        if (!restoreFailed) return
        restoreFailed = false
        publishStatus("")
        val id = currentSessionId
        if (id == null) ensureSession() else {
            restoringSessionId = id
            publishLoading()
            restoreSession(id)
        }
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
                lastRowTime = maxOf(lastRowTime, restored.lastOrNull()?.timestampMs ?: 0L)
                val pending = jobs.values.filter { it.owner.sessionId == sessionId }.map { it.row }
                messageList = if (pending.isEmpty()) restored else {
                    val pendingIds = pending.mapTo(HashSet(pending.size)) { it.id }
                    (restored.filterNot { it.id in pendingIds } + pending).sortedBy { it.timestampMs }
                }
                publishMessages()
                publishLoading()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (delivery.restoration(ticket) == ChatSessionDelivery.RestoreDecision.OBSOLETE) return@launch
                restoringSessionId = null
                restoreFailed = true
                publishLoading()
                publishStatus(application.getString(R.string.chat_restore_failed))
                LogUtils.e("Chat restoration failed", error)
            }
        }
    }

    fun deleteSession(session: ChatSession) {
        if (cleared || delivery.isDeleted(session.id) || _deleting.value.orEmpty().isNotEmpty()) return
        _deleting.value = _deleting.value.orEmpty() + session.id
        _deletionNotice.value = ""
        jobs.values.filter { it.owner.sessionId == session.id }.forEach(::stop)
        publishLoading()
        val deletion = repository.delete(session)
        viewModelScope.launch {
            try {
                val latestId = deletion.await()?.id
                delivery.delete(session.id)
                sessionUi.remove(session.id)
                val next = nextSessionIdAfterDeletion(session.id, currentSessionId, latestId)
                when {
                    next == null || delivery.isDeleted(next) -> createNewSession()
                    next != currentSessionId -> switchToSession(next)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                _deletionNotice.value = application.getString(R.string.agent_chat_delete_failed, session.title)
                LogUtils.e("Chat deletion failed", error)
            } finally {
                if (!cleared) {
                    _deleting.value = _deleting.value.orEmpty() - session.id
                    publishLoading()
                }
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
                restoreFailed = true
                publishLoading()
                publishStatus(application.getString(R.string.chat_restore_failed))
                LogUtils.e("Chat initialization failed", error)
            }
        }
    }

    internal fun sendDraft(
        sessionId: String,
        provider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>,
    ): Boolean {
        if (!canSendFromComposer(sessionId)) return false
        return submit(sessionId, ui(sessionId).draft, provider, consumeDraft = true)
    }

    internal fun sendSuggestion(
        sessionId: String,
        text: String,
        provider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>,
    ): Boolean {
        if (!canSendFromComposer(sessionId)) return false
        return submit(sessionId, ChatDraft(TextFieldValue(text)), provider)
    }

    private fun canSendFromComposer(sessionId: String): Boolean =
        sessionId == currentSessionId && jobs.keys.none { it.sessionId == sessionId }

    private fun nextRowTime(): Long {
        lastRowTime = maxOf(lastRowTime + 1, System.currentTimeMillis())
        return lastRowTime
    }

    /** Admits an explicit input snapshot. Composer actions additionally reject while a turn is active. */
    internal fun submit(
        sessionId: String,
        input: ChatDraft,
        provider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>,
        consumeDraft: Boolean = false,
    ): Boolean {
        if (cleared || sessionId != currentSessionId || delivery.isDeleted(sessionId) ||
            sessionId in _deleting.value.orEmpty() || restoringSessionId == sessionId ||
            restoreFailed || !input.hasContent) return false

        val retry = input.retryRowId?.let { ui(sessionId).retries[it] }?.takeIf {
            it.input.text.text == input.text.text && it.input.attachment == input.attachment
        }
        val attempt = Attempt(
            input.copy(text = input.text.copy(composition = null), retryRowId = null),
            retry?.user ?: AgentChatMessage(AgentChatMessage.Role.USER, input.text.text.trim(), timestampMs = nextRowTime()),
            retry?.reply ?: AgentChatMessage(
                AgentChatMessage.Role.ASSISTANT,
                application.getString(R.string.agent_chat_processing),
                isPlaceholder = true,
                timestampMs = nextRowTime(),
            ),
        )
        val history = messageList.mapNotNull { row ->
            if (row.timestampMs >= attempt.user.timestampMs || row.isPlaceholder) return@mapNotNull null
            when (row.role) {
                AgentChatMessage.Role.USER -> ChatMessage("user", row.text)
                AgentChatMessage.Role.ASSISTANT -> ChatMessage("assistant", row.text)
                AgentChatMessage.Role.TRACE -> null
            }
        }
        val running = Running(ChatConfirmationGate.Owner(sessionId, ++nextRequestId), attempt)
        running.job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                execute(running, history, provider)
            } finally {
                confirmationGate.cancel(running.owner)
                if (jobs.remove(running.owner) != null && currentSessionId == sessionId) publishLoading()
            }
        }
        jobs[running.owner] = running
        ui(sessionId).retries.remove(attempt.reply.id)
        if (consumeDraft) updateDraft(sessionId, ChatDraft())
        publishLoading()
        placeVisible(running.row)
        running.job.start()
        return true
    }

    internal fun hasRecoverableInput(sessionId: String, rowId: String): Boolean =
        sessionUi[sessionId]?.retries?.containsKey(rowId) == true

    internal fun recoverInput(sessionId: String, rowId: String, replace: Boolean = false): Boolean {
        if (!canSendFromComposer(sessionId) || sessionId in _deleting.value.orEmpty()) return false
        val ui = ui(sessionId)
        val attempt = ui.retries[rowId] ?: return false
        if (!replace && ui.draft.hasContent &&
            (ui.draft.text.text != attempt.input.text.text || ui.draft.attachment != attempt.input.attachment)) return false
        updateDraft(sessionId, attempt.input.copy(retryRowId = rowId))
        return true
    }

    internal fun resolveConfirmation(pending: ChatConfirmationGate.Pending, approved: Boolean): Boolean {
        val request = jobs[pending.owner] ?: return false
        if (!request.job.isActive || pending.owner.sessionId in _deleting.value.orEmpty()) return false
        return confirmationGate.resolve(pending, currentSessionId, approved)
    }

    internal fun stopRequest(owner: ChatConfirmationGate.Owner) {
        if (owner.sessionId != currentSessionId) return
        jobs[owner]?.let(::stop)
    }

    private fun stop(running: Running) {
        jobs.remove(running.owner)
        running.job.cancel()
        confirmationGate.cancel(running.owner)
        markFailure(
            running,
            application.getString(R.string.agent_chat_stopped),
            recoverable = !running.writeAuthorized,
        )
        if (running.owner.sessionId == currentSessionId) publishLoading()
    }

    private suspend fun execute(
        running: Running,
        history: List<ChatMessage>,
        provider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>,
    ) {
        val sessionId = running.owner.sessionId
        val attempt = running.attempt
        try {
            var userText = attempt.user.text
            var media: ChatMediaContent? = null
            attempt.input.attachment?.let { uri ->
                currentCoroutineContext().ensureActive()
                val result = try { attachmentLoader.load(uri) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { ChatAttachmentResult.Failure(ChatAttachmentResult.Kind.READ, detail = error.message) }
                currentCoroutineContext().ensureActive()
                when (result) {
                    is ChatAttachmentResult.Text -> userText += "\n\n[附件: ${result.fileName}]\n${result.content}"
                    is ChatAttachmentResult.Media -> {
                        userText += "\n\n[附件: ${result.fileName}]"
                        media = ChatMediaContent(result.base64, result.mimeType)
                    }
                    is ChatAttachmentResult.Failure -> {
                        markFailure(running, attachmentFailureText(application, result))
                        return
                    }
                }
            }
            persistMessage(sessionId, attempt.user.copy(text = userText))
            runLlm(running, history + ChatMessage("user", userText), media, provider)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            LogUtils.e("Chat request failed", error)
            markFailure(running, application.getString(R.string.agent_chat_request_failed, error.message.orEmpty()))
        }
    }

    private suspend fun runLlm(
        running: Running,
        history: List<ChatMessage>,
        media: ChatMediaContent?,
        provider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>,
    ) {
        val operation = UsageAnalyticsClient.begin(UsageAnalyticsEvent.AI_CHAT_STARTED)
        var outcome = "failure"
        try {
            val result = chatRunner.run(
                history = history,
                media = media,
                provider = provider,
                onTrace = { trace ->
                    if (!cleared && jobs[running.owner] === running && running.job.isActive) {
                        running.row = running.row.copy(
                            text = chatTraceText(trace, media != null),
                            thinking = if (trace.stage == "react_step") trace.payload.ifBlank { trace.message } else running.row.thinking,
                        )
                        if (running.owner.sessionId == currentSessionId) placeVisible(running.row)
                    }
                },
                confirmationGate = confirmationGate.bind(running.owner) { running.writeAuthorized = true },
            )
            currentCoroutineContext().ensureActive()
            when (result) {
                is LlmChatResult.Success -> {
                    outcome = "success"
                    persistMessage(
                        running.owner.sessionId,
                        running.attempt.reply.copy(
                            text = result.text, thinking = result.thinking, resourceCards = result.resourceCards,
                            isPlaceholder = false,
                        ),
                    )
                }
                is LlmChatResult.Error ->
                    markFailure(running, application.getString(R.string.agent_chat_request_failed, result.error))
            }
        } catch (cancelled: CancellationException) {
            outcome = "cancelled"
            throw cancelled
        } finally {
            UsageAnalyticsClient.finish(operation, UsageAnalyticsEvent.AI_CHAT_FINISHED, mapOf("outcome" to outcome))
        }
    }

    private fun markFailure(running: Running, description: String, recoverable: Boolean = !running.writeAuthorized) {
        val sessionId = running.owner.sessionId
        if (cleared || delivery.isDeleted(sessionId)) return
        val reply = running.attempt.reply
        if (recoverable) ui(sessionId).retries[reply.id] = running.attempt
        val text = if (running.writeAuthorized) {
            application.getString(R.string.agent_chat_check_write, description)
        } else description
        // App-generated status is not model conversation history, even after restoring from Room.
        persistMessage(sessionId, reply.copy(role = AgentChatMessage.Role.TRACE, text = text, isPlaceholder = false))
    }

    override fun onCleared() {
        cleared = true
        // 页面销毁时让挂起的确认请求以拒绝收场，避免工具协程永久等待。
        confirmationGate.cancelAll()
        jobs.values.toList().forEach { it.job.cancel() }
        jobs.clear()
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
