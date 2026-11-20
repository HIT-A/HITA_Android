package cn.limpu.hita.data.repository.grader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class GraderReadCoordinatorTest {
    @Test fun `concurrent visible pages share local load and never run remote refresh`() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var calls = 0
            val coordinator = GraderReadCoordinator({ calls++; started.complete(Unit); release.await(); true }, { 0L })
            coordinator.setVisible("today", true); coordinator.setVisible("homework", true)
            val first = async { coordinator.load("today") }
            started.await()
            val second = async { coordinator.load("homework") }
            release.complete(Unit)
            assertTrue(first.await()); assertTrue(second.await()); assertEquals(1, calls)
        }
    }
    @Test fun `failure retries only explicitly or in new period and generation`() = runBlocking {
        var generation = 0L; var calls = 0
        val coordinator = GraderReadCoordinator({ calls++; false }, { generation })
        coordinator.setVisible("today", true)
        assertFalse(coordinator.load("today")); assertFalse(coordinator.load("today")); assertEquals(1, calls)
        coordinator.load("today", retry = true); assertEquals(2, calls)
        coordinator.setVisible("today", false); coordinator.setVisible("today", true)
        coordinator.load("today"); assertEquals(3, calls)
        generation++
        coordinator.load("today"); assertEquals(4, calls)
        coordinator.setVisible("today", false)
        assertFalse(coordinator.load("today")); assertEquals(4, calls)
    }
    @Test fun `cancelled load is not cached as success and new caller can recover`() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            var calls = 0
            val coordinator = GraderReadCoordinator({
                if (++calls == 1) { started.complete(Unit); CompletableDeferred<Unit>().await() }
                true
            }, { 0L })
            coordinator.setVisible("today", true)
            val first = launch { coordinator.load("today") }
            started.await(); first.cancelAndJoin()
            assertTrue(coordinator.load("today")); assertEquals(2, calls)
        }
    }
    @Test fun `concurrent explicit retries share one in flight result`() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var calls = 0
            val coordinator = GraderReadCoordinator({
                calls++
                if (calls == 2) { started.complete(Unit); release.await() }
                false
            }, { 0L })
            coordinator.setVisible("today", true); coordinator.setVisible("homework", true)
            coordinator.load("today")
            val first = async { coordinator.load("today", retry = true) }
            started.await()
            val secondStarted = CompletableDeferred<Unit>()
            val second = async { secondStarted.complete(Unit); coordinator.load("homework", retry = true) }
            secondStarted.await(); release.complete(Unit)
            assertFalse(first.await()); assertFalse(second.await()); assertEquals(2, calls)
        }
    }

}
