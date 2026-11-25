package cn.limpu.hita.ui.main.homework

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.R
import cn.limpu.hita.data.model.homework.ImportedTodo
import cn.limpu.hita.data.repository.geniusta.GeniusTaError
import cn.limpu.hita.data.repository.geniusta.GeniusTaRepository
import cn.limpu.hita.data.repository.geniusta.GeniusTaState
import cn.limpu.hita.data.repository.homework.HomeworkRepository
import cn.limpu.hita.data.repository.grader.GraderError
import cn.limpu.hita.data.repository.grader.GraderRepository
import cn.limpu.hita.data.repository.grader.GraderState
import cn.limpu.hita.data.source.preference.TodoLoginCredential
import cn.limpu.hita.data.source.preference.TodoPlatformCredentialStore
import cn.limpu.hita.ui.design.HitaComposeTheme
import androidx.activity.ComponentActivity
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeworkUiInstrumentedTest {
    private val base = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    @Test fun geniusTaAutomaticallySubmitsSavedAccountOnceAndLeavesManualEntryAfterRejection() {
        val attempts = LinkedBlockingQueue<String>()
        val state = mutableStateOf(GeniusTaState(ready = true))
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(ComposeView(activity).apply {
                    setContent {
                        HitaComposeTheme {
                            GeniusTaLoginDialog(
                                state = state.value,
                                onDismiss = {},
                                readSavedLogin = { TodoLoginCredential("fixture-user", "fixture-password", "fixture-account") },
                                forgetSavedLogin = { false },
                                onLogin = { username, password, remember, expectedAccount ->
                                    check(password == "fixture-password" && remember)
                                    attempts.add("$username:$expectedAccount")
                                    state.value = GeniusTaState(ready = true, error = GeniusTaError.AUTHENTICATION)
                                    TodoLoginResult.Failed
                                },
                                onConnected = { error("rejected credentials cannot close the login form") },
                            )
                        }
                    }
                })
            }
            assertEquals("fixture-user:fixture-account", attempts.poll(10, TimeUnit.SECONDS))
            awaitText(base.getString(R.string.geniusta_authentication_error))
            awaitNode("manual password after rejected saved login") { it.isVisibleToUser && it.isEnabled && it.isEditable && it.isPassword }
            assertTrue("A failed automatic submission must not loop", attempts.isEmpty())
        }
    }

    @Test fun dailyScreenSupportsEditingCompletionAndLegacyTasks() = runBlocking {
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
                                GeniusTaRepository(isolatedApp),
                                TodoPlatformCredentialStore(isolatedApp),
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
                val geniusLogin = base.getString(R.string.homework_platform_login, base.getString(R.string.geniusta_title))
                clickText(geniusLogin)
                val password = awaitNode("masked GeniusTA password field") {
                    it.isVisibleToUser && it.isEditable && it.isPassword
                }
                val sample = "fixture-password"
                assertTrue(password.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, sample)
                }))
                assertFalse(hasText(sample))
                clickText(base.getString(R.string.show_password))
                awaitNode("revealed GeniusTA password") { it.isEditable && it.text?.toString() == sample }
                clickText(base.getString(R.string.hide_password))
                awaitNode("remasked GeniusTA password") { it.isEditable && it.isPassword && it.text?.toString() != sample }
                clickText(base.getString(R.string.homework_cancel))
                awaitText(geniusLogin)
                assertFalse(hasText(base.getString(R.string.grader_login_title)))
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
                // 聚焦编辑器并确认新标题可见，再验证取消和保存。
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                val focused = awaitNode("focused title input") {
                    it.className?.toString() == "android.widget.EditText" && it.isFocused
                }
                assertTrue(focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "fixture-edited")
                }))
                awaitNode("edited title reached the field") {
                    it.className?.toString() == "android.widget.EditText" && it.text?.toString() == "fixture-edited"
                }
                clickText(base.getString(R.string.homework_cancel))
                awaitText(base.getString(R.string.homework_discard_title))
                clickDialogText(base.getString(R.string.homework_keep_editing))
                awaitText("fixture-edited")
                // 编辑器内的 OutlinedTextField 用 Compose 语义接收输入；直接点保存按钮，
                // 比依赖背景列表的滚动定位更接近真实触摸路径。
                clickDialogText(base.getString(R.string.homework_save))
                awaitText("fixture-edited")

                // 完成：条目进入折叠区，撤销入口在展开后的行内，而不是一次性 Snackbar。
                val checkbox = awaitNode { it.isCheckable && it.contentDescription?.toString() == "fixture-edited" }
                assertTrue(checkbox.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val completedHeader = base.getString(R.string.homework_completed_section, 1)
                val expandCompleted = base.getString(R.string.homework_show_all, 1)
                val collapseCompleted = base.getString(R.string.homework_collapse)
                awaitText(completedHeader)
                awaitText(expandCompleted)
                assertFalse("已完成项在默认收起时不应出现", hasText("fixture-edited"))
                clickText(expandCompleted)
                awaitText(collapseCompleted)
                awaitText(base.getString(R.string.homework_undo))
                clickText(base.getString(R.string.homework_undo))
                awaitNode { it.isCheckable && it.contentDescription?.toString() == "fixture-edited" && !it.isChecked }
                assertTrue(awaitNode {
                    it.isCheckable && it.contentDescription?.toString() == "fixture-edited"
                }.performAction(AccessibilityNodeInfo.ACTION_CLICK))

                // 撤销最后一项会移除区块，但不会重置用户之前的展开选择。
                awaitText(completedHeader)
                awaitText(collapseCompleted)
                awaitText("fixture-edited")
                assertTrue(awaitNode { it.isCheckable && it.contentDescription?.toString() == "fixture-edited" }.isChecked)
                clickText(collapseCompleted)
                awaitText(expandCompleted)
                val collapseDeadline = System.nanoTime() + 5_000_000_000L
                while (hasText("fixture-edited") && System.nanoTime() < collapseDeadline) {
                    instrumentation.waitForIdleSync()
                    Thread.sleep(50)
                }
                assertFalse("收起后已完成项应隐藏", hasText("fixture-edited"))
                clickText(expandCompleted)
                awaitText(collapseCompleted)
                awaitText("fixture-edited")
                assertTrue(awaitNode {
                    it.isCheckable && it.contentDescription?.toString() == "fixture-edited"
                }.isChecked)
            }
        } finally {
            base.deleteSharedPreferences("$preferenceName-timeline_homework_v1")
            base.deleteSharedPreferences("$preferenceName-grader_secure_v1")
            base.deleteSharedPreferences("$preferenceName-geniusta_secure_v1")
        }
    }

    @Test fun bothPlatformControlsAndImportedTodosShareTheBoard() = runBlocking<Unit> {
        val preferenceName = "homework-grader-" + UUID.randomUUID()
        val upcoming = System.currentTimeMillis() + 3_600_000L
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
                            HomeworkViewModel(
                                repository,
                                GraderRepository(isolatedApp),
                                GeniusTaRepository(isolatedApp),
                                TodoPlatformCredentialStore(isolatedApp),
                                easRepository,
                            ) as T
                    })[HomeworkViewModel::class.java]
                    activity.setContentView(ComposeView(activity).apply {
                        setContent {
                            HitaComposeTheme {
                                HomeworkScreen(
                                    model,
                                    graderVisibleOverride = true,
                                    graderStateOverride = GraderState(
                                        accountId = "account",
                                        tasks = listOf(
                                            ImportedTodo("old", "fixture-expired-grader", "fixture-course", 1L, false),
                                            ImportedTodo("upcoming", "fixture-grader", "fixture-course", upcoming, false),
                                            ImportedTodo("submitted", "fixture-submitted-grader", "fixture-course",
                                                upcoming, completed = true, submitted = true),
                                        ),
                                        ready = true,
                                        error = GraderError.NETWORK,
                                    ),
                                    geniusTaStateOverride = GeniusTaState(
                                        accountId = "genius",
                                        ready = true,
                                        tasks = listOf(
                                            ImportedTodo("old-complete", "fixture-expired-geniusta", "fixture-course", 2L, true),
                                            ImportedTodo("completed", "fixture-geniusta", "fixture-course", upcoming, true),
                                        ),
                                    ),
                                )
                            }
                        }
                    })
                }
                val grader = base.getString(R.string.grader_title)
                awaitText(base.getString(R.string.homework_platform_relogin, grader))
                assertFalse(hasDescription(base.getString(R.string.homework_platform_refresh, grader)))
                assertFalse(hasDescription(base.getString(R.string.homework_platform_disconnect, grader)))
                awaitText(base.getString(R.string.homework_platform_refresh, base.getString(R.string.geniusta_title)))
                assertFalse("不得为重新登录弹窗", hasText(base.getString(R.string.grader_login_title)))

                awaitText("fixture-grader")
                awaitText(base.getString(R.string.grader_submit_action))
                awaitText(base.getString(R.string.homework_imported_source, base.getString(R.string.grader_title)))
                assertFalse("过期 Grader 待办不显示", hasText("fixture-expired-grader"))
                assertFalse("手动完成项应默认收起", hasText("fixture-geniusta"))
                assertFalse("服务端已提交项应默认收起", hasText("fixture-submitted-grader"))
                clickText(base.getString(R.string.homework_completed_section, 2))
                awaitText("fixture-geniusta")
                awaitText(base.getString(R.string.homework_imported_source, base.getString(R.string.geniusta_title)))
                assertFalse("过期 GeniusTA 待办不显示在已完成中", hasText("fixture-expired-geniusta"))
                val submitted = awaitNode("remote completion is read-only", scroll = true) {
                    it.isVisibleToUser && it.isCheckable && it.contentDescription?.toString() == "fixture-submitted-grader"
                }
                assertTrue(submitted.isChecked)
                assertFalse(submitted.isEnabled)
                clickText(base.getString(R.string.grader_submit_resubmit_action))
                awaitText(base.getString(R.string.grader_submit_error_detail))
                clickText(base.getString(R.string.homework_cancel))
                awaitText("fixture-submitted-grader")
            }
        } finally {
            base.deleteSharedPreferences("$preferenceName-timeline_homework_v1")
            base.deleteSharedPreferences("$preferenceName-grader_secure_v1")
            base.deleteSharedPreferences("$preferenceName-geniusta_secure_v1")
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

    private fun hasDescription(description: String): Boolean {
        fun contains(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.isVisibleToUser && node.contentDescription?.toString() == description) return true
            return (0 until node.childCount).any { contains(node.getChild(it)) }
        }
        return contains(freshRoot())
    }

    private fun awaitText(text: String) = awaitNode("text: $text", scroll = true) {
        it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text)
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target?.takeIf { it.isEnabled }
    }

    /** 对话框里的按钮不该靠滚动背景列表来命中。 */
    private fun clickDialogText(text: String) {
        val node = awaitNode("enabled dialog click target: $text", scroll = false) { candidate ->
            candidate.isVisibleToUser && candidate.text?.toString() == text && clickableAncestor(candidate) != null
        }
        val target = requireNotNull(clickableAncestor(node))
        assertTrue("Accessibility click was rejected: $text", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
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
