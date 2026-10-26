package cn.limpu.hita.ui.subject

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.text.Spanned
import android.text.style.ClickableSpan
import android.widget.TextView
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.model.resource.ExternalResourceEntry
import com.limpu.component.data.DataState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubjectResourcePlatformInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun actualMarkwonLinkSpansResolveAgainstEachCardSourceAndViewReuse() = main {
        val opened = mutableListOf<String>()
        val renderer = SubjectMarkdownRenderer(app, opened::add)
        val a = renderer.createView(app, Color.BLACK)
        val b = renderer.createView(app, Color.BLACK)
        renderer.render(a, "[讲义](notes.pdf)", "https://a/course/README.md", Color.BLACK)
        renderer.render(b, "[讲义](notes.pdf)", "https://b/other/README.md", Color.BLACK)
        clickLink(a)
        clickLink(b)
        renderer.render(a, "[讲义](notes.pdf)", "", Color.BLACK)
        clickLink(a)
        assertEquals(listOf("https://a/course/notes.pdf", "https://b/other/notes.pdf", "notes.pdf"), opened)
    }

    @Test fun downloadServiceFailureFallsBackToBrowserAndMarkdownKeepsExplicitIntent() = main {
        val intents = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.DOWNLOAD_SERVICE) throw IllegalStateException("download unavailable")
                return super.getSystemService(name)
            }
            override fun startActivity(intent: Intent) { intents.add(intent) }
        }
        val launcher = SubjectResourceLauncher(context)
        launcher.open(ExternalResourceEntry(name = "a.pdf", path = "a.pdf"))
        assertEquals(Intent.ACTION_VIEW, intents.single().action)
        assertEquals("https://ghproxy.net/https://raw.githubusercontent.com/HITLittleZheng/HITCS/main/a.pdf", intents.single().dataString)
        launcher.open(ExternalResourceEntry(name = "README.MD", path = "a b/README.MD"))
        assertEquals("cn.limpu.hita.ui.resource.MarkdownViewerActivity", intents.last().component?.className)
        assertEquals("README.MD", intents.last().getStringExtra("title"))
        assertTrue(intents.last().getStringExtra("url").orEmpty().contains("a%20b/README.MD"))
        launcher.open(ExternalResourceEntry(downloadUrl = "https://fireworks.jwyihao.top/notes"))
        assertEquals("https://fireworks.jwyihao.top/notes", intents.last().dataString)
    }

    @Test fun liveDataAdapterDetachesOnCancelTerminalAndSynchronousCachedDelivery() = main {
        val source = MutableLiveData<DataState<String>>()
        val delivered = mutableListOf<Result<String>>()
        val request = observeSubjectResource(source, delivered::add)
        source.value = DataState(DataState.STATE.LOADING)
        assertTrue(source.hasObservers())
        assertTrue(delivered.isEmpty())
        request.cancel()
        source.value = DataState("cancelled")
        assertFalse(source.hasObservers())
        assertTrue(delivered.isEmpty())
        val cached = MutableLiveData(DataState("cached"))
        observeSubjectResource(cached, delivered::add)
        assertEquals("cached", delivered.single().getOrNull())
        assertFalse(cached.hasObservers())
        val failed = MutableLiveData<DataState<String>>()
        observeSubjectResource(failed, delivered::add)
        failed.value = DataState(DataState.STATE.FETCH_FAILED, "offline")
        assertEquals("offline", delivered.last().exceptionOrNull()?.message)
        assertFalse(failed.hasObservers())
    }

    private fun clickLink(view: TextView) {
        val text = view.text as Spanned
        val links = text.getSpans(0, text.length, ClickableSpan::class.java)
        assertEquals(1, links.size)
        links.single().onClick(view)
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
