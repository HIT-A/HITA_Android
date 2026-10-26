package cn.limpu.hita.data.repository

import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.ScoreCacheEntity
import cn.limpu.hita.data.model.eas.ScoreDetailCacheEntity
import cn.limpu.hita.data.source.preference.EasQuerySessionState
import com.limpu.component.data.DataState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TermSessionIntegrationInstrumentedTest : TermRepositoryContractHarness() {
    @Test fun separateRepositoryFacadesSharePersistenceTicketsAndSessionInvalidation() {
        val fixture = fixture()
        val second = fixture.newFacade()
        val older = fixture.queryFlow()
        val olderCall = fixture.scripted.awaitCall()
        val newer = fixture.queryFlow(request = second.createTermQueryRequest())
        fixture.scripted.complete(fixture.scripted.awaitCall(), DataState(listOf(remoteTerm(label = "new facade"))))
        newer.awaitCompletion()
        fixture.scripted.complete(olderCall, DataState(listOf(remoteTerm(label = "old facade"))))
        older.awaitCompletion()
        assertEquals("old facade", older.states.last().data!!.single().termName)
        assertEquals("new facade", fixture.rows().single().termName)
        val pending = fixture.queryFlow(request = second.createTermQueryRequest())
        val pendingCall = fixture.scripted.awaitCall()
        main { fixture.repository.logout() }
        fixture.scripted.complete(pendingCall, DataState(listOf(remoteTerm(label = "obsolete"))))
        pending.awaitCompletion()
        drain(EasQueryCoordinator.forSession(fixture.preferences.querySessionState))
        assertTrue(pending.cancelled)
        assertFalse(second.captureQuerySession().loggedIn)
        assertTrue(fixture.rows().isEmpty())
    }

    @Test fun actualLogoutWithBlockedCleanupAndSameOwnerReloginCannotDeleteNewDataOrRestoreOldWrites() {
        val fixture = fixture()
        val dao = fixture.database.scoreCacheDao()
        fixture.seed(cachedRow(label = "old cache"))
        dao.saveScoreSync(ScoreCacheEntity("BENBU:student", "year", "term", "NORMAL", "[]", null, 1L))
        dao.saveDetailSync(ScoreDetailCacheEntity("BENBU:student", "detail", "{}", 1L))
        val old = fixture.queryFlow()
        val oldCall = fixture.scripted.awaitCall()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleanupAttempted = CountDownLatch(1)
        val blocker = thread(name = "logout-integration-transaction") {
            fixture.database.runInTransaction {
                locked.countDown()
                check(release.await(20, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(locked.await(20, TimeUnit.SECONDS))
            fixture.onSql = { call ->
                if (call.thread !== blocker && call.sql.startsWith("BEGIN", true)) cleanupAttempted.countDown()
            }
            main { fixture.repository.logout() }
            assertTrue("Actual logout must schedule cleanup", cleanupAttempted.await(20, TimeUnit.SECONDS))
            assertTrue(fixture.preferences.querySessionState.storage.contains("pending_owner:BENBU:student"))
            fixture.preferences.saveEasToken(accountToken().apply { sessionGeneration++ })
            val current = fixture.queryFlow(request = fixture.newFacade().createTermQueryRequest())
            release.countDown()
            fixture.scripted.complete(fixture.scripted.awaitCall(), DataState(listOf(remoteTerm(label = "new session"))))
            current.awaitCompletion()
            fixture.scripted.complete(oldCall, DataState(listOf(remoteTerm(label = "late old result"))))
            old.awaitCompletion()
            drain(EasQueryCoordinator.forSession(fixture.preferences.querySessionState))
            assertTrue(old.cancelled)
            assertFalse(current.states.any { it.fromCache })
            assertEquals("new session", fixture.rows().single().termName)
            assertNull(dao.getScoreSync("BENBU:student", "year", "term", "NORMAL"))
            assertNull(dao.getDetailSync("BENBU:student", "detail"))
            assertFalse(fixture.preferences.querySessionState.storage.contains("pending_owner:BENBU:student"))
        } finally {
            release.countDown()
            blocker.join(20_000)
            fixture.onSql = {}
            assertFalse(blocker.isAlive)
        }
    }

    @Test fun persistedLogoutMarkerReconstructsAndReplaysCleanupWithFreshCoordinationState() {
        val fixture = fixture()
        fixture.seed(cachedRow())
        fixture.database.openHelper.writableDatabase.execSQL(
            "CREATE TEMP TRIGGER reject_restart_cleanup BEFORE DELETE ON score_term_cache " +
                "BEGIN SELECT RAISE(ABORT, 'injected cleanup failure'); END")
        val original = EasQueryCoordinator.forSession(fixture.preferences.querySessionState)
        main { fixture.repository.logout() }
        drain(original)
        assertTrue(fixture.preferences.querySessionState.storage.contains("pending_owner:BENBU:student"))
        original.applicationScope.cancel()
        fixture.database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_restart_cleanup")
        // A new wrapper identity avoids the process registries while retaining the same persisted file.
        // This validates reconstruction, not an Android process-kill/relaunch test.
        val reopenedStorage = object : SharedPreferences by fixture.preferences.querySessionState.storage {}
        val reopened = EasQuerySessionState.forPreferences(reopenedStorage) { fixture.preferences.getEasToken() }
        val recovered = EasQueryCoordinator.forSession(reopened)
        try {
            assertTrue(recovered.hasPendingCleanup("BENBU:student"))
            recovered.replayPendingCleanup(fixture.database.scoreCacheDao())
            drain(recovered)
            assertTrue(fixture.rows().isEmpty())
            assertFalse(reopened.storage.contains("pending_owner:BENBU:student"))
            assertFalse(recovered.hasPendingCleanup("BENBU:student"))
        } finally { recovered.applicationScope.cancel() }
    }

    @Test fun cancellingActualAutomaticImportWhileWaitingForTermsPreventsLaterWorkAndReleasesTheSlot() {
        val fixture = fixture()
        fixture.clearSql()
        val results = CopyOnWriteArrayList<AutoImportResult>()
        val first = fixture.repository.startAutoImportCurrentTimetable(true, results::add)
        val oldCall = fixture.scripted.awaitCall()
        runBlocking { withTimeout(20_000) { first!!.cancelAndJoin() } }
        assertEquals(listOf(AutoImportResult.FAILED), results.toList())
        val second = fixture.repository.startAutoImportCurrentTimetable(true, results::add)
        assertNotNull(second)
        val nextCall = fixture.scripted.awaitCall()
        fixture.scripted.complete(oldCall, DataState(listOf(remoteTerm(label = "obsolete import"))))
        fixture.scripted.complete(nextCall, DataState(emptyList()))
        runBlocking { withTimeout(20_000) { second!!.join() } }
        assertEquals(listOf(AutoImportResult.FAILED, AutoImportResult.FAILED), results.toList())
        assertTrue(fixture.scripted.metadataCalls.isEmpty())
        assertEquals(2, fixture.scripted.calls.size)
        assertTrue("Automatic import's shared term Once must not write/read the term cache",
            fixture.sql.none { it.sql.contains("score_term_cache", true) })
    }

    private fun drain(coordinator: EasQueryCoordinator) = runBlocking {
        withTimeout(20_000) {
            coordinator.applicationScope.coroutineContext[Job]!!.children.toList().joinAll()
        }
    }
}
