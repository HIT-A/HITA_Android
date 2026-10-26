package cn.limpu.hita.ui.eas.classroom

import androidx.lifecycle.MutableLiveData
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.classroom.ClassroomCacheEntity
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.ui.eas.classroom.EmptyClassroomViewModelContractHarness.Companion.success
import cn.limpu.hita.ui.eas.classroom.EmptyClassroomViewModelContractHarness.Companion.term
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Desired behavior: these four regressions intentionally fail before the coordinated query fix. */
@RunWith(AndroidJUnit4::class)
class EmptyClassroomViewModelRegressionInstrumentedTest {
    private var harness: EmptyClassroomViewModelContractHarness? = null
    private fun create() = EmptyClassroomViewModelContractHarness().also { harness = it }
    @After fun tearDown() { harness?.close() }

    @Test fun changedTermDoesNotQueryUsingThePreviousTermsWeek() {
        val h = create()
        h.start()
        h.select(term = term("1"), week = 3)
        h.main { h.model.selectTerm(term("2")) }
        h.flush()

        assertEquals("The next term's default week has not arrived", 1, h.requestedQueries.size)
        h.completeWeek(h.weeks.lastIndex, 8)
        assertEquals(listOf(term("1") to 3, term("2") to 8), h.requestedQueries.map { it.term to it.week })
    }

    @Test fun retryDoesNotReloadTheDefaultWeekSource() {
        val h = create()
        h.start()
        h.select(week = 3)
        h.main { h.model.selectWeek(9) }
        h.flush()
        val defaultWeekRequests = h.weeks.size
        val classroomRequests = h.requestedQueries.size
        h.main { assertTrue(h.model.retryCurrentQuery()) }
        h.flush()

        assertEquals("Retry must use the selected week without querying the default again", defaultWeekRequests, h.weeks.size)
        assertEquals(classroomRequests + 1, h.requestedQueries.size)
        assertEquals(9, h.requestedQueries.last().week)
    }

    @Test fun retryKeepsTheManualWeekWhenADefaultWeekResultArrives() {
        val h = create()
        h.start()
        h.select(week = 3)
        h.main { h.model.selectWeek(9) }
        h.flush()
        h.main { assertTrue(h.model.retryCurrentQuery()) }
        h.flush()
        h.completeWeek(h.weeks.lastIndex, 2)

        h.main { assertEquals("A delayed default cannot replace the user's selection", 9, h.model.selectedWeekLiveData.value) }
        assertEquals(9, h.requestedQueries.last().week)
    }

    @Test fun slowCacheCannotBeTheFinalPageResultAfterRemoteSuccess() {
        val h = create()
        val cacheReadEntered = CountDownLatch(1)
        val releaseCacheRead = CountDownLatch(1)
        val bothResultsDelivered = CountDownLatch(2)
        val saveFinished = CountDownLatch(1)
        val remote = MutableLiveData(success("remote room"))
        val token = EASToken().apply { accessToken = "synthetic-classroom-test-token" }
        val oldCacheSnapshot = listOf(ClassroomCacheEntity(
            buildingId = "A", buildingName = "A", termYearCode = "2026-2027", termTermCode = "1",
            week = 3, name = "cached room", capacity = 40, specialClassroom = "",
            scheduleJson = "[]", cachedAt = 1,
        ))
        h.useRepositoryQuery(
            token = token,
            readCache = { _, _, _ ->
                cacheReadEntered.countDown()
                // The snapshot is the state read before a concurrent remote save.
                // Release depends only on cache entry, so the same latch also permits
                // a future serial cache -> remote implementation to make progress.
                releaseCacheRead.await(5, TimeUnit.SECONDS)
                oldCacheSnapshot
            },
            saveCache = { _, _, _, _ -> saveFinished.countDown() },
            remote = remote,
        )
        h.onState = { bothResultsDelivered.countDown() }
        try {
            h.start()
            h.select()
            assertTrue("Cache read did not start", cacheReadEntered.await(5, TimeUnit.SECONDS))
            releaseCacheRead.countDown()
            assertTrue("Cache and remote results were not both delivered", bothResultsDelivered.await(5, TimeUnit.SECONDS))
            assertTrue("The successful remote query was not saved", saveFinished.await(5, TimeUnit.SECONDS))
            h.flush()

            h.main {
                assertEquals(listOf("remote room"), h.model.classroomLiveData.value?.data?.map { it.name })
                assertFalse(h.model.classroomLiveData.value?.fromCache ?: true)
            }
        } finally {
            releaseCacheRead.countDown()
        }
    }
}
