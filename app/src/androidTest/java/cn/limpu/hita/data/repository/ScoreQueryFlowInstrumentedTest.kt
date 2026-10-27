package cn.limpu.hita.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.ScoreCacheEntity
import cn.limpu.hita.data.source.web.service.EASService
import com.google.gson.Gson
import com.limpu.component.data.DataState
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScoreQueryFlowInstrumentedTest {
    @Test
    fun cacheThenRemoteEmitsOrderedStatesAndPersistsRemoteRows() = runBlocking {
        BackgroundTermConsumerHarness().use { harness ->
            val term = BackgroundTermConsumerHarness.term()
            val gson = Gson()
            harness.database.scoreCacheDao().saveScoreSync(
                ScoreCacheEntity(
                    ownerKey = "BENBU:student",
                    termYearCode = term.yearCode,
                    termTermCode = term.termCode,
                    testType = EASService.TestType.NORMAL.name,
                    scoresJson = gson.toJson(listOf(BackgroundTermConsumerHarness.score("cached"))),
                    summaryJson = null,
                    cachedAt = System.currentTimeMillis(),
                )
            )
            val remote = BackgroundTermConsumerHarness.score("remote")
            harness.scoresOnce = { _, _, _ -> DataState(listOf(remote)) }

            val states = harness.repository
                .getPersonalScoresWithSummaryFlow(term, EASService.TestType.NORMAL)
                .toList()

            assertEquals(listOf(true, false), states.map { it.fromCache })
            assertEquals("cached", states[0].data?.items?.single()?.courseName)
            assertEquals("remote", states[1].data?.items?.single()?.courseName)
            val persisted = harness.database.scoreCacheDao().getScoreSync(
                "BENBU:student", term.yearCode, term.termCode, EASService.TestType.NORMAL.name,
            )
            assertTrue(persisted?.scoresJson.orEmpty().contains("remote"))
        }
    }

    @Test
    fun cancelledRemoteDoesNotWriteScoreCache() = runBlocking {
        BackgroundTermConsumerHarness().use { harness ->
            val entered = CompletableDeferred<Unit>()
            harness.scoresOnce = { _, _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            }
            val job = launch {
                harness.repository
                    .getPersonalScoresWithSummaryFlow(
                        BackgroundTermConsumerHarness.term(), EASService.TestType.NORMAL,
                    )
                    .collect()
            }
            withTimeout(10_000) { entered.await() }
            job.cancelAndJoin()
            assertEquals(
                null,
                harness.database.scoreCacheDao().getScoreSync(
                    "BENBU:student", "2025-2026", "1", EASService.TestType.NORMAL.name,
                ),
            )
        }
    }

    @Test
    fun uncachedQueryReturnsRemoteRowsWithoutReplacingExistingCache() = runBlocking {
        BackgroundTermConsumerHarness().use { harness ->
            val term = BackgroundTermConsumerHarness.term()
            val gson = Gson()
            harness.database.scoreCacheDao().saveScoreSync(
                ScoreCacheEntity(
                    ownerKey = "BENBU:student",
                    termYearCode = term.yearCode,
                    termTermCode = term.termCode,
                    testType = EASService.TestType.NORMAL.name,
                    scoresJson = gson.toJson(listOf(BackgroundTermConsumerHarness.score("original"))),
                    summaryJson = null,
                    cachedAt = System.currentTimeMillis(),
                ),
            )
            harness.scoresOnce = { _, _, _ ->
                DataState(listOf(BackgroundTermConsumerHarness.score("uncached")))
            }

            val states = harness.repository
                .getPersonalScoresWithSummaryFlow(
                    term,
                    EASService.TestType.NORMAL,
                    useCache = false,
                )
                .toList()

            assertEquals(1, states.size)
            assertEquals("uncached", states.single().data?.items?.single()?.courseName)
            val persisted = harness.database.scoreCacheDao().getScoreSync(
                "BENBU:student", term.yearCode, term.termCode, EASService.TestType.NORMAL.name,
            )
            assertTrue(persisted?.scoresJson.orEmpty().contains("original"))
            assertFalse(persisted?.scoresJson.orEmpty().contains("uncached"))
        }
    }

    @Test
    fun newerSameKeyQueryCancelsOlderBeforeItCanEmitOrWrite() = runBlocking {
        BackgroundTermConsumerHarness().use { harness ->
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            harness.scoresOnce = { _, _, _ ->
                if (calls.getAndIncrement() == 0) {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                    DataState(listOf(BackgroundTermConsumerHarness.score("old")))
                } else {
                    DataState(listOf(BackgroundTermConsumerHarness.score("new")))
                }
            }
            val first = async {
                runCatching {
                    harness.repository
                        .getPersonalScoresWithSummaryFlow(
                            BackgroundTermConsumerHarness.term(), EASService.TestType.NORMAL,
                        )
                        .toList()
                }
            }
            withTimeout(10_000) { firstEntered.await() }
            val second = harness.repository
                .getPersonalScoresWithSummaryFlow(
                    BackgroundTermConsumerHarness.term(), EASService.TestType.NORMAL,
                )
                .toList()
            releaseFirst.complete(Unit)
            val firstResult = first.await()
            assertTrue(firstResult.isFailure)
            assertTrue(firstResult.exceptionOrNull() is CancellationException)
            assertFalse(second.any { it.data?.items?.singleOrNull()?.courseName == "old" })
            assertEquals("new", second.last().data?.items?.single()?.courseName)
            val persisted = harness.database.scoreCacheDao().getScoreSync(
                "BENBU:student", "2025-2026", "1", EASService.TestType.NORMAL.name,
            )
            assertTrue(persisted?.scoresJson.orEmpty().contains("new"))
            assertFalse(persisted?.scoresJson.orEmpty().contains("old"))
        }
    }
}
