package cn.limpu.hita.ui.notice

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import cn.limpu.hita.data.repository.CampusNoticeRepository
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.ui.main.MainTab
import cn.limpu.hita.data.source.web.notice.CampusNoticeParser
import cn.limpu.hita.utils.LogUtils
import cn.limpu.hita.utils.LogRedaction
import dagger.hilt.android.AndroidEntryPoint
import org.json.JSONTokener
import javax.inject.Inject

@AndroidEntryPoint
class InfoPortalLoginActivity : AppCompatActivity() {
    @Inject lateinit var noticeRepository: CampusNoticeRepository
    @Inject lateinit var easRepository: EASRepository
    private lateinit var webView: WebView
    private var finished = false
    private var extractScheduled = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!MainTab.showsBlog(easRepository.getEasToken())) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        webView = WebView(this)
        setContentView(webView)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.setBackgroundColor(Color.WHITE)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // 通知门户与 CAS 只加载 http(s) 资源，不需要本地文件/内容访问能力。
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        webView.settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        webView.settings.allowUniversalAccessFromFileURLs = false
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }

            /**
             * 门户登录同样会提交统一身份认证凭据，证书错误必须取消而非忽略。
             */
            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                handler?.cancel()
                LogUtils.e(
                    "notice portal ssl error canceled: primary=${error?.primaryError} " +
                        "url=${LogRedaction.url(error?.url)}"
                )
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (isPortalHome(url)) {
                    scheduleExtract()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }
        })
        webView.loadUrl(CampusNoticeParser.LIST_URL)
    }

    private fun isPortalHome(url: String): Boolean {
        if (CampusNoticeParser.isCasLoginUrl(url)) return false
        return url.contains("info.hitsz.edu.cn", ignoreCase = true)
    }

    private fun scheduleExtract() {
        if (extractScheduled || finished) return
        extractScheduled = true
        webView.postDelayed({ finishSuccess() }, 800)
    }

    private fun extractNotices() {
        finishSuccess()
    }

    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching {
            JSONTokener(raw).nextValue()?.toString().orEmpty()
        }.getOrDefault(raw.trim().removeSurrounding("\""))
    }

    private fun finishSuccess() {
        if (finished) return
        finished = true
        CookieManager.getInstance().flush()
        setResult(RESULT_OK, Intent())
        finish()
    }

    companion object {
        private const val EXTRACT_JS = """
            (function(){
              function textOf(el){
                return ((el.getAttribute('title')||'') || (el.textContent||'')).replace(/\s+/g,' ').trim();
              }
              var items = [];
              var seen = {};
              var anchors = document.querySelectorAll('a');
              for (var i = 0; i < anchors.length; i++) {
                var a = anchors[i];
                var href = a.href || '';
                var title = textOf(a);
                if (title.length < 6 || title.length > 120) continue;
                if (/登录|首页|导航|邮箱|下载|English/.test(title)) continue;
                if (!/info\.hitsz|\/info\/|\.htm/.test(href)) continue;
                if (seen[href]) continue;
                seen[href] = true;
                var near = a.parentElement ? (a.parentElement.innerText||'').slice(0,80) : '';
                items.push({title:title,url:href,near:near});
                if (items.length >= 40) break;
              }
              return JSON.stringify(items);
            })()
        """
    }
}
