package cn.limpu.hita.ui.main

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainWallpaperInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private data class Published(val bitmap: Bitmap?, val visible: Boolean, val title: Int, val label: Int)
    private class Images : WallpaperImageSource {
        data class Pending(val ready: (Bitmap) -> Unit, val cleared: () -> Unit, var closed: Boolean = false)
        val pending = mutableListOf<Pending>()
        override fun load(path: String, ready: (Bitmap) -> Unit, cleared: () -> Unit): AutoCloseable {
            val item = Pending(ready, cleared)
            pending += item
            return AutoCloseable { item.closed = true; item.cleared() }
        }
    }

    @Test fun slowOldBitmapAndClearCannotReplaceNewImageOrEitherColor() = main {
        val images = Images()
        val output = mutableListOf<Published>()
        val loader = WallpaperLoader(images) { bitmap, visible, title, label -> output += Published(bitmap, visible, title, label) }
        val old = bitmap(Color.BLACK)
        val newest = bitmap(Color.WHITE)
        try {
            loader.load("A")
            loader.load("B")
            images.pending[1].ready(newest)
            val before = output.last()
            assertSame(newest, before.bitmap)
            assertEquals(Color.rgb(0x33, 0x33, 0x33), before.title)
            assertEquals(before.title, before.label)
            images.pending[0].ready(old)
            images.pending[0].cleared()
            assertEquals(before, output.last())
            assertTrue(images.pending[0].closed)
        } finally { loader.close(); old.recycle(); newest.recycle() }
    }

    @Test fun closeClearsTargetAndReleasesConsumerBeforeAnyLateCallback() = main {
        val images = Images()
        val output = mutableListOf<Published>()
        val loader = WallpaperLoader(images) { bitmap, visible, title, label -> output += Published(bitmap, visible, title, label) }
        val image = bitmap(Color.BLACK)
        loader.load("A")
        loader.close()
        val count = output.size
        images.pending.single().ready(image)
        images.pending.single().cleared()
        loader.load("B")
        assertTrue(images.pending.single().closed)
        assertEquals(count, output.size)
        assertEquals(1, images.pending.size)
        image.recycle()
    }

    @Test fun removingWallpaperResetsBitmapAndColorsAndRejectsPendingImage() = main {
        val images = Images()
        val output = mutableListOf<Published>()
        val loader = WallpaperLoader(images) { bitmap, visible, title, label -> output += Published(bitmap, visible, title, label) }
        val image = bitmap(Color.BLACK)
        try {
            loader.load("A")
            images.pending.single().ready(image)
            loader.load("")
            val cleared = Published(null, false, Color.WHITE, Color.WHITE)
            assertEquals(cleared, output.last())
            images.pending.single().ready(image)
            assertEquals(cleared, output.last())
        } finally { loader.close(); image.recycle() }
    }

    @Test fun actualActivityScopedGlideLoadsFileSamplesColorsAndCloses() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File.createTempFile("main-glide-fixture", ".png", app.cacheDir)
        val fixture = bitmap(Color.WHITE)
        file.outputStream().use { fixture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        fixture.recycle()
        val ready = CountDownLatch(1)
        var result: Published? = null
        var loader: WallpaperLoader? = null
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    loader = WallpaperLoader(activity) { bitmap, visible, title, label ->
                        if (visible) { result = Published(bitmap, visible, title, label); ready.countDown() }
                    }.also { it.load("local://${file.absolutePath}") }
                }
                assertTrue("Glide did not load local fixture", ready.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    assertNotNull(result?.bitmap)
                    assertTrue(result?.visible == true)
                    assertEquals(Color.rgb(0x33, 0x33, 0x33), result?.title)
                    assertEquals(result?.title, result?.label)
                    loader?.close()
                }
            }
        } finally {
            main { loader?.close() }
            file.delete()
        }
    }

    private fun bitmap(color: Int) = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
