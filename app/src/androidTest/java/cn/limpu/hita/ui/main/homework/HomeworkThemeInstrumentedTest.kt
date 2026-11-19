package cn.limpu.hita.ui.main.homework

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.EditText
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import cn.limpu.hita.R
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.main.MainThemeBackground
import com.limpu.style.ThemeTools
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeworkThemeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val backLabel get() = context.getString(R.string.today_back)

    @Test
    fun soraCloudArtworkContinuesFromToolbarIntoTodoListInBothModes() {
        val preferences = ThemeTools.getThemePreferences(context)
        val originalStyle = preferences.getString(ThemeTools.KEY_STYLE, null)
        val originalMode = preferences.getString(ThemeTools.KEY_MODE, null)
        try {
            ThemeTools.setThemeStyle(context, ThemeTools.STYLE.SORA_CLOUD)
            for (mode in listOf(ThemeTools.MODE.LIGHT, ThemeTools.MODE.DARK)) {
                ThemeTools.setThemeMode(context, mode)
                ActivityScenario.launch(HomeworkActivity::class.java).use { scenario ->
                    requireNotNull(device.wait(Until.findObject(By.desc(backLabel)), 5_000))
                    requireNotNull(device.wait(Until.findObject(By.clazz(EditText::class.java)), 5_000))
                    val page = capture(scenario)
                    try {
                        scenario.onActivity { activity ->
                            todoView(activity).setContent {
                                HitaComposeTheme { MainThemeBackground(page = {}) }
                            }
                        }
                        val bare = capture(scenario)
                        try {
                            val gutter = page.leftInset + page.px(8)
                            val toolbar = page.topInset + page.px(32)
                            val listStart = page.topInset + page.px(76)
                            val paper = page.bitmap.height * 40 / 100
                            val indigo = page.bitmap.height * 67 / 100
                            // The gutter is outside the controls and list cards. Compare the same
                            // coordinates to the unobscured art, not just to adjacent pixels.
                            assertTrue("Sora artwork missing in $mode", distance(
                                bare.bitmap.getPixel(gutter, paper), bare.bitmap.getPixel(gutter, indigo),
                            ) > 20)
                            for (y in listOf(toolbar, listStart, paper, indigo)) {
                                val expected = bare.bitmap.getPixel(gutter, y)
                                val actual = page.bitmap.getPixel(gutter, y)
                                assertTrue("Artwork covered at y=$y in $mode", distance(expected, actual) <= 3)
                            }
                        } finally {
                            bare.bitmap.recycle()
                        }
                    } finally {
                        page.bitmap.recycle()
                    }
                }
            }
        } finally {
            preferences.edit()
                .putString(ThemeTools.KEY_STYLE, originalStyle)
                .putString(ThemeTools.KEY_MODE, originalMode)
                .apply()
        }
    }

    @Test
    fun standaloneRetainsDraftOnRecreationAndToolbarNavigatesBack() {
        ActivityScenario.launch(HomeworkActivity::class.java).use { scenario ->
            val editor = By.clazz(EditText::class.java)
            val draft = "fixture unsaved todo"
            val draftField = By.clazz(EditText::class.java).text(draft)
            requireNotNull(device.wait(Until.findObject(editor.enabled(true)), 5_000)).text = draft
            requireNotNull(device.wait(Until.findObject(draftField), 5_000))

            scenario.recreate()
            requireNotNull(device.wait(Until.findObject(draftField), 5_000))
            val destroyed = CountDownLatch(1)
            scenario.onActivity { activity ->
                activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onDestroy(owner: LifecycleOwner) { destroyed.countDown() }
                })
            }
            requireNotNull(device.wait(Until.findObject(By.desc(backLabel)), 5_000)).click()
            assertTrue("Toolbar back did not close the standalone host", destroyed.await(5, TimeUnit.SECONDS))
        }
    }

    private fun todoView(activity: HomeworkActivity): ComposeView =
        activity.supportFragmentManager.findFragmentById(R.id.today_homework_container)!!.requireView() as ComposeView

    private fun capture(scenario: ActivityScenario<HomeworkActivity>): Frame {
        val copied = CountDownLatch(1)
        lateinit var frame: Frame
        var result = PixelCopy.ERROR_UNKNOWN
        scenario.onActivity { activity ->
            val view = todoView(activity)
            view.postOnAnimation {
                view.postOnAnimation {
                    val (x, y) = IntArray(2).also(view::getLocationInWindow)
                    val insets = requireNotNull(ViewCompat.getRootWindowInsets(view)).getInsets(
                        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                    )
                    frame = Frame(
                        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888),
                        insets.left, insets.top, view.resources.displayMetrics.density,
                    )
                    PixelCopy.request(
                        activity.window, Rect(x, y, x + view.width, y + view.height), frame.bitmap,
                        { result = it; copied.countDown() }, Handler(Looper.getMainLooper()),
                    )
                }
            }
        }
        assertTrue("No completed pixel capture", copied.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        return frame
    }

    private fun distance(a: Int, b: Int): Int = maxOf(
        abs(Color.red(a) - Color.red(b)),
        abs(Color.green(a) - Color.green(b)),
        abs(Color.blue(a) - Color.blue(b)),
    )

    private data class Frame(val bitmap: Bitmap, val leftInset: Int, val topInset: Int, val density: Float) {
        fun px(dp: Int): Int = (dp * density).roundToInt()
    }
}
