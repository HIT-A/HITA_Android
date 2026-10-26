package cn.limpu.hita.ui.main

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.repository.AutoImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainStartupCoordinatorTest {
    private class Settings : MainStartupSettings {
        var enabled = true
        var last = 0L
        val saved = mutableListOf<Long>()
        override fun autoImportEnabled() = enabled
        override fun lastSuccessfulImport() = last
        override fun saveSuccessfulImport(time: Long) { last = time; saved += time }
    }
    private val settings = Settings()
    private var clock = 6_000_000L
    private val coordinator = MainStartupCoordinator(settings) { clock }
    private val account = MainSession("SHENZHEN:one", 1, true)

    @Test fun accountReplayAndRepeatedStartsDoNotRepeatImportAttempt() {
        assertEquals(account, coordinator.accountChanged(account, false).imports().single().session)
        repeat(3) {
            assertTrue(coordinator.accountChanged(account, false).imports().isEmpty())
            assertTrue(coordinator.start(true, 100).imports().isEmpty())
        }
    }

    @Test fun switchingAccountsAndLoggingOutThenBackInAllowFreshAttempts() {
        val first = coordinator.accountChanged(account, false).imports().single()
        val other = account.copy(key = "BENBU:two", undergraduate = false)
        val second = coordinator.accountChanged(other, false).imports().single()
        assertEquals(other, second.session)
        assertTrue(second.sequence > first.sequence)
        assertTrue(coordinator.accountChanged(MainSession(null, 3, true), false).imports().isEmpty())
        assertTrue(coordinator.start(true, 100).imports().isEmpty())
        assertEquals(other, coordinator.accountChanged(other, false).imports().single().session)
    }

    @Test fun onlyCurrentSuccessfulImportRecordsCompletionTimeOnce() {
        val request = coordinator.accountChanged(account, false).imports().single()
        clock += 1234
        assertFalse(coordinator.importFinished(request, AutoImportResult.SUCCESS))
        assertEquals(listOf(clock), settings.saved)
        clock += 1234
        assertFalse(coordinator.importFinished(request, AutoImportResult.SUCCESS))
        assertEquals(1, settings.saved.size)
    }

    @Test fun failureAndDeduplicatedImportDoNotWriteSuccessOrRetryOnSameStart() {
        listOf(AutoImportResult.FAILED, AutoImportResult.SKIPPED_IN_PROGRESS).forEachIndexed { index, result ->
            val next = account.copy(key = "SHENZHEN:failure$index")
            val request = coordinator.accountChanged(next, false).imports().single()
            assertFalse(coordinator.importFinished(request, result))
            assertTrue(settings.saved.isEmpty())
            assertTrue(coordinator.start(true, 100).imports().isEmpty())
            assertTrue(coordinator.accountChanged(next, false).imports().isEmpty())
        }
    }

    @Test fun tenMinuteImportBoundaryIsInclusiveAndSuppressionDoesNotConsumeAttempt() {
        settings.last = clock - 600_000 + 1
        assertTrue(coordinator.accountChanged(account, false).imports().isEmpty())
        clock++
        assertEquals(account, coordinator.start(true, 100).imports().single().session)
        assertTrue(coordinator.start(true, 100).imports().isEmpty())
    }

    @Test fun disabledSettingDoesNotConsumeLaterEnabledImport() {
        settings.enabled = false
        assertTrue(coordinator.accountChanged(account, false).imports().isEmpty())
        settings.enabled = true
        assertEquals(account, coordinator.start(false, 100).imports().single().session)
    }

    @Test fun updateCheckRequiresStrictlyMoreThanFiveMinutesForLocalUser() {
        assertEquals(listOf(MainStartupAction.CheckUpdate(100)), coordinator.start(true, 100))
        clock += 300_000
        assertTrue(coordinator.start(true, 101).isEmpty())
        clock++
        assertEquals(listOf(MainStartupAction.CheckUpdate(102)), coordinator.start(true, 102))
    }

    @Test fun startsWithoutLocalUserPreserveUnthrottledUpdateChecks() {
        repeat(3) { assertEquals(listOf(MainStartupAction.CheckUpdate(100)), coordinator.start(false, 100)) }
        assertEquals(listOf(MainStartupAction.CheckUpdate(101)), coordinator.start(true, 101))
        assertTrue(coordinator.start(false, 102).isEmpty())
    }

    @Test fun interruptedUpdateCanRetryOnTheNextHostWithoutWaitingFiveMinutes() {
        assertEquals(listOf(MainStartupAction.CheckUpdate(100)), coordinator.start(true, 100))
        assertTrue(coordinator.start(true, 100).isEmpty())
        coordinator.retryInterruptedUpdate()
        assertEquals(listOf(MainStartupAction.CheckUpdate(100)), coordinator.start(true, 100))
    }

    @Test fun blogSyncRunsOnFirstAppearanceAndAgainOnlyAfterBecomingHidden() {
        settings.enabled = false
        assertTrue(coordinator.accountChanged(account, false).isEmpty())
        assertEquals(listOf(MainStartupAction.SyncBlog), coordinator.accountChanged(account, true))
        assertTrue(coordinator.accountChanged(account, true).isEmpty())
        assertTrue(coordinator.accountChanged(account.copy(key = "BENBU:two"), true).isEmpty())
        assertTrue(coordinator.accountChanged(MainSession(null, 2, true), false).isEmpty())
        assertEquals(listOf(MainStartupAction.SyncBlog), coordinator.accountChanged(account, true))
    }

    @Test fun oldImportCannotPersistSuccessOrPromptAfterAccountSwitchOrLogout() {
        val first = coordinator.accountChanged(account, false).imports().single()
        val other = account.copy(key = "WEIHAI:two")
        val second = coordinator.accountChanged(other, false).imports().single()
        assertFalse(coordinator.importFinished(first, AutoImportResult.SUCCESS))
        assertFalse(coordinator.importFinished(first, AutoImportResult.NOT_LOGGED_IN))
        coordinator.accountChanged(MainSession(null, 4, true), false)
        assertFalse(coordinator.importFinished(second, AutoImportResult.SUCCESS))
        assertFalse(coordinator.importFinished(second, AutoImportResult.NOT_LOGGED_IN))
        assertTrue(settings.saved.isEmpty())
    }

    @Test fun generationChangeInvalidatesOldImportWithoutRepeatingSameAccountAttempt() {
        val request = coordinator.accountChanged(account, false).imports().single()
        assertTrue(coordinator.accountChanged(account.copy(generation = 2), false).imports().isEmpty())
        assertFalse(coordinator.importFinished(request, AutoImportResult.SUCCESS))
        assertFalse(coordinator.importFinished(request, AutoImportResult.NOT_LOGGED_IN))
        assertTrue(settings.saved.isEmpty())
    }

    @Test fun expiredPromptIsDeliveredOnceAndCloseRejectsAllPendingAndNewWork() {
        val first = coordinator.accountChanged(account, false).imports().single()
        assertTrue(coordinator.importFinished(first, AutoImportResult.NOT_LOGGED_IN))
        assertFalse(coordinator.importFinished(first, AutoImportResult.NOT_LOGGED_IN))
        assertTrue(settings.saved.isEmpty())
        coordinator.reloginSucceeded()
        val second = coordinator.accountChanged(account.copy(key = "SHENZHEN:two"), true).imports().single()
        coordinator.close()
        assertFalse(coordinator.importFinished(second, AutoImportResult.SUCCESS))
        assertFalse(coordinator.importFinished(second, AutoImportResult.NOT_LOGGED_IN))
        assertTrue(coordinator.start(true, 100).isEmpty())
        assertTrue(coordinator.accountChanged(account, true).isEmpty())
        assertTrue(settings.saved.isEmpty())
    }

    @Test fun sessionSnapshotRetainsIdentityAndStudyTypeWithoutHoldingMutableToken() {
        val token = EASToken().apply {
            campus = EASToken.Campus.BENBU; username = "account"; accessToken = "session"
            sessionGeneration = 7; stutype = EASToken.TYPE.GRAD
        }
        val snapshot = MainSession.from(token)
        token.username = "other"; token.accessToken = null; token.stutype = EASToken.TYPE.UNDERGRAD
        assertEquals(MainSession("BENBU:account", 7, false), snapshot)
        assertEquals(null, MainSession.from(token).key)
    }

    private fun List<MainStartupAction>.imports() = filterIsInstance<MainStartupAction.Import>()
}
