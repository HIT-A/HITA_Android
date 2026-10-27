package cn.limpu.hita.ui.main

import androidx.lifecycle.MutableLiveData
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainRequestAdaptersInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun synchronousCachedValueCompletesAndRemovesTheRealObserver() = main {
        val source = MutableLiveData("cached")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val values = mutableListOf<String>()
            val job = scope.launch { values += source.awaitMainValue() }
            assertTrue(job.isCompleted)
            assertEquals(listOf("cached"), values)
            assertFalse(source.hasObservers())
            source.value = "later"
            assertEquals(listOf("cached"), values)
        } finally {
            scope.cancel()
        }
    }

    @Test fun nullEmissionDoesNotCompleteAndFirstValueRemovesTheRealObserver() = main {
        val source = MutableLiveData<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val values = mutableListOf<String>()
            val job = scope.launch { values += source.awaitMainValue() }
            assertTrue(source.hasObservers())
            source.value = null
            assertTrue(values.isEmpty())
            assertTrue(job.isActive)
            source.value = "first"
            assertEquals(listOf("first"), values)
            assertTrue(job.isCompleted)
            assertFalse(source.hasObservers())
            source.value = "second"
            assertEquals(listOf("first"), values)
        } finally {
            scope.cancel()
        }
    }

    @Test fun mainThreadCancellationImmediatelyRemovesObserverAndRejectsLateValue() = main {
        val source = MutableLiveData<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val values = mutableListOf<String>()
            val job = scope.launch { values += source.awaitMainValue() }
            assertTrue(source.hasObservers())
            job.cancel()
            assertTrue(job.isCancelled)
            assertFalse(source.hasObservers())
            source.value = "late"
            assertTrue(values.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test fun backgroundCancellationRemovesObserverOnMainAndRejectsLateValue() {
        val source = MutableLiveData<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val values = mutableListOf<String>()
        lateinit var job: Job
        try {
            main {
                job = scope.launch { values += source.awaitMainValue() }
                assertTrue(source.hasObservers())
            }
            // Instrumentation test methods execute off Main; removeObserver itself must not run here.
            job.cancel()
            instrumentation.waitForIdleSync()
            main {
                assertTrue(job.isCancelled)
                assertFalse(source.hasObservers())
                source.value = "late"
                assertTrue(values.isEmpty())
            }
        } finally {
            main { scope.cancel() }
        }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
