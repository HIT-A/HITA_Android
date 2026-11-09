package cn.limpu.hita.ui.main.homework

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cn.limpu.hita.R
import cn.limpu.hita.data.repository.grader.GraderRepository
import cn.limpu.hita.data.source.web.grader.GraderSessionPolicy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Official SSO only. A browser cookie/landing page is a candidate, never proof of login. */
@AndroidEntryPoint
class GraderLoginActivity : AppCompatActivity() {
    @Inject lateinit var repository: GraderRepository
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var retry: Button
    private var verification: Job? = null
    private var lastAttemptCookie: String? = null
    private var closing = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        title = getString(R.string.grader_login_title)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            text = getString(R.string.grader_login_title)
            textSize = 20f
            setPadding(dp(16), dp(16), dp(16), dp(12))
        })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        status = TextView(this).apply {
            visibility = View.GONE
            setPadding(dp(16), dp(8), dp(16), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
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
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (!closing && verification?.isActive != true) {
                        this@GraderLoginActivity.progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                    !isSecureNavigation(request?.url)

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    !isSecureNavigation(url?.let(Uri::parse))

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!closing && url != null) verifyCandidate(url)
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (!closing && request?.isForMainFrame == true) showError()
                }

                /** 证书错误必须取消加载；作业平台会话 cookie 不能交给中间人。 */
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: android.webkit.SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    handler?.cancel()
                    if (!closing) showError()
                }
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val controls = LinearLayout(this).apply { setPadding(dp(8), dp(4), dp(8), dp(8)) }
        controls.addView(Button(this).apply {
            setText(R.string.homework_cancel)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        retry = Button(this).apply {
            setText(R.string.homework_retry)
            setOnClickListener {
                lastAttemptCookie = null
                status.visibility = View.GONE
                val url = webView.url
                val cookie = CookieManager.getInstance().getCookie(GraderSessionPolicy.ORIGIN)
                    ?.let(GraderSessionPolicy::sessionCookie)
                if (url != null && GraderSessionPolicy.isCandidatePage(url) && cookie != null &&
                    repository.state.value?.needsLogin != true) verifyCandidate(url)
                else startLogin()
            }
        }
        controls.addView(retry, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(controls)
        setContentView(root)
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, false)
        startLogin()
    }

    private fun startLogin() {
        val cookies = CookieManager.getInstance()
        // Never clear EAS/SSO or other sites' cookies. Start with a fresh Grader session only.
        cookies.setCookie(GraderSessionPolicy.ORIGIN, "sessionid=; Path=/; Max-Age=0; Secure; HttpOnly") {
            if (!closing) {
                webView.loadUrl(GraderSessionPolicy.LOGIN_URL)
            }
        }
    }

    private fun verifyCandidate(url: String) {
        if (!GraderSessionPolicy.isCandidatePage(url) || verification?.isActive == true) return
        val cookie = GraderSessionPolicy.sessionCookie(
            CookieManager.getInstance().getCookie(GraderSessionPolicy.ORIGIN) ?: ""
        )
        if (cookie == null) {
            showError()
            return
        }
        if (cookie == lastAttemptCookie) return
        lastAttemptCookie = cookie
        verification = lifecycleScope.launch {
            retry.isEnabled = false
            progress.visibility = View.VISIBLE
            status.setText(R.string.grader_loading)
            status.visibility = View.VISIBLE
            try {
                if (repository.connect(cookie)) {
                    setResult(Activity.RESULT_OK)
                    finish()
                } else showError()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (!closing) {
                    progress.visibility = View.GONE
                    retry.isEnabled = true
                }
            }
        }
    }

    private fun showError() {
        progress.visibility = View.GONE
        status.setText(R.string.grader_login_error)
        status.visibility = View.VISIBLE
    }

    private fun isSecureNavigation(uri: Uri?): Boolean = uri?.scheme == "https" && !uri.host.isNullOrBlank()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        closing = true
        verification?.cancel()
        lastAttemptCookie = null
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.webChromeClient = null
            webView.destroy()
        }
        super.onDestroy()
    }
}
