package cn.limpu.hita.data.source.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cn.limpu.hita.data.model.chat.ChatMessageEntity
import cn.limpu.hita.data.model.chat.ChatSession

@Dao
interface ChatSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(session: ChatSession)

    @Delete
    fun delete(session: ChatSession)

    @Query("SELECT * FROM chat_session ORDER BY updatedAt DESC")
    fun getAll(): LiveData<List<ChatSession>>

    @Query("SELECT * FROM chat_session ORDER BY updatedAt DESC LIMIT 1")
    fun getLatest(): ChatSession?

    @Query("SELECT EXISTS(SELECT 1 FROM chat_session WHERE id = :id)")
    fun exists(id: String): Boolean

    @Query("UPDATE chat_session SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    fun updateTitle(id: String, title: String, updatedAt: Long = System.currentTimeMillis())
}

@Dao
interface ChatMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveAll(messages: List<ChatMessageEntity>)

    @Query("SELECT * FROM chat_message WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    fun getBySession(sessionId: String): LiveData<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_message WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    fun getBySessionSync(sessionId: String): List<ChatMessageEntity>

    @Query("SELECT text FROM chat_message WHERE sessionId = :sessionId AND role = 'USER' ORDER BY timestampMs ASC LIMIT 1")
    fun getFirstUserTextSync(sessionId: String): String?

    @Query("DELETE FROM chat_message WHERE sessionId = :sessionId")
    fun deleteBySession(sessionId: String)
}
