package cn.limpu.hita.data.repository.grader

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import cn.limpu.hita.data.source.web.grader.GraderAccount
import cn.limpu.hita.data.source.web.grader.GraderAssignment
import cn.limpu.hita.data.source.web.grader.GraderAssignmentDetail
import cn.limpu.hita.data.source.web.grader.GraderAuthenticationException
import cn.limpu.hita.data.source.web.grader.GraderNetworkException
import cn.limpu.hita.data.source.web.grader.GraderRemoteSnapshot
import cn.limpu.hita.data.source.web.grader.GraderSessionPolicy.Entry
import cn.limpu.hita.data.source.web.grader.GraderSubmitRemote
import cn.limpu.hita.data.source.web.grader.GraderUploadFile
import cn.limpu.hita.data.source.web.grader.GraderUploadTarget
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GraderRepositoryCancellationTest {
    private val account = GraderAccount("1", "synthetic")
    private val previous = GraderAssignment("11", "expired", "course", 1L, true)
    private val oldCookie = "sessionid=synthetic-old"
    private val freshCookie = "sessionid=synthetic-new"
    private var disk: String? = null
    private var refuseCommit = false
    private fun store() = GraderStore({ disk }) { next ->
        if (refuseCommit) false else { disk = next; true }
    }

    @Before fun immediateLiveData() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
    }

    @After fun restoreLiveData() {
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test fun cancellingDuringInitialPublicationReleasesTheLoginButton() = runBlocking {
        val dispatcher = PausedPublicationDispatcher()
        val store = GraderStore({ null }, { error("Cancelled initialization must not persist") })
        val repository = GraderRepository(store, dispatcher) { _, _, _, _ -> error("Cancelled login must not fetch") }
        val operation = launch(Dispatchers.Default) { repository.connect("sessionid=synthetic-cancelled") }
        try {
            val initialPublication = dispatcher.next()
            operation.cancel()
            initialPublication.run()
            dispatcher.next().run()
            operation.join()
            assertTrue(operation.isCancelled)
            val state = requireNotNull(repository.state.value)
            assertTrue(state.ready)
            assertFalse(state.syncing)
            assertNull(state.accountId)
            assertTrue(state.tasks.isEmpty())
            assertTrue(state.summaryItems.isEmpty())
        } finally {
            operation.cancel()
            dispatcher.release()
            operation.cancelAndJoin()
        }
    }

    @Test fun disconnectHidesSummaryAndNeverRefreshesTheDormantCookieAfterRestart() = runBlocking {
        store().replace(account, oldCookie, listOf(previous))
        val repository = GraderRepository(store(), Dispatchers.Unconfined) { _, _, _, _ -> error("Logout is local") }
        assertTrue(repository.reload())
        val loaded = requireNotNull(repository.state.value)
        assertEquals(account.id, loaded.accountId)
        assertEquals("11", loaded.tasks.single().id)
        assertEquals(1L, loaded.tasks.single().dueAt)
        assertEquals("11", loaded.summaryItems.single().id)
        assertTrue(repository.disconnect())
        val dormant = disk
        val receivedCookies = mutableListOf<String>()
        var candidate = GraderAccount("2", "synthetic other")
        val restarted = GraderRepository(store(), Dispatchers.Unconfined) { _, cookie, expectedAccountId, checkActive ->
            receivedCookies += cookie
            assertEquals(account.id, expectedAccountId)
            checkActive()
            if (candidate.id != expectedAccountId) throw GraderAuthenticationException()
            GraderRemoteSnapshot(candidate, listOf(previous), emptySet())
        }
        assertTrue(restarted.reload())
        assertFalse(restarted.refresh())
        assertTrue(receivedCookies.isEmpty())
        assertEquals(dormant, disk)
        assertEquals(oldCookie, store().load().cookie)
        val disconnected = requireNotNull(restarted.state.value)
        assertNull(disconnected.accountId)
        assertTrue(disconnected.tasks.isEmpty())
        assertTrue(disconnected.summaryItems.isEmpty())

        assertFalse(restarted.connect(freshCookie, expectedAccountId = account.id))
        assertEquals(GraderError.AUTHENTICATION, restarted.state.value?.error)
        assertNull(restarted.state.value?.accountId)
        assertEquals(dormant, disk)
        candidate = account
        assertTrue(restarted.connect(freshCookie, expectedAccountId = account.id))
        assertEquals(account.id, restarted.state.value?.accountId)
        assertEquals("11", restarted.state.value?.tasks?.single()?.id)
        assertFalse(store().load().disconnected)
        assertEquals(freshCookie, store().load().cookie)
        assertTrue(restarted.refresh())
        assertEquals(List(3) { freshCookie }, receivedCookies)
    }

    @Test fun failedDisconnectHidesAccountAndReloadRetriesWithoutDeletingCookie() = runBlocking {
        store().replace(account, oldCookie, listOf(previous))
        val repository = GraderRepository(store(), Dispatchers.Unconfined) { _, _, _, _ -> error("Dormant session must not fetch") }
        assertTrue(repository.reload())
        refuseCommit = true
        assertFalse(repository.disconnect())
        assertEquals(GraderError.STORAGE, repository.state.value?.error)
        assertNull(repository.state.value?.accountId)
        assertFalse(repository.refresh())
        refuseCommit = false
        assertTrue(repository.reload())
        assertNull(repository.state.value?.accountId)
        val restored = store().load()
        assertTrue(restored.disconnected)
        assertEquals(oldCookie, restored.cookie)
    }

    @Test fun disconnectSupersedesInFlightRefreshEvenIfItsResultReturnsNormally() = runBlocking {
        store().replace(account, oldCookie, listOf(previous))
        val fetchStarted = CountDownLatch(1)
        val allowFetch = CountDownLatch(1)
        val repository = GraderRepository(store(), Dispatchers.Unconfined) { _, _, _, checkActive ->
            checkActive()
            fetchStarted.countDown()
            check(allowFetch.await(10, TimeUnit.SECONDS))
            GraderRemoteSnapshot(account, listOf(previous.copy(id = "12")), emptySet())
        }
        val refresh = async { repository.refresh() }
        try {
            assertTrue(withContext(Dispatchers.IO) { fetchStarted.await(10, TimeUnit.SECONDS) })
            assertTrue(repository.disconnect())
        } finally {
            allowFetch.countDown()
        }
        assertFalse(refresh.await())
        assertNull(repository.state.value?.accountId)
        val restored = store().load()
        assertTrue(restored.disconnected)
        assertEquals(listOf(previous), restored.assignmentsByAccount[account.id])
    }

    @Test fun staleSubmissionPageDoesNotReadOrWriteAnotherAccountsAssignment() = runBlocking {
        val store = store()
        store.replace(account, oldCookie, listOf(previous))
        val detailReads = mutableListOf<String>()
        val remote = SubmitRemote(onDetail = { detailReads.add(it) })
        val repository = GraderRepository(store, Dispatchers.Unconfined, remote) { _, _, _, _ ->
            error("unexpected refresh")
        }

        assertNull(repository.submissionDetail("2", previous.id))
        assertEquals(GraderSubmitError.SESSION_CHANGED, repository.submit("2", previous.id, submissionFile()))
        assertTrue(detailReads.isEmpty())
        assertEquals(previous.id, repository.submissionDetail(account.id, previous.id)?.id)
        assertEquals(listOf(previous.id), detailReads)
    }

    @Test fun uploadFromPreviousSessionCannotCreateARecordAfterDisconnect() = runBlocking {
        val store = store()
        store.replace(account, oldCookie, listOf(previous))
        val startedUpload = CountDownLatch(1)
        val allowUpload = CountDownLatch(1)
        val remote = SubmitRemote(
            onUpload = {
                startedUpload.countDown()
                check(allowUpload.await(10, TimeUnit.SECONDS))
            },
            onCreate = { error("disconnected session may not create a submission") },
        )
        val repository = GraderRepository(store, Dispatchers.Unconfined, remote) { _, _, _, _ ->
            error("unexpected refresh")
        }
        val submission = async { repository.submit(account.id, previous.id, submissionFile()) }
        try {
            assertTrue(withContext(Dispatchers.IO) { startedUpload.await(10, TimeUnit.SECONDS) })
            assertTrue(repository.disconnect())
        } finally {
            allowUpload.countDown()
        }
        assertEquals(GraderSubmitError.SESSION_CHANGED, submission.await())
        assertTrue(store.load().submittedByAccount[account.id].orEmpty().isEmpty())
    }

    @Test fun lastStepTimeoutIsUnconfirmedButLocalFailureAfterSuccessIsNot() = runBlocking {
        val store = store()
        store.replace(account, oldCookie, listOf(previous))
        var lostResponse = true
        val remote = SubmitRemote(onCreate = { if (lostResponse) throw GraderNetworkException() })
        val repository = GraderRepository(store, Dispatchers.Unconfined, remote) { _, _, _, _ ->
            error("unexpected refresh")
        }
        assertTrue(repository.reload())

        assertEquals(GraderSubmitError.UNCONFIRMED, repository.submit(account.id, previous.id, submissionFile()))
        assertFalse(repository.state.value!!.tasks.single().submitted)
        lostResponse = false
        refuseCommit = true
        assertNull(repository.submit(account.id, previous.id, submissionFile()))
        assertEquals(GraderError.STORAGE, repository.state.value?.error)
    }

    @Test fun earlierRefreshCannotEraseAConfirmedSubmission() = runBlocking {
        val store = store()
        store.replace(account, oldCookie, listOf(previous))
        val fetchStarted = CountDownLatch(1)
        val allowFetch = CountDownLatch(1)
        val repository = GraderRepository(store, Dispatchers.Unconfined, SubmitRemote()) { _, _, _, _ ->
            fetchStarted.countDown()
            check(allowFetch.await(10, TimeUnit.SECONDS))
            GraderRemoteSnapshot(account, listOf(previous), emptySet())
        }
        val refresh = async { repository.refresh() }
        try {
            assertTrue(withContext(Dispatchers.IO) { fetchStarted.await(10, TimeUnit.SECONDS) })
            assertNull(repository.submit(account.id, previous.id, submissionFile()))
        } finally {
            allowFetch.countDown()
        }
        assertFalse(refresh.await())
        assertTrue(repository.state.value!!.tasks.single().submitted)
        assertEquals(setOf(previous.id), store.load().submittedByAccount[account.id])
    }

    private fun submissionFile() = GraderUploadFile("only-fixture.pdf", 1) { byteArrayOf(1).inputStream() }

    private class SubmitRemote(
        private val onDetail: (String) -> Unit = {},
        private val onUpload: () -> Unit = {},
        private val onCreate: () -> Unit = {},
    ) : GraderSubmitRemote {
        override fun assignmentDetail(entry: Entry, cookieHeader: String, assignmentId: String, fallbackTitle: String):
            GraderAssignmentDetail {
            onDetail(assignmentId)
            return GraderAssignmentDetail(assignmentId, fallbackTitle, null, 10, listOf("pdf"), true)
        }

        override fun requestUpload(entry: Entry, cookieHeader: String, fileName: String, assignmentId: String) =
            GraderUploadTarget("https://example.invalid/fixture", emptyMap(), "fixture")

        override fun upload(
            entry: Entry, cookieHeader: String, target: GraderUploadTarget, file: GraderUploadFile,
            checkActive: () -> Unit, onProgress: (Long) -> Unit,
        ) = onUpload()

        override fun finishUpload(entry: Entry, cookieHeader: String, ident: String) = Unit

        override fun createSubmission(entry: Entry, cookieHeader: String, assignmentId: String, ident: String) = onCreate()
    }

    @Test fun disconnectIsAlreadyDurableWhenPublicationIsCancelled() = runBlocking {
        store().replace(account, oldCookie, listOf(previous))
        val dispatcher = PausedPublicationDispatcher()
        val repository = GraderRepository(store(), dispatcher) { _, _, _, _ -> error("Logout is local") }
        val operation = launch(Dispatchers.Default) { repository.disconnect() }
        try {
            val publication = dispatcher.next()
            operation.cancel()
            publication.run()
            operation.join()
            assertTrue(operation.isCancelled)
            val restored = store().load()
            assertTrue(restored.disconnected)
            assertEquals(oldCookie, restored.cookie)
            assertNull(repository.state.value?.accountId)
        } finally {
            dispatcher.release()
            operation.cancelAndJoin()
        }
    }

    private class PausedPublicationDispatcher : CoroutineDispatcher() {
        private val lock = Any()
        private val queue = LinkedBlockingQueue<Runnable>()
        private var immediate = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val runNow = synchronized(lock) {
                if (immediate) true else { queue.add(block); false }
            }
            if (runNow) block.run()
        }

        fun next(): Runnable = checkNotNull(queue.poll(10, TimeUnit.SECONDS)) { "Publication did not arrive" }

        fun release() {
            val pending = synchronized(lock) {
                immediate = true
                generateSequence { queue.poll() }.toList()
            }
            pending.forEach(Runnable::run)
        }
    }
}
