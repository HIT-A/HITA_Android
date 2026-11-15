package cn.limpu.hita.ui.eas.login

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EasRememberMeInstrumentedTest {
    @Test fun nativeCheckboxIsCheckedOnceAndEmitsRealChange() = Page("""
        <label><input id="rememberMe" type="checkbox"
          onchange="window.changes=(window.changes||0)+1">14天免登录</label>
    """).use { page ->
        repeat(4) { page.runRememberMe() }
        assertEquals("true", page.eval("document.getElementById('rememberMe').checked"))
        assertEquals("1", page.eval("window.changes"))
    }

    @Test fun hiddenNativeCheckboxUsesItsVisibleAssociatedLabel() = Page("""
        <input id="choice" type="checkbox" style="display:none">
        <label for="choice">记住我</label>
    """).use { page ->
        repeat(4) { page.runRememberMe() }
        assertEquals("true", page.eval("document.getElementById('choice').checked"))
    }

    @Test fun ariaCheckboxIsNotToggledOffByRepeatedInjection() = Page("""
        <div id="choice" role="checkbox" aria-checked="false"
          onclick="window.clicks=(window.clicks||0)+1; this.setAttribute('aria-checked', this.getAttribute('aria-checked')==='true'?'false':'true')">
          14天免登录
        </div>
    """).use { page ->
        repeat(4) { page.runRememberMe() }
        assertEquals("\"true\"", page.eval("document.getElementById('choice').getAttribute('aria-checked')"))
        assertEquals("1", page.eval("window.clicks"))
    }

    @Test fun unknownStateControlIsNotClickedOrReportedAsChecked() = Page("""
        <span onclick="window.clicked=true">14天免登录</span>
    """).use { page ->
        repeat(4) { assertTrue(page.runRememberMe().contains("none")) }
        assertEquals("false", page.eval("window.clicked===true"))
    }

    @Test fun mfaTrustDeviceIsNeverSelected() = Page("""
        <label><input id="rememberDevice" type="checkbox">记住／信任设备</label>
        <div role="checkbox" aria-checked="false" onclick="window.clicked=true">Remember this device</div>
    """).use { page ->
        repeat(4) { page.runRememberMe() }
        assertEquals("false", page.eval("document.getElementById('rememberDevice').checked"))
        assertEquals("false", page.eval("window.clicked===true"))
    }

    @Test fun laterRetriesRespectUserUncheckingTheOption() = Page("""
        <label><input id="rememberMe" type="checkbox" checked>记住我</label>
    """).use { page ->
        page.runRememberMe()
        page.eval("document.getElementById('rememberMe').click()")
        repeat(3) { page.runRememberMe() }
        assertEquals("false", page.eval("document.getElementById('rememberMe').checked"))
    }

    @Test fun retriesWaitForLateRenderedControl() = Page("<div id='root'></div>").use { page ->
        assertTrue(page.runRememberMe().contains("none"))
        page.eval("document.getElementById('root').innerHTML='<label><input id=rememberMe type=checkbox>记住我</label>'")
        repeat(3) { page.runRememberMe() }
        assertEquals("true", page.eval("document.getElementById('rememberMe').checked"))
    }

    @Test fun delayedAutofillDoesNotReplaceManualCredentialsOrSubmitThem() = Page("""
        <form onsubmit="window.submitted=true; return false">
          <input id="username" value="manual-account"><input type="password" value="manual-password">
          <button type="submit">登录</button>
        </form>
    """).use { page ->
        page.eval(EasWebCredentialPolicy.autofillAndSubmitScript("saved-account", "saved-password"))
        assertEquals("\"manual-account\"", page.eval("document.getElementById('username').value"))
        assertEquals("\"manual-password\"", page.eval("document.querySelector('input[type=password]').value"))
        assertEquals("false", page.eval("window.submitted===true"))
    }

    @Test fun emptyLoginFormStillFillsAndSubmitsSavedCredentials() = Page("""
        <form onsubmit="window.submitted=true; return false">
          <input id="username"><input type="password"><button type="submit">登录</button>
        </form>
    """).use { page ->
        page.eval(EasWebCredentialPolicy.autofillAndSubmitScript("saved-account", "saved-password"))
        assertEquals("\"saved-account\"", page.eval("document.getElementById('username').value"))
        assertEquals("\"saved-password\"", page.eval("document.querySelector('input[type=password]').value"))
        assertEquals("true", page.eval("window.submitted===true"))
    }

    private class Page(html: String) : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private lateinit var webView: WebView

        init {
            val loaded = CountDownLatch(1)
            instrumentation.runOnMainSync {
                webView = WebView(ApplicationProvider.getApplicationContext())
                webView.settings.javaScriptEnabled = true
                webView.layout(0, 0, 800, 600)
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                }
                webView.loadDataWithBaseURL("https://ids.hit.edu.cn/authserver/login", html, "text/html", "utf-8", null)
            }
            assertTrue("Fixture page must load", loaded.await(15, TimeUnit.SECONDS))
        }

        fun eval(script: String): String {
            val done = CountDownLatch(1)
            var result = "null"
            instrumentation.runOnMainSync {
                webView.evaluateJavascript(script) { result = it; done.countDown() }
            }
            assertTrue("JavaScript must finish", done.await(5, TimeUnit.SECONDS))
            return result
        }

        fun runRememberMe() = eval(EasWebCredentialPolicy.rememberMeScript())
        override fun close() = instrumentation.runOnMainSync { webView.destroy() }
    }
}
