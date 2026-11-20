package cn.limpu.hita.ui.timetable.history

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import cn.limpu.hita.data.repository.DeletedEventHistory
import cn.limpu.hita.data.repository.DeletedEventStatus
import cn.limpu.hita.ui.design.HitaComposeTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeletionHistoryDialogInstrumentedTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun restoredRowDisablesFurtherRestoration() {
        val calls = AtomicInteger()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var state by mutableStateOf(DeletionHistoryUiState(
                    entries = listOf(DeletedEventHistory("history", "测试课程", 1_800_000_000_000L,
                        1_800_000_000_000L, DeletedEventStatus.AVAILABLE)),
                ))
                activity.setContentView(ComposeView(activity).apply {
                    setContent { HitaComposeTheme {
                        DeletionHistoryDialog(state, {}, {}, { id ->
                            assertEquals("history", id)
                            calls.incrementAndGet()
                            state = state.copy(entries = state.entries.map { it.copy(status = DeletedEventStatus.RESTORED) })
                        })
                    } }
                })
            }
            assertNotNull(device.wait(Until.findObject(By.text("测试课程")), 5_000))
            val restore = device.wait(Until.findObject(By.text("恢复")), 5_000)
            assertNotNull(restore)
            assertTrue(restore.isEnabled)
            restore.click()
            assertNotNull(device.wait(Until.findObject(By.text("已恢复")), 5_000))
            // Verify the user action itself: Android accessibility may expose the label
            // as an enabled text node even when the Compose button is disabled.
            device.findObject(By.text("恢复")).click()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun failedLoadCanRetryAndDisplayAnEmptyHistory() {
        val retries = AtomicInteger()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var state by mutableStateOf(DeletionHistoryUiState(loadFailed = true))
                activity.setContentView(ComposeView(activity).apply {
                    setContent { HitaComposeTheme {
                        DeletionHistoryDialog(state, {}, {
                            retries.incrementAndGet()
                            state = DeletionHistoryUiState()
                        }, {})
                    } }
                })
            }
            assertNotNull(device.wait(Until.findObject(By.text("无法加载删除历史")), 5_000))
            device.findObject(By.text("重试")).click()
            assertNotNull(device.wait(Until.findObject(By.text("暂无删除记录")), 5_000))
            assertEquals(1, retries.get())
        }
    }
}
