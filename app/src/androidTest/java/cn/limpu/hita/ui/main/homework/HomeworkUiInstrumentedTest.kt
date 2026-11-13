package cn.limpu.hita.ui.main.homework

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.R
import cn.limpu.hita.data.repository.homework.HomeworkRepository
import cn.limpu.hita.data.repository.grader.GraderError
import cn.limpu.hita.data.repository.grader.GraderRepository
import cn.limpu.hita.data.repository.grader.GraderState
import cn.limpu.hita.ui.design.HitaComposeTheme
import androidx.activity.ComponentActivity
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeworkUiInstrumentedTest {
    private val base = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun dailyScreenFiltersDatesAndSupportsEditingCompletionAndLegacyTasks() = runBlocking {
        val preferenceName = "homework-ui-" + UUID.randomUUID()
        val isolatedApp = object : Application() {
            init { attachBaseContext(base) }
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$preferenceName-$name", mode)
        }
        val repository = HomeworkRepository(isolatedApp)
        val today = LocalDate.now()
        assertTrue(repository.add("fixture-today", today.toString()))
        assertTrue(repository.add("fixture-tomorrow", today.plusDays(1).toString()))
        assertTrue(repository.add("fixture-yesterday", today.minusDays(1).toString()))
        assertTrue(repository.add("fixture-undated"))
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val easRepository = dagger.hilt.android.EntryPointAccessors.fromApplication(
                        base, cn.limpu.hita.data.repository.EasRepositoryWiringEntryPoint::class.java,
                    ).easRepository()
                    val model = ViewModelProvider(activity, object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            HomeworkViewModel(
                                repository,
                                GraderRepository(isolatedApp),
                                easRepository,
                            ) as T
                    })[HomeworkViewModel::class.java]
                    activity.setContentView(ComposeView(activity).apply {
                        // 测试进程没有教务登录态，显式关掉评阅入口以保持断言稳定。
                        setContent {
                            HitaComposeTheme { HomeworkScreen(model, graderVisibleOverride = false) }
                        }
                    })
                }
                // 所有日期的待办常驻显示：不再因为选中日期而隐藏其它日子。
                awaitText("fixture-today")
                awaitText("fixture-yesterday")
                awaitText("fixture-tomorrow")
                awaitText("fixture-undated")

                // 快捷添加落在当前选中日期（默认今天），并立刻出现在今天的分组里。
                val quickInput = awaitNode { it.isEnabled && it.isVisibleToUser && it.className?.toString() == "android.widget.EditText" }
                assertTrue(quickInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "fixture-quick")
                }))
                awaitNode("quick input contains the entered title") {
                    it.className?.toString() == "android.widget.EditText" && it.text?.toString() == "fixture-quick"
                }
                clickText(base.getString(R.string.homework_add))
                awaitText("fixture-quick")

                // 切换目标日期只改变新增落点，不影响既有列表。
                clickText(base.getString(R.string.homework_next))
                awaitText("fixture-today")
                awaitText("fixture-quick")
                clickText(base.getString(R.string.homework_today))

                // 编辑
                clickText("fixture-today", matchDescription = false)
                awaitText(base.getString(R.string.homework_cancel))
                val input = awaitNode { it.className?.toString() == "android.widget.EditText" }
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "fixture-edited")
                }))
                clickText(base.getString(R.string.homework_cancel))
                awaitText(base.getString(R.string.homework_discard_title))
                clickText(base.getString(R.string.homework_keep_editing))
                awaitText("fixture-edited")
                clickText(base.getString(R.string.homework_save))
                awaitText("fixture-edited")

                // 完成 + 撤销
                val checkbox = awaitNode { it.isCheckable && it.contentDescription?.toString() == "fixture-edited" }
                assertTrue(checkbox.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitText(base.getString(R.string.homework_marked_done))
                clickText(base.getString(R.string.homework_undo))
                awaitNode { it.isCheckable && it.contentDescription?.toString() == "fixture-edited" && !it.isChecked }
                assertTrue(awaitNode {
                    it.isCheckable && it.contentDescription?.toString() == "fixture-edited"
                }.performAction(AccessibilityNodeInfo.ACTION_CLICK))

                // 已完成整块默认收起：折叠头可见，条目不可见；展开后条目出现。
                val completedHeader = base.getString(R.string.homework_completed_section, 1)
                awaitText(completedHeader)
                assertFalse("已完成项在折叠态不应出现", hasText("fixture-edited"))
                clickText(completedHeader)
                awaitText("fixture-edited")
                assertTrue(awaitNode { it.isCheckable && it.contentDescription?.toString() == "fixture-edited" }.isChecked)
                // 再收起，条目重新隐藏（收起有动画，轮询等待它消失）。
                clickText(base.getString(R.string.homework_collapse))
                val collapseDeadline = System.nanoTime() + 5_000_000_000L
                while (hasText("fixture-edited") && System.nanoTime() < collapseDeadline) {
                    instrumentation.waitForIdleSync()
                    Thread.sleep(50)
                }
                assertFalse("收起后已完成项应隐藏", hasText("fixture-edited"))
            }
        } finally {
            base.deleteSharedPreferences("$preferenceName-timeline_homework_v1")
            base.deleteSharedPreferences("$preferenceName-grader_secure_v1")
        }
    }

    @Test fun graderCardShowsTrimmedHintAndReloginOnRefreshFailure() = runBlocking {
        val preferenceName = "homework-grader-" + UUID.randomUUID()
        val isolatedApp = object : Application() {
            init { attachBaseContext(base) }
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$preferenceName-$name", mode)
        }
        val repository = HomeworkRepository(isolatedApp)
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val easRepository = dagger.hilt.android.EntryPointAccessors.fromApplication(
                        base, cn.limpu.hita.data.repository.EasRepositoryWiringEntryPoint::class.java,
                    ).easRepository()
                    val model = ViewModelProvider(activity, object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            HomeworkViewModel(repository, GraderRepository(isolatedApp), easRepository) as T
                    })[HomeworkViewModel::class.java]
                    activity.setContentView(ComposeView(activity).apply {
                        setContent {
                            HitaComposeTheme {
                                HomeworkScreen(
                                    model,
                                    graderVisibleOverride = true,
                                    graderStateOverride = GraderState(
                                        accountId = "account",
                                        accountName = "学号 20230001",
                                        ready = true,
                                        error = GraderError.NETWORK,
                                    ),
                                )
                            }
                        }
                    })
                }
                // 已连接用户下的说明只保留前半句，后半句已删除。
                val hint = base.getString(R.string.grader_local_completion_hint)
                awaitText(hint)
                assertEquals("已在 Grader 提交过的作业会自动隐藏。", hint)

                // 刷新失败：只显示错误文本 + 就地「重新登录」，不弹任何对话框。
                awaitText(base.getString(R.string.grader_network_error))
                awaitText(base.getString(R.string.grader_relogin))
                assertFalse("不得为重新登录弹窗", hasText(base.getString(R.string.grader_login_title)))
            }
        } finally {
            base.deleteSharedPreferences("$preferenceName-timeline_homework_v1")
            base.deleteSharedPreferences("$preferenceName-grader_secure_v1")
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
