package cn.limpu.hita.ui.timetable.history

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import cn.limpu.hita.data.model.timetable.TimetableVersionSummary
import cn.limpu.hita.data.repository.*
import cn.limpu.hita.feature.timetable.version.*
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.main.timetable.SourceOccurrenceChangesDialog
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimetableVersionsUiInstrumentedTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val time = 1_800_000_000_000L
    private fun version(id: String, revision: Long) = TimetableVersionSummary(id, revision, time, "MANUAL", null, id)
    private fun lesson(start: Long) = VersionLesson("lesson", "MATH", "测试数学", "EAS_IMPORT", start, start + 3600000, week = 1)

    @Test fun versionComparisonRequiresConfirmationAndCreatesANewCurrentVersion() {
        val restored = AtomicInteger()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                var state by mutableStateOf(CourseVersionsUiState(timetableId = "table",
                    versions = listOf(version("two", 2), version("one", 1))))
                activity.setContentView(ComposeView(activity).apply { setContent { HitaComposeTheme {
                    TimetableVersionsDialog(state, {}, {}, { id ->
                        assertEquals("one", id)
                        state = state.copy(comparison = TimetableVersionComparison(version("one", 1), 2,
                            TimetableVersionDiff.compare(listOf(lesson(time)), listOf(lesson(time + 86400000))),
                            TimetableVersionDiff.compare(listOf(lesson(time + 86400000)), listOf(lesson(time)))))
                    }, {
                        restored.incrementAndGet()
                        state = state.copy(comparison = null, versions = listOf(version("three", 3)), restored = true)
                    }, {})
                } } })
            }
            assertNotNull(device.wait(Until.findObject(By.text("当前版本 · v2")), 5000))
            device.findObject(By.text("与当前比较")).click()
            assertNotNull(device.wait(Until.findObject(By.text("历史 v1 → 当前 v2")), 5000))
            assertNotNull(device.findObject(By.text("调课 1 节")))
            device.findObject(By.text("恢复此版本")).click()
            assertNotNull(device.wait(Until.findObject(By.text("恢复这个课表版本？")), 5000))
            assertEquals(0, restored.get())
            device.findObject(By.text("恢复此版本")).click()
            assertNotNull(device.wait(Until.findObject(By.text("当前版本 · v3")), 5000))
            assertEquals(1, restored.get())
        }
    }

    @Test fun weekViewShowsBothSidesOfAMoveWithoutEditingCourses() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val state = CourseVersionsUiState(timetableId = "table", versions = listOf(version("two", 2), version("one", 1)),
                    comparison = TimetableVersionComparison(version("one", 1), 2,
                        TimetableVersionDiff.compare(listOf(lesson(time)), listOf(lesson(time + 3600000))),
                        TimetableVersionDiff.compare(listOf(lesson(time + 3600000)), listOf(lesson(time)))))
                activity.setContentView(ComposeView(activity).apply { setContent { HitaComposeTheme {
                    TimetableVersionsDialog(state, {}, {}, {}, {}, {})
                } } })
            }
            assertNotNull(device.wait(Until.findObject(By.text("周视图")), 5000))
            device.findObject(By.text("周视图")).click()
            assertNotNull(device.wait(Until.findObject(By.textStartsWith("+1 测试数学")), 5000))
            assertNotNull(device.findObject(By.textStartsWith("−1 测试数学")))
        }
    }

    @Test fun localConflictDefaultsToKeepAndDoesNotAdoptAnUnseenMove() {
        val kept = AtomicInteger()
        val a = MergeLesson("测试数学", "A101", "教师", time, time + 3600000, 1, 2, 1)
        val b = a.copy(fromMillis = time + 86400000, toMillis = time + 86400000 + 3600000)
        val row = PendingChangeRow("move", "math", "测试数学", PendingChangeKind.LESSON_MOVED, "0", 0, 1, 2,
            "", "", a, b)
        val course = MergeCourse("subject", "测试数学", "MATH", listOf(a))
        val update = PendingCourseUpdate("term", "table", "math", "subject", "测试数学", false, course,
            course.copy(lessons = listOf(b)), listOf(row), "owner", localChanged = true)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContentView(ComposeView(activity).apply { setContent { HitaComposeTheme {
                    SourceOccurrenceChangesDialog(listOf(update), {}, { adopted, ignored ->
                        assertTrue(adopted.isEmpty())
                        assertEquals(setOf("move"), ignored)
                        kept.incrementAndGet()
                    })
                } } })
            }
            assertNotNull(device.wait(Until.findObject(By.text("调课 1 节")), 5000))
            device.findObject(By.text("采纳勾选的改动")).click()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(1, kept.get())
        }
    }
    @Test fun hiddenTeachingCoursesCanBeShownWithoutRestoringAVersion() {
        val shown = AtomicInteger()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                var events by mutableStateOf(listOf(cn.limpu.hita.data.model.timetable.EventItem().apply {
                    id = "hidden"; name = "隐藏的测试数学"; hidden = 1
                }))
                activity.setContentView(ComposeView(activity).apply { setContent { HitaComposeTheme {
                    cn.limpu.hita.ui.timetable.detail.HiddenTeachingCourses(events) {
                        shown.incrementAndGet(); events = emptyList()
                    }
                } } })
            }
            device.wait(Until.findObject(By.text("隐藏的教务课程（1）")), 5000).click()
            assertNotNull(device.wait(Until.findObject(By.text("隐藏的测试数学")), 5000))
            device.findObject(By.text("重新显示")).click()
            assertNotNull(device.wait(Until.findObject(By.text("没有隐藏的教务课程")), 5000))
            assertEquals(1, shown.get())
        }
    }

    @Test fun legacyBackupEntryOnlyAppearsWhenBackupsExist() {
        val state = mutableStateOf(CourseVersionsUiState(timetableId = "table", versions = listOf(version("one", 1))))
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.setContentView(ComposeView(activity).apply { setContent { HitaComposeTheme {
                    TimetableVersionsDialog(state.value, {}, {}, {}, {}, {})
                } } })
            }
            assertNotNull(device.wait(Until.findObject(By.text("教务版本")), 5000))
            assertFalse(device.hasObject(By.textStartsWith("升级前的教务备份")))
            scenario.onActivity { state.value = state.value.copy(hasLegacy = true) }
            assertNotNull(device.wait(Until.findObject(By.textStartsWith("升级前的教务备份")), 5000))
        }
    }

}
