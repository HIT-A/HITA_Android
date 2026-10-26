package cn.limpu.hita.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limpu.component.data.DataState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** These assertions first run against the original repository before the guarded Flow replaces it. */
@RunWith(AndroidJUnit4::class)
class TermRepositorySafetyRegressionTest : TermRepositoryContractHarness() {
    @Test
    fun failedReplacementKeepsTheWholePreviousBatchAndStillDeliversRemoteSuccess() {
        val fixture = fixture()
        fixture.seed(cachedRow(code = "old-1", label = "first-old"),
            cachedRow(code = "old-2", label = "second-old"))
        fixture.database.openHelper.writableDatabase.execSQL(
            "CREATE TEMP TRIGGER reject_term_batch BEFORE INSERT ON score_term_cache " +
                "WHEN NEW.termName = 'reject-new' BEGIN " +
                "SELECT RAISE(ABORT, 'injected term replacement failure'); END"
        )
        fixture.allowInjectedTermReplacementFailure()
        val observed = fixture.query()
        fixture.awaitCacheRead()
        val call = fixture.scripted.awaitCall()
        val result = DataState(listOf(remoteTerm(code = "new-1", label = "first-new"),
            remoteTerm(code = "new-2", label = "reject-new")), DataState.STATE.SUCCESS)
        fixture.scripted.complete(call, result)
        fixture.awaitCacheSave()

        assertEquals(DataState.STATE.SUCCESS, observed.states.last().state)
        assertEquals("A failed replacement must roll back its deletion and all inserts",
            setOf("first-old", "second-old"), fixture.rows().map { it.termName }.toSet())
    }

    @Test
    fun olderCompletionCannotOverwriteTheNewerRequestsCache() {
        val fixture = fixture()
        fixture.query()
        fixture.awaitCacheRead()
        val older = fixture.scripted.awaitCall()
        fixture.query()
        fixture.awaitCacheRead()
        val newer = fixture.scripted.awaitCall()

        fixture.scripted.complete(newer, DataState(listOf(remoteTerm(label = "newer"))))
        fixture.awaitCacheSave()
        assertEquals("newer", fixture.rows().single().termName)
        fixture.scripted.complete(older, DataState(listOf(remoteTerm(label = "older"))))
        fixture.awaitCacheSave()

        assertEquals("A late older query must not replace the newer shared cache",
            "newer", fixture.rows().single().termName)
    }

    @Test
    fun newerUncachedExecutionRevokesTheOlderWriteWithoutWritingItsOwnResult() {
        val fixture = fixture()
        fixture.seed(cachedRow(label = "original"))
        fixture.query()
        fixture.awaitCacheRead()
        val older = fixture.scripted.awaitCall()
        fixture.query(useCache = false)
        val uncached = fixture.scripted.awaitCall()

        fixture.scripted.complete(uncached, DataState(listOf(remoteTerm(label = "uncached"))))
        assertEquals("An uncached request must not write the cache", "original", fixture.rows().single().termName)
        fixture.scripted.complete(older, DataState(listOf(remoteTerm(label = "late-old"))))
        fixture.awaitCacheSave()

        assertEquals("The newer uncached execution must also revoke older persistence tickets",
            "original", fixture.rows().single().termName)
    }

    @Test
    fun accountSwitchRejectsThePreviousSessionsRemoteDeliveryAndCacheWrite() {
        val fixture = fixture()
        fixture.seed(cachedRow())
        val observed = fixture.query()
        fixture.awaitCacheRead()
        val previousSession = fixture.scripted.awaitCall()
        fixture.preferences.saveEasToken(accountToken().apply {
            stuId = "other-student"
            username = "other-login"
            sessionGeneration = 212L
        })

        fixture.scripted.complete(previousSession, DataState(listOf(remoteTerm(label = "previous-session"))))
        fixture.awaitCacheSave()

        assertEquals("A result from the previous account must not reach the current consumer",
            listOf("cached"), observed.states.flatMap { it.data.orEmpty() }.map { it.termName })
        assertEquals("A previous session must not recreate its cache after invalidation",
            "cached", fixture.rows().single().termName)
    }
}
