package cn.limpu.hita.data.repository.chat

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.chat.ChatMessageRecord
import cn.limpu.hita.data.model.chat.ChatSession
import cn.limpu.hita.data.model.resource.AgentResourceCard
import cn.limpu.hita.data.source.dao.ChatSessionDao
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatSessionRepositoryInstrumentedTest {
    private lateinit var db: AppDatabase
    private val repositories = mutableListOf<ChatSessionRepository>()
    private val releases = mutableListOf<CountDownLatch>()
    private val session = ChatSession(id = "session-a")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java).build()
    }
    @After fun tearDown() = runBlocking {
        releases.forEach { it.countDown() }
        repositories.forEach { it.awaitClosed() }
        db.close()
    }
    private fun repository(store: ChatSessionStore = RoomChatSessionStore(db)) = ChatSessionRepository(store).also { repositories += it }
    private fun user(text: String, time: Long = 1L) = ChatMessageRecord(ChatMessageRecord.Role.USER, text, timestampMs = time)

    @Test fun serialCreateAppendAndReadKeepTheFirstUserTitleAndResources() = runBlocking {
        val repository = repository()
        val created = repository.create(session)
        val question = "01234567890123456789012345"
        val first = repository.append(session.id, user(question))
        val second = repository.append(session.id, ChatMessageRecord(ChatMessageRecord.Role.ASSISTANT, "reply", listOf(AgentResourceCard(title = "Course", path = "local")), 2L))
        val restored = repository.restore(session.id)
        created.await()
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(listOf(question, "reply"), restored.await().map { it.text })
        assertEquals("Course", restored.await()[1].resourceCards.single().title)
        assertEquals(question.take(20) + "…", repository.latest().await()?.title)
    }

    @Test fun titleFailureRollsBackTheInsertedMessageInTheSameTransaction() = runBlocking {
        val real = db.chatSessionDao()
        val fault = Proxy.newProxyInstance(ChatSessionDao::class.java.classLoader, arrayOf(ChatSessionDao::class.java)) { _, method, args ->
            if (method.name == "updateTitle") throw IllegalStateException("injected title failure")
            method.invoke(real, *(args ?: emptyArray()))
        } as ChatSessionDao
        val repository = repository(RoomChatSessionStore(db, fault, db.chatMessageDao()))
        repository.create(session).await()
        val result = runCatching { repository.append(session.id, user("question")).await() }
        assertTrue(result.isFailure)
        assertTrue(repository.restore(session.id).await().isEmpty())
        assertEquals("新对话", repository.latest().await()?.title)
    }

    @Test fun queuedAndLateSavesCannotReviveADeletedSession() = runBlocking {
        val repository = repository()
        repository.create(session).await()
        val first = repository.append(session.id, user("before delete"))
        val deletion = repository.delete(session)
        val late = repository.append(session.id, user("after delete"))
        assertTrue(first.await())
        assertNull(deletion.await())
        assertFalse(late.await())
        assertTrue(repository.restore(session.id).await().isEmpty())
        assertFalse(db.chatSessionDao().exists(session.id))
    }

    @Test fun cancellingTheUiAwaiterDoesNotDiscardAnAcceptedWrite() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also { releases += it }
        val store = RoomChatSessionStore(db)
        val repository = repository(object : ChatSessionStore by store {
            override fun append(sessionId: String, message: ChatMessageRecord): Boolean {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return store.append(sessionId, message)
            }
        })
        repository.create(session).await()
        val accepted = repository.append(session.id, user("must survive UI clear"))
        val awaiter = launch { accepted.await() }
        withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
        awaiter.cancel()
        awaiter.join()
        release.countDown()
        assertTrue(accepted.await())
        assertEquals("must survive UI clear", repository.restore(session.id).await().single().text)
    }

    @Test fun recreatingAnExistingSessionDoesNotCascadeDeleteItsMessages() = runBlocking {
        val repository = repository()
        repository.create(session).await()
        repository.append(session.id, user("preserved")).await()
        repository.create(session.copy(title = "replacement")).await()
        assertEquals("preserved", repository.restore(session.id).await().single().text)
        assertEquals("preserved", repository.latest().await()?.title)
    }

    @Test fun closingDrainsAcceptedWritesAndRejectsNewSubmissions() = runBlocking {
        val repository = repository()
        repository.create(session)
        val saved = repository.append(session.id, user("drained"))
        repository.close()
        assertTrue(runCatching { repository.append(session.id, user("rejected")).await() }.isFailure)
        repository.awaitClosed()
        assertTrue(saved.await())
        assertEquals(listOf("drained"), db.chatMessageDao().getBySessionSync(session.id).map { it.text })
    }
}
