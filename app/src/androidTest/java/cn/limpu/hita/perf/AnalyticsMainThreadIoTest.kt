package cn.limpu.hita.perf

import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.analytics.UsageAnalyticsEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

/**
 * PERF-01 regression: telemetry consent/session bookkeeping must never perform a synchronous
 * `SharedPreferences.commit()` on the main thread.
 *
 * The probe installs a main-thread StrictMode disk-write policy and extracts violations from the
 * logcat stack traces. A deliberate control write on main proves the probe actually captures
 * violations before the test asserts the analytics frames are absent.
 */
@RunWith(AndroidJUnit4::class)
class AnalyticsMainThreadIoTest {

    @Test
    fun consentAndSessionWritesNeverCommitOnTheMainThread() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Initialize first so the probe measures the write paths, not lazy startup.
        UsageAnalyticsClient.initialize(context)
        shell("logcat -c")

        instrumentation.runOnMainSync {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectDiskWrites().penaltyLog().build(),
            )
            UsageAnalyticsClient.setEnabled(context, true)
        }
        // Let the asynchronous queue catch up to its own internal writes; those are off-main and
        // must not be attributed to the caller thread.
        Thread.sleep(1_000)

        // Positive control: a guaranteed main-thread write, proving capture works.
        instrumentation.runOnMainSync {
            File(context.cacheDir, "strictmode-probe.txt").writeText("probe")
        }
        instrumentation.runOnMainSync {
            UsageAnalyticsClient.setEnabled(context, true)
            UsageAnalyticsClient.record(UsageAnalyticsEvent.SCORES_VIEWED)
            UsageAnalyticsClient.setEnabled(context, true)
        }
        Thread.sleep(1_000)

        val log = shell("logcat -d")
        instrumentation.runOnMainSync {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitAll().build())
        }

        val violations = extractViolations(log)
        assertTrue("StrictMode capture is broken: the control write produced no violation", violations.isNotEmpty())
        assertTrue(
            "the control write must be attributed to this test class, got: ${violations.first()}",
            violations.any { it.contains(CONTROL_FRAME) },
        )

        val analytics = violations.filter { it.contains("UsageAnalyticsClient") || it.contains("UsageAnalyticsQueueStore") }
        assertFalse(
            "usage analytics performed synchronous disk I/O on the main thread:\n" + analytics.joinToString("\n\n"),
            analytics.isNotEmpty(),
        )
        // Consent must still be persisted and observable.
        assertTrue(UsageAnalyticsClient.isEnabled(context))
    }

    /**
     * StrictMode logs one `policy violation` header followed by an indented stack trace, so each
     * violation is the header plus the frames until the next header.
     */
    private fun extractViolations(log: String): List<String> {
        val blocks = mutableListOf<String>()
        val current = StringBuilder()
        log.lineSequence().forEach { line ->
            if (line.contains("StrictMode policy violation")) {
                if (current.isNotEmpty()) blocks += current.toString()
                current.setLength(0)
            }
            if (current.isNotEmpty() || line.contains("StrictMode policy violation")) {
                current.append(line).append('\n')
            }
        }
        if (current.isNotEmpty()) blocks += current.toString()
        return blocks
    }

    private fun shell(command: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return descriptor.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { stream -> stream.readBytes().decodeToString() }
        }
    }

    private companion object {
        const val CONTROL_FRAME = "AnalyticsMainThreadIoTest"
    }
}
