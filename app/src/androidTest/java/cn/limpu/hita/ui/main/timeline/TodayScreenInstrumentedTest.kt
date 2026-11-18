package cn.limpu.hita.ui.main.timeline

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.ui.design.HitaComposeTheme
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TodayScreenInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val base get() = instrumentation.targetContext
    private val window = TodayDateWindow.from(Instant.parse("2026-11-17T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private val now = window.start + 12 * 3_600_000L
    private fun event(id: String, start: Long, end: Long) = EventItem().apply {
        this.id = id; name = id; place = "Fixture location"; from = Timestamp(start); to = Timestamp(end)
    }
    private fun state(events: List<EventItem>, at: Long = now): TodayUiState {
        val agenda = TodayProjection.project(window, events, at)
        return TodayUiState(at, window, scopeKey = "fixture", scopeName = "Fixture timetable",
            status = TodayPageStatus.READY, agenda = agenda,
            header = TodayHeaderResolver.resolve(at, window.zoneId, agenda, emptyList()))
    }
    @Test fun defaultGroupExpansionKeepsEndedTimesAndExactEventActionAcrossRecreation() {
        val events = listOf(event("Fixture ended", now - 120_000, now - 60_000),
            event("Fixture next A", now + 60_000, now + 120_000),
            event("Fixture next B", now + 60_000, now + 120_000))
        val rendered = mutableStateOf(state(events))
        var action: TodayAction? = null
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            fun show() { scenario.onActivity { activity -> activity.setContentView(ComposeView(activity).apply {
                id = android.R.id.content
                setContent { HitaComposeTheme { TodayScreen(rendered.value, emptyList(), {}, { action = it }) } }
            }) } }
            show()
            awaitText("Fixture next A"); awaitText("Fixture next B")
            assertFalse(hasText("Fixture ended"))
            clickText(base.getString(R.string.today_ended_count, 1))
            awaitText("Fixture ended")
            awaitText("11:58–11:59")
            clickText("Fixture ended")
            assertEquals(TodayAction.OpenEvent(events.first().id), action)
            scenario.onActivity { rendered.value = rendered.value.copy(now = now + 1000) }
            awaitText(base.getString(R.string.today_collapse))
            scenario.recreate(); show()
            awaitText(base.getString(R.string.today_collapse))
        }
    }
    @Test fun noTargetAndFailedReadExposeTheirOwnActions() {
        val rendered = mutableStateOf(TodayUiState(now, window, status = TodayPageStatus.NO_TARGET))
        var action: TodayAction? = null
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContentView(ComposeView(activity).apply {
                setContent { HitaComposeTheme { TodayScreen(rendered.value, emptyList(), {}, { action = it }) } }
            }) }
            clickText(base.getString(R.string.today_choose)); assertEquals(TodayAction.SelectTimetable, action)
            clickText(base.getString(R.string.today_import)); assertEquals(TodayAction.ImportTimetable, action)
            scenario.onActivity { rendered.value = rendered.value.copy(status = TodayPageStatus.FAILED) }
            clickText(base.getString(R.string.today_retry)); assertEquals(TodayAction.Retry(TodayRetrySource.AGENDA), action)
            assertFalse(hasText(base.getString(R.string.today_empty)))
        }
    }
    @Test fun tomorrowEmptyAndInvalidAreDifferent() {
        val at = window.preparationStart
        val rendered = mutableStateOf(state(emptyList(), at))
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContentView(ComposeView(activity).apply {
                setContent { HitaComposeTheme { TodayScreen(rendered.value, emptyList(), {}, {}) } }
            }) }
            awaitText(base.getString(R.string.today_tomorrow, "2026-11-18"))
            awaitText(base.getString(R.string.today_tomorrow_empty))
            scenario.onActivity { rendered.value = state(listOf(event("Fixture invalid", window.tomorrowStart + 1000, window.tomorrowStart)), at) }
            awaitText(base.getString(R.string.today_tomorrow_unknown))
            awaitText("Fixture invalid")
            awaitText(base.getString(R.string.today_invalid))
        }
    }
    @Test fun longTitlesWrapAtLargeFontAndNarrowWidth() {
        val title = "Fixture long title " + "课程名称需要完整换行".repeat(8)
        val rendered = state(listOf(event(title, now + 1000, now + 60000)))
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContentView(ComposeView(activity).apply {
                setContent { HitaComposeTheme {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.6f)) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(320.dp)) {
                            TodayScreen(rendered, emptyList(), {}, {})
                        }
                    }
                } }
            }) }
            awaitText(title)
            awaitText("12:00–12:01")
            awaitText("Fixture location")
        }
    }

    @Test fun standaloneHomeworkHostReusesFragmentAndIsInternal() {
        val component = android.content.ComponentName(base, cn.limpu.hita.ui.main.homework.HomeworkActivity::class.java)
        @Suppress("DEPRECATION")
        assertFalse(base.packageManager.getActivityInfo(component, 0).exported)
        ActivityScenario.launch(cn.limpu.hita.ui.main.homework.HomeworkActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.supportFragmentManager.executePendingTransactions()
                assertTrue(activity.supportFragmentManager.findFragmentById(R.id.today_homework_container)
                    is cn.limpu.hita.ui.main.homework.HomeworkFragment)
            }
            awaitText(base.getString(R.string.today_homework_title))
        }
    }

    @Test fun summariesKeepKnownSourceIdentityAndUseTheExistingHomeworkRoute() {
        val local = TodayHomeworkSourceSummary(TodayHomeworkSource.LOCAL, TodaySourceStatus.READY, dueToday = 1, overdue = 0)
        val grader = TodayHomeworkSourceSummary(TodayHomeworkSource.GRADER, TodaySourceStatus.DISCONNECTED)
        val rendered = mutableStateOf(state(emptyList()).copy(homework = TodayHomeworkSummary(local, grader)))
        var action: TodayAction? = null
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContentView(ComposeView(activity).apply {
                setContent { HitaComposeTheme { TodayScreen(rendered.value, emptyList(), {}, { action = it }) } }
            }) }
            awaitText(base.getString(R.string.today_source_status, "Grader", base.getString(R.string.today_disconnected)))
            clickText(base.getString(R.string.today_homework_local_short))
            assertEquals(TodayAction.OpenHomework(TodayHomeworkSource.LOCAL), action)
            scenario.onActivity { rendered.value = rendered.value.copy(homework = TodayHomeworkSummary(local,
                grader.copy(status = TodaySourceStatus.READY, dueToday = 1, overdue = 0))) }
            awaitText(base.getString(R.string.today_homework_card))
            awaitText("2")
            awaitText(base.getString(R.string.today_source_count, base.getString(R.string.today_homework_local_short), 1) +
                " · " + base.getString(R.string.today_source_count, "Grader", 1))
        }
    }

    @Test fun tomorrowGroupRemainsSingleWhenHistoryExpandsAndUsesActualIds() {
        val at = window.preparationStart
        val first = event("Fixture tomorrow A", window.tomorrowStart + 8 * 3_600_000, window.tomorrowStart + 9 * 3_600_000)
        val second = event("Fixture tomorrow B", first.from.time, first.to.time)
        val events = listOf(event("Fixture history", now - 60_000, now), first, second)
        val rendered = state(events, at)
        var action: TodayAction? = null
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContentView(ComposeView(activity).apply {
                setContent { HitaComposeTheme { TodayScreen(rendered, emptyList(), {}, { action = it }) } }
            }) }
            val title = base.getString(R.string.today_tomorrow, "2026-11-18")
            awaitText(title); awaitText("Fixture tomorrow A"); awaitText("Fixture tomorrow B")
            clickText(base.getString(R.string.today_finished_count, 1))
            awaitText(title)
            fun occurrences(node: AccessibilityNodeInfo?): Int {
                if (node == null) return 0
                val own = if (node.isVisibleToUser && (node.text?.toString() == title || node.contentDescription?.toString() == title)) 1 else 0
                return own + (0 until node.childCount).sumOf { occurrences(node.getChild(it)) }
            }
            assertEquals(1, occurrences(freshRoot()))
            clickText("Fixture tomorrow B")
            assertEquals(TodayAction.OpenEvent(second.id), action)
        }
    }

    @Test fun approvedDesignDayAndEveningRenderWithSourceSummaries() {
        val course = event("概率论与数理统计", now + 12 * 60_000, now + 107 * 60_000).apply {
            fromNumber = 3; lastNumber = 4; place = "T3201"; teacher = "张老师"
        }
        val exam = event("高等数学", now + 2 * 3_600_000, now + 4 * 3_600_000).apply {
            type = EventItem.TYPE.EXAM; place = "正心201"
        }
        val tomorrow = event("线性代数", window.tomorrowStart + 8 * 3_600_000, window.tomorrowStart + 9 * 3_600_000).apply { place = "格物205" }
        val events = listOf(course, exam, tomorrow)
        val homework = TodayHomeworkSummary(
            TodayHomeworkSourceSummary(TodayHomeworkSource.LOCAL, TodaySourceStatus.READY, dueToday = 1, overdue = 0),
            TodayHomeworkSourceSummary(TodayHomeworkSource.GRADER, TodaySourceStatus.READY, accountId = "fixture", dueToday = 1, overdue = 1))
        val exams = TodayExamSummary(listOf(TodayExamEntry(exam.id, TodayExamSource.IMPORTED, exam.name, exam.place,
            exam.from.time, exam.to.time, "fixture")), emptyList(), emptyList(), emptyList(), 1)
        val rendered = mutableStateOf(state(events).copy(homework = homework, exams = exams))
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContentView(ComposeView(activity).apply {
                setContent { HitaComposeTheme {
                    androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background.copy(alpha = 1f)) {
                        TodayScreen(rendered.value, emptyList(), {}, {})
                    }
                } }
            }) }
            awaitText("概率论与数理统计")
            awaitText(base.getString(R.string.today_homework_card))
            fun capture(name: String) {
                instrumentation.waitForIdleSync()
                val bitmap = requireNotNull(automation.takeScreenshot())
                base.cacheDir.resolve(name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            capture("today-visual-day.png")
            scenario.onActivity { rendered.value = state(events, window.preparationStart).copy(homework = homework,
                exams = exams.copy(remaining = emptyList())) }
            awaitText("线性代数")
            awaitText(base.getString(R.string.today_greeting_finished))
            capture("today-visual-evening.png")
        }
    }

    private fun freshRoot(): AccessibilityNodeInfo? {
        instrumentation.waitForIdleSync()
        // Compose updates virtual nodes asynchronously; cached enabled/text values can
        // still describe the empty input after ACTION_SET_TEXT has already succeeded.
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        return automation.rootInActiveWindow
    }

    private fun hasText(text: String): Boolean =
        freshRoot()?.findAccessibilityNodeInfosByText(text)?.any {
            it.isVisibleToUser && it.text?.toString() == text
        } == true

    private fun awaitText(text: String) = awaitNode("text: $text", scroll = true) {
        it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text)
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target?.takeIf { it.isEnabled }
    }

    private fun clickText(text: String, matchDescription: Boolean = true) {
        val node = awaitNode("enabled click target: $text", scroll = true) { candidate ->
            candidate.isVisibleToUser &&
                (candidate.text?.toString() == text ||
                    (matchDescription && candidate.contentDescription?.toString() == text)) &&
                clickableAncestor(candidate) != null
        }
        // Finding a node must not click it: polling used to conflate missing, disabled
        // and rejected actions, and could retry an action without reporting which failed.
        val target = requireNotNull(clickableAncestor(node))
        assertTrue("Accessibility click was rejected: $text", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun describeTree(node: AccessibilityNodeInfo?, depth: Int = 0): String {
        if (node == null) return "<no active window>"
        return buildString {
            append("  ".repeat(depth))
            append("${node.className} text=${node.text} description=${node.contentDescription}")
            append(" visible=${node.isVisibleToUser} enabled=${node.isEnabled} clickable=${node.isClickable}\n")
            for (index in 0 until node.childCount) append(describeTree(node.getChild(index), depth + 1))
        }
    }

    private fun awaitNode(
        description: String = "homework control",
        scroll: Boolean = false,
        matches: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (matches(node)) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        fun verticalList(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable && node.actionList.any {
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id ||
                    it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            }) return node
            for (index in 0 until node.childCount) verticalList(node.getChild(index))?.let { return it }
            return null
        }
        val deadline = System.nanoTime() + 10_000_000_000L
        var nextScroll = System.nanoTime() + 350_000_000L
        var forward = true
        while (System.nanoTime() < deadline) {
            val root = freshRoot()
            find(root)?.let { return it }
            if (scroll && System.nanoTime() >= nextScroll) {
                // LazyColumn items can be outside the viewport on CI's 320 x 640 emulator.
                // Scroll only vertically so looking for an item cannot change the selected day.
                verticalList(root)?.let { list ->
                    val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    if (!list.performAction(action)) forward = !forward
                }
                nextScroll = System.nanoTime() + 350_000_000L
            }
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for $description\n${describeTree(freshRoot())}")
    }
}
