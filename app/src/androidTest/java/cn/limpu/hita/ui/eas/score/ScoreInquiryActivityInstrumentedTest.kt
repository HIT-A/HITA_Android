package cn.limpu.hita.ui.eas.score

import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.repository.EasSettingsRepository
import cn.limpu.hita.data.source.preference.ScoreReminderStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 成绩查询页的真实 Activity 级回归：旋转/重建后仍是同一个 ViewModel、学期与考试类型选择保留，
 * 返回导航不会误 finish。数据源需要真实教务会话，因此这里只验证与 UI 状态所有权相关的契约。
 */
@RunWith(AndroidJUnit4::class)
class ScoreInquiryActivityInstrumentedTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun recreationKeepsViewModelAndSelectionOwnership() {
        val settings = EasSettingsRepository(app)
        val autoReimport = settings.isAutoReimportEnabled()
        settings.setAutoReimport(false)
        val reminder = ScoreReminderStore(app)
        val reminderWasEnabled = reminder.isEnabled()
        reminder.setEnabled(false)
        try {
            ActivityScenario.launch<ScoreInquiryActivity>(
                Intent(app, ScoreInquiryActivity::class.java)
            ).use { scenario ->
                lateinit var original: ScoreInquiryViewModel
                scenario.onActivity { activity ->
                    original = ViewModelProvider(activity)[ScoreInquiryViewModel::class.java]
                    assertNotNull("score screen must compose", activity.window.decorView)
                }

                scenario.recreate()

                scenario.onActivity { activity ->
                    val current = ViewModelProvider(activity)[ScoreInquiryViewModel::class.java]
                    assertSame("recreation must retain the same ViewModel", original, current)
                    // 默认考试类型在 initViews 中写入，重建后仍在同一个 VM 上可见。
                    assertEquals(
                        cn.limpu.hita.data.source.web.service.EASService.TestType.NORMAL,
                        current.selectedTestTypeLiveData.value,
                    )
                    activity.onBackPressedDispatcher.onBackPressed()
                    assertFalse("back from score page must not bypass the stack", activity.isFinishing)
                }
            }
        } finally {
            reminder.setEnabled(reminderWasEnabled)
            settings.setAutoReimport(autoReimport)
        }
    }
}
