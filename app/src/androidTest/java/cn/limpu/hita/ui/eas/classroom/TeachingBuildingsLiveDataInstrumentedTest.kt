package cn.limpu.hita.ui.eas.classroom

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.repository.TeachingBuildingsRequest
import com.limpu.component.data.DataState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@RunWith(AndroidJUnit4::class)
class TeachingBuildingsLiveDataInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store = ViewModelStore()
    private val requests = mutableListOf<PendingRequest>()
    private val states = mutableListOf<DataState<List<BuildingItem>>>()
    private lateinit var owner: Owner
    private lateinit var model: BuildingsModel
    private var initialState: DataState<List<BuildingItem>>? = null
    private var query: (suspend () -> DataState<List<BuildingItem>>)? = null
    private var factoryCalls = 0

    @Before fun setUp() = main {
        owner = Owner()
        model = BuildingsModel {
            factoryCalls++
            TeachingBuildingsRequest(initialState) {
                val queryOverride = query
                if (queryOverride != null) {
                    queryOverride()
                } else {
                    val request = PendingRequest(currentCoroutineContext().job)
                    requests += request
                    suspendCoroutine { request.continuation = it }
                }
            }
        }
        store.put("buildings", model)
        owner.registry.currentState = Lifecycle.State.CREATED
        model.buildings.observe(owner) { states += it }
    }

    @After fun tearDown() = main {
        owner.registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        requests.filterNot { it.completed }.forEach { it.complete(success("teardown")) }
    }

    @Test fun observingWithoutRefreshDoesNotStartRequest() = main {
        owner.start()
        assertEquals(0, factoryCalls)
        assertTrue(requests.isEmpty())
        assertNull(model.buildings.value)
    }

    @Test fun refreshesBeforeActivationCoalesceIntoOneRequest() = main {
        repeat(3) { model.refresh() }
        assertEquals(0, factoryCalls)

        owner.start()
        assertEquals(1, factoryCalls)
        assertEquals(1, requests.size)
        assertTrue(states.isEmpty())
    }

    @Test fun reactivationWithoutRefreshKeepsExistingRequest() = main {
        owner.start()
        model.refresh()
        val original = requests.single()

        owner.stop()
        owner.start()

        assertEquals(1, factoryCalls)
        assertFalse(original.job.isCancelled)
        val result = success("original")
        original.complete(result)
        assertEquals(listOf(result), states)
    }

    @Test fun refreshesWhileInactiveWaitAndReplaceRequestOnActivation() = main {
        owner.start()
        model.refresh()
        val original = requests.single()
        owner.stop()
        repeat(3) { model.refresh() }
        assertEquals(1, factoryCalls)
        assertFalse(original.job.isCancelled)

        owner.start()
        assertEquals(2, factoryCalls)
        assertEquals(2, requests.size)
        assertTrue(original.job.isCancelled)
        original.complete(success("obsolete"))
        assertTrue(states.isEmpty())
        val result = success("latest")
        requests.last().complete(result)
        assertEquals(listOf(result), states)
    }

    @Test fun inactiveCompletionIsDeliveredOnReactivationWithoutRestarting() = main {
        owner.start()
        model.refresh()
        owner.stop()
        val result = success("finished-while-inactive")
        requests.single().complete(result)
        assertTrue(states.isEmpty())

        owner.start()
        assertEquals(1, factoryCalls)
        assertEquals(listOf(result), states)
    }

    @Test fun refreshWithNoInitialStateRetainsPreviouslyDisplayedData() = main {
        owner.start()
        model.refresh()
        val previous = success("previous")
        requests.single().complete(previous)

        model.refresh()
        assertSame(previous, model.buildings.value)
        assertEquals(listOf(previous), states)
        val replacement = success("replacement")
        requests.last().complete(replacement)
        assertEquals(listOf(previous, replacement), states)
        assertSame(replacement, model.buildings.value)
    }

    @Test fun legacyInitialNothingIsDeliveredOnEveryRefreshWithoutLoading() = main {
        initialState = DataState(DataState.STATE.NOTHING)
        owner.start()
        model.refresh()
        val first = success("first")
        requests.single().complete(first)
        initialState = DataState(DataState.STATE.NOTHING)
        model.refresh()
        val second = success("second")
        requests.last().complete(second)

        assertEquals(
            listOf(DataState.STATE.NOTHING, DataState.STATE.SUCCESS,
                DataState.STATE.NOTHING, DataState.STATE.SUCCESS),
            states.map { it.state },
        )
        assertFalse(states.any { it.state == DataState.STATE.LOADING })
    }

    @Test fun immediateNotLoggedInIsDeliveredOnlyOnce() = main {
        query = { DataState(DataState.STATE.NOT_LOGGED_IN) }
        owner.start()
        model.refresh()

        assertEquals(1, factoryCalls)
        assertTrue(requests.isEmpty())
        assertEquals(listOf(DataState.STATE.NOT_LOGGED_IN), states.map { it.state })
    }

    @Test fun staleNonCancellableCompletionCannotReplaceNewerResult() = main {
        owner.start()
        model.refresh()
        val previous = requests.single()
        model.refresh()
        val latest = requests.last()
        assertTrue(previous.job.isCancelled)
        val result = success("latest")
        latest.complete(result)

        previous.complete(success("stale"))

        assertEquals(listOf(result), states)
        assertSame(result, model.buildings.value)
    }

    @Test fun clearingViewModelCancelsPendingJobAndDropsLateCompletion() = main {
        owner.start()
        model.refresh()
        val pending = requests.single()
        store.clear()
        assertTrue(pending.job.isCancelled)

        pending.complete(success("after-clear"))

        assertTrue(states.isEmpty())
        assertNull(model.buildings.value)
    }

    @Test fun cancellationIsNotPublishedAsFailureAndNextRefreshStillWorks() = main {
        query = { throw CancellationException("cancelled query") }
        owner.start()
        model.refresh()
        assertTrue(states.isEmpty())

        query = null
        model.refresh()
        val result = success("retry")
        requests.single().complete(result)
        assertEquals(listOf(result), states)
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun success(id: String): DataState<List<BuildingItem>> = DataState(
        listOf(BuildingItem().apply { this.id = id; name = id }),
    )

    private class PendingRequest(val job: Job) {
        lateinit var continuation: Continuation<DataState<List<BuildingItem>>>
        var completed = false
            private set

        fun complete(state: DataState<List<BuildingItem>>) {
            check(!completed)
            completed = true
            continuation.resume(state)
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun start() { registry.currentState = Lifecycle.State.STARTED }
        fun stop() { registry.currentState = Lifecycle.State.CREATED }
    }

    private class BuildingsModel(createRequest: () -> TeachingBuildingsRequest) : ViewModel() {
        private val refreshes = MutableLiveData<Int>()
        val buildings: LiveData<DataState<List<BuildingItem>>> =
            refreshes.loadTeachingBuildings(viewModelScope, createRequest)

        fun refresh() { refreshes.value = (refreshes.value ?: 0) + 1 }
    }
}
