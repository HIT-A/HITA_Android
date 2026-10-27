package cn.limpu.hita.ui.main

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.repository.EasSettingsRepository
import cn.limpu.hita.ui.main.agent.AgentChatFragment
import cn.limpu.hita.ui.main.blog.BlogFragment
import cn.limpu.hita.ui.main.navigation.NavigationFragment
import cn.limpu.hita.ui.main.timeline.FragmentTimeLine
import cn.limpu.hita.ui.main.timetable.TimetableFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun realMainRecreationPreservesSelectedIdentityAndRestoredFragmentArguments() = launch { scenario ->
        lateinit var originalModel: MainViewModel
        scenario.onActivity {
            originalModel = ViewModelProvider(it)[MainViewModel::class.java]
            originalModel.selectTab(originalModel.state.value.navigation.visibleTabs.indexOf(MainTab.NAVIGATION))
        }
        waitForTab(scenario, MainTab.NAVIGATION)
        scenario.onActivity {
            val fragment = requireNotNull(it.supportFragmentManager.findFragmentByTag(MainTabHost.tag(MainTab.NAVIGATION)))
            fragment.arguments = Bundle().apply { putString("main_restore_marker", "preserved") }
        }
        scenario.recreate()
        waitForTab(scenario, MainTab.NAVIGATION)
        scenario.onActivity {
            val model = ViewModelProvider(it)[MainViewModel::class.java]
            assertSame(originalModel, model)
            assertEquals(MainTab.NAVIGATION, model.state.value.navigation.selectedTab)
            val fragments = it.supportFragmentManager.fragments.filter { fragment -> fragment.tag == MainTabHost.tag(MainTab.NAVIGATION) }
            assertEquals(1, fragments.size)
            assertEquals("preserved", fragments.single().arguments?.getString("main_restore_marker"))
            assertTrue(requireNotNull(fragments.single().view).isAttachedToWindow)
        }
    }

    @Test fun recompositionEquivalentRendersActivateOnlyTransitionsAndRetainDynamicTabs() = launch { scenario ->
        scenario.onActivity { activity ->
            val activated = mutableListOf<Fragment>()
            val host = replaceHost(activity, activated::add)
            val withoutBlog = MainTab.visibleFor(null)
            host.render(MainTab.TIMELINE, withoutBlog)
            val timeline = requireNotNull(host.find(MainTab.TIMELINE))
            repeat(12) { host.render(MainTab.TIMELINE, withoutBlog) }
            host.render(MainTab.TIMELINE, MainTab.entries)
            assertEquals(listOf(timeline), activated)
            host.render(MainTab.BLOG, MainTab.entries)
            val blog = requireNotNull(host.find(MainTab.BLOG))
            host.render(MainTab.BLOG, withoutBlog)
            assertEquals(listOf(timeline, blog, timeline), activated)
            assertSame(blog, host.find(MainTab.BLOG))
            host.render(MainTab.BLOG, MainTab.entries)
            assertSame(blog, host.find(MainTab.BLOG))
            assertFalse(blog.isHidden)
            assertTrue(timeline.isHidden)
            assertEquals(1, activity.supportFragmentManager.fragments.count { it.tag == MainTabHost.tag(MainTab.BLOG) })
        }
    }

    @Test fun selectionWhileStateSavedWaitsForResumeWithoutStateLoss() = launch { scenario ->
        lateinit var host: MainTabHost
        scenario.onActivity { host = replaceHost(it) }
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.onActivity {
            assertTrue(it.supportFragmentManager.isStateSaved)
            host.render(MainTab.NAVIGATION, MainTab.visibleFor(null))
            assertNull(host.find(MainTab.NAVIGATION))
        }
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.onActivity {
            assertNotNull(host.find(MainTab.NAVIGATION))
            assertFalse(requireNotNull(host.find(MainTab.NAVIGATION)).isHidden)
        }
    }

    @Test fun realDrawerBackAndPageControllerUpdateExplicitStateWithoutFinishing() = launch { scenario ->
        scenario.onActivity { activity ->
            val model = ViewModelProvider(activity)[MainViewModel::class.java]
            model.setDrawer(true)
            activity.onBackPressedDispatcher.onBackPressed()
            assertFalse(model.state.value.drawerOpen)
            assertFalse(activity.isFinishing)
            activity.setTitleText("第八周")
            activity.setTimetableName("课表 fixture")
            activity.setTimelineTitleText("周一 fixture")
            assertEquals("第八周", model.state.value.timetableTitle)
            assertEquals("课表 fixture", model.state.value.timetableName)
            assertTrue(model.state.value.showTimetableName)
            activity.setSingleTitle("单标题")
            assertFalse(model.state.value.showTimetableName)
            assertEquals("周一 fixture", model.state.value.todayTitle)
            assertEquals("external-course", activity.intent.getStringExtra("live_course_id"))
        }
    }

    @Test fun retainedViewModelRejectsEffectsAddressedToDetachedOrReplacedHosts() = launch { scenario ->
        scenario.onActivity { activity ->
            val model = ViewModelProvider(activity)[MainViewModel::class.java]
            val first = model.attachHost()
            val value = MainHostEffect(first, null, MainEffect.SessionExpired)
            assertTrue(model.accepts(value, first))
            model.detachHost(first)
            assertFalse(model.accepts(value, first))
            val second = model.attachHost()
            assertFalse(model.accepts(value, second))
            assertTrue(model.accepts(MainHostEffect(second, null, MainEffect.SessionExpired), second))
            model.detachHost(second)
        }
    }

    private fun replaceHost(activity: MainActivity, activated: (Fragment) -> Unit = {}): MainTabHost {
        // Replace the owned platform adapter, keeping the real Activity/FragmentManager and fragments.
        val field = MainActivity::class.java.getDeclaredField("tabHost").apply { isAccessible = true }
        (field.get(activity) as MainTabHost).close()
        val host = MainTabHost(activity.supportFragmentManager, null, ::fragment, activated)
        field.set(activity, host)
        activity.setContentView(host.createView(activity))
        host.render(MainTab.TIMELINE, MainTab.visibleFor(null))
        return host
    }

    private fun fragment(tab: MainTab): Fragment = when (tab) {
        MainTab.TIMELINE -> FragmentTimeLine()
        MainTab.TIMETABLE -> TimetableFragment()
        MainTab.AGENT -> AgentChatFragment()
        MainTab.BLOG -> BlogFragment()
        MainTab.NAVIGATION -> NavigationFragment()
    }

    private fun waitForTab(scenario: ActivityScenario<MainActivity>, tab: MainTab) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync()
            var visible = false
            scenario.onActivity { visible = it.supportFragmentManager.findFragmentByTag(MainTabHost.tag(tab))?.view?.isAttachedToWindow == true }
            if (visible) return
            Thread.sleep(20)
        }
        fail("Main did not mount $tab")
    }

    private fun launch(block: (ActivityScenario<MainActivity>) -> Unit) {
        val settings = EasSettingsRepository(app)
        val enabled = settings.isAutoReimportEnabled()
        settings.setAutoReimport(false)
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).putExtra("live_course_id", "external-course")).use(block)
        } finally {
            settings.setAutoReimport(enabled)
        }
    }
}
