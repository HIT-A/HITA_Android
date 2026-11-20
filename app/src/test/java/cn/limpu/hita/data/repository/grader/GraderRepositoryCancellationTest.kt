package cn.limpu.hita.data.repository.grader

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GraderRepositoryCancellationTest {
    @Test fun cancellingDuringInitialPublicationReleasesTheLoginButton() = runBlocking {
        val dispatcher = PausedPublicationDispatcher()
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        try {
            val store = GraderStore({ null }, { error("Cancelled initialization must not persist or fetch") })
            val repository = GraderRepository(store, dispatcher)
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
        } finally {
            dispatcher.release()
            ArchTaskExecutor.getInstance().setDelegate(null)
        }
    }

    @Test fun reloadAndDisconnectPublishCredentialFreeSummaryTogetherWithAccount() = runBlocking {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread() = true
        })
        try {
            val store = GraderStore({ null }, { true })
            store.replace(cn.limpu.hita.data.source.web.grader.GraderAccount("1", "synthetic"), "sessionid=synthetic",
                listOf(cn.limpu.hita.data.source.web.grader.GraderAssignment("11", "expired", "course", 1L, true)))
            val repository = GraderRepository(store, Dispatchers.Unconfined)
            assertTrue(repository.reload())
            val loaded = requireNotNull(repository.state.value)
            assertEquals("1", loaded.accountId)
            assertEquals("11", loaded.tasks.single().id)
            assertEquals(1L, loaded.tasks.single().dueAt)
            assertEquals("11", loaded.summaryItems.single().id)
            assertTrue(repository.disconnect())
            val disconnected = requireNotNull(repository.state.value)
            assertNull(disconnected.accountId)
            assertTrue(disconnected.tasks.isEmpty())
            assertTrue(disconnected.summaryItems.isEmpty())
        } finally { ArchTaskExecutor.getInstance().setDelegate(null) }
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
