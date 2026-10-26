package cn.limpu.hita.data.repository

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomCacheEntity
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import com.limpu.component.data.DataState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Actual production request coordinator, with controlled cache/HTTP boundaries. */
@RunWith(AndroidJUnit4::class)
class EmptyClassroomQueryRequestInstrumentedTest {
    @Test
    fun creatingRequestAndObtainingFlowDoesNoIoUntilCollection() = runBlocking {
        val events = mutableListOf<String>()
        val request = request(
            read = { events += "read"; emptyList() },
            save = { events += "save" },
            remote = { events += "remote"; success() },
        )

        val flow = request.states()

        assertTrue(events.isEmpty())
        assertEquals(1, flow.toList().size)
        assertEquals(listOf("read", "remote", "save"), events)
    }

    @Test
    fun everyCollectionStartsItsOwnCacheAndRemoteWork() = runBlocking {
        var reads = 0
        var remotes = 0
        var saves = 0
        val request = request(
            read = { reads++; emptyList() },
            save = { saves++ },
            remote = { remotes++; success("remote-$remotes") },
        )

        val first = request.states().toList()
        val second = request.states().toList()

        assertEquals("remote-1", first.single().data?.single()?.name)
        assertEquals("remote-2", second.single().data?.single()?.name)
        assertEquals(2, reads)
        assertEquals(2, remotes)
        assertEquals(2, saves)
    }

    @Test
    fun loggedOutRequestsOnAllCampusesPublishOnlyNotLoggedInAndDoNoIo() = runBlocking {
        for (campus in EASToken.Campus.entries) {
            val calls = CopyOnWriteArrayList<String>()
            val request = request(
                token = EASToken().apply { this.campus = campus },
                read = { calls += "read"; emptyList() },
                save = { calls += "save" },
                remote = { calls += "remote"; success() },
            )

            val states = request.states().toList()

            assertEquals(listOf(DataState.STATE.NOT_LOGGED_IN), states.map { it.state })
            assertNull(states.single().data)
            assertTrue(calls.isEmpty())
        }
    }

    @Test
    fun loginAddedAfterRequestCreationDoesNotActivateLoggedOutRequest() = runBlocking {
        val token = EASToken()
        var calls = 0
        val request = request(token = token, remote = { calls++; success() })
        token.accessToken = "later-session"

        assertEquals(DataState.STATE.NOT_LOGGED_IN, request.states().toList().single().state)
        assertEquals(0, calls)
    }

    @Test
    fun creationCapturesAllTermFieldsBuildingWeekTokenAndSelectedRemote() = runBlocking {
        val token = loggedIn(EASToken.Campus.BENBU)
        val term = term().apply { id = "remote-term"; name = "custom term"; isCurrent = true }
        val building = building().apply { id = " 001 "; name = null }
        val remoteResult = success()
        var laterRemoteCalled = false
        var selectedRemote: suspend (EASToken, TermItem, BuildingItem, List<String>) -> DataState<List<ClassroomItem>> =
            { receivedToken, capturedTerm, capturedBuilding, weeks ->
                assertSame(token, receivedToken)
                assertEquals("refreshed-session", receivedToken.accessToken)
                assertNotSame(term, capturedTerm)
                assertNotSame(building, capturedBuilding)
                assertEquals(listOf("2026-2027", "original year", "1", "秋季", "remote-term", "custom term"),
                    listOf(capturedTerm.yearCode, capturedTerm.yearName, capturedTerm.termCode,
                        capturedTerm.termName, capturedTerm.id, capturedTerm.name))
                assertTrue(capturedTerm.isCurrent)
                assertEquals(" 001 ", capturedBuilding.id)
                assertNull(capturedBuilding.name)
                assertEquals(listOf("6"), weeks)
                remoteResult
            }
        val receivedSelections = CopyOnWriteArrayList<Triple<TermItem, BuildingItem, Int>>()
        val request = EmptyClassroomQueryRequest.forToken(
            token, term, building, 6,
            readCache = { t, b, w -> receivedSelections += Triple(t, b, w); emptyList() },
            saveCache = { t, b, w, _ -> receivedSelections += Triple(t, b, w) },
            queryRemote = selectedRemote,
        )
        selectedRemote = { _, _, _, _ -> laterRemoteCalled = true; success("later remote") }
        term.yearCode = "changed"
        term.yearName = "changed"
        term.termCode = "2"
        term.termName = "春季"
        term.id = "changed"
        term.name = "changed"
        term.isCurrent = false
        building.id = "changed"
        building.name = "changed"
        token.accessToken = "refreshed-session"
        token.campus = EASToken.Campus.SHENZHEN

        val states = request.states().toList()

        assertEquals(listOf(DataState.STATE.NOTHING, DataState.STATE.SUCCESS), states.map { it.state })
        assertSame(remoteResult, states.last())
        assertEquals(2, receivedSelections.size)
        receivedSelections.forEach { (t, b, w) ->
            assertEquals("2026-2027", t.yearCode)
            assertEquals("1", t.termCode)
            assertEquals(" 001 ", b.id)
            assertEquals(6, w)
        }
        assertFalse(laterRemoteCalled)
    }

    @Test
    fun cacheRemoteSaveAndDeliveryHappenInOrderWithoutAnInitialStateOnCacheHit() = runBlocking {
        for (campus in EASToken.Campus.entries) {
            val events = CopyOnWriteArrayList<String>()
            val remote = success("remote")
            val request = request(
                token = loggedIn(campus),
                read = { events += "read"; listOf(row("cached")) },
                save = { events += "save:${it.single().name}" },
                remote = { events += "remote"; remote },
            )
            val states = mutableListOf<DataState<List<ClassroomItem>>>()

            request.states().collect {
                states += it
                events += "emit:${it.data?.single()?.name}"
            }

            assertEquals(listOf("read", "emit:cached", "remote", "save:remote", "emit:remote"), events)
            assertTrue(states.first().fromCache)
            assertSame(remote, states.last())
        }
    }

    @Test
    fun cacheMissKeepsNothingOnlyForBenbuAndWeihaiAndPreservesRemoteMetadata() = runBlocking {
        val duplicate = classroom("same")
        val remote = DataState(listOf(duplicate, duplicate)).apply {
            message = "remote message"
            listAction = DataState.LIST_ACTION.APPEND
            stateRetried = DataState.STATE.TOKEN_INVALID
        }
        for (campus in EASToken.Campus.entries) {
            val states = request(token = loggedIn(campus), remote = { remote }).states().toList()

            val expected = if (campus == EASToken.Campus.SHENZHEN) {
                listOf(DataState.STATE.SUCCESS)
            } else listOf(DataState.STATE.NOTHING, DataState.STATE.SUCCESS)
            assertEquals(expected, states.map { it.state })
            assertSame(remote, states.last())
        }
    }

    @Test
    fun everyNonSuccessRemoteStateIsSuppressedWithCacheAndForwardedWithoutCache() = runBlocking {
        for (state in DataState.STATE.entries.filter { it != DataState.STATE.SUCCESS }) {
            val remote = DataState<List<ClassroomItem>>(state, "original $state")
            var saves = 0
            val hit = request(read = { listOf(row("cached")) }, save = { saves++ }, remote = { remote })
                .states().toList()
            val miss = request(save = { saves++ }, remote = { remote }).states().toList()

            assertEquals("cached", hit.single().data?.single()?.name)
            assertTrue(hit.single().fromCache)
            assertSame(remote, miss.single())
            assertEquals(0, saves)
        }
    }

    @Test
    fun disabledCacheSkipsReadButStillSavesBeforePublishingRemoteSuccess() = runBlocking {
        val events = CopyOnWriteArrayList<String>()
        val remote = success()
        val request = request(
            useCache = false,
            read = { error("cache read is disabled") },
            save = { assertSame(remote.data, it); events += "save" },
            remote = { remote },
        )

        request.states().collect { assertSame(remote, it); events += "emit" }

        assertEquals(listOf("save", "emit"), events)
    }

    @Test
    fun emptyAndNullSuccessPayloadsSaveEmptyBatchesAndKeepOriginalStates() = runBlocking {
        val successes = listOf(
            DataState<List<ClassroomItem>>(emptyList()),
            DataState<List<ClassroomItem>>(DataState.STATE.SUCCESS),
        )
        for (remote in successes) {
            var saved: List<ClassroomItem>? = null
            val states = request(
                read = { listOf(row("cached")) }, save = { saved = it }, remote = { remote },
            ).states().toList()

            assertSame(remote, states.last())
            assertEquals(emptyList<ClassroomItem>(), saved)
            assertEquals(2, states.size)
        }
    }

    @Test
    fun cacheReadFailureFallsBackToRemoteAndStillSavesSuccess() = runBlocking {
        val remote = success()
        var saved = false

        val states = request(
            read = { throw IllegalStateException("injected read failure") },
            save = { saved = true }, remote = { remote },
        ).states().toList()

        assertSame(remote, states.single())
        assertTrue(saved)
    }

    @Test
    fun invalidCachedJsonDiscardsTheWholeCacheBatchAndFallsBackToRemote() = runBlocking {
        val remote = DataState<List<ClassroomItem>>(DataState.STATE.FETCH_FAILED, "original failure")

        val states = request(
            read = { listOf(row("valid"), row("broken").copy(scheduleJson = "broken-json")) },
            remote = { remote },
        ).states().toList()

        assertSame(remote, states.single())
    }

    @Test
    fun cacheSaveFailureStillPublishesTheOriginalRemoteSuccess() = runBlocking {
        val remote = success()

        val states = request(
            save = { throw IllegalStateException("injected save failure") }, remote = { remote },
        ).states().toList()

        assertSame(remote, states.single())
    }

    @Test
    fun cacheAndRemoteBoundariesRunOffMainEvenWhenCollectedOnMain() = runBlocking {
        val boundaryOnMain = CopyOnWriteArrayList<Boolean>()
        val collectorOnMain = mutableListOf<Boolean>()
        val request = request(
            read = { boundaryOnMain += isMain(); listOf(row("cached")) },
            save = { boundaryOnMain += isMain() },
            remote = { boundaryOnMain += isMain(); success() },
        )

        withContext(Dispatchers.Main) { request.states().collect { collectorOnMain += isMain() } }

        assertEquals(listOf(false, false, false), boundaryOnMain)
        assertEquals(listOf(true, true), collectorOnMain)
    }

    @Test
    fun cancellationThrownByCacheReadIsNotConvertedIntoACacheMiss() = runBlocking {
        val cancelled = CancellationException("read cancelled")
        var remoteCalls = 0
        val request = request(read = { throw cancelled }, remote = { remoteCalls++; success() })

        assertCancellation(cancelled) { request.states().toList() }

        assertEquals(0, remoteCalls)
    }

    @Test
    fun cancellationThrownByRemoteIsPropagatedWithoutSaveOrFailureState() = runBlocking {
        val cancelled = CancellationException("remote cancelled")
        var saves = 0
        val states = mutableListOf<DataState<List<ClassroomItem>>>()
        val request = request(save = { saves++ }, remote = { throw cancelled })

        assertCancellation(cancelled) { request.states().collect { states += it } }

        assertEquals(0, saves)
        assertTrue(states.isEmpty())
    }

    @Test
    fun cancellationThrownBySaveIsNotConvertedIntoRemoteSuccess() = runBlocking {
        val cancelled = CancellationException("save cancelled")
        val states = mutableListOf<DataState<List<ClassroomItem>>>()
        val request = request(save = { throw cancelled })

        assertCancellation(cancelled) { request.states().collect { states += it } }

        assertTrue(states.isEmpty())
    }

    @Test
    fun cancelledCollectorDoesNotStartAnyCacheOrRemoteWork() = runBlocking {
        val events = CopyOnWriteArrayList<String>()
        val request = request(
            read = { events += "read"; emptyList() },
            save = { events += "save" }, remote = { events += "remote"; success() },
        )

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            request.states().collect { events += "emit" }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(events.isEmpty())
    }

    @Test
    fun cancellationWhileSynchronousCacheReadIsBlockedPreventsCacheDeliveryAndRemote() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val request = request(
            read = { entered.countDown(); release.await(10, TimeUnit.SECONDS); listOf(row("late")) },
            remote = { events += "remote"; success() },
        )
        val job = launch(Dispatchers.Default) { request.states().collect { events += "emit" } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            job.join()

            assertTrue(job.isCancelled)
            assertTrue(events.isEmpty())
        } finally {
            release.countDown()
            job.cancelAndJoin()
        }
    }

    @Test
    fun cancellingFromCacheCollectorPreventsRemoteAndSave() = runBlocking {
        val events = CopyOnWriteArrayList<String>()
        val request = request(
            read = { listOf(row("cached")) },
            save = { events += "save" }, remote = { events += "remote"; success() },
        )

        val job = launch {
            request.states().collect {
                events += "cached"
                currentCoroutineContext().cancel()
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(listOf("cached"), events)
    }

    @Test
    fun cancellingFromCampusInitialStatePreventsRemote() = runBlocking {
        var remotes = 0
        val states = mutableListOf<DataState<List<ClassroomItem>>>()
        val request = request(token = loggedIn(EASToken.Campus.BENBU), remote = { remotes++; success() })

        val job = launch {
            request.states().collect {
                states += it
                currentCoroutineContext().cancel()
            }
        }
        job.join()

        assertEquals(listOf(DataState.STATE.NOTHING), states.map { it.state })
        assertEquals(0, remotes)
    }

    @Test
    fun remoteThatCompletesAfterCancellationCannotSaveOrPublish() = runBlocking {
        val entered = CountDownLatch(1)
        val pending = AtomicReference<Continuation<DataState<List<ClassroomItem>>>>()
        val events = CopyOnWriteArrayList<String>()
        val request = request(
            save = { events += "save" },
            remote = { suspendCoroutine { pending.set(it); entered.countDown() } },
        )
        val job = launch(Dispatchers.Default) { request.states().collect { events += "emit" } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            pending.getAndSet(null).resume(success("late"))
            job.join()

            assertTrue(job.isCancelled)
            assertTrue(events.isEmpty())
        } finally {
            pending.getAndSet(null)?.resume(success())
            job.cancelAndJoin()
        }
    }

    @Test
    fun remoteCancellingItsContextBeforeReturningDoesNotStartSave() = runBlocking {
        val events = CopyOnWriteArrayList<String>()
        val request = request(
            save = { events += "save" },
            remote = { currentCoroutineContext().cancel(); success() },
        )

        val job = launch { request.states().collect { events += "emit" } }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(events.isEmpty())
    }

    @Test
    fun alreadyStartedSynchronousSaveCanCompleteAfterCancellationWithoutPublishing() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val request = request(save = {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            events += "saved"
        })
        val job = launch(Dispatchers.Default) { request.states().collect { events += "emit" } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            job.join()

            assertTrue(job.isCancelled)
            assertEquals(listOf("saved"), events)
        } finally {
            release.countDown()
            job.cancelAndJoin()
        }
    }

    @Test
    fun collectorFailureIsNotMistakenForCacheFailureAndDoesNotStartRemote() = runBlocking {
        val expected = IllegalArgumentException("collector failed")
        var remotes = 0
        val request = request(read = { listOf(row("cached")) }, remote = { remotes++; success() })

        try {
            request.states().collect { throw expected }
            fail("collector failure must propagate")
        } catch (actual: IllegalArgumentException) {
            assertSame(expected, actual)
        }

        assertEquals(0, remotes)
    }

    private fun request(
        token: EASToken = loggedIn(),
        useCache: Boolean = true,
        read: () -> List<ClassroomCacheEntity> = { emptyList() },
        save: suspend (List<ClassroomItem>) -> Unit = {},
        remote: suspend () -> DataState<List<ClassroomItem>> = { success() },
    ) = EmptyClassroomQueryRequest.forToken(
        token, term(), building(), 6, useCache,
        readCache = { _, _, _ -> read() },
        saveCache = { _, _, _, classrooms -> save(classrooms) },
        queryRemote = { _, _, _, _ -> remote() },
    )

    private suspend fun assertCancellation(expected: CancellationException, block: suspend () -> Unit) {
        try {
            block()
            fail("cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
    }

    private fun loggedIn(campus: EASToken.Campus = EASToken.Campus.SHENZHEN) = EASToken().apply {
        this.campus = campus
        accessToken = "synthetic-session"
    }

    private fun term() = TermItem("2026-2027", "original year", "1", "秋季")
    private fun building() = BuildingItem().apply { id = "building"; name = "正心楼" }
    private fun classroom(name: String) = ClassroomItem().apply { this.name = name; id = name }
    private fun success(name: String = "remote") = DataState(listOf(classroom(name)))
    private fun row(name: String) = ClassroomCacheEntity(
        "building", "正心楼", "2026-2027", "1", 6, name, 60, null, "[]", 1L,
    )
    private fun isMain() = Looper.myLooper() == Looper.getMainLooper()
}
