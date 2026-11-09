package cn.limpu.hita.perf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PERF-01 companion regression: consent must be published synchronously to readers while the
 * durable copy is written off the main thread.
 *
 * Before the fix `setEnabled` ran `SharedPreferences.commit()` inside a `synchronized` block on
 * the caller thread (a Compose switch on the UI thread) and `check(...)` would throw a raw
 * exception into the UI on a failed disk write. Consensus was also re-read from disk on every
 * `enabled()` call, which the heartbeat repeated every 30 s on main.
 */
@RunWith(AndroidJUnit4::class)
class AnalyticsConsentStateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun tearDown() {
        UsageAnalyticsClient.setEnabled(context, true)
    }

    @Test fun consentIsVisibleImmediatelyAfterTheSwitchEvenBeforeTheDiskWrite() {
        UsageAnalyticsClient.initialize(context)
        UsageAnalyticsClient.setEnabled(context, false)
        assertFalse("consent change must be observable immediately", UsageAnalyticsClient.isEnabled(context))

        UsageAnalyticsClient.setEnabled(context, true)
        assertTrue("re-enabling must be observable immediately", UsageAnalyticsClient.isEnabled(context))
    }

    @Test fun disabledConsentSuppressesRecordsWithoutThrowing() {
        UsageAnalyticsClient.setEnabled(context, false)
        // Recording while disabled is a no-op; it must never crash the caller thread.
        UsageAnalyticsClient.record(cn.limpu.hita.data.analytics.UsageAnalyticsEvent.SCORES_VIEWED)
        assertFalse(UsageAnalyticsClient.isEnabled(context))
    }

}
