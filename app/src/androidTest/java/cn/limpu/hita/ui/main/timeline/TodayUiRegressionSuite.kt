package cn.limpu.hita.ui.main.timeline

import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Suite

/** Explicit nested suite: default test discovery skips nested classes, avoiding duplicate runs. */
object TodayUiRegressionSuite {
    @RunWith(Suite::class)
    @Suite.SuiteClasses(
        TodayViewModelInstrumentedTest::class,
        TodayScreenInstrumentedTest::class,
        TodayLayoutInstrumentedTest::class,
        TodayScopePickerInstrumentedTest::class,
        cn.limpu.hita.ui.main.MainBottomBarGeometryInstrumentedTest::class,
        cn.limpu.hita.data.repository.TimetableDisplayStorageTest::class,
    )
    class Foreground {
        companion object {
            private var host: ActivityScenario<ComponentActivity>? = null
            @BeforeClass @JvmStatic fun showIsolatedHost() {
                host = ActivityScenario.launch(ComponentActivity::class.java)
            }
            @AfterClass @JvmStatic fun closeIsolatedHost() {
                host?.close()
                host = null
            }
        }
    }
}
