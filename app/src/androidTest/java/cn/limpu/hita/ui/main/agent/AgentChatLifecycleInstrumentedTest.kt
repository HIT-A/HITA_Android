package cn.limpu.hita.ui.main.agent

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.agent.attachment.ChatAttachmentLoader
import cn.limpu.hita.agent.attachment.ChatAttachmentResult
import cn.limpu.hita.agent.core.AgentProvider
import cn.limpu.hita.agent.core.AgentSession
import cn.limpu.hita.agent.core.AgentTraceEvent
import cn.limpu.hita.agent.llm.ChatMediaContent
import cn.limpu.hita.agent.llm.ChatMessage
import cn.limpu.hita.agent.llm.ChatRequestRunner
import cn.limpu.hita.agent.llm.LlmChatResult
import cn.limpu.hita.agent.timetable.TimetableAgentInput
import cn.limpu.hita.agent.timetable.TimetableAgentOutput
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.chat.ChatMessageRecord
import cn.limpu.hita.data.model.chat.ChatSession
import cn.limpu.hita.data.repository.chat.ChatSessionRepository
import cn.limpu.hita.data.repository.chat.ChatSessionStore
import cn.limpu.hita.data.repository.chat.RoomChatSessionStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentChatLifecycleInstrumentedTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: AppDatabase
    private lateinit var repository: ChatSessionRepository
    private lateinit var viewModel: AgentChatViewModel
    private val owner = ViewModelStore()
    private val runner = HeldRunner()
    private val attachment = HeldAttachment()
    private val releases = mutableListOf<CountDownLatch>()
    private var storeDecorator: (RoomChatSessionStore) -> ChatSessionStore = { it }
    private val provider = object : AgentProvider<TimetableAgentInput, TimetableAgentOutput> {
        override fun createSession(): AgentSession<TimetableAgentInput, TimetableAgentOutput> = error("Synthetic runner must not start real tools")
    }

    @Before fun setUp() { db = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build() }
    @After fun tearDown() = runBlocking {
        releases.forEach { it.countDown() }
        withContext(Dispatchers.Main) { owner.clear() }
        if (::repository.isInitialized) repository.awaitClosed()
        db.close()
    }
    private suspend fun start() {
        repository = ChatSessionRepository(storeDecorator(RoomChatSessionStore(db)))
        withContext(Dispatchers.Main) {
            viewModel = AgentChatViewModel(application, repository, attachment, runner)
            owner.put("chat", viewModel)
            viewModel.createNewSession()
        }
    }
    private suspend fun send(text: String) = withContext(Dispatchers.Main) {
        viewModel.addMessage(AgentChatMessage(AgentChatMessage.Role.USER, text))
        viewModel.sendToLlm(text, provider)
    }
    private suspend fun call(): HeldRunner.Call = withTimeout(10_000) { runner.calls.receive() }
    private suspend fun eventually(condition: () -> Boolean) = withTimeout(10_000) {
        while (!withContext(Dispatchers.Main) { condition() }) delay(10)
    }

    @Test fun backgroundReplyStaysInItsOriginalSessionAndReturnsWhenRestored() = runBlocking {
        start()
        val original = withContext(Dispatchers.Main) { requireNotNull(viewModel.currentSessionId) }
        send("question A")
        val request = call()
        withContext(Dispatchers.Main) { viewModel.createNewSession() }
        val other = withContext(Dispatchers.Main) { viewModel.currentSessionId }
        request.result.complete(LlmChatResult.Success("answer A"))
        eventually { !viewModel.isLoading.value!! && viewModel.messages.value!!.isEmpty() }
        withTimeout(10_000) { while (repository.restore(original).await().size < 2) delay(10) }
        assertEquals(other, withContext(Dispatchers.Main) { viewModel.currentSessionId })
        withContext(Dispatchers.Main) { viewModel.switchToSession(original) }
        eventually { viewModel.messages.value?.map { it.text } == listOf("question A", "answer A") }
    }

    @Test fun deletingASessionCancelsItsNetworkJobAndRejectsLateCallbacks() = runBlocking {
        start()
        val id = withContext(Dispatchers.Main) { requireNotNull(viewModel.currentSessionId) }
        send("delete me")
        val request = call()
        withContext(Dispatchers.Main) { viewModel.deleteSession(ChatSession(id = id)) }
        withTimeout(10_000) { request.cancelled.await() }
        withContext(Dispatchers.Main) { request.trace(AgentTraceEvent("react_step", "late trace")) }
        request.result.complete(LlmChatResult.Success("late answer"))
        eventually { viewModel.currentSessionId != id && viewModel.messages.value!!.isEmpty() }
        assertTrue(repository.restore(id).await().isEmpty())
    }

    @Test fun twoRequestsKeepSeparatePlaceholdersAndLoadingUntilBothFinish() = runBlocking {
        start()
        send("first")
        val first = call()
        send("second")
        val second = call()
        eventually { viewModel.messages.value!!.count { it.isPlaceholder } == 2 }
        first.result.complete(LlmChatResult.Success("first answer"))
        eventually { viewModel.messages.value!!.any { it.text == "first answer" } }
        withContext(Dispatchers.Main) {
            assertEquals(true, viewModel.isLoading.value)
            assertEquals(1, viewModel.messages.value!!.count { it.isPlaceholder })
        }
        second.result.complete(LlmChatResult.Success("second answer"))
        eventually { viewModel.isLoading.value == false }
        withContext(Dispatchers.Main) {
            assertEquals(listOf("first", "first answer", "second", "second answer"), viewModel.messages.value!!.map { it.text })
            assertFalse(viewModel.messages.value!!.any { it.isPlaceholder })
        }
        assertEquals(listOf("first", "second"), second.history.filter { it.role == "user" }.map { it.content })
    }

    @Test fun responseAfterRestoreReadTriggersARereadAfterItsQueuedInsert() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also { releases += it }
        var heldId: String? = null
        var hold = true
        storeDecorator = { store -> object : ChatSessionStore by store {
            override fun restore(sessionId: String): List<ChatMessageRecord> {
                val snapshot = store.restore(sessionId)
                if (sessionId == heldId && hold) {
                    hold = false
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                return snapshot
            }
        } }
        start()
        heldId = withContext(Dispatchers.Main) { viewModel.currentSessionId }
        send("question")
        val request = call()
        withContext(Dispatchers.Main) { viewModel.createNewSession(); viewModel.switchToSession(requireNotNull(heldId)) }
        withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
        request.result.complete(LlmChatResult.Success("arrived during read"))
        eventually { viewModel.messages.value!!.any { it.text == "arrived during read" } }
        release.countDown()
        eventually { viewModel.isLoading.value == false && viewModel.messages.value?.map { it.text } == listOf("question", "arrived during read") }
    }

    @Test fun clearingViewModelKeepsAcceptedUserWriteAndCancelsItsRequestWithoutUiDelivery() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also { releases += it }
        storeDecorator = { store -> object : ChatSessionStore by store {
            override fun append(sessionId: String, message: ChatMessageRecord): Boolean {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return store.append(sessionId, message)
            }
        } }
        start()
        val id = withContext(Dispatchers.Main) { requireNotNull(viewModel.currentSessionId) }
        send("must persist")
        val request = call()
        withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
        val before = withContext(Dispatchers.Main) { owner.clear(); viewModel.messages.value }
        withTimeout(10_000) { request.cancelled.await() }
        withContext(Dispatchers.Main) { request.trace(AgentTraceEvent("react_step", "after clear")) }
        request.result.complete(LlmChatResult.Success("after clear"))
        release.countDown()
        assertEquals(listOf("must persist"), repository.restore(id).await().map { it.text })
        assertSame(before, withContext(Dispatchers.Main) { viewModel.messages.value })
    }

    @Test fun attachmentSelectionStaysWithOriginalSessionAndDeletionCancelsProcessing() = runBlocking {
        start()
        val original = withContext(Dispatchers.Main) { requireNotNull(viewModel.currentSessionId) }
        withContext(Dispatchers.Main) { viewModel.sendWithAttachment("read", Uri.parse("content://fixture/document"), provider) }
        withTimeout(10_000) { attachment.started.await() }
        withContext(Dispatchers.Main) { viewModel.createNewSession() }
        attachment.result.complete(ChatAttachmentResult.Text("notes.txt", "【文本文件】\nhello"))
        val request = call()
        request.result.complete(LlmChatResult.Success("document answer"))
        withTimeout(10_000) { while (repository.restore(original).await().size < 2) delay(10) }
        withContext(Dispatchers.Main) { assertTrue(viewModel.messages.value!!.isEmpty()) }

        val deleted = withContext(Dispatchers.Main) { requireNotNull(viewModel.currentSessionId) }
        attachment.reset()
        withContext(Dispatchers.Main) { viewModel.sendWithAttachment("cancel", Uri.parse("content://fixture/second"), provider) }
        withTimeout(10_000) { attachment.started.await() }
        withContext(Dispatchers.Main) { viewModel.deleteSession(ChatSession(id = deleted)) }
        withTimeout(10_000) { attachment.cancelled.await() }
        assertTrue(repository.restore(deleted).await().isEmpty())
    }

    private class HeldRunner : ChatRequestRunner {
        data class Call(val history: List<ChatMessage>, val trace: (AgentTraceEvent) -> Unit) {
            val result = CompletableDeferred<LlmChatResult>()
            val cancelled = CompletableDeferred<Unit>()
        }
        val calls = Channel<Call>(Channel.UNLIMITED)
        override suspend fun run(history: List<ChatMessage>, media: ChatMediaContent?, provider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>, onTrace: (AgentTraceEvent) -> Unit): LlmChatResult {
            val call = Call(history, onTrace)
            onTrace(AgentTraceEvent("react_step", "thinking", history.last().content))
            calls.send(call)
            return try { call.result.await() }
            catch (cancelled: CancellationException) { call.cancelled.complete(Unit); throw cancelled }
        }
    }

    private class HeldAttachment : ChatAttachmentLoader {
        var started = CompletableDeferred<Unit>()
        var result = CompletableDeferred<ChatAttachmentResult>()
        var cancelled = CompletableDeferred<Unit>()
        fun reset() { started = CompletableDeferred(); result = CompletableDeferred(); cancelled = CompletableDeferred() }
        override suspend fun load(uri: Uri): ChatAttachmentResult {
            started.complete(Unit)
            return try { result.await() }
            catch (error: CancellationException) { cancelled.complete(Unit); throw error }
        }
    }
}
