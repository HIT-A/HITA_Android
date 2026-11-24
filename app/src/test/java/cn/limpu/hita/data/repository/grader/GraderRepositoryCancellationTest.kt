package cn.limpu.hita.data.repository.grader

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import cn.limpu.hita.data.source.web.grader.GraderAccount
import cn.limpu.hita.data.source.web.grader.GraderAssignment
import cn.limpu.hita.data.source.web.grader.GraderAuthenticationException
import cn.limpu.hita.data.source.web.grader.GraderRemoteSnapshot
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
