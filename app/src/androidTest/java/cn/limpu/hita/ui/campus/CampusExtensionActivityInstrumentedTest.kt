package cn.limpu.hita.ui.campus

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.source.preference.CourseReminderStore
import cn.limpu.hita.data.work.ReminderRuleSet
import cn.limpu.hita.ui.settings.CourseReminderSettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 校园扩展四个页面的 Activity 级回归。
 *
 * 这些页面依赖独立的门户会话（dxcdp / utszlib / lab.hitsz.edu.cn），
 * 在测试环境里一定没有会话。要验证的正是**没有会话时的降级路径**：
 * 页面必须能建立、停在「需要登录」，而不是崩溃或白屏。
 *
 * 真实门户数据依赖读者账号，不在自动化范围内；那部分只做纯函数解析测试。
 */
@RunWith(AndroidJUnit4::class)
class CampusExtensionActivityInstrumentedTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun campusCardLaunchesWithoutSession() {
        ActivityScenario.launch<CampusCardActivity>(Intent(app, CampusCardActivity::class.java)).use {
            // 能建立并不被立即销毁，就说明未登录分支没有抛异常。
            it.onActivity { activity -> assertTrue(!activity.isFinishing) }
        }
    }

    @Test
    fun silentPodLaunchesWithoutSession() {
        ActivityScenario.launch<SilentPodActivity>(Intent(app, SilentPodActivity::class.java)).use {
            it.onActivity { activity -> assertTrue(!activity.isFinishing) }
        }
    }

    @Test
    fun universityTownLibraryLaunchesWithoutSession() {
        ActivityScenario.launch<UniversityTownLibraryActivity>(
            Intent(app, UniversityTownLibraryActivity::class.java),
        ).use {
            it.onActivity { activity -> assertTrue(!activity.isFinishing) }
        }
    }

    @Test
    fun labCenterLaunchesWithoutSession() {
        ActivityScenario.launch<LabCenterActivity>(Intent(app, LabCenterActivity::class.java)).use {
            it.onActivity { activity -> assertTrue(!activity.isFinishing) }
        }
    }
}

/**
 * 课程提醒设置页的 Activity 级回归：把规则写入后必须能原样读回。
 *
 * 这是 R-01 / R-04 的用户可见契约——设置页改了值，Worker 读到的必须是同一个值。
 */
@RunWith(AndroidJUnit4::class)
class CourseReminderSettingsActivityInstrumentedTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun settingsActivityLaunchesAndStoreRoundTrips() {
        val store = CourseReminderStore(app)
        val original = store.rules()
        try {
            // 直接走 store 契约：设置页保存后就是这个终态。
            val desired = ReminderRuleSet(
                leads = listOf(30),
                endLeads = listOf(10),
            )
            assertTrue(store.saveRules(desired))

            val reloaded = store.rules()
            assertEquals(listOf(30), reloaded.leads)
            assertEquals(listOf(10), reloaded.endLeads)

            ActivityScenario.launch<CourseReminderSettingsActivity>(
                Intent(app, CourseReminderSettingsActivity::class.java),
            ).use {
                it.onActivity { activity -> assertTrue(!activity.isFinishing) }
            }
        } finally {
            store.saveRules(original)
        }
    }
}
