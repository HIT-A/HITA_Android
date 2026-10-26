package cn.limpu.hita.data.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomCacheEntity
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import com.limpu.component.data.DataState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Compatibility of the production LiveData entry point retained for synchronous AI tools. */
@RunWith(AndroidJUnit4::class)
class LegacyEmptyClassroomQueryInstrumentedTest {
    private val harnesses = mutableListOf<Harness>()

    @After
    fun tearDown() {
        harnesses.forEach { it.close() }
    }

    @Test
    fun loggedOutReturnsNotLoggedInWithoutCacheRemoteOrSave() {
        val h = harness()

        h.start(token = EASToken())
        h.observe()

        assertEquals(listOf(DataState.STATE.NOT_LOGGED_IN), h.states.map { it.state })
        assertTrue(h.reads.isEmpty())
        assertTrue(h.remoteCalls.isEmpty())
        assertTrue(h.saves.isEmpty())
    }

    @Test
    fun remoteStartsBeforeObservationButSuccessfulResultIsSavedOnlyAfterObservation() {
        val h = harness()
        val remote = state("remote")
        h.onMain { h.remote.value = remote }

        h.start(useCache = false)

        assertEquals(1, h.remoteCalls.size)
        assertFalse(h.remote.hasActiveObservers())
        assertTrue(h.saves.isEmpty())
        h.onMain { assertNull(h.result.value) }

        h.observe()
        h.awaitSave()

        assertSame(remote, h.states.single())
        assertSame(remote.data, h.saves.single().classrooms)
    }

    @Test
    fun cacheStartsBeforeObservationAndPublishesCacheFlagWithoutSavingIt() {
        val h = harness(listOf(row("cached")))

        h.start()
        h.awaitRead()

        assertFalse(h.remote.hasActiveObservers())
        h.onMain {
            assertEquals("cached", h.result.value?.data?.single()?.name)
            assertTrue(h.result.value?.fromCache == true)
        }
        assertTrue(h.saves.isEmpty())
        h.observe()
        assertEquals("cached", h.states.single().data?.single()?.name)
        assertTrue(h.states.single().fromCache)
    }

    @Test
    fun cacheDisabledSkipsReadButStillSavesObservedSuccessWithOriginalState() {
        val h = harness(listOf(row("cached")))
        val remote = state("remote").apply {
            message = "remote metadata"
            listAction = DataState.LIST_ACTION.APPEND
            stateRetried = DataState.STATE.TOKEN_INVALID
        }

        h.start(useCache = false)
        h.observe()
        h.onMain { h.remote.value = remote }
        h.awaitSave()

        assertTrue(h.reads.isEmpty())
        assertSame(remote, h.states.single())
        assertSame(remote.data, h.saves.single().classrooms)
        assertFalse(h.states.single().fromCache)
    }

    @Test
    fun nonemptyCacheSuppressesEveryNonSuccessRemoteStateIncludingAuthenticationFailures() {
        val h = harness(listOf(row("cached")))
        h.start()
        h.awaitRead()
        h.observe()

        h.onMain {
            DataState.STATE.entries.filter { it != DataState.STATE.SUCCESS }.forEach {
                h.remote.value = DataState(it, "remote $it")
            }
        }

        assertEquals(1, h.states.size)
        assertEquals("cached", h.states.single().data?.single()?.name)
        assertTrue(h.states.single().fromCache)
        assertTrue(h.saves.isEmpty())
    }

    @Test
    fun cacheMissForwardsEveryNonSuccessRemoteStateUnchangedAndDoesNotSave() {
        val h = harness()
        h.start()
        h.awaitRead()
        h.observe()
        val states = DataState.STATE.entries.filter { it != DataState.STATE.SUCCESS }.map {
            DataState<List<ClassroomItem>>(it, "remote $it")
        }

        h.onMain { states.forEach { h.remote.value = it } }

        assertEquals(states.size, h.states.size)
        states.zip(h.states).forEach { (expected, actual) -> assertSame(expected, actual) }
        assertTrue(h.saves.isEmpty())
    }

    @Test
    fun cacheHitStillForwardsRemoteSuccessAndSavesItsList() {
        val h = harness(listOf(row("cached")))
        val remote = state("remote")
        h.start()
        h.awaitRead()
        h.observe()

        h.onMain { h.remote.value = remote }
        h.awaitSave()

        assertEquals(listOf("cached", "remote"), h.states.map { it.data?.single()?.name })
        assertSame(remote, h.states.last())
        assertSame(remote.data, h.saves.single().classrooms)
    }

    @Test
    fun emptySuccessIsForwardedAndSavesAnEmptyBatch() {
        val h = harness()
        val remote = DataState<List<ClassroomItem>>(emptyList()).apply { message = "no rooms" }
        h.start(useCache = false)
        h.observe()

        h.onMain { h.remote.value = remote }
        h.awaitSave()

        assertSame(remote, h.states.single())
        assertTrue(h.saves.single().classrooms.isEmpty())
    }

    @Test
    fun successWithNullPayloadIsForwardedAndSavesAnEmptyBatch() {
        val h = harness()
        val remote = DataState<List<ClassroomItem>>(DataState.STATE.SUCCESS)
        h.start(useCache = false)
        h.observe()

        h.onMain { h.remote.value = remote }
        h.awaitSave()

        assertSame(remote, h.states.single())
        assertTrue(h.saves.single().classrooms.isEmpty())
    }

    @Test
    fun removingObserverDetachesRemoteAndPreventsLaterSuccessFromStartingSave() {
        val h = harness()
        h.start(useCache = false)
        h.observe()
        h.stopObserving()

        h.onMain { h.remote.value = state("unobserved") }

        assertFalse(h.remote.hasActiveObservers())
        assertTrue(h.states.isEmpty())
        assertTrue(h.saves.isEmpty())
    }

    @Test
    fun legacyDependenciesReceiveOriginalMutableParametersAndSingleRequestedWeek() {
        val h = harness()
        val remote = state("remote")
        h.start()
        h.awaitRead()
        h.observe()
        h.onMain { h.remote.value = remote }
        h.awaitSave()

        val query = h.remoteCalls.single()
        assertSame(h.token, query.token)
        assertSame(h.term, query.term)
        assertSame(h.building, query.building)
        assertEquals(listOf("6"), query.weeks)
        val read = h.reads.single()
        val save = h.saves.single()
        assertSame(h.term, read.term)
        assertSame(h.building, read.building)
        assertEquals(6, read.week)
        assertSame(h.term, save.term)
        assertSame(h.building, save.building)
        assertEquals(6, save.week)
    }

    private fun harness(rows: List<ClassroomCacheEntity> = emptyList()): Harness =
        Harness(rows).also { harnesses += it }

    private fun state(name: String) = DataState(listOf(ClassroomItem().apply { this.name = name }))

    private fun row(name: String) = ClassroomCacheEntity(
        "building", "正心楼", "2026-2027", "1", 6, name, 60, null, "[]", 1L,
    )

    private class Harness(private val rows: List<ClassroomCacheEntity>) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        val token = EASToken().apply { accessToken = "test-session" }
        val term = TermItem("2026-2027", "2026-2027", "1", "秋季")
        val building = BuildingItem().apply { id = "building"; name = "正心楼" }
        val remote = MutableLiveData<DataState<List<ClassroomItem>>>()
        val states = mutableListOf<DataState<List<ClassroomItem>>>()
        val reads = CopyOnWriteArrayList<Read>()
        val saves = CopyOnWriteArrayList<Save>()
        val remoteCalls = mutableListOf<Remote>()
        private val readEntered = CountDownLatch(1)
        private val saveEntered = CountDownLatch(1)
        private val threads = CopyOnWriteArrayList<Thread>()
        private val observer = Observer<DataState<List<ClassroomItem>>> { states += it }
        lateinit var result: LiveData<DataState<List<ClassroomItem>>>

        fun start(token: EASToken = this.token, useCache: Boolean = true) = onMain {
            result = LegacyEmptyClassroomQuery(
                readCache = { term, building, week ->
                    threads += Thread.currentThread()
                    reads += Read(term, building, week)
                    readEntered.countDown()
                    rows
                },
                saveCache = { term, building, week, classrooms ->
                    threads += Thread.currentThread()
                    saves += Save(term, building, week, classrooms)
                    saveEntered.countDown()
                },
                queryRemote = { queryToken, term, building, weeks ->
                    remoteCalls += Remote(queryToken, term, building, weeks)
                    remote
                },
            ).query(token, term, building, 6, useCache)
        }

        fun observe() = onMain { result.observeForever(observer) }
        fun stopObserving() = onMain { result.removeObserver(observer) }
        fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

        fun awaitRead() {
            assertTrue("cache read must start", readEntered.await(5, TimeUnit.SECONDS))
            joinWorkers()
            instrumentation.waitForIdleSync()
        }

        fun awaitSave() {
            assertTrue("cache save must start", saveEntered.await(5, TimeUnit.SECONDS))
            joinWorkers()
            instrumentation.waitForIdleSync()
        }

        fun close() {
            if (::result.isInitialized) stopObserving()
            joinWorkers()
            instrumentation.waitForIdleSync()
        }

        private fun joinWorkers() {
            threads.forEach {
                it.join(5_000)
                assertFalse("cache worker must finish", it.isAlive)
            }
        }

        data class Read(val term: TermItem, val building: BuildingItem, val week: Int)
        data class Save(
            val term: TermItem,
            val building: BuildingItem,
            val week: Int,
            val classrooms: List<ClassroomItem>,
        )
        data class Remote(
            val token: EASToken,
            val term: TermItem,
            val building: BuildingItem,
            val weeks: List<String>,
        )
    }
}
