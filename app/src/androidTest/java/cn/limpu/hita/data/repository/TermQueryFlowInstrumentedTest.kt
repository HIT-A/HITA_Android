package cn.limpu.hita.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ScoreCacheEntity
import cn.limpu.hita.data.model.eas.ScoreDetailCacheEntity
import com.limpu.component.data.DataState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class TermQueryFlowInstrumentedTest : TermRepositoryContractHarness() {
    @Test fun unusedRequestDoesNotReadCacheCallRemoteOrRevokeAnOlderExecution() {
        val fixture = fixture()
        val active = fixture.queryFlow()
        val call = fixture.scripted.awaitCall()
        fixture.clearSql()
        fixture.repository.createTermQueryRequest()
        assertTrue(fixture.sql.isEmpty())
        assertEquals(1, fixture.scripted.calls.size)
        fixture.scripted.complete(call, DataState(listOf(remoteTerm())))
        active.awaitCompletion()
        assertEquals("remote", fixture.rows().single().termName)
    }

    @Test fun cacheAndRemoteAreSeparateOrderedEventsAndRemoteMetadataSurvives() {
        val fixture = fixture()
        fixture.seed(cachedRow())
        val observed = fixture.queryFlow()
        val call = fixture.scripted.awaitCall()
        val remote = DataState(listOf(remoteTerm())).apply {
            message = "remote metadata"
            listAction = DataState.LIST_ACTION.APPEND
            stateRetried = DataState.STATE.TOKEN_INVALID
        }
        fixture.scripted.complete(call, remote)
        observed.awaitCompletion()
        assertEquals(listOf(true, false), observed.states.map { it.fromCache })
        assertEquals(listOf("cached", "remote"), observed.states.flatMap { it.data.orEmpty() }.map { it.termName })
        assertSame(remote, observed.states.last())
        assertEquals("remote metadata", observed.states.last().message)
        assertEquals(DataState.LIST_ACTION.APPEND, observed.states.last().listAction)
        assertEquals(DataState.STATE.TOKEN_INVALID, observed.states.last().stateRetried)
    }

    @Test fun uncachedCollectionPerformsNoBusinessCacheSql() {
        val fixture = fixture()
        fixture.seed(cachedRow())
        fixture.clearSql()
        val observed = fixture.queryFlow(useCache = false)
        fixture.scripted.complete(fixture.scripted.awaitCall(), DataState(listOf(remoteTerm())))
        observed.awaitCompletion()
        assertTrue(fixture.sql.none { it.sql.contains("score_term_cache", ignoreCase = true) })
        assertEquals("cached", fixture.rows().single().termName)
    }

    @Test fun cacheMissIncludesLegacyBenbuInitialState() {
        val fixture = fixture()
        val observed = fixture.queryFlow()
        fixture.scripted.complete(fixture.scripted.awaitCall(), DataState(listOf(remoteTerm())))
        observed.awaitCompletion()
        assertEquals(listOf(DataState.STATE.NOTHING, DataState.STATE.SUCCESS), observed.states.map { it.state })
    }

    @Test fun retainedIdentityCanReadOfflineCacheWithoutRemote() {
        val fixture = fixture(accountToken().apply { cookies.clear() })
        fixture.seed(cachedRow())
        val observed = fixture.queryFlow()
        observed.awaitCompletion()
        assertEquals(listOf(DataState.STATE.SUCCESS), observed.states.map { it.state })
        assertTrue(observed.states.single().fromCache)
        assertTrue(fixture.scripted.calls.isEmpty())
    }

    @Test fun offlineCacheMissReturnsNotLoggedInWithoutAnInitialState() {
        val fixture = fixture(accountToken().apply { cookies.clear() })
        val observed = fixture.queryFlow()
        observed.awaitCompletion()
        assertEquals(listOf(DataState.STATE.NOT_LOGGED_IN), observed.states.map { it.state })
    }

    @Test fun cacheSuppressesRemoteAuthenticationAndNullSuccessWithoutDeletingRows() {
        for (state in listOf(DataState.STATE.NOT_LOGGED_IN, DataState.STATE.FETCH_FAILED, DataState.STATE.SUCCESS)) {
            val fixture = fixture()
            fixture.seed(cachedRow())
            val observed = fixture.queryFlow()
            fixture.scripted.complete(fixture.scripted.awaitCall(), DataState(state, "preserve old suppression"))
            observed.awaitCompletion()
            assertEquals(1, observed.states.size)
            assertTrue(observed.states.single().fromCache)
            assertEquals("cached", fixture.rows().single().termName)
        }
    }

    @Test fun emptySuccessClearsItsOwnerAndStillAppliesRetentionAcrossOwners() {
        val fixture = fixture()
        fixture.seed(cachedRow(), cachedRow(owner = "BENBU:old", cachedAt = 1L), cachedRow(owner = "BENBU:fresh"))
        val observed = fixture.queryFlow()
        fixture.scripted.complete(fixture.scripted.awaitCall(), DataState(emptyList()))
        observed.awaitCompletion()
        assertEquals(emptyList<Any>(), observed.states.last().data)
        assertTrue(fixture.rows().isEmpty())
        assertTrue(fixture.rows("BENBU:old").isEmpty())
        assertEquals(1, fixture.rows("BENBU:fresh").size)
    }

    @Test fun eachIndependentConsumerGetsItsOwnResultWhileOnlyNewestWrites() {
        val fixture = fixture()
        val first = fixture.queryFlow()
        val firstCall = fixture.scripted.awaitCall()
        val second = fixture.queryFlow()
        val secondCall = fixture.scripted.awaitCall()
        fixture.scripted.complete(secondCall, DataState(listOf(remoteTerm(label = "second"))))
        second.awaitCompletion()
        fixture.scripted.complete(firstCall, DataState(listOf(remoteTerm(label = "first"))))
        first.awaitCompletion()
        assertEquals("first", first.states.last().data!!.single().termName)
        assertEquals("second", second.states.last().data!!.single().termName)
        assertEquals("second", fixture.rows().single().termName)
    }

    @Test fun sameSessionCookieRefreshPreservesCapturedRequest() {
        val fixture = fixture()
        val observed = fixture.queryFlow()
        val call = fixture.scripted.awaitCall()
        fixture.preferences.saveEasToken(accountToken().apply { cookies["JSESSIONID"] = "refreshed" })
        fixture.scripted.complete(call, DataState(listOf(remoteTerm())))
        observed.awaitCompletion()
        assertFalse(observed.cancelled)
        assertEquals("remote", fixture.rows().single().termName)
    }

    @Test fun identityCompletionCancelsTheOldOwnerProjection() {
        val fixture = fixture(accountToken().apply { stuId = null })
        val observed = fixture.queryFlow()
        val call = fixture.scripted.awaitCall()
        fixture.preferences.saveEasToken(accountToken())
        fixture.scripted.complete(call, DataState(listOf(remoteTerm())))
        observed.awaitCompletion()
        assertTrue(observed.cancelled)
        assertTrue(observed.states.none { it.state == DataState.STATE.SUCCESS })
        assertTrue(fixture.rows("BENBU:login-name").isEmpty())
        assertTrue(fixture.rows().isEmpty())
    }

    @Test fun pendingLogoutCleanupClearsAllThreeTablesBeforeSameOwnerReads() {
        val fixture = fixture()
        val dao = fixture.database.scoreCacheDao()
        fixture.seed(cachedRow())
        dao.saveScoreSync(ScoreCacheEntity("BENBU:student", "year", "term", "NORMAL", "[]", null, 1L))
        dao.saveDetailSync(ScoreDetailCacheEntity("BENBU:student", "detail", "{}", 1L))
        val coordinator = EasQueryCoordinator.forSession(fixture.preferences.querySessionState)
        coordinator.invalidateOwner("BENBU:student")
        val observed = fixture.queryFlow()
        fixture.scripted.complete(fixture.scripted.awaitCall(), DataState(listOf(remoteTerm())))
        observed.awaitCompletion()
        assertFalse(observed.states.any { it.fromCache })
        assertNull(dao.getScoreSync("BENBU:student", "year", "term", "NORMAL"))
        assertNull(dao.getDetailSync("BENBU:student", "detail"))
        assertEquals("remote", fixture.rows().single().termName)
        assertFalse(coordinator.hasPendingCleanup("BENBU:student"))
    }

    @Test fun cleanupFailureKeepsPendingMarkerBypassesCacheAndStillReturnsRemote() {
        val fixture = fixture()
        fixture.seed(cachedRow())
        fixture.database.openHelper.writableDatabase.execSQL(
            "CREATE TEMP TRIGGER reject_cleanup BEFORE DELETE ON score_term_cache " +
                "BEGIN SELECT RAISE(ABORT, 'injected cleanup failure'); END")
        val coordinator = EasQueryCoordinator.forSession(fixture.preferences.querySessionState)
        coordinator.invalidateOwner("BENBU:student")
        val observed = fixture.queryFlow()
        fixture.scripted.complete(fixture.scripted.awaitCall(), DataState(listOf(remoteTerm())))
        observed.awaitCompletion()
        assertFalse(observed.states.any { it.fromCache })
        assertEquals("remote", observed.states.last().data!!.single().termName)
        assertEquals("cached", fixture.rows().single().termName)
        assertTrue(coordinator.hasPendingCleanup("BENBU:student"))
        assertTrue(fixture.preferences.querySessionState.storage.contains("pending_owner:BENBU:student"))
    }

    @Test fun ticketIsRecheckedAfterWaitingForTheDatabaseTransactionLock() {
        val fixture = fixture()
        fixture.seed(cachedRow(label = "original"))
        val older = fixture.queryFlow()
        val olderCall = fixture.scripted.awaitCall()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val saveAttempted = CountDownLatch(1)
        val blocker = thread(name = "term-test-transaction-blocker") {
            fixture.database.runInTransaction {
                locked.countDown()
                check(release.await(20, TimeUnit.SECONDS)) { "Test transaction was not released" }
            }
        }
        try {
            assertTrue(locked.await(20, TimeUnit.SECONDS))
            fixture.onSql = { call ->
                if (call.thread !== blocker && call.sql.startsWith("BEGIN", ignoreCase = true)) saveAttempted.countDown()
            }
            fixture.scripted.complete(olderCall, DataState(listOf(remoteTerm(label = "older"))))
            assertTrue("Older save must actually reach the transaction boundary", saveAttempted.await(20, TimeUnit.SECONDS))
            val newest = fixture.queryFlow(useCache = false)
            fixture.scripted.complete(fixture.scripted.awaitCall(), DataState(listOf(remoteTerm(label = "newest"))))
            newest.awaitCompletion()
            release.countDown()
            older.awaitCompletion()
            assertEquals("original", fixture.rows().single().termName)
        } finally {
            release.countDown()
            blocker.join(20_000)
            fixture.onSql = {}
            assertFalse(blocker.isAlive)
        }
    }
}
