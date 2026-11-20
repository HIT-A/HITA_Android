package cn.limpu.hita.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.ui.design.HitaComposeTheme
import com.limpu.style.ThemeTools
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MainBottomBarGeometryInstrumentedTest {
    @Test fun renderedBarHeightsMatchSharedGeometry() {
        for (scale in listOf(1f, 1.3f, 1.6f)) for (dock in listOf(true, false)) {
            val measured = CountDownLatch(1)
            var height = 0f
            var expected = 0f
            var measuredDensity = 1f
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContentView(ComposeView(activity).apply {
                    setContent { HitaComposeTheme {
                        val nativeDensity = LocalDensity.current.density
                        measuredDensity = nativeDensity
                        CompositionLocalProvider(LocalDensity provides Density(nativeDensity, scale)) {
                            expected = if (dock) dockBarHeight(10f * scale) else pillBarHeight(scale, rememberPillBarLabelHeight()).value
                            val modifier = Modifier.onGloballyPositioned { height = it.size.height.toFloat(); measured.countDown() }
                            val tabs = listOf(MainTab.TIMELINE, MainTab.TIMETABLE, MainTab.HOMEWORK)
                            // Drop ComposeView's full-window minimum constraints before measuring the bar body.
                            Box {
                                if (dock) DockTabBar(0, tabs, false, 1f, ThemeTools.STYLE.CLASSIC, null, null, {}, modifier)
                                else MainPillTabBar(0, tabs, false, 1f, ThemeTools.STYLE.CLASSIC, null, null, {}, modifier)
                            }
                        }
                    } }
                }) }
                assertTrue("Bar did not lay out", measured.await(5, TimeUnit.SECONDS))
                assertEquals("dock=$dock scale=$scale", expected, height / measuredDensity, 1f)
            }
        }
    }
}
