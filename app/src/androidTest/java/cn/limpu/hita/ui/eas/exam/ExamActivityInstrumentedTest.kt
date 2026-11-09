package cn.limpu.hita.ui.eas.exam

import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.data.repository.EasSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 考试查询页的 Activity 级回归：重建后 ViewModel 与考试类型选择保持、返回导航可回退；
 * 同时锁定"校区过滤行可见性"这一由 Activity 传入的派生值（UI-035 的修复契约）。
 */
@RunWith(AndroidJUnit4::class)
class ExamActivityInstrumentedTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun recreationKeepsViewModelExamTypeAndCampusDerivedFilter() {
        val settings = EasSettingsRepository(app)
        val autoReimport = settings.isAutoReimportEnabled()
        settings.setAutoReimport(false)
        // 校区只用于推导"过滤行是否出现"，直接读偏好，避免构造整个 Repository。
        val campus = EasPreferenceSource(app).getEasToken().campus
        try {
            ActivityScenario.launch<ExamActivity>(
                Intent(app, ExamActivity::class.java)
            ).use { scenario ->
                lateinit var original: ExamViewModel
                var expectedFilter = false
                scenario.onActivity { activity ->
                    original = ViewModelProvider(activity)[ExamViewModel::class.java]
                    assertNotNull(activity.window.decorView)
                    // initViews 会把考试类型置为 ALL；过滤行只在本部校区出现。
                    assertEquals(ExamViewModel.ExamType.ALL, original.selectedExamTypeLiveData.value)
                    expectedFilter = original.shouldShowExamTypeFilter()
                    assertEquals(campus == EASToken.Campus.BENBU, expectedFilter)
                }

                scenario.recreate()

                scenario.onActivity { activity ->
                    val current = ViewModelProvider(activity)[ExamViewModel::class.java]
                    assertSame("recreation must retain the same ViewModel", original, current)
                    assertEquals(ExamViewModel.ExamType.ALL, current.selectedExamTypeLiveData.value)
                    assertEquals(expectedFilter, current.shouldShowExamTypeFilter())
                    activity.onBackPressedDispatcher.onBackPressed()
                    assertFalse("back from exam page must not bypass the stack", activity.isFinishing)
                }
            }
        } finally {
            settings.setAutoReimport(autoReimport)
        }
    }
}
