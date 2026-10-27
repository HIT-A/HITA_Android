package cn.limpu.hita.ui.main

import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.notice.AppNotice
import cn.limpu.hita.data.notice.AppNoticeCenter
import cn.limpu.hita.data.repository.EasSettingsRepository
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainNoticePresenterInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val noticeIds = mutableSetOf<String>()

    @Suppress("DEPRECATION")
    @Test fun criticalIsSeenOnShowAndSurvivesRecreationUntilAcknowledged() = fixture { scenario, fixture ->
        val notice = notice(severity = "critical")
        val selection = MainNoticeSelection.Critical(notice)
        scenario.onActivity { activity ->
            fixture.show(activity, selection)
            assertTrue(notice.id in AppNoticeCenter.seenNoticeIds(app))
            assertTrue(fixture.finished.isEmpty())
            fixture.dialog().onBackPressed()
            assertTrue("Critical notice must reject the back action", fixture.dialog().isShowing)
            fixture.close()
        }
        instrumentation.waitForIdleSync()
        assertTrue("Closing an old host does not acknowledge its notice", fixture.finished.isEmpty())
        scenario.recreate()
        scenario.onActivity { activity ->
            fixture.show(activity, selection)
            assertTrue(fixture.dialog().isShowing)
            assertTrue(fixture.finished.isEmpty())
            fixture.dialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        }
        instrumentation.waitForIdleSync()
        assertEquals(listOf(selection), fixture.finished)
    }

    @Test fun versionIsSeenOnShowAndSurvivesRecreationUntilLaterIsPressed() = fixture { scenario, fixture ->
        val notice = notice(kind = "version", minimumVersion = Long.MAX_VALUE)
        val selection = MainNoticeSelection.Version(notice)
        scenario.onActivity { activity ->
            fixture.show(activity, selection)
            assertTrue(notice.id in AppNoticeCenter.seenNoticeIds(app))
            assertTrue(fixture.finished.isEmpty())
            fixture.close()
        }
        instrumentation.waitForIdleSync()
        assertTrue(fixture.finished.isEmpty())
        scenario.recreate()
        scenario.onActivity { activity ->
            fixture.show(activity, selection)
            assertTrue(fixture.dialog().isShowing)
            fixture.dialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        }
        instrumentation.waitForIdleSync()
        assertEquals(listOf(selection), fixture.finished)
    }

    @Test fun ordinaryBatchStaysUnreadAcrossRecreationAndIsSeenOnAcknowledgement() = fixture { scenario, fixture ->
        val first = notice()
        val second = notice()
        val selection = MainNoticeSelection.Unseen(listOf(first, second))
        scenario.onActivity { activity ->
            fixture.show(activity, selection)
            assertFalse(first.id in AppNoticeCenter.seenNoticeIds(app))
            assertFalse(second.id in AppNoticeCenter.seenNoticeIds(app))
            fixture.close()
        }
        instrumentation.waitForIdleSync()
        assertTrue(fixture.finished.isEmpty())
        assertFalse(first.id in AppNoticeCenter.seenNoticeIds(app))
        assertFalse(second.id in AppNoticeCenter.seenNoticeIds(app))
        scenario.recreate()
        scenario.onActivity { activity ->
            fixture.show(activity, selection)
            fixture.dialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        }
        instrumentation.waitForIdleSync()
        assertEquals(listOf(selection), fixture.finished)
        assertTrue(first.id in AppNoticeCenter.seenNoticeIds(app))
        assertTrue(second.id in AppNoticeCenter.seenNoticeIds(app))
    }

    @Test fun ordinaryUserCancellationCompletesAndMarksSeen() = fixture { scenario, fixture ->
        val notice = notice()
        val selection = MainNoticeSelection.Unseen(listOf(notice))
        scenario.onActivity { activity ->
            fixture.show(activity, selection)
            assertFalse(notice.id in AppNoticeCenter.seenNoticeIds(app))
            fixture.dialog().cancel()
        }
        instrumentation.waitForIdleSync()
        assertEquals(listOf(selection), fixture.finished)
        assertTrue(notice.id in AppNoticeCenter.seenNoticeIds(app))
    }

    @Test fun replacingDialogDoesNotAcknowledgeTheReplacedOrdinaryNotice() = fixture { scenario, fixture ->
        val first = notice()
        val second = notice()
        val selection = MainNoticeSelection.Unseen(listOf(second))
        scenario.onActivity { activity ->
            fixture.show(activity, MainNoticeSelection.Unseen(listOf(first)))
            val replaced = fixture.dialog()
            fixture.presenter?.show(selection)
            assertFalse(replaced.isShowing)
            assertTrue(fixture.dialog().isShowing)
            assertTrue(fixture.finished.isEmpty())
            fixture.dialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        }
        instrumentation.waitForIdleSync()
        assertEquals(listOf(selection), fixture.finished)
        assertFalse(first.id in AppNoticeCenter.seenNoticeIds(app))
        assertTrue(second.id in AppNoticeCenter.seenNoticeIds(app))
    }

    @Test fun closedPresenterRejectsLateShowWithoutSeenOrCompletion() = fixture { scenario, fixture ->
        val first = notice()
        val late = notice(severity = "critical")
        scenario.onActivity { activity ->
            fixture.show(activity, MainNoticeSelection.Unseen(listOf(first)))
            val oldDialog = fixture.dialog()
            fixture.close()
            fixture.close()
            fixture.presenter?.show(MainNoticeSelection.Critical(late))
            assertFalse(oldDialog.isShowing)
            assertNull(fixture.dialogOrNull())
        }
        instrumentation.waitForIdleSync()
        assertTrue(fixture.finished.isEmpty())
        assertFalse(first.id in AppNoticeCenter.seenNoticeIds(app))
        assertFalse(late.id in AppNoticeCenter.seenNoticeIds(app))
    }

    private class Fixture {
        val finished = mutableListOf<MainNoticeSelection>()
        var presenter: MainNoticePresenter? = null

        fun show(activity: MainActivity, selection: MainNoticeSelection) {
            close()
            presenter = MainNoticePresenter(activity, finished::add).also { it.show(selection) }
        }

        fun dialogOrNull(): AlertDialog? {
            val field = MainNoticePresenter::class.java.getDeclaredField("dialog").apply { isAccessible = true }
            return presenter?.let { field.get(it) as? AlertDialog }
        }

        fun dialog(): AlertDialog = requireNotNull(dialogOrNull())
        fun close() { presenter?.close() }
    }

    private fun notice(kind: String = "service", severity: String = "info", minimumVersion: Long? = null): AppNotice {
        val id = "main-notice-test-${UUID.randomUUID()}"
        noticeIds += id
        return AppNotice(id, kind, severity, "fixture title", "fixture body", minimumVersion, null, null, null)
    }

    private fun fixture(block: (ActivityScenario<MainActivity>, Fixture) -> Unit) {
        val settings = EasSettingsRepository(app)
        val autoImport = settings.isAutoReimportEnabled()
        settings.setAutoReimport(false)
        val fixture = Fixture()
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                try {
                    block(scenario, fixture)
                } finally {
                    instrumentation.runOnMainSync(fixture::close)
                }
            }
        } finally {
            settings.setAutoReimport(autoImport)
            val preferences = app.getSharedPreferences("app_notice", Context.MODE_PRIVATE)
            val remaining = preferences.getStringSet("seen_notice_ids_v1", emptySet()).orEmpty() - noticeIds
            preferences.edit().putStringSet("seen_notice_ids_v1", remaining).commit()
        }
    }
}
