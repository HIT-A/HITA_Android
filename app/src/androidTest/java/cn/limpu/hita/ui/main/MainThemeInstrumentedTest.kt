package cn.limpu.hita.ui.main

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.R
import com.limpu.style.ThemeTools
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose/Fragment rendering smoke; screenshots are evidence, not a pixel-equivalence oracle. */
@RunWith(AndroidJUnit4::class)
class MainThemeInstrumentedTest {
    @Test fun allSevenThemesRenderDrawerAndPageWithoutReplacingTheActiveFragment() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val previous = ThemeTools.getThemeStyle(app)
        val screenshots = File(app.cacheDir, "main-theme-smoke").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    // Public notice/update responses are not part of this rendering fixture.
                    listOf("noticePresenter", "updatePresenter").forEach { fieldName ->
                        val field = MainActivity::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
                        (field.get(activity) as AutoCloseable).close()
                    }
                }
                ThemeTools.STYLE.entries.forEach { style ->
                    val frame = CountDownLatch(1)
                    scenario.onActivity { activity ->
                        ThemeTools.setThemeStyle(activity, style)
                        val model = ViewModelProvider(activity)[MainViewModel::class.java]
                        model.setDrawer(true)
                        activity.window.decorView.postOnAnimation { activity.window.decorView.postOnAnimation { frame.countDown() } }
                    }
                    assertTrue("No rendered frame for $style", frame.await(10, TimeUnit.SECONDS))
                    instrumentation.waitForIdleSync()
                    // A frame callback can precede dialog dismissal and the drawer animation.
                    // Wait for the actual visible drawer entry before capturing the platform surface.
                    val label = "${app.getString(R.string.main_drawer_menu_theme_style)} · ${app.getString(style.mainDisplayName())}"
                    val deadline = SystemClock.uptimeMillis() + 10_000
                    var drawerVisible = false
                    fun hasVisibleLabel(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                        if (node == null) return false
                        val bounds = Rect().also(node::getBoundsInScreen)
                        if (node.isVisibleToUser && node.text?.toString() == label && bounds.width() > 0) return true
                        return (0 until node.childCount).any { hasVisibleLabel(node.getChild(it)) }
                    }
                    while (!drawerVisible && SystemClock.uptimeMillis() < deadline) {
                        // Read fresh virtual nodes after the previous theme's accessibility events.
                        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                        drawerVisible = hasVisibleLabel(instrumentation.uiAutomation.rootInActiveWindow)
                        if (!drawerVisible) SystemClock.sleep(50)
                    }
                    assertTrue("Visible drawer was not rendered for $style", drawerVisible)
                    instrumentation.uiAutomation.waitForIdle(250, 10_000)
                    val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        assertTrue(screenshot.width > 0 && screenshot.height > 0)
                        File(screenshots, "${style.name}.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { screenshot.recycle() }
                    scenario.onActivity { activity ->
                        val fragments = activity.supportFragmentManager.fragments.filter { it.tag == MainTabHost.tag(MainTab.TIMELINE) }
                        assertEquals(1, fragments.size)
                        assertTrue(requireNotNull(fragments.single().view).isAttachedToWindow)
                        activity.onBackPressedDispatcher.onBackPressed()
                        assertFalse(ViewModelProvider(activity)[MainViewModel::class.java].state.value.drawerOpen)
                    }
                }
                assertEquals(ThemeTools.STYLE.entries.size, screenshots.listFiles().orEmpty().count { it.extension == "png" })
            }
        } finally { ThemeTools.setThemeStyle(app, previous) }
    }
}
