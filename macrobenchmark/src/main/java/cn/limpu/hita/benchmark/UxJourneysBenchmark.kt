package cn.limpu.hita.benchmark

import android.content.Intent
import android.graphics.Point
import android.os.SystemClock
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UxJourneysBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui by lazy { UiDevice.getInstance(instrumentation) }

    @Test fun classicTabs() = tabs("classic")
    @Test fun glassTabs() = tabs("glass")
    @Test fun classicDrawer() = drawer("classic")
    @Test fun glassDrawer() = drawer("glass")

    @Test fun switchWeeks() = measure("classic", "时间表") {
        val centerY = displayHeight / 2
        repeat(5) {
            check(swipe(displayWidth * 4 / 5, centerY, displayWidth / 5, centerY, 12))
            check(swipe(displayWidth / 5, centerY, displayWidth * 4 / 5, centerY, 12))
        }
    }

    @Test fun scrollLongAgenda() = measure("classic", "待办", prepare = {
        checkNotNull(ui.wait(Until.findObject(By.desc("UX benchmark task 219")), TIMEOUT_MS)
            ?: ui.findObject(By.descStartsWith("UX benchmark task"))) { "Seeded tasks did not load" }
    }) {
        scrollLongList()
    }

    @Test fun scrollLongConversation() = measure("glass", "助手", prepare = {
        checkNotNull(ui.wait(Until.findObject(By.textContains("UX benchmark reply")), TIMEOUT_MS)) {
            "Seeded conversation did not load"
        }
    }) {
        scrollLongList()
    }

    private fun tabs(style: String) {
        var targets = emptyList<Point>()
        measure(style, "今日", prepare = {
            targets = listOf("时间表", "待办", "助手", "更多", "今日").map { label -> named(label).visibleCenter }
        }) {
            repeat(3) { for (point in targets) { check(click(point.x, point.y)); SystemClock.sleep(180) } }
        }
    }

    private fun drawer(style: String) {
        var target = Point()
        measure(style, "今日", prepare = { target = named("账号与设置").visibleCenter }) {
            repeat(5) {
                check(click(target.x, target.y)); SystemClock.sleep(250)
                check(pressBack()); SystemClock.sleep(220)
            }
        }
    }

    private fun UiDevice.scrollLongList() {
        val middle = displayWidth / 2
        repeat(5) {
            check(swipe(middle, displayHeight * 4 / 5, middle, displayHeight / 3, 16))
            check(swipe(middle, displayHeight / 3, middle, displayHeight * 4 / 5, 16))
        }
    }

    private fun measure(
        style: String,
        page: String,
        prepare: () -> Unit = {},
        scenario: UiDevice.() -> Unit,
    ) = benchmark.measureRepeated(
        packageName = TARGET,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = 10,
        setupBlock = {
            pressHome()
            // Launch from the signed instrumentation, not via shell, for the seed permission.
            instrumentation.context.startActivity(Intent().setClassName(TARGET, SEED_ACTIVITY)
                .putExtra("style", style).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            // 一次性公告是启动后异步拉取的，会盖住主界面；它属于准备，不属于被测旅程。
            dismissStartupNotices()
            check(ui.wait(Until.hasObject(By.desc("今日")), TIMEOUT_MS) == true) { "Today did not start" }
            dismissStartupNotices()
            if (page != "今日") {
                // 模拟器上首次点击可能落在进入动画里；重点一次并放宽等待，避免把准备抖动算成旅程失败。
                var selected = false
                repeat(2) {
                    if (selected) return@repeat
                    // 用坐标点击而不是节点点击：节点在进入动画里会失效，坐标与 tabs() 成功的路径一致。
                    val targetPoint = named(page).visibleCenter
                    check(ui.click(targetPoint.x, targetPoint.y))
                    selected = ui.wait(Until.hasObject(By.desc(page).selected(true)), TIMEOUT_MS) == true
                }
                check(selected) { "Page did not become selected: $page" }
            }
            prepare()
            ui.waitForIdle()
        },
        measureBlock = { ui.scenario() },
    )

    /** Closes the one-shot startup notices; they belong to preparation, not to the measured journey. */
    private fun dismissStartupNotices() {
        // 公告依赖网络，出现时间不定；最多等一次见过公告后的 20 秒，没出现就立即返回。
        var first = ui.wait(Until.findObject(By.text("知道了")), 6_000) ?: return
        val deadline = System.currentTimeMillis() + 20_000
        while (true) {
            first.click()
            ui.waitForIdle()
            if (System.currentTimeMillis() >= deadline) return
            first = ui.wait(Until.findObject(By.text("知道了")), 2_000) ?: return
        }
    }

    private fun named(label: String): UiObject2 = checkNotNull(
        ui.wait(Until.findObject(By.desc(label)), TIMEOUT_MS),
    ) { "Missing accessible action: $label" }

    private companion object {
        const val TARGET = "cn.limpu.hita.benchmark"
        const val SEED_ACTIVITY = "cn.limpu.hita.benchmark.BenchmarkSeedActivity"
        const val TIMEOUT_MS = 25_000L
    }
}
