package cn.limpu.hita.feature.livecourse

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.feature.livecourse.guard.LiveCourseGuardService
import cn.limpu.hita.feature.livecourse.settings.LiveCourseSettings
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveCourseGuardRecoveryInstrumentedTest {
    private val context = RecordingContext(ApplicationProvider.getApplicationContext())
    private val settings = LiveCourseSettings(context)

    @After fun tearDown() {
        context.baseContext.deleteSharedPreferences(context.preferencesName)
    }

    @Test fun restorationRequiresBothUserSwitches() {
        for ((enabled, strong) in listOf(false to false, false to true, true to false)) {
            settings.setEnabled(enabled)
            settings.setStrongReminderEnabled(strong)
            LiveCourseGuardService.restoreIfEnabled(context)
        }
        assertEquals(0, context.starts)
        settings.setEnabled(true)
        settings.setStrongReminderEnabled(true)
        LiveCourseGuardService.restoreIfEnabled(context)
        assertEquals(1, context.starts)
        assertEquals(LiveCourseGuardService::class.java.name, context.lastIntent?.component?.className)
    }

    @Test fun backgroundStartRejectionPreservesPreferenceForTheNextForegroundRecovery() {
        assertRejectedStartCanRetry(IllegalStateException("Background start rejected"))
    }

    @Test fun permissionRejectionPreservesPreferenceAndDoesNotEscapeToReceiver() {
        assertRejectedStartCanRetry(SecurityException("Permission rejected"))
    }

    private fun assertRejectedStartCanRetry(error: RuntimeException) {
        settings.setEnabled(true)
        settings.setStrongReminderEnabled(true)
        context.failure = error
        LiveCourseGuardService.restoreIfEnabled(context)
        assertTrue(settings.isEnabled())
        assertTrue(settings.isStrongReminderEnabled())
        context.failure = null
        LiveCourseGuardService.restoreIfEnabled(context)
        assertEquals(2, context.starts)
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val preferencesName = "guard-recovery-test-" + UUID.randomUUID()
        var starts = 0
        var lastIntent: Intent? = null
        var failure: RuntimeException? = null
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences(preferencesName, mode)

        override fun startForegroundService(service: Intent): ComponentName? {
            starts++
            lastIntent = service
            failure?.let { throw it }
            return service.component
        }
    }
}
