package cn.limpu.hita.ui.campus

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cn.limpu.hita.R
import cn.limpu.hita.data.source.web.campus.LabCenterClient
import cn.limpu.hita.data.source.web.campus.LabCenterSessionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 实验中心登录页（WebView）。
 *
 * 入口是 `hitszlims/test?PROJECT_NAME=hitszlims`，用户在页面里走站点自带的登录表单。
 * 每加载完一页就用 [LabCenterSessionPolicy.isLoginComplete] 判一次；判定通过后
 * 再用 [LabCenterClient.probeSession] 确认 Cookie 真能取到课表页，才结束登录——
 * 只看页面内容会因为服务端返回 200 的登录壳而误判。
 */
class LabCenterLoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var status: android.widget.TextView
    private lateinit var retry: android.widget.Button
    private val client = LabCenterClient()
    private var closing = false
    private var verification: Job? = null
    private var lastCookie: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        CookieManager.getInstance().setAcceptCookie(true)
        // 实验中心是单站登录，没有第三方票据；关掉可以避免把会话泄露给嵌入内容。
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.loadUrl(LabCenterSessionPolicy.ENTRY_URL)
    }

    private fun buildLayout(): ViewGroup {
        val root = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        status = android.widget.TextView(this).apply {
            setText(R.string.lab_center_login_hint)
            setPadding(dp(16), dp(16), dp(16), dp(8))
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status)
        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            @Suppress("DEPRECATION")
            settings.saveFormData = false
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !LabCenterSessionPolicy.isLabUrl(request.url.toString())

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean =
                    url == null || !LabCenterSessionPolicy.isLabUrl(url)

                override fun onPageFinished(view: WebView, url: String) {
                    if (!closing) verify(url)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (!closing && request.isForMainFrame) {
                        status.setText(R.string.lab_center_login_error)
                    }
                }

                /** 实验中心会话 Cookie 不能交给中间人：证书错误一律取消加载。 */
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: android.webkit.SslErrorHandler?,
                    error: android.net.http.SslError?,
                ) {
                    handler?.cancel()
                    if (!closing) status.setText(R.string.lab_center_login_error)
                }
            }
        }
        root.addView(
            webView,
            android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        retry = android.widget.Button(this).apply {
            setText(R.string.homework_retry)
            visibility = android.view.View.GONE
            setOnClickListener {
                verification?.cancel()
                lastCookie = null
                status.setText(R.string.lab_center_login_hint)
                visibility = android.view.View.GONE
                webView.loadUrl(LabCenterSessionPolicy.ENTRY_URL)
            }
        }
        root.addView(retry)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finishCancelled()
            }
        })
        return root
    }

    /**
     * 判定一次登录是否完成。
     *
     * 只有页面像课表页时才值得再花一次网络往返确认：`probeSession` 会重新请求课表页，
     * 而登录过程中每一页都触发的话会明显拖慢 WebView。
     */
    private fun verify(url: String) {
        if (verification?.isActive == true) return
        if (!LabCenterSessionPolicy.isLabUrl(url) || LabCenterSessionPolicy.isLoginTransitPage(url)) {
            retry.visibility = android.view.View.GONE
            return
        }
        val cookie = CookieManager.getInstance().getCookie(LabCenterSessionPolicy.ORIGIN).orEmpty()
        val header = LabCenterSessionPolicy.cookieHeader(cookie) ?: return
        if (header == lastCookie) return
        lastCookie = header
        verification = lifecycleScope.launch {
            try {
                if (client.probeSession(header)) {
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    retry.visibility = android.view.View.VISIBLE
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (closing) retry.visibility = android.view.View.GONE
            }
        }
    }

    private fun finishCancelled() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        closing = true
        verification?.cancel()
        lastCookie = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        fun start(from: Context) {
            from.startActivity(Intent(from, LabCenterLoginActivity::class.java))
        }
    }
}
