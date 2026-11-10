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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import cn.limpu.hita.R
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.source.preference.EasCredential
import cn.limpu.hita.data.source.preference.EasCredentialStore
import cn.limpu.hita.ui.eas.login.EasWebCredentialCaptureState
import cn.limpu.hita.ui.eas.login.EasWebCredentialPolicy
import cn.limpu.hita.ui.main.MainTab
import cn.limpu.hita.data.source.web.notice.CampusNoticeParser
import cn.limpu.hita.data.source.web.notice.InfoPortalLoginPolicy
import cn.limpu.hita.utils.LogUtils
import cn.limpu.hita.utils.LogRedaction
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class InfoPortalLoginActivity : AppCompatActivity() {
    @Inject lateinit var easRepository: EASRepository
    @Inject lateinit var credentialStore: EasCredentialStore

    private lateinit var webView: WebView
    private var finished = false
    private var extractScheduled = false
    private var silentMode = false
    private var mfaTimeoutArmed = false
    private var multifactorHintShown = false
    private var submittedLoginPages = mutableSetOf<String>()
    private val credentialCapture = EasWebCredentialCaptureState()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        silentMode = intent?.getBooleanExtra(EXTRA_SILENT_MODE, false) == true
        // 静默重登必须无感：透明主题避免用户看到整个登录页闪一下。
        if (silentMode) setTheme(R.style.WebViewLoginSilentTheme)
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
                if (finished) return
                prepareCasCredentials(view, url)
                if (InfoPortalLoginPolicy.isNoticeListPage(url)) {
                    // CAS 回跳后 WebView 可能刚拿到 JSESSIONID 但尚未同步到
                    // CookieManager，给一个短窗口轮询确认再判定成功。
                    pollForPortalSession(view, url, attempt = 0)
                } else if (InfoPortalLoginPolicy.isMultifactorPage(url)) {
                    // 真实账号在这一步会进多因子认证（短信/APP 验证码），无法自动完成。
                    // 静默模式必须立刻收场，否则透明窗口会永久挂住、用户既看不到页面
                    // 也点不动任何东西；交互模式则把页面交给用户手动完成。
                    if (silentMode) {
                        LogUtils.d("notice portal silent renewal requires multifactor")
                        finishCancelled()
                    } else {
                        // 用户看不到"为什么要输验证码"，用一条 Toast 说明这一步是什么、
                        // 以及验证码从哪来；页面本身保持可操作，用户手动完成即可。
                        if (!multifactorHintShown) {
                            multifactorHintShown = true
                            Toast.makeText(
                                this@InfoPortalLoginActivity,
                                R.string.campus_notice_multifactor_hint,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        armMultifactorTimeout(view)
                    }
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
        installCredentialBridge()
    }

    /**
     * 安装凭据捕获桥：用户在 CAS 表单页手输账号密码并提交时，把值暂存到内存，
     * 只有整条登录链路成功后才由 [finishSuccess] 落盘。origin 与页面双重校验，
     * 与网页教务共用同一份白名单，避免被门户内嵌的第三方 iframe 注入。
     */
    private fun installCredentialBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        runCatching {
            WebViewCompat.addWebMessageListener(
                webView,
                CREDENTIAL_BRIDGE,
                portalCredentialOrigins(),
            ) { view, message, sourceOrigin, isMainFrame, _ ->
                if (!isMainFrame) return@addWebMessageListener
                val origin = sourceOrigin.toString()
                val campus = InfoPortalLoginPolicy.credentialCampusFor(origin)
                if (!EasWebCredentialPolicy.matchesLoginOrigin(campus, origin) ||
                    !EasWebCredentialPolicy.matchesLoginPage(
                        campus, view.url.orEmpty(), isMainFrame = true
                    )
                ) {
                    return@addWebMessageListener
                }
                runCatching {
                    val payload = org.json.JSONObject(message.data ?: return@runCatching)
                    val username = payload.optString("username").trim()
                    val password = payload.optString("password")
                    if (username.isNotEmpty() && password.isNotEmpty()) {
                        credentialCapture.stage(EasCredential(campus, username, password))
                    }
                }
            }
        }.onFailure { LogUtils.e("portal credential bridge unavailable", it) }
    }

    /** 门户登录走本部 CAS，origin 白名单与本部教务一致（含校园 VPN 两条入口）。 */
    private fun portalCredentialOrigins(): Set<String> = setOf(
        "http://ids-hit-edu-cn-s.vpn.hit.edu.cn",
        "http://ids-hit-edu-cn-s.ivpn.hit.edu.cn",
        "https://ids.hit.edu.cn",
    )

    /**
     * 门户登录复用本部统一身份认证：勾选「N 天免登录」、自动填充、自动提交一次。
     *
     * 与网页教务同一套策略对象，因此三处（本部/威海/深圳教务与这里）行为一致。
     * 每张表单只提交一次，累计有上限，避免密码失效时反复提交触发风控。
     *
     * **首次登录必须停在表单页**：没有已保存凭据时只勾选免登录，绝不自动提交、
     * 也绝不自行收场。历史上的顺序是「先勾选、再无凭据就 finishCancelled()」，
     * 于是用户在静默重登里刚看到页面就被弹出，必须点第二次才能登录。
     */
    private fun prepareCasCredentials(view: WebView, url: String) {
        if (!InfoPortalLoginPolicy.isCasLoginForm(url)) return
        // 门户的 CAS 可能落在本部 ids.hit.edu.cn，也可能落在深圳 aTrust 放行后的
        // ids-hit-edu-cn-s.hitsz.edu.cn；用实际页面归属的校区取凭据与 origin 白名单，
        // 否则深圳用户永远匹配不上，静默重登会立即失败。
        val campus = InfoPortalLoginPolicy.credentialCampusFor(url)
        if (!EasWebCredentialPolicy.matchesLoginPage(
                campus, url, isMainFrame = true
            )
        ) return

        val credential = EasWebCredentialPolicy.readOptionalCredential {
            credentialStore.get(campus)
        }?.takeIf { it.username.isNotBlank() && it.password.isNotBlank() }

        when (InfoPortalLoginPolicy.casFormAction(credential != null, silentMode)) {
            InfoPortalLoginPolicy.CasFormAction.GIVE_UP -> {
                // 静默重登且没有可用凭据：无法完成，交回宿主让用户手点登录。
                LogUtils.d("notice portal silent renewal needs credentials")
                finishCancelled()
                return
            }

            InfoPortalLoginPolicy.CasFormAction.PREPARE_ONLY -> {
                // 首次登录：仅勾选免登录并安装凭据捕获桥，然后把页面留给用户输入。
                // 不填充、不提交、不结束 Activity。
                view.evaluateJavascript(EasWebCredentialPolicy.rememberMeScript(), null)
                view.evaluateJavascript(EasWebCredentialPolicy.captureScript(), null)
                return
            }

            InfoPortalLoginPolicy.CasFormAction.AUTO_SUBMIT -> Unit
        }

        val stored = credential ?: return
        view.evaluateJavascript(
            EasWebCredentialPolicy.rememberMeScript(), null
        )
        view.evaluateJavascript(
            EasWebCredentialPolicy.captureScript(), null
        )
        val pageKey = url.substringBefore('#')
        if (pageKey in submittedLoginPages || submittedLoginPages.size >= MAX_AUTO_SUBMIT) {
            view.evaluateJavascript(
                EasWebCredentialPolicy.autofillScript(
                    stored.username, stored.password
                ),
                null
            )
            return
        }
        submittedLoginPages.add(pageKey)
        view.postDelayed({
            if (finished || view.url.orEmpty().substringBefore('#') != pageKey) return@postDelayed
            view.evaluateJavascript(
                EasWebCredentialPolicy.autofillAndSubmitScript(
                    stored.username, stored.password
                )
            ) { raw ->
                LogUtils.d("notice portal auto submit: ${raw?.take(120) ?: "null"}")
                if (silentMode &&
                    !EasWebCredentialPolicy.submittedFromResult(raw)
                ) {
                    LogUtils.w("notice portal silent auto submit failed")
                    finishCancelled()
                }
            }
        }, CAS_AUTO_SUBMIT_DELAY_MS)
    }

    /**
     * 交互模式下进入多因子认证页后，给用户一个上限窗口；超时仍未完成就收场，
     * 避免 Activity 停留在一个永远等不到结果的页面上。
     */
    private fun armMultifactorTimeout(view: WebView) {
        if (mfaTimeoutArmed || finished) return
        mfaTimeoutArmed = true
        view.postDelayed({
            if (finished) return@postDelayed
            LogUtils.d("notice portal multifactor did not complete in time")
            finishCancelled()
        }, MULTIFACTOR_TIMEOUT_MS)
    }

    private fun pollForPortalSession(view: WebView, url: String, attempt: Int) {
        if (finished) return
        if (InfoPortalLoginPolicy.isLoginComplete(view.url.orEmpty(), portalCookies())) {
            scheduleExtract()
            return
        }
        if (attempt >= MAX_SESSION_POLLS) {
            LogUtils.w("notice portal session not established after ${attempt} polls: path=${
                runCatching { java.net.URI(url).path }.getOrNull()
            }")
            if (silentMode) finishCancelled()
            return
        }
        view.postDelayed(
            { pollForPortalSession(view, url, attempt + 1) },
            SESSION_POLL_DELAY_MS,
        )
    }

    /** 门户域上的 cookie 表，供策略判定使用。 */
    private fun portalCookies(): Map<String, String> {
        val header = runCatching {
            CookieManager.getInstance().getCookie(CampusNoticeParser.PORTAL_URL).orEmpty()
        }.getOrDefault("")
        if (header.isBlank()) return emptyMap()
        return header.split(';').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null
            else part.substring(0, index).trim() to part.substring(index + 1).trim()
        }.toMap()
    }

    private fun scheduleExtract() {
        if (extractScheduled || finished) return
        extractScheduled = true
        webView.postDelayed({ finishSuccess() }, 800)
    }

    override fun onDestroy() {
        // 用户手动输入的账号密码只在本 Activity 生命周期内保留，取消时不落盘。
        credentialCapture.discard()
        if (::webView.isInitialized) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                runCatching { WebViewCompat.removeWebMessageListener(webView, CREDENTIAL_BRIDGE) }
            }
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun finishCancelled() {
        if (finished) return
        finished = true
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun finishSuccess() {
        if (finished) return
        finished = true
        // 登录成功才落盘，避免把用户输错的密码存成"可用凭据"。
        credentialCapture.commitOnSuccess { credential ->
            runCatching {
                credentialStore.save(credential.campus, credential.username, credential.password)
            }
        }
        CookieManager.getInstance().flush()
        setResult(RESULT_OK, Intent())
        finish()
    }

    companion object {
        const val EXTRA_SILENT_MODE = "silent_mode"

        /** JS 桥名称，与网页教务共用同一约定。 */
        private const val CREDENTIAL_BRIDGE = "hitaCredentialBridge"

        /** 同一张 CAS 表单只自动提交一次，单次登录最多两张。 */
        private const val MAX_AUTO_SUBMIT = 2
        private const val CAS_AUTO_SUBMIT_DELAY_MS = 600L

        /** 列表页出现后等待门户 JSESSIONID 落盘的上限（500ms × 20 = 10s）。 */
        private const val MAX_SESSION_POLLS = 20
        private const val SESSION_POLL_DELAY_MS = 500L

        /** 交互模式下多因子认证的用户操作上限。 */
        private const val MULTIFACTOR_TIMEOUT_MS = 180_000L
    }
}
