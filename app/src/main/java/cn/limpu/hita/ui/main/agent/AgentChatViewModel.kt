package cn.limpu.hita.ui.main.agent

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.limpu.hita.agent.core.AgentProvider
import cn.limpu.hita.agent.core.AgentTraceEvent
import cn.limpu.hita.agent.llm.ChatMessage
import cn.limpu.hita.agent.llm.LlmChatResult
import cn.limpu.hita.agent.llm.LlmChatService
import cn.limpu.hita.agent.llm.chatWithAttachment
import cn.limpu.hita.agent.tools.AgentResourceCardCollector
import cn.limpu.hita.agent.timetable.TimetableAgentInput
import cn.limpu.hita.agent.timetable.TimetableAgentOutput
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import cn.limpu.hita.data.model.chat.ChatMessageEntity
import cn.limpu.hita.data.model.chat.ChatSession
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.utils.LogUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executors
import javax.inject.Inject

internal fun nextSessionIdAfterDeletion(
    deletedSessionId: String,
    currentSessionId: String?,
    remainingLatestSessionId: String?,
): String? {
    return if (deletedSessionId == currentSessionId) remainingLatestSessionId else currentSessionId
}

@HiltViewModel
class AgentChatViewModel @Inject constructor(
    private val application: Application,
    private val easRepository: EASRepository,
) : ViewModel() {

    private val db = AppDatabase.getDatabase(application)
    private val sessionDao = db.chatSessionDao()
    private val messageDao = db.chatMessageDao()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val delivery = ChatSessionDelivery()
    private var restoringSessionId: String? = null
    private var initializingSession = false

    private val _messages = MutableLiveData<List<AgentChatMessage>>(emptyList())
    val messages: LiveData<List<AgentChatMessage>> = _messages

    private val _status = MutableLiveData("")
    val status: LiveData<String> = _status

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _pendingAttachment = MutableLiveData<Uri?>(null)
    val pendingAttachment: LiveData<Uri?> = _pendingAttachment

    val sessions = sessionDao.getAll()

    @Volatile
    private var messageList: List<AgentChatMessage> = emptyList()

    // 按 session 隔离的状态
    private val sessionChatHistories = mutableMapOf<String, MutableList<ChatMessage>>()
    private val sessionPlaceholders = mutableMapOf<String, AgentChatMessage>()
    private val sessionLoadingStates = mutableMapOf<String, Boolean>()

    val currentSessionId: String?
        get() = delivery.currentSessionId

    private fun publishMessages() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _messages.value = messageList
        } else {
            _messages.postValue(messageList)
        }
    }

    private fun publishStatus(text: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _status.value = text
        } else {
            _status.postValue(text)
        }
    }

    private fun publishLoading(loading: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _isLoading.value = loading
        } else {
            _isLoading.postValue(loading)
        }
    }

    fun addMessage(message: AgentChatMessage, targetSessionId: String? = null) {
        val sid = targetSessionId ?: currentSessionId ?: return
        when (delivery.receive(sid)) {
            ChatSessionDelivery.Destination.DELETED -> return
            ChatSessionDelivery.Destination.CURRENT -> {
                messageList = messageList + message
                publishMessages()
            }
            ChatSessionDelivery.Destination.BACKGROUND -> Unit
        }
        persistMessage(sid, message)
    }

    private fun persistMessage(sid: String, message: AgentChatMessage) {
        ioExecutor.execute {
            db.runInTransaction {
                persistChatMessage(
                    sessionId = sid,
                    message = message,
                    sessionExists = sessionDao::exists,
                    save = { sessionId, value ->
                        messageDao.save(ChatMessageEntity(
                            sessionId = sessionId,
                            role = value.role.name,
                            text = value.text,
                            resourceCardsJson = AgentResourceCardCollector.toJson(value.resourceCards),
                            timestampMs = value.timestampMs,
                        ))
                    },
                    firstUserText = messageDao::getFirstUserTextSync,
                    updateTitle = { sessionId, title -> sessionDao.updateTitle(sessionId, title) },
                )
            }
        }
    }

    fun updateOrCreatePlaceholder(text: String, thinking: String? = null, targetSessionId: String? = null) {
        val sid = targetSessionId ?: currentSessionId ?: return
        if (sid != currentSessionId || delivery.isDeleted(sid)) return
        synchronized(this) {
            val existing = sessionPlaceholders[sid]
            if (existing != null) {
                val idx = messageList.indexOf(existing)
                if (idx >= 0) {
                    val updated = existing.copy(
                        text = text,
                        thinking = thinking ?: existing.thinking,
                    )
                    messageList = messageList.toMutableList().apply { set(idx, updated) }
                    sessionPlaceholders[sid] = updated
                    publishMessages()
                    return
                }
            }
            val newPlaceholder = AgentChatMessage(
                role = AgentChatMessage.Role.ASSISTANT,
                text = text,
                thinking = thinking,
                isPlaceholder = true,
            )
            sessionPlaceholders[sid] = newPlaceholder
            messageList = messageList + newPlaceholder
            publishMessages()
        }
    }

    fun replacePlaceholder(finalMessage: AgentChatMessage, targetSessionId: String? = null) {
        val sid = targetSessionId ?: currentSessionId ?: return
        val destination = delivery.receive(sid)
        if (destination == ChatSessionDelivery.Destination.DELETED) return
        if (destination == ChatSessionDelivery.Destination.CURRENT) {
            synchronized(this) {
                val existing = sessionPlaceholders[sid]
                if (existing != null) {
                    val idx = messageList.indexOf(existing)
                    if (idx >= 0) {
                        messageList = messageList.toMutableList().apply { set(idx, finalMessage) }
                    } else {
                        messageList = messageList + finalMessage
                    }
                    sessionPlaceholders.remove(sid)
                    publishMessages()
                } else {
                    messageList = messageList + finalMessage
                    sessionPlaceholders.remove(sid)
                    publishMessages()
                }
            }
        } else {
            sessionPlaceholders.remove(sid)
        }
        persistMessage(sid, finalMessage)
    }

    fun setStatus(text: String) {
        publishStatus(text)
    }

    fun setLoading(loading: Boolean, targetSessionId: String? = null) {
        val sid = targetSessionId ?: currentSessionId ?: return
        if (delivery.isDeleted(sid)) return
        sessionLoadingStates[sid] = loading
        if (sid == currentSessionId) publishLoading(loading || restoringSessionId == sid)
    }

    fun setPendingAttachment(uri: Uri?) {
        _pendingAttachment.value = uri
    }

    fun clearPendingAttachment() {
        _pendingAttachment.value = null
    }

    fun createNewSession() {
        val session = ChatSession()
        delivery.select(session.id)
        restoringSessionId = null
        initializingSession = false
        sessionChatHistories[session.id] = mutableListOf()
        sessionPlaceholders.remove(session.id)
        sessionLoadingStates[session.id] = false
        messageList = emptyList()
        publishMessages()
        publishStatus("")
        publishLoading(false)
        ioExecutor.execute { sessionDao.save(session) }
    }

    fun switchToSession(sessionId: String) {
        if (sessionId == currentSessionId || delivery.isDeleted(sessionId)) return
        delivery.select(sessionId)
        restoringSessionId = sessionId
        messageList = emptyList()
        publishMessages()
        publishStatus("")
        publishLoading(true)
        restoreSession(sessionId)
    }

    private fun restoreSession(sessionId: String) {
        val ticket = delivery.beginRestore(sessionId)
        ioExecutor.execute {
            val restored = messageDao.getBySessionSync(sessionId).map { e ->
                AgentChatMessage(
                    role = AgentChatMessage.Role.valueOf(e.role),
                    text = e.text,
                    resourceCards = AgentResourceCardCollector.fromJson(e.resourceCardsJson),
                    timestampMs = e.timestampMs,
                )
            }
            mainHandler.post {
                when (delivery.restoration(ticket)) {
                    ChatSessionDelivery.RestoreDecision.OBSOLETE -> return@post
                    ChatSessionDelivery.RestoreDecision.RELOAD -> {
                        // A response arrived after this read. Its queued insert must finish
                        // before another read can replace the visible messages/history.
                        restoreSession(sessionId)
                        return@post
                    }
                    ChatSessionDelivery.RestoreDecision.APPLY -> Unit
                }
                restoringSessionId = null
                sessionChatHistories[sessionId] = restored.filter {
                    it.role == AgentChatMessage.Role.USER || it.role == AgentChatMessage.Role.ASSISTANT
                }.map {
                    ChatMessage(if (it.role == AgentChatMessage.Role.USER) "user" else "assistant", it.text)
                }.toMutableList()
                messageList = restored + listOfNotNull(sessionPlaceholders[sessionId])
                publishMessages()
                publishLoading(sessionLoadingStates[sessionId] ?: false)
                publishStatus("")
            }
        }
    }

    fun deleteSession(session: ChatSession) {
        // Reject late results immediately, including while the database delete is queued.
        delivery.delete(session.id)
        sessionChatHistories.remove(session.id)
        sessionPlaceholders.remove(session.id)
        sessionLoadingStates.remove(session.id)
        ioExecutor.execute {
            sessionDao.delete(session) // ForeignKey.CASCADE removes the messages atomically.
            val latestId = sessionDao.getLatest()?.id
            mainHandler.post {
                val nextSessionId = nextSessionIdAfterDeletion(session.id, currentSessionId, latestId)
                when {
                    nextSessionId == null || delivery.isDeleted(nextSessionId) -> createNewSession()
                    nextSessionId != currentSessionId -> switchToSession(nextSessionId)
                }
            }
        }
    }

    fun ensureSession() {
        if (currentSessionId != null || initializingSession) return
        initializingSession = true
        publishLoading(true)
        ioExecutor.execute {
            val latestId = sessionDao.getLatest()?.id
            mainHandler.post {
                initializingSession = false
                if (currentSessionId != null) return@post
                if (latestId != null && !delivery.isDeleted(latestId)) switchToSession(latestId)
                else createNewSession()
            }
        }
    }

    fun sendToLlm(
        text: String,
        agentProvider: cn.limpu.hita.agent.core.AgentProvider<TimetableAgentInput, TimetableAgentOutput>,
        targetSessionId: String? = null,
    ) {
        ensureSession()
        val sid = targetSessionId ?: currentSessionId ?: return
        if (delivery.isDeleted(sid)) return
        val history = sessionChatHistories.getOrPut(sid) { mutableListOf() }
        val analyticsOperation = UsageAnalyticsClient.begin(UsageAnalyticsEvent.AI_CHAT_STARTED)
        history.add(ChatMessage(role = "user", content = text))

        viewModelScope.launch {
            setLoading(true, sid)

            LlmChatService.chat(
                history = history.toList(),
                timetableId = null,
                application = application,
                easRepository = easRepository,
                agentProvider = agentProvider,
                onTrace = { trace ->
                    if (currentSessionId != sid) return@chat
                    val statusText = when (trace.stage) {
                        "react_start" -> "正在分析您的问题…"
                        "react_step" -> {
                            val action = trace.message.substringAfter("→ ", "").trim()
                            when {
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
                        }
                        else -> "正在处理…"
                    }
                    val currentThinking = if (trace.stage == "react_step") {
                        trace.payload.ifBlank { trace.message }
                    } else {
                        null
                    }
                    updateOrCreatePlaceholder(statusText, currentThinking, targetSessionId = sid)
                },
                onResult = { result ->
                    UsageAnalyticsClient.finish(analyticsOperation, UsageAnalyticsEvent.AI_CHAT_FINISHED, mapOf("outcome" to if (result is LlmChatResult.Success) "success" else "failure"))
                    if (delivery.isDeleted(sid)) return@chat
                    setLoading(false, sid)
                    when (result) {
                        is LlmChatResult.Success -> {
                            sessionChatHistories.getOrPut(sid) { mutableListOf() }
                                .add(ChatMessage(role = "assistant", content = result.text))
                            replacePlaceholder(AgentChatMessage(
                                role = AgentChatMessage.Role.ASSISTANT,
                                text = result.text,
                                thinking = result.thinking,
                                resourceCards = result.resourceCards,
                            ), targetSessionId = sid)
                            if (currentSessionId == sid) setStatus("完成")
                        }
                        is LlmChatResult.Error -> {
                            replacePlaceholder(AgentChatMessage(
                                role = AgentChatMessage.Role.ASSISTANT,
                                text = "操作失败: ${result.error}",
                            ), targetSessionId = sid)
                            if (currentSessionId == sid) setStatus("失败")
                        }
                    }
                },
            )
        }
    }

    fun sendToLlmWithAttachment(
        text: String,
        fileName: String,
        base64Content: String,
        mimeType: String,
        agentProvider: cn.limpu.hita.agent.core.AgentProvider<TimetableAgentInput, TimetableAgentOutput>,
        targetSessionId: String? = null,
    ) {
        ensureSession()
        val sid = targetSessionId ?: currentSessionId ?: return
        if (delivery.isDeleted(sid)) return
        val history = sessionChatHistories.getOrPut(sid) { mutableListOf() }

        val analyticsOperation = UsageAnalyticsClient.begin(UsageAnalyticsEvent.AI_CHAT_STARTED)
        val userMessage = "$text\n\n[文件: $fileName]"
        history.add(ChatMessage(role = "user", content = userMessage))

        viewModelScope.launch {
            setLoading(true, sid)

            LlmChatService.chatWithAttachment(
                history = history.toList(),
                attachmentBase64 = base64Content,
                attachmentMimeType = mimeType,
                timetableId = null,
                application = application,
                easRepository = easRepository,
                agentProvider = agentProvider,
                onTrace = { trace: AgentTraceEvent ->
                    if (currentSessionId != sid) return@chatWithAttachment
                    val statusText = when (trace.stage) {
                        "react_start" -> "正在分析附件…"
                        "react_step" -> {
                            val action = trace.message.substringAfter("→ ", "").trim()
                            when {
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
                        }
                        else -> "正在处理…"
                    }
                    val currentThinking = if (trace.stage == "react_step") {
                        trace.payload.ifBlank { trace.message }
                    } else {
                        null
                    }
                    updateOrCreatePlaceholder(statusText, currentThinking, targetSessionId = sid)
                },
                onResult = { result: LlmChatResult ->
                    UsageAnalyticsClient.finish(analyticsOperation, UsageAnalyticsEvent.AI_CHAT_FINISHED, mapOf("outcome" to if (result is LlmChatResult.Success) "success" else "failure"))
                    if (delivery.isDeleted(sid)) return@chatWithAttachment
                    setLoading(false, sid)
                    when (result) {
                        is LlmChatResult.Success -> {
                            sessionChatHistories.getOrPut(sid) { mutableListOf() }
                                .add(ChatMessage(role = "assistant", content = result.text))
                            replacePlaceholder(AgentChatMessage(
                                role = AgentChatMessage.Role.ASSISTANT,
                                text = result.text,
                                thinking = result.thinking,
                                resourceCards = result.resourceCards,
                            ), targetSessionId = sid)
                        }
                        is LlmChatResult.Error -> {
                            replacePlaceholder(AgentChatMessage(
                                role = AgentChatMessage.Role.ASSISTANT,
                                text = "抱歉，处理过程中出现错误：${result.error}"
                            ), targetSessionId = sid)
                        }
                    }
                },
            )
        }
    }

    // region File attachment processing

    private sealed class LocalParseResult {
        data class Success(val content: String) : LocalParseResult()
        data class Error(val error: String) : LocalParseResult()
    }

    companion object {
        private const val MAX_FILE_SIZE = 20 * 1024 * 1024
        private const val MAX_IMAGE_SIZE = 10 * 1024 * 1024
    }

    fun sendWithAttachment(
        text: String,
        uri: Uri,
        agentProvider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>,
    ) {
        val sid = currentSessionId ?: return
        if (delivery.isDeleted(sid)) return
        fun addAttachmentMessage(message: AgentChatMessage) = addMessage(message, sid)
        setLoading(true, sid)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = getFileName(uri)

                val maxSize = when {
                    fileName.endsWith(".jpg", true) ||
                    fileName.endsWith(".jpeg", true) ||
                    fileName.endsWith(".png", true) ||
                    fileName.endsWith(".gif", true) ||
                    fileName.endsWith(".bmp", true) ||
                    fileName.endsWith(".webp", true) -> MAX_IMAGE_SIZE
                    fileName.endsWith(".mp4", true) ||
                    fileName.endsWith(".mov", true) ||
                    fileName.endsWith(".avi", true) ||
                    fileName.endsWith(".mkv", true) ||
                    fileName.endsWith(".webm", true) -> MAX_IMAGE_SIZE
                    else -> MAX_FILE_SIZE
                }

                val input = application.contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("无法打开文件，请重试")
                cacheChatAttachment(input, application.cacheDir, maxSize).use { attachment ->
                    val fileSize = attachment.byteCount
                    val tempFile = attachment.file
                    if (fileSize == 0) {
                        withContext(Dispatchers.Main) {
                            setLoading(false, sid)
                            addAttachmentMessage(AgentChatMessage(
                                role = AgentChatMessage.Role.ASSISTANT,
                                text = "无法打开文件，请重试"
                            ))
                        }
                        return@launch
                    }

                    val mimeType = getMimeType(fileName)
                    val needLocalParse = when {
                        fileSize < 100 * 1024 && isTextFile(fileName) -> true
                        fileName.endsWith(".docx", true) -> true
                        fileName.endsWith(".xlsx", true) -> true
                        fileName.endsWith(".pptx", true) -> true
                        fileName.endsWith(".pdf", true) -> true
                        else -> false
                    }
                    val needCloudAI = mimeType.startsWith("image/") || mimeType.startsWith("video/")

                    if (!needLocalParse && !needCloudAI) {
                        withContext(Dispatchers.Main) {
                            setLoading(false, sid)
                            addAttachmentMessage(AgentChatMessage(
                                role = AgentChatMessage.Role.ASSISTANT,
                                text = "不支持的文件类型：${fileName}\n\n支持的格式：\n- 文档：PDF、Word、Excel、PowerPoint（本地解析）\n- 图片：JPG、PNG、GIF、WebP、BMP\n- 视频：MP4、MOV、AVI、MKV、WebM"
                            ))
                        }
                        return@launch
                    }

                    val result = when {
                        fileSize < 100 * 1024 && isTextFile(fileName) -> {
                            val fileText = tempFile.readText(Charsets.UTF_8)
                            LocalParseResult.Success("【文本文件】\n$fileText")
                        }
                        fileName.endsWith(".pdf", true) -> parsePdfFile(tempFile)
                        fileName.endsWith(".docx", true) -> parseDocxFile(tempFile)
                        fileName.endsWith(".xlsx", true) -> parseExcelFile(tempFile)
                        fileName.endsWith(".pptx", true) -> parsePptxFile(tempFile)
                        mimeType.startsWith("image/") || mimeType.startsWith("video/") -> null
                        else -> LocalParseResult.Error("不支持的文件类型")
                    }

                    val base64Content = if (result == null && needCloudAI) {
                        try {
                            val fileBytes = tempFile.readBytes()
                            Base64.encodeToString(fileBytes, Base64.NO_WRAP)
                        } catch (e: OutOfMemoryError) {
                            withContext(Dispatchers.Main) {
                                setLoading(false, sid)
                                addAttachmentMessage(AgentChatMessage(
                                    role = AgentChatMessage.Role.ASSISTANT,
                                    text = "文件过大，无法处理。\n\n建议：\n1. 图片请压缩后重新上传（建议小于5MB）\n2. 视频请剪辑后重新上传（建议小于10MB）\n3. 或使用截图功能"
                                ))
                            }
                            return@launch
                        }
                    } else null

                    withContext(Dispatchers.Main) {
                        when (result) {
                            is LocalParseResult.Success -> {
                                val maxLength = 5000
                                val content = if (result.content.length > maxLength) {
                                    result.content.take(maxLength) + "\n\n...(内容过长，仅显示前${maxLength}字)"
                                } else result.content
                                val fullText = "$text\n\n[附件: $fileName]\n$content"
                                addAttachmentMessage(AgentChatMessage(role = AgentChatMessage.Role.USER, text = fullText))
                                sendToLlm(fullText, agentProvider, targetSessionId = sid)
                            }
                            is LocalParseResult.Error -> {
                                if (needCloudAI && base64Content != null) {
                                    val messageWithFile = "$text\n\n[附件: $fileName]"
                                    addAttachmentMessage(AgentChatMessage(role = AgentChatMessage.Role.USER, text = messageWithFile))
                                    sendToLlmWithAttachment(text, fileName, base64Content, mimeType, agentProvider, targetSessionId = sid)
                                } else {
                                    setLoading(false, sid)
                                    addAttachmentMessage(AgentChatMessage(
                                        role = AgentChatMessage.Role.ASSISTANT,
                                        text = "附件解析失败：${result.error}\n\n建议：请复制文件内容粘贴到对话框中"
                                    ))
                                }
                            }
                            null -> {
                                if (base64Content != null) {
                                    val messageWithFile = "$text\n\n[附件: $fileName]"
                                    addAttachmentMessage(AgentChatMessage(role = AgentChatMessage.Role.USER, text = messageWithFile))
                                    sendToLlmWithAttachment(text, fileName, base64Content, mimeType, agentProvider, targetSessionId = sid)
                                } else {
                                    setLoading(false, sid)
                                    addAttachmentMessage(AgentChatMessage(
                                        role = AgentChatMessage.Role.ASSISTANT,
                                        text = "不支持的文件类型，请尝试图片或视频"
                                    ))
                                }
                            }
                        }
                    }
                }
            } catch (e: ChatAttachmentTooLargeException) {
                withContext(Dispatchers.Main) {
                    setLoading(false, sid)
                    addAttachmentMessage(AgentChatMessage(
                        role = AgentChatMessage.Role.ASSISTANT,
                        text = "文件过大！\n限制：${formatFileSize(e.maxBytes)}\n\n建议：\n- 图片/视频请压缩到10MB以下\n- 文档请控制在20MB以下"
                    ))
                }
            } catch (e: Exception) {
                LogUtils.e("附件处理异常", e)
                withContext(Dispatchers.Main) {
                    setLoading(false, sid)
                    addAttachmentMessage(AgentChatMessage(
                        role = AgentChatMessage.Role.ASSISTANT,
                        text = "附件处理失败：${e.message}"
                    ))
                }
            } catch (e: OutOfMemoryError) {
                LogUtils.e("内存不足异常", e)
                withContext(Dispatchers.Main) {
                    setLoading(false, sid)
                    addAttachmentMessage(AgentChatMessage(
                        role = AgentChatMessage.Role.ASSISTANT,
                        text = "内存不足，请尝试更小的文件"
                    ))
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        val path = uri.path
        if (path != null && path.contains("/")) {
            val nameFromPath = path.substringAfterLast("/")
            if (nameFromPath.contains(".") && !nameFromPath.endsWith(".mht")) {
                return nameFromPath
            }
        }
        val cursor = application.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    val displayName = it.getString(nameIndex)
                    if (displayName.endsWith(".mht", true) && displayName.contains(".pdf", true)) {
                        val pdfIndex = displayName.indexOf(".pdf", ignoreCase = true)
                        if (pdfIndex > 0) {
                            return displayName.substring(0, pdfIndex + 4)
                        }
                    }
                    return displayName
                }
            }
        }
        return uri.lastPathSegment ?: "文件"
    }

    private fun formatFileSize(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }

    private fun isTextFile(fileName: String): Boolean {
        val textExtensions = listOf(".txt", ".md", ".json", ".xml", ".csv", ".html", ".htm")
        return textExtensions.any { fileName.endsWith(it, true) }
    }

    private fun getMimeType(fileName: String): String {
        val mimeTypeFromFile = when {
            fileName.endsWith(".pdf", true) -> "application/pdf"
            fileName.endsWith(".docx", true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            fileName.endsWith(".doc", true) && !fileName.endsWith(".docx", true) -> "application/msword"
            fileName.endsWith(".xlsx", true) -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            fileName.endsWith(".xls", true) && !fileName.endsWith(".xlsx", true) -> "application/vnd.ms-excel"
            fileName.endsWith(".pptx", true) -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            fileName.endsWith(".ppt", true) && !fileName.endsWith(".pptx", true) -> "application/vnd.ms-powerpoint"
            fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
            fileName.endsWith(".png", true) -> "image/png"
            fileName.endsWith(".gif", true) -> "image/gif"
            fileName.endsWith(".webp", true) -> "image/webp"
            fileName.endsWith(".mp4", true) -> "video/mp4"
            fileName.endsWith(".mov", true) -> "video/quicktime"
            fileName.endsWith(".mp3", true) -> "audio/mpeg"
            else -> null
        }
        return mimeTypeFromFile ?: "application/octet-stream"
    }

    private fun parsePdfFile(file: File): LocalParseResult {
        var parser: com.tom_roush.pdfbox.pdmodel.PDDocument? = null
        return try {
            parser = com.tom_roush.pdfbox.pdmodel.PDDocument.load(file)
            val totalPages = parser.numberOfPages
            val maxPages = 10
            val pagesToParse = minOf(totalPages, maxPages)
            val text = StringBuilder()
            text.append("【PDF文档】\n")
            if (totalPages > maxPages) {
                text.append("总页数：${totalPages}（仅解析前${maxPages}页）\n\n内容：\n")
            } else {
                text.append("页数：${totalPages}\n\n内容：\n")
            }
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
            stripper.startPage = 1
            stripper.endPage = pagesToParse
            stripper.setSortByPosition(true)
            val pdfText = stripper.getText(parser)
            val maxLength = 5000
            if (pdfText.length > maxLength) {
                text.append(pdfText.take(maxLength))
                text.append("\n\n...(内容过长，仅显示前${maxLength}字)")
            } else {
                text.append(pdfText)
            }
            parser.close()
            LocalParseResult.Success(text.toString())
        } catch (e: OutOfMemoryError) {
            LogUtils.e("PDF内存不足", e)
            parser?.close()
            LocalParseResult.Error("内存不足，请尝试更小的PDF文件")
        } catch (e: Exception) {
            LogUtils.e("PDF解析失败: ${e.message}", e)
            parser?.close()
            LocalParseResult.Error("错误：${e.message}\n\n建议：截图上传或复制PDF中的文本")
        }
    }

    private fun parseDocxFile(file: File): LocalParseResult {
        return try {
            if (!file.exists()) return LocalParseResult.Error("文件不存在，请重试")
            val text = extractDocxTextDirectly(file)
            LocalParseResult.Success("【Word文档】\n$text")
        } catch (e: OutOfMemoryError) {
            LogUtils.e("DOCX内存不足", e)
            LocalParseResult.Error("内存不足，请尝试更小的文件")
        } catch (e: Exception) {
            LogUtils.e("DOCX解析失败: ${e.message}", e)
            LocalParseResult.Error("错误：${e.message}\n\n建议：复制文档中的文本粘贴到聊天框")
        }
    }

    private fun extractDocxTextDirectly(file: File): String {
        val result = StringBuilder()
        val MAX_LENGTH = 5000
        java.util.zip.ZipInputStream(file.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xmlContent = zip.reader().readText()
                    val textPattern = Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
                    val matches = textPattern.findAll(xmlContent).toList()
                    var inParagraph = false
                    for (match in matches) {
                        val text = match.groupValues[1]
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&amp;", "&")
                            .replace("&quot;", "\"")
                            .replace("&apos;", "'")
                            .trim()
                        if (text.isNotEmpty()) {
                            if (!inParagraph) {
                                if (result.isNotEmpty()) result.append("\n")
                                inParagraph = true
                            }
                            result.append(text).append(" ")
                            if (result.length > MAX_LENGTH) break
                        }
                    }
                    break
                }
                entry = zip.nextEntry
            }
        }
        val processed = result.toString()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\n\\s*\\n"), "\n")
            .trim()
        return if (processed.length > MAX_LENGTH) {
            processed.take(MAX_LENGTH) + "\n\n...(内容过长，仅显示前${MAX_LENGTH}字)"
        } else processed
    }

    private fun parseExcelFile(file: File): LocalParseResult {
        return try {
            val fis = java.io.FileInputStream(file)
            val workbook = org.apache.poi.xssf.usermodel.XSSFWorkbook(fis)
            val text = StringBuilder()
            text.append("【Excel表格】\n工作表数量：${workbook.numberOfSheets}\n\n")
            val sheet = workbook.getSheetAt(0)
            text.append("工作表1：${sheet.sheetName}\n")
            var rowCount = 0
            val maxRows = 100
            val maxCols = 20
            for (row in sheet) {
                if (rowCount >= maxRows) {
                    text.append("\n...(行数过多，仅显示前${maxRows}行)")
                    break
                }
                val rowData = StringBuilder()
                var colCount = 0
                for (cell in row) {
                    if (colCount >= maxCols) {
                        rowData.append(" ...(列数过多)")
                        break
                    }
                    val cellValue = when (cell.cellType) {
                        org.apache.poi.ss.usermodel.CellType.STRING -> cell.stringCellValue
                        org.apache.poi.ss.usermodel.CellType.NUMERIC -> cell.numericCellValue.toString()
                        org.apache.poi.ss.usermodel.CellType.BOOLEAN -> cell.booleanCellValue.toString()
                        org.apache.poi.ss.usermodel.CellType.FORMULA -> cell.cellFormula
                        else -> ""
                    }
                    if (cellValue.isNotEmpty()) rowData.append("[$cellValue] ")
                    colCount++
                }
                if (rowData.isNotEmpty()) text.append("第${rowCount + 1}行：$rowData\n")
                rowCount++
            }
            workbook.close()
            fis.close()
            LocalParseResult.Success(text.toString())
        } catch (e: OutOfMemoryError) {
            LogUtils.e("Excel内存不足", e)
            LocalParseResult.Error("内存不足，请尝试更小的文件")
        } catch (e: Exception) {
            LogUtils.e("Excel解析失败: ${e.message}", e)
            LocalParseResult.Error("错误：${e.message}")
        }
    }

    private fun parsePptxFile(file: File): LocalParseResult {
        return try {
            val fis = java.io.FileInputStream(file)
            val slideShow = org.apache.poi.xslf.usermodel.XMLSlideShow(fis)
            val text = StringBuilder()
            text.append("【PowerPoint演示文稿】\n幻灯片数量：${slideShow.slides.size}\n\n")
            var slideCount = 0
            val maxLength = 5000
            var charCount = 0
            for (slide in slideShow.slides) {
                slideCount++
                text.append("幻灯片${slideCount}：\n")
                val slideText = StringBuilder()
                for (shape in slide.shapes) {
                    if (shape is org.apache.poi.xslf.usermodel.XSLFTextShape) {
                        val shapeText = shape.text
                        if (shapeText.isNotEmpty()) {
                            if (charCount + shapeText.length > maxLength) {
                                slideText.append(shapeText.take(maxLength - charCount))
                                charCount = maxLength
                                break
                            }
                            slideText.append(shapeText).append("\n")
                            charCount += shapeText.length
                        }
                    }
                }
                if (slideText.isNotEmpty()) {
                    text.append(slideText.toString()).append("\n")
                }
                if (charCount >= maxLength) {
                    text.append("\n...(内容过长，仅显示前${maxLength}字符)")
                    break
                }
            }
            slideShow.close()
            fis.close()
            LocalParseResult.Success(text.toString())
        } catch (e: OutOfMemoryError) {
            LogUtils.e("PPTX内存不足", e)
            LocalParseResult.Error("内存不足，请尝试更小的文件")
        } catch (e: Exception) {
            LogUtils.e("PPTX解析失败: ${e.message}", e)
            LocalParseResult.Error("错误：${e.message}")
        }
    }

    // endregion
}
