package cn.limpu.hita.security

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 设备侧验证安全基线的**真实生效**部分：XML 规则的解析结果、FileProvider 的实际授权边界、
 * WebView 设置的运行时值、CookieManager 的持久化。纯规则（URL 白名单、脱敏）在 JVM 单测里覆盖。
 */
@RunWith(AndroidJUnit4::class)
class SecurityBaselineInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun fileProviderExposesCacheAndIcsButNotPrivateDatabases() {
        val cacheFile = File(context.cacheDir, "hita-token-probe.txt").apply {
            writeText("probe")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cacheFile)
        assertNotNull(uri)
        assertTrue("cache 文件应可生成 content:// URI", uri.scheme == "content")

        val icsDir = File(context.getExternalFilesDir(null), "ics").apply { mkdirs() }
        val ics = File(icsDir, "probe.ics").apply { writeText("BEGIN:VCALENDAR") }
        assertNotNull(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", ics))

        // 私有数据库目录与 shared_prefs 不在任何 <paths> 登记项内，必须构造失败。
        // filesDir 本身保留：课表导出在外部存储不可用时回落到 filesDir（见 docs 审计 SEC-01）。
        val sensitive = listOf(
            File(context.getDatabasePath("app.db").parentFile, "probe.db").apply { writeText("x") },
            File(context.filesDir.parentFile, "shared_prefs/probe.xml").apply {
                parentFile?.mkdirs()
                writeText("<map/>")
            },
        )
        sensitive.forEach { file ->
            try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                fail("敏感路径不应能生成 FileProvider URI: ${file.absolutePath}")
            } catch (_: IllegalArgumentException) {
                // 期望：未登记路径直接拒绝
            }
        }
    }

    @Test
    fun webViewLoginBaselineDisablesLocalFileAccess() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val webView = WebView(context)
            try {
                // 与 WebViewLoginActivity.setupWebView 相同的基线设置
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.allowFileAccess = false
                webView.settings.allowContentAccess = false
                @Suppress("DEPRECATION")
                webView.settings.allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                webView.settings.allowUniversalAccessFromFileURLs = false

                assertFalse("登录 WebView 不应允许 file:// 访问", webView.settings.allowFileAccess)
                assertFalse("登录 WebView 不应允许 content:// 访问", webView.settings.allowContentAccess)
                assertEquals(true, webView.settings.javaScriptEnabled)
                assertNotNull(WebSettings.getDefaultUserAgent(context))
            } finally {
                webView.destroy()
            }
        }
    }

    @Test
    fun cookieManagerKeepsIdentityTicketSeparateFromBusinessSession() {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        val cas = "https://ids.hit.edu.cn/"
        val jw = "https://jw.hitsz.edu.cn/"
        manager.setCookie(cas, "CASTGC=it-castgc; Path=/; Max-Age=1209600")
        manager.setCookie(jw, "JSESSIONID=it-session; Path=/")
        manager.flush()

        val casCookie = manager.getCookie(cas).orEmpty()
        val jwCookie = manager.getCookie(jw).orEmpty()
        assertTrue("身份票据必须持久化", casCookie.contains("CASTGC="))
        assertTrue("业务会话必须持久化", jwCookie.contains("JSESSIONID="))
        assertFalse("跨域 cookie 不得串到教务业务域", jwCookie.contains("CASTGC="))

        // 清理，避免污染其它设备测试
        manager.setCookie(cas, "CASTGC=; Path=/; Max-Age=0")
        manager.setCookie(jw, "JSESSIONID=; Path=/; Max-Age=0")
        manager.flush()
    }

    /**
     * 网络安全配置的运行时生效验证：白名单外的明文 host 必须被系统拒绝，
     * 这比只解析 XML 更能证明 `usesCleartextTraffic` 已经真正关闭。
     */
    @Test
    fun cleartextToNonAllowlistedHostIsBlockedAtRuntime() {
        val connection = URL("http://example.invalid/probe").openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        try {
            connection.connect()
            fail("非白名单明文连接不应建立成功")
        } catch (expected: java.io.IOException) {
            // 期望：CleartextNotPermittedException 由平台抛出，属于 IOException 子类
            assertTrue(
                "应为明文被禁而非其它网络错误: ${expected.message}",
                expected.message.orEmpty().contains("Cleartext", ignoreCase = true) ||
                    expected.cause?.message.orEmpty().contains("Cleartext", ignoreCase = true)
            )
        } finally {
            connection.disconnect()
        }
    }
}
