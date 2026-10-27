package cn.limpu.hita.data.repository.chat

import androidx.lifecycle.LiveData
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.chat.ChatMessageEntity
import cn.limpu.hita.data.model.chat.ChatMessageRecord
import cn.limpu.hita.data.model.chat.ChatSession
import cn.limpu.hita.data.model.resource.AgentResourceCard
import cn.limpu.hita.data.source.dao.ChatMessageDao
import cn.limpu.hita.data.source.dao.ChatSessionDao
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

interface ChatSessionStore {
    val sessions: LiveData<List<ChatSession>>
    fun create(session: ChatSession)
    fun latest(): ChatSession?
    fun restore(sessionId: String): List<ChatMessageRecord>
    fun append(sessionId: String, message: ChatMessageRecord): Boolean
    fun delete(session: ChatSession): ChatSession?
}

class RoomChatSessionStore internal constructor(
    private val database: AppDatabase,
    private val sessionDao: ChatSessionDao = database.chatSessionDao(),
    private val messageDao: ChatMessageDao = database.chatMessageDao(),
) : ChatSessionStore {
    private val gson = Gson()
    private val cardsType = object : TypeToken<List<AgentResourceCard>>() {}.type
    override val sessions = sessionDao.getAll()

    override fun create(session: ChatSession) = database.runInTransaction {
        // A repeated create must never replace the row and cascade-delete existing messages.
        if (!sessionDao.exists(session.id)) sessionDao.save(session)
    }

    override fun latest(): ChatSession? = sessionDao.getLatest()

    override fun restore(sessionId: String): List<ChatMessageRecord> = messageDao.getBySessionSync(sessionId).map {
        ChatMessageRecord(
            role = ChatMessageRecord.Role.valueOf(it.role),
            text = it.text,
            resourceCards = if (it.resourceCardsJson.isBlank()) emptyList() else try {
                gson.fromJson<List<AgentResourceCard>>(it.resourceCardsJson, cardsType).orEmpty()
            } catch (_: Exception) { emptyList() },
            timestampMs = it.timestampMs,
        )
    }

    override fun append(sessionId: String, message: ChatMessageRecord): Boolean {
        var saved = false
        database.runInTransaction {
            saved = persistChatRecord(
                sessionId, message, sessionDao::exists,
                save = { id, value ->
                    messageDao.save(ChatMessageEntity(
                        sessionId = id,
                        role = value.role.name,
                        text = value.text,
                        resourceCardsJson = if (value.resourceCards.isEmpty()) "" else gson.toJson(value.resourceCards),
                        timestampMs = value.timestampMs,
                    ))
                },
                firstUserText = messageDao::getFirstUserTextSync,
                updateTitle = { id, title -> sessionDao.updateTitle(id, title) },
            )
        }
        return saved
    }

    override fun delete(session: ChatSession): ChatSession? {
        var latest: ChatSession? = null
        database.runInTransaction {
            sessionDao.delete(session) // The foreign key cascade removes messages in this transaction.
            latest = sessionDao.getLatest()
        }
        return latest
    }
}

/** The existence check, insert and title update must share the store transaction. */
internal fun persistChatRecord(
    sessionId: String,
    message: ChatMessageRecord,
    sessionExists: (String) -> Boolean,
    save: (String, ChatMessageRecord) -> Unit,
    firstUserText: (String) -> String?,
    updateTitle: (String, String) -> Unit,
): Boolean {
    if (!sessionExists(sessionId)) return false
    save(sessionId, message)
    val text = firstUserText(sessionId)
    val title = text?.take(20)?.plus(if (text.length > 20) "…" else "") ?: "新对话"
    updateTitle(sessionId, title)
    return true
}
