package cn.limpu.hita.data.repository

import android.os.Looper
import androidx.lifecycle.Observer
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import com.limpu.component.data.DataState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Compatibility assertions against the real legacy repository, Room and Main thread. */
@RunWith(AndroidJUnit4::class)
class LegacyTermRepositoryContractTest : TermRepositoryContractHarness() {
    @Test
    fun bypassReturnsTheRemoteSourceAndNeverReadsOrSavesTerms() {
        val fixture = fixture()
        fixture.seed(cachedRow())
        fixture.clearSql()
        val observed = fixture.query(useCache = false)
        val call = fixture.scripted.awaitCall()
        assertSame(call.source, observed.source)
        val success = richState(DataState.STATE.SUCCESS, listOf(remoteTerm()))
        fixture.scripted.complete(call, success)
        assertSame(success, observed.await { it === success })
        assertTrue(termSql(fixture).isEmpty())
        assertEquals("cached", fixture.rows().single().termName)
    }

    @Test
    fun bypassWithoutLoginIgnoresRetainedOwnerCache() {
        val fixture = fixture(offlineToken())
        fixture.seed(cachedRow())
        fixture.clearSql()
        val observed = fixture.query(useCache = false)
        assertEquals(DataState.STATE.NOT_LOGGED_IN, observed.await { true }.state)
        assertTrue(fixture.scripted.calls.isEmpty())
        assertTrue(termSql(fixture).isEmpty())
        assertEquals("cached", fixture.rows().single().termName)
    }

    @Test
    fun absentOwnerBypassesCacheEvenWhenCacheWasRequested() {
        val fixture = fixture(accountToken().apply { stuId = "  "; id = "\t"; username = " " })
        fixture.seed(cachedRow(owner = "BENBU:"), cachedRow(owner = "BENBU:student"))
        fixture.clearSql()
        val observed = fixture.query()
        val call = fixture.scripted.awaitCall()
        assertSame(call.source, observed.source)
        val success = richState(DataState.STATE.SUCCESS, listOf(remoteTerm()))
        fixture.scripted.complete(call, success)
        assertSame(success, observed.await { it === success })
        assertTrue(termSql(fixture).isEmpty())
        assertEquals("cached", fixture.rows("BENBU:").single().termName)
    }

    @Test
    fun absentOwnerAndAbsentLoginProduceNoCacheOrRemoteWork() {
        val fixture = fixture(offlineToken().apply { stuId = null; id = null; username = null })
        val observed = fixture.query()
        assertEquals(DataState.STATE.NOT_LOGGED_IN, observed.await { true }.state)
        assertTrue(fixture.scripted.calls.isEmpty())
        assertTrue(termSql(fixture).isEmpty())
    }

    @Test
    fun ownerUsesTrimmedStudentIdThenIdThenUsername() {
        listOf(
            Triple(" student ", " internal ", " login ") to "student",
            Triple(" \t", " internal ", " login ") to "internal",
            Triple(null, " ", " login ") to "login",
        ).forEach { (identity, expected) ->
            val fixture = fixture(offlineToken().apply {
                stuId = identity.first; id = identity.second; username = identity.third
            })
            fixture.seed(
                cachedRow(owner = "BENBU:student", label = "student-row"),
                cachedRow(owner = "BENBU:internal", label = "internal-row"),
                cachedRow(owner = "BENBU:login", label = "login-row"),
            )
            fixture.clearSql()
            val observed = fixture.query()
            fixture.awaitCacheRead()
            val state = observed.await { it.state == DataState.STATE.SUCCESS }
            assertTrue(state.fromCache)
            assertEquals("$expected-row", requireNotNull(state.data).single().termName)
            assertEquals(listOf("BENBU:$expected"), termReads(fixture).single().arguments)
            assertTrue(fixture.scripted.calls.isEmpty())
            observed.close()
        }
    }

    @Test
    fun sameStudentIdIsIsolatedByCampusAndDifferentStudentIdsStaySeparate() {
        EASToken.Campus.entries.forEach { campus ->
            val fixture = fixture(offlineToken(campus))
            fixture.seed(*EASToken.Campus.entries.flatMap { storedCampus ->
                listOf(
                    cachedRow(owner = "${storedCampus.name}:student", label = storedCampus.name),
                    cachedRow(owner = "${storedCampus.name}:other", label = "other-account"),
                )
            }.toTypedArray())
            fixture.clearSql()
            val observed = fixture.query()
            fixture.awaitCacheRead()
            assertEquals(campus.name, observed.await { it.fromCache }.data?.single()?.termName)
            assertEquals(listOf("${campus.name}:student"), termReads(fixture).single().arguments)
            assertTrue(fixture.scripted.calls.isEmpty())
            assertTrue(apiRequests.isEmpty())
            assertTrue(webRequests.isEmpty())
            observed.close()
        }
    }

    @Test
    fun offlineCacheHitReturnsOnlyCachedSuccess() {
        val fixture = fixture(offlineToken())
        fixture.seed(cachedRow())
        fixture.clearSql()
        val observed = fixture.query()
        fixture.awaitCacheRead()
        assertEquals(listOf(DataState.STATE.SUCCESS), observed.states.map { it.state })
        assertTrue(observed.states.single().fromCache)
        assertTrue(fixture.scripted.calls.isEmpty())
        assertTrue(termWrites(fixture).isEmpty())
    }

    @Test
    fun offlineCacheMissReturnsNotLoggedInWithoutRemoteWork() {
        val fixture = fixture(offlineToken())
        val observed = fixture.query()
        fixture.awaitCacheRead()
        assertEquals(listOf(DataState.STATE.NOT_LOGGED_IN), observed.states.map { it.state })
        assertFalse(observed.states.single().fromCache)
        assertTrue(fixture.scripted.calls.isEmpty())
        assertTrue(termWrites(fixture).isEmpty())
    }

    @Test
    fun cacheProjectionRebuildsNamesIdsAndSortsYearAndTermCodesAsStrings() {
        val fixture = fixture(offlineToken())
        fixture.seed(
            cachedRow(year = "10", code = "9", label = "ten"),
            cachedRow(year = "9", code = "10", label = "second"),
            cachedRow(year = "9", code = "2", label = "first", current = true),
        )
        val observed = fixture.query()
        fixture.awaitCacheRead()
        val terms = requireNotNull(observed.await { it.fromCache }.data)
        assertEquals(listOf("9-2", "9-10", "10-9"), terms.map { it.id })
        assertEquals(listOf("year-9first", "year-9second", "year-10ten"), terms.map { it.name })
        assertEquals(listOf(true, false, false), terms.map { it.isCurrent })
        assertEquals(listOf("first", "second", "ten"), terms.map { it.termName })
    }

    @Test
    fun cacheHitSuppressesEveryNonSuccessStateAndNullSuccess() {
        val fixture = fixture()
        fixture.seed(cachedRow())
        fixture.clearSql()
        val observed = fixture.query()
        fixture.awaitCacheRead()
        val call = fixture.scripted.awaitCall()
        val cached = observed.await { it.fromCache }
        DataState.STATE.entries.filter { it != DataState.STATE.SUCCESS }.forEach { state ->
            fixture.scripted.complete(call, richState(state, listOf(remoteTerm())))
        }
        fixture.scripted.complete(call, richState(DataState.STATE.SUCCESS))
        assertEquals(listOf(cached), observed.states.toList())
        assertTrue(termWrites(fixture).isEmpty())
        assertEquals("cached", fixture.rows().single().termName)
    }

    @Test
    fun cacheMissForwardsEveryNonSuccessAndNullSuccessByIdentityWithoutSaving() {
        val fixture = fixture()
        val observed = fixture.query()
        fixture.awaitCacheRead()
        val call = fixture.scripted.awaitCall()
        val expected = DataState.STATE.entries.filter { it != DataState.STATE.SUCCESS }
            .map { richState(it, listOf(remoteTerm())) } + richState(DataState.STATE.SUCCESS)
        expected.forEach { fixture.scripted.complete(call, it) }
        assertEquals(expected.size, observed.states.size)
        expected.zip(observed.states).forEach { (sent, received) -> assertSame(sent, received) }
        assertTrue(termWrites(fixture).isEmpty())
        assertTrue(fixture.rows().isEmpty())
    }

    @Test
    fun remoteSuccessDoesNotTurnAnInitialCacheMissIntoErrorSuppression() {
        val fixture = fixture()
        val observed = fixture.query()
        fixture.awaitCacheRead()
        val call = fixture.scripted.awaitCall()
        val success = richState(DataState.STATE.SUCCESS, listOf(remoteTerm()))
        fixture.scripted.complete(call, success)
        fixture.awaitCacheSave()
        val failure = richState(DataState.STATE.FETCH_FAILED)
        fixture.scripted.complete(call, failure)
        assertEquals(listOf(success, failure), observed.states.toList())
        assertSame(failure, observed.states.last())
        assertEquals("remote", fixture.rows().single().termName)
    }

    @Test
    fun cacheSuccessPrecedesRemoteSuccessAndRemoteMetadataRemainsUnchanged() {
        val fixture = fixture()
        fixture.seed(cachedRow())
        val observed = fixture.query()
        fixture.awaitCacheRead()
        val cached = observed.await { it.fromCache }
        val call = fixture.scripted.awaitCall()
        val remote = richState(DataState.STATE.SUCCESS, listOf(remoteTerm()))
        fixture.scripted.complete(call, remote)
        fixture.awaitCacheSave()
        assertEquals(listOf(cached, remote), observed.states.toList())
        assertSame(remote, observed.states.last())
        assertEquals("state-SUCCESS", remote.message)
        assertEquals(DataState.LIST_ACTION.APPEND, remote.listAction)
        assertEquals(DataState.STATE.TOKEN_INVALID, remote.stateRetried)
        assertTrue(remote.fromCache)
    }

    @Test
    fun nonemptySuccessReplacesOnlyItsOwnerAndPersistsTheSupportedProjection() {
        val fixture = fixture()
        fixture.seed(cachedRow(code = "old-1"), cachedRow(code = "old-2"),
            cachedRow(owner = "BENBU:other", label = "other"))
        val observed = fixture.query()
        fixture.awaitCacheRead()
        val call = fixture.scripted.awaitCall()
        val first = remoteTerm(code = "1", label = "one").apply {
            name = "custom display"; id = "custom id"; isCurrent = true
        }
        val second = remoteTerm(code = "2", label = "two")
        val remote = richState(DataState.STATE.SUCCESS, listOf(first, second))
        fixture.scripted.complete(call, remote)
        fixture.awaitCacheSave()
        assertSame(remote, observed.states.last())
        val rows = fixture.rows()
        assertEquals(listOf("2", "1"), rows.map { it.termTermCode })
        assertEquals(listOf("two", "one"), rows.map { it.termName })
        assertEquals(listOf(false, true), rows.map { it.isCurrent })
        assertEquals(1, rows.map { it.cachedAt }.distinct().size)
        assertEquals("other", fixture.rows("BENBU:other").single().termName)
        observed.close()
        fixture.preferences.saveEasToken(offlineToken())
        val cached = fixture.query()
        fixture.awaitCacheRead()
        val restored = requireNotNull(cached.await { it.fromCache }.data).last()
        assertEquals("2025-2026-1", restored.id)
        assertEquals("year-2025-2026one", restored.name)
        assertTrue(restored.isCurrent)
    }

    @Test
    fun remoteOrderAndDuplicatesSurviveDeliveryWhileCacheKeepsTheLastDuplicate() {
        val fixture = fixture()
        val observed = fixture.query()
        fixture.awaitCacheRead()
        val call = fixture.scripted.awaitCall()
        val terms = listOf(remoteTerm(code = "2", label = "first"),
            remoteTerm(code = "1", label = "middle"), remoteTerm(code = "2", label = "last"))
        val success = richState(DataState.STATE.SUCCESS, terms)
        fixture.scripted.complete(call, success)
        fixture.awaitCacheSave()
        assertSame(terms, observed.states.single().data)
        assertEquals(listOf("first", "middle", "last"), terms.map { it.termName })
        assertEquals(listOf("last", "middle"), fixture.rows().map { it.termName })
    }

    @Test
    fun emptySuccessIsDeliveredAndClearsOnlyTheCurrentOwner() {
        val fixture = fixture()
        fixture.seed(cachedRow(), cachedRow(owner = "BENBU:other", label = "other"))
        val observed = fixture.query()
        fixture.awaitCacheRead()
        val call = fixture.scripted.awaitCall()
        val success = richState(DataState.STATE.SUCCESS, emptyList())
        fixture.scripted.complete(call, success)
        fixture.awaitCacheSave()
        assertSame(success, observed.states.last())
        assertTrue(fixture.rows().isEmpty())
        assertEquals("other", fixture.rows("BENBU:other").single().termName)
    }

    @Test
    fun bothEmptyAndNonemptySavesApply365DayRetentionAcrossOwners() {
        listOf(false, true).forEach { empty ->
            val fixture = fixture()
            val now = System.currentTimeMillis()
            fixture.seed(cachedRow(),
                cachedRow(owner = "WEIHAI:expired", cachedAt = now - 366L * DAY_MS),
                cachedRow(owner = "SHENZHEN:fresh", cachedAt = now - 364L * DAY_MS))
            fixture.clearSql()
            val observed = fixture.query()
            fixture.awaitCacheRead()
            val call = fixture.scripted.awaitCall()
            val beforeSave = System.currentTimeMillis()
            fixture.scripted.complete(call, richState(DataState.STATE.SUCCESS,
                if (empty) emptyList() else listOf(remoteTerm())))
            fixture.awaitCacheSave()
            val afterSave = System.currentTimeMillis()
            val deletion = termWrites(fixture).single { it.sql.contains("cachedAt <") }
            val cutoff = (deletion.arguments.single() as Number).toLong()
            assertTrue(cutoff in (beforeSave - 365L * DAY_MS)..(afterSave - 365L * DAY_MS))
            if (!empty) assertEquals(365L * DAY_MS, fixture.rows().single().cachedAt - cutoff)
            assertTrue(fixture.rows("WEIHAI:expired").isEmpty())
            assertEquals(1, fixture.rows("SHENZHEN:fresh").size)
            observed.close()
        }
    }

    @Test
    fun retentionDaoPreservesRowsExactlyAtItsCutoff() {
        val fixture = fixture()
        val cutoff = 1_000_000L
        fixture.seed(cachedRow(owner = "BENBU:before", cachedAt = cutoff - 1),
            cachedRow(owner = "BENBU:at", cachedAt = cutoff),
            cachedRow(owner = "BENBU:after", cachedAt = cutoff + 1))
        fixture.database.scoreCacheDao().deleteOldTermsSync(cutoff)
        assertTrue(fixture.rows("BENBU:before").isEmpty())
        assertEquals(1, fixture.rows("BENBU:at").size)
        assertEquals(1, fixture.rows("BENBU:after").size)
    }

    @Test
    fun cacheReadAndRemoteCreationStartWithoutAnyObserverButSaveWaitsForOne() {
        val fixture = fixture()
        val observed = fixture.query(observe = false)
        fixture.awaitCacheRead()
        val call = fixture.scripted.awaitCall()
        main {
            assertFalse(observed.source.hasObservers())
            assertFalse(call.source.hasActiveObservers())
        }
        val remote = richState(DataState.STATE.SUCCESS, listOf(remoteTerm()))
        fixture.scripted.complete(call, remote)
        assertTrue(observed.states.isEmpty())
        assertTrue(termWrites(fixture).isEmpty())
        observed.attach()
        fixture.awaitCacheSave()
        assertSame(remote, observed.states.single())
        assertEquals("remote", fixture.rows().single().termName)
    }

    @Test
    fun detachingStopsRemoteObservationAndReattachingReplaysAndSavesSuccess() {
        val fixture = fixture()
        val observed = fixture.query()
        fixture.awaitCacheRead()
        val call = fixture.scripted.awaitCall()
        main { assertTrue(call.source.hasActiveObservers()) }
        observed.close()
        main { assertFalse(call.source.hasActiveObservers()) }
        val remote = richState(DataState.STATE.SUCCESS, listOf(remoteTerm()))
        fixture.scripted.complete(call, remote)
        assertTrue(observed.states.isEmpty())
        assertTrue(termWrites(fixture).isEmpty())
        observed.attach()
        fixture.awaitCacheSave()
        assertSame(remote, observed.states.single())
        assertEquals("remote", fixture.rows().single().termName)
    }

    @Test
    fun successIsPublishedOnMainBeforeTheSeparateSaveWorkerStarts() {
        val fixture = fixture()
        val observed = fixture.query()
        fixture.awaitCacheRead()
        val call = fixture.scripted.awaitCall()
        val remote = richState(DataState.STATE.SUCCESS, listOf(remoteTerm()))
        val inspected = AtomicBoolean(false)
        val observer = Observer<DataState<List<TermItem>>> { state ->
            if (state === remote) {
                assertSame(Looper.getMainLooper().thread, Thread.currentThread())
                assertTrue(termWrites(fixture).isEmpty())
                inspected.set(true)
            }
        }
        try {
            main { observed.source.observeForever(observer) }
            fixture.scripted.complete(call, remote)
            fixture.awaitCacheSave()
            assertTrue(inspected.get())
            assertSame(Looper.getMainLooper().thread, call.thread)
            assertEquals(setOf("score-term-cache-load"), termReads(fixture).map { it.thread.name }.toSet())
            assertEquals(setOf("score-term-cache-save"), termWrites(fixture).map { it.thread.name }.toSet())
        } finally {
            main { observed.source.removeObserver(observer) }
        }
    }

    @Test
    fun activeObserverReceivesSuccessWhileTheRealSaveWorkerIsStillBlocked() {
        val fixture = fixture()
        fixture.seed(cachedRow())
        val observed = fixture.query()
        fixture.awaitCacheRead()
        val call = fixture.scripted.awaitCall()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.onSql = { sql ->
            if (sql.thread.name == "score-term-cache-save" &&
                sql.sql.startsWith("DELETE FROM score_term_cache WHERE ownerKey")) {
                hold(entered, release)
            }
        }
        val remote = richState(DataState.STATE.SUCCESS, listOf(remoteTerm()))
        try {
            fixture.scripted.complete(call, remote)
            assertTrue(entered.await(20, TimeUnit.SECONDS))
            assertSame(remote, observed.states.last())
        } finally {
            release.countDown()
            fixture.awaitCacheSave()
            fixture.onSql = {}
        }
        assertEquals("remote", fixture.rows().single().termName)
    }

    private fun offlineToken(campus: EASToken.Campus = EASToken.Campus.BENBU) = accountToken(campus).apply {
        accessToken = null
        cookies.clear()
        webCookies.clear()
    }

    private fun richState(state: DataState.STATE, data: List<TermItem>? = null) =
        DataState<List<TermItem>>(state).apply {
            this.data = data
            message = "state-$state"
            listAction = DataState.LIST_ACTION.APPEND
            stateRetried = DataState.STATE.TOKEN_INVALID
            fromCache = true
        }

    private fun termSql(fixture: RepositoryFixture) = fixture.sql.filter {
        it.sql.contains("score_term_cache") &&
            listOf("SELECT", "INSERT", "DELETE", "UPDATE").any(it.sql::startsWith)
    }

    private fun termReads(fixture: RepositoryFixture) = termSql(fixture).filter { it.sql.startsWith("SELECT") }
    private fun termWrites(fixture: RepositoryFixture) = termSql(fixture).filterNot { it.sql.startsWith("SELECT") }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
