package cn.limpu.hita.ui.main.agent

import android.app.Application
import android.net.Uri
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
import cn.limpu.hita.agent.tools.ConfirmationGate
import cn.limpu.hita.agent.tools.ConfirmationRequest
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
import kotlinx.coroutines.selects.select
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
        assertTrue(viewModel.submit(checkNotNull(viewModel.currentSessionId), ChatDraft(TextFieldValue(text)), provider))
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

    @Test fun delayedWriteApprovalRemainsWithAWhileBCompletesAndCanBeApprovedOnlyOnce() = runBlocking {
        start()
        val a = withContext(Dispatchers.Main) { checkNotNull(viewModel.currentSessionId) }
        send("question A")
        val requestA = call()
        withContext(Dispatchers.Main) { viewModel.createNewSession() }
        val b = withContext(Dispatchers.Main) { checkNotNull(viewModel.currentSessionId) }
        send("question B")
        val requestB = call()

        requestA.ask.complete(ConfirmationRequest("Add activity A", listOf("Only A")))
        eventually { viewModel.pendingConfirmation.value != null }
        val pending = checkNotNull(viewModel.pendingConfirmation.value)
        assertEquals(a, pending.owner.sessionId)
        assertFalse(pending.isVisibleIn(b, foreground = true))
        assertFalse(pending.isVisibleIn(a, foreground = false))
        withContext(Dispatchers.Main) { assertFalse(viewModel.resolveConfirmation(pending, approved = true)) }
        assertFalse(requestA.decision.isCompleted)
        requestB.result.complete(LlmChatResult.Success("answer B"))
        eventually { viewModel.messages.value?.map { it.text } == listOf("question B", "answer B") }

        withContext(Dispatchers.Main) {
            viewModel.switchToSession(a)
            assertTrue(pending.isVisibleIn(viewModel.selectedSessionId.value, foreground = true))
            assertSame(pending, viewModel.pendingConfirmation.value)
            assertTrue(viewModel.resolveConfirmation(pending, approved = true))
            assertFalse(viewModel.resolveConfirmation(pending, approved = true))
        }
        assertEquals(ConfirmationGate.Decision.APPROVED, withTimeout(10_000) { requestA.decision.await() })
        requestA.result.complete(LlmChatResult.Success("answer A"))
        eventually { viewModel.messages.value?.map { it.text } == listOf("question A", "answer A") }
        assertEquals(listOf("question B", "answer B"), repository.restore(b).await().map { it.text })
    }

    @Test fun stoppingPendingWriteClearsItsPromptAndLeavesTheSameReplyRow() = runBlocking {
        start()
        send("stop before confirming")
        val request = call()
        request.ask.complete(ConfirmationRequest("Stop this prompt", emptyList()))
        eventually { viewModel.pendingConfirmation.value != null }
        val pending = checkNotNull(viewModel.pendingConfirmation.value)
        val replyId = withContext(Dispatchers.Main) {
            val row = viewModel.messages.value!!.last()
            viewModel.stopRequest(pending.owner)
            assertFalse(viewModel.resolveConfirmation(pending, approved = true))
            assertNull(viewModel.pendingConfirmation.value)
            assertFalse(viewModel.isLoading.value!!)
            assertEquals(row.id, viewModel.messages.value!!.last().id)
            assertEquals(AgentChatMessage.Role.TRACE, viewModel.messages.value!!.last().role)
            row.id
        }
        withTimeout(10_000) { request.cancelled.await() }
        assertFalse(request.decision.isCompleted)
        assertEquals(replyId, repository.restore(pending.owner.sessionId).await().last().id)
    }

    @Test fun stoppingAfterApprovalDoesNotOfferBlindRetry() = runBlocking {
        start()
        send("write")
        val request = call()
        request.ask.complete(ConfirmationRequest("Confirm a write", emptyList()))
        eventually { viewModel.pendingConfirmation.value != null }
        val pending = checkNotNull(viewModel.pendingConfirmation.value)
        withContext(Dispatchers.Main) { assertTrue(viewModel.resolveConfirmation(pending, approved = true)) }
        assertEquals(ConfirmationGate.Decision.APPROVED, withTimeout(10_000) { request.decision.await() })
        withContext(Dispatchers.Main) {
            viewModel.stopRequest(pending.owner)
            val row = viewModel.messages.value!!.last()
            assertFalse(viewModel.hasRecoverableInput(pending.owner.sessionId, row.id))
            assertTrue(row.text.contains("可能已提交"))
        }
        withTimeout(10_000) { request.cancelled.await() }
    }

    @Test fun failedDeletionClearsAuthorizationButKeepsTheSessionRecordsAndDraft() = runBlocking {
        storeDecorator = { store -> object : ChatSessionStore by store {
            override fun delete(session: ChatSession): ChatSession? = error("Synthetic deletion failure")
        } }
        start()
        val id = withContext(Dispatchers.Main) { checkNotNull(viewModel.currentSessionId) }
        send("keep these records")
        val request = call()
        request.ask.complete(ConfirmationRequest("Never replay this", emptyList()))
        eventually { viewModel.pendingConfirmation.value != null }
        val pending = checkNotNull(viewModel.pendingConfirmation.value)
        val draft = TextFieldValue("keep this draft", TextRange(3, 7))
        withContext(Dispatchers.Main) {
            viewModel.editDraft(id, draft)
            viewModel.deleteSession(ChatSession(id = id, title = "A"))
            assertNull(viewModel.pendingConfirmation.value)
            assertFalse(viewModel.resolveConfirmation(pending, approved = true))
        }
        eventually { viewModel.deletionNotice.value!!.isNotBlank() && viewModel.deleting.value!!.isEmpty() }
        assertEquals(listOf("keep these records", "已停止生成回复。"), repository.restore(id).await().map { it.text })
        withContext(Dispatchers.Main) {
            assertEquals(id, viewModel.currentSessionId)
            assertEquals(draft, viewModel.draft.value?.text)
            assertFalse(viewModel.isLoading.value!!)
        }
    }

    @Test fun pickerAndDraftKeepOriginalSessionAndBusyOrBlankSendDoesNotConsume() = runBlocking {
        start()
        val first = Uri.parse("content://fixture/a.txt")
        val second = Uri.parse("content://fixture/b.txt")
        val draftA = TextFieldValue(" draft A ", TextRange(3, 5))
        val draftB = TextFieldValue(" ", TextRange(1))
        val a = withContext(Dispatchers.Main) { checkNotNull(viewModel.currentSessionId) }
        val b = withContext(Dispatchers.Main) {
            viewModel.editDraft(a, draftA)
            assertTrue(viewModel.beginAttachmentPicker(a))
            viewModel.createNewSession()
            val other = checkNotNull(viewModel.currentSessionId)
            viewModel.editDraft(other, draftB)
            viewModel.finishAttachmentPicker(first)
            assertFalse(viewModel.sendDraft(other, provider))
            assertEquals(ChatDraft(draftB), viewModel.draft.value)
            viewModel.switchToSession(a)
            other
        }
        eventually { !viewModel.isLoading.value!! }
        withContext(Dispatchers.Main) {
            assertEquals(ChatDraft(draftA, first), viewModel.draft.value)
            assertTrue(viewModel.beginAttachmentPicker(a))
            viewModel.finishAttachmentPicker(null)
            assertEquals(ChatDraft(draftA, first), viewModel.draft.value)
            assertTrue(viewModel.beginAttachmentPicker(a))
            viewModel.finishAttachmentPicker(second)
            assertEquals(ChatDraft(draftA, second), viewModel.draft.value)
            assertTrue(viewModel.sendDraft(a, provider))
            assertEquals(ChatDraft(), viewModel.draft.value)
            viewModel.editDraft(a, draftA)
            assertFalse(viewModel.sendDraft(a, provider))
            assertEquals(ChatDraft(draftA), viewModel.draft.value)
            viewModel.switchToSession(b)
            assertEquals(ChatDraft(draftB), viewModel.draft.value)
        }
    }

    @Test fun parseAndModelFailuresRestoreExactInputAndRetryUpdatesTheSameRows() = runBlocking {
        start()
        val a = withContext(Dispatchers.Main) { checkNotNull(viewModel.currentSessionId) }
        val input = ChatDraft(TextFieldValue("  analyse ", TextRange(2, 4)), Uri.parse("content://fixture/notes.txt"))
        withContext(Dispatchers.Main) {
            viewModel.editDraft(a, input.text)
            assertTrue(viewModel.beginAttachmentPicker(a))
            viewModel.finishAttachmentPicker(input.attachment)
            assertTrue(viewModel.sendDraft(a, provider))
        }
        withTimeout(10_000) { attachment.started.await() }
        attachment.result.complete(ChatAttachmentResult.Failure(ChatAttachmentResult.Kind.PARSE, "notes.txt"))
        eventually { !viewModel.isLoading.value!! }
        val replyId = withContext(Dispatchers.Main) {
            val failed = viewModel.messages.value!!.single()
            assertTrue(viewModel.recoverInput(a, failed.id))
            assertEquals(input.copy(retryRowId = failed.id), viewModel.draft.value)
            attachment.reset()
            assertTrue(viewModel.sendDraft(a, provider))
            failed.id
        }
        attachment.result.complete(ChatAttachmentResult.Text("notes.txt", "local fixture"))
        val firstModel = call()
        firstModel.result.complete(LlmChatResult.Error("synthetic model failure"))
        eventually { !viewModel.isLoading.value!! }
        withContext(Dispatchers.Main) {
            val current = TextFieldValue("newer draft")
            viewModel.editDraft(a, current)
            assertFalse(viewModel.recoverInput(a, replyId))
            assertEquals(current, viewModel.draft.value!!.text)
            assertTrue(viewModel.recoverInput(a, replyId, replace = true))
            assertEquals(input.copy(retryRowId = replyId), viewModel.draft.value)
            attachment.reset()
            assertTrue(viewModel.sendDraft(a, provider))
        }
        attachment.result.complete(ChatAttachmentResult.Text("notes.txt", "local fixture"))
        val secondModel = call()
        assertEquals(firstModel.history, secondModel.history)
        secondModel.result.complete(LlmChatResult.Success("success"))
        eventually { !viewModel.isLoading.value!! }
        val saved = repository.restore(a).await()
        assertEquals(2, saved.size)
        assertEquals(replyId, saved.last().id)
        assertEquals("success", saved.last().text)
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
        withContext(Dispatchers.Main) {
            assertTrue(viewModel.submit(original, ChatDraft(TextFieldValue("read"), Uri.parse("content://fixture/document")), provider))
        }
        withTimeout(10_000) { attachment.started.await() }
        withContext(Dispatchers.Main) { viewModel.createNewSession() }
        attachment.result.complete(ChatAttachmentResult.Text("notes.txt", "【文本文件】\nhello"))
        val request = call()
        request.result.complete(LlmChatResult.Success("document answer"))
        withTimeout(10_000) { while (repository.restore(original).await().size < 2) delay(10) }
        withContext(Dispatchers.Main) { assertTrue(viewModel.messages.value!!.isEmpty()) }

        val deleted = withContext(Dispatchers.Main) { requireNotNull(viewModel.currentSessionId) }
        attachment.reset()
        withContext(Dispatchers.Main) {
            assertTrue(viewModel.submit(deleted, ChatDraft(TextFieldValue("cancel"), Uri.parse("content://fixture/second")), provider))
        }
        withTimeout(10_000) { attachment.started.await() }
        withContext(Dispatchers.Main) { viewModel.deleteSession(ChatSession(id = deleted)) }
        withTimeout(10_000) { attachment.cancelled.await() }
        assertTrue(repository.restore(deleted).await().isEmpty())
    }

    private class HeldRunner : ChatRequestRunner {
        data class Call(val history: List<ChatMessage>, val trace: (AgentTraceEvent) -> Unit) {
            val result = CompletableDeferred<LlmChatResult>()
            val cancelled = CompletableDeferred<Unit>()
            val ask = CompletableDeferred<ConfirmationRequest>()
            val decision = CompletableDeferred<ConfirmationGate.Decision>()
        }
        val calls = Channel<Call>(Channel.UNLIMITED)
        override suspend fun run(
            history: List<ChatMessage>,
            media: ChatMediaContent?,
            provider: AgentProvider<TimetableAgentInput, TimetableAgentOutput>,
            onTrace: (AgentTraceEvent) -> Unit,
            confirmationGate: ConfirmationGate,
        ): LlmChatResult {
            val call = Call(history, onTrace)
            onTrace(AgentTraceEvent("react_step", "thinking", history.last().content))
            calls.send(call)
            return try {
                select {
                    call.result.onAwait { it }
                    call.ask.onAwait { request ->
                        call.decision.complete(confirmationGate.request(request))
                        call.result.await()
                    }
                }
            }
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
