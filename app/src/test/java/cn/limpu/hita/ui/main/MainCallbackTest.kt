package cn.limpu.hita.ui.main

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MainCallbackTest {
    @Test fun deliveryConsumesCallbackBeforeReentrantOrDuplicateDelivery() {
        val delivered = mutableListOf<Int>()
        lateinit var slot: MainCallback<Int>
        slot = MainCallback { value -> delivered += value; slot.deliver(2) }
        slot.deliver(1)
        slot.deliver(3)
        slot.close()
        assertEquals(listOf(1), delivered)
    }

    @Test fun closedSlotNeverCallsItsFormerConsumer() {
        val delivered = mutableListOf<String>()
        val slot = MainCallback<String>(delivered::add)
        slot.close()
        slot.close()
        slot.deliver("late")
        assertTrue(delivered.isEmpty())
    }

    @Test fun awaitAcceptsSynchronousCompletionAndIgnoresSecondTerminal() = runBlocking {
        assertEquals("first", awaitMainCallback<String> { callback ->
            callback("first")
            callback("second")
            throw IllegalStateException("after completion")
        })
    }

    @Test fun cancelledAwaitDoesNotResumeWhenLegacyWorkerFinishes() = runBlocking {
        lateinit var legacyCallback: (String) -> Unit
        val delivered = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            delivered += awaitMainCallback<String> { callback -> legacyCallback = callback }
        }
        job.cancelAndJoin()
        legacyCallback("late")
        assertTrue(job.isCancelled)
        assertTrue(delivered.isEmpty())
    }

    @Test fun delayedAwaitHasOnlyOneResult() = runBlocking {
        lateinit var legacyCallback: (Int) -> Unit
        val deferred = async(start = CoroutineStart.UNDISPATCHED) {
            awaitMainCallback<Int> { legacyCallback = it }
        }
        legacyCallback(1)
        legacyCallback(2)
        assertEquals(1, deferred.await())
    }

    @Test fun synchronousStartFailureIsDeliveredWithoutRetainingLateCallback() = runBlocking {
        lateinit var legacyCallback: (String) -> Unit
        val failure = IllegalStateException("start failed")
        try {
            awaitMainCallback<String> { callback -> legacyCallback = callback; throw failure }
            fail("Expected the registration failure")
        } catch (actual: IllegalStateException) {
            // Coroutine stack-trace recovery may wrap the same failure in a copied exception.
            assertEquals(failure.message, actual.message)
            assertSame(failure, if (actual === failure) actual else actual.cause)
        }
        legacyCallback("late")
    }
}
