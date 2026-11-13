package cn.limpu.hita.ui.eas.login

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.net.http.SslError
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.source.web.eas.HttpCampusReachabilityProbe
import cn.limpu.hita.data.source.web.eas.WeihaiEasEntryPolicy
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import cn.limpu.hita.data.source.preference.EasCredential
import cn.limpu.hita.data.source.preference.EasCredentialStore
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.limpu.hita.BuildConfig
import cn.limpu.hita.R
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.utils.LogUtils
import cn.limpu.hita.utils.LogRedaction
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import org.json.JSONObject
import org.json.JSONArray
import java.net.URL

/** Plan D: native MFA overlay state shared between activity and composable. */
internal data class MfaOverlayState(
    val visible: Boolean = false,
    val promptTitle: String = "",
    val promptText: String = "",
    val verifyMethod: String = "",
    val verifyMethodType: String = "",
    val hasVisibleInput: Boolean = false,
    val inputId: String = "",
    val inputType: String = "text",
    val inputPlaceholder: String = "",
    val submitButtonId: String = "",
    val canSendCode: Boolean = false,
    val switchMethodJs: String = ""
)

@AndroidEntryPoint
class WebViewLoginActivity : AppCompatActivity() {

    @Inject lateinit var easCredentialStore: EasCredentialStore

    protected val viewModel: WebViewLoginViewModel by viewModels()

    companion object {
        const val EXTRA_SILENT_MODE = "silent_mode"
        const val EXTRA_CAMPUS = "campus"
        const val EXTRA_STUDENT_TYPE = "student_type"

        // 校园网络URL常量
        private object CampusUrls {
            // 2026-10 起本部零信任网关应用域名由 ivpn.hit.edu.cn 改为 vpn.hit.edu.cn（别名、端口不变）
            private const val BENBU_VPN_DOMAIN = "vpn.hit.edu.cn"

            // 本部校区URL
            private const val BENBU_BASE = "http://i-hit-edu-cn.$BENBU_VPN_DOMAIN:1080"
            private const val JWTS_BASE = "http://jwts-hit-edu-cn.$BENBU_VPN_DOMAIN:1080"

            // 威海校区URL
            private const val WEIHAI_BASE = "https://webvpn.hitwh.edu.cn"
            const val WEIHAI_EAS_PREFIX = "$WEIHAI_BASE/http/77726476706e69737468656265737421fae0558f693861446900c7a99c406d3667"

            val BENBU_LOGIN = "$BENBU_BASE/portal/home/"
            val BENBU_JWTS = "$JWTS_BASE/loginCAS"
            val BENBU_PROBE_URLS = listOf(
                "$JWTS_BASE/loginCAS",
                "$BENBU_BASE/",
                "$BENBU_BASE/portal/home/"
            )

            val WEIHAI_LOGIN = "$WEIHAI_BASE/"
            val WEIHAI_JWTS = "$WEIHAI_EAS_PREFIX/loginCAS"
            val WEIHAI_PROBE_URLS = listOf(
                WEIHAI_JWTS,
                "$WEIHAI_EAS_PREFIX/kjscx/queryJxlListBySjid",
                "$WEIHAI_EAS_PREFIX/cjcx/queryQmcj",
                "$WEIHAI_BASE/"
            )

            const val SHENZHEN_PROXY_BASE = "https://jw-hitsz-edu-cn.hitsz.edu.cn"
            const val SHENZHEN_DIRECT_BASE = "https://jw.hitsz.edu.cn"
            const val SHENZHEN_LOGIN = "$SHENZHEN_PROXY_BASE/"
            const val SHENZHEN_JWTS = "$SHENZHEN_PROXY_BASE/authentication/main"
            val SHENZHEN_PROBE_URLS = WebLoginSuccessPolicy.shenzhenCookieProbeUrls(
                proxyBaseUrl = SHENZHEN_PROXY_BASE,
                directBaseUrl = SHENZHEN_DIRECT_BASE
            )

            const val EELABINFO_URL = "http://eelabinfo-hit-edu-cn.$BENBU_VPN_DOMAIN:1080"
        }

        private const val COOKIE_RETRY_COUNT = 30
        private const val COOKIE_RETRY_DELAY_MS = 500L
        private const val MAX_STUCK_NUDGE = 2
        private const val SILENT_TIMEOUT_MS = 18000L
        private const val MFA_DETECTION_INITIAL_DELAY_MS = 800L
        private const val MFA_DETECTION_RETRY_DELAY_MS = 700L
        private const val MFA_DETECTION_MAX_RETRIES = 4
        private const val MFA_NATIVE_OVERLAY_ENABLED = false
        private const val MFA_METHOD_SWITCH_POLL_DELAY_MS = 400L
        private const val MFA_METHOD_SWITCH_MAX_POLLS = 75
        private const val SHENZHEN_AUTO_ADVANCE_RETRY_DELAY_MS = 300L
        private const val SHENZHEN_AUTO_ADVANCE_MAX_RETRIES = 12

        /** CAS 登录页渲染节奏不一，分多轮尝试勾选「N 天免登录」，确保身份票据是持久 cookie。 */
        private val CAS_REMEMBER_ME_DELAYS_MS = longArrayOf(0L, 400L, 1200L, 2500L)

        /** 自动填充后等页面把按钮/表单渲染完成再提交，避免提交未就绪的 DOM。 */
        private const val CAS_AUTO_SUBMIT_DELAY_MS = 600L

        /** aTrust 门户登录页是 SPA，等它渲染出「登录」按钮再自动点击。 */
        private const val TRUST_PORTAL_AUTO_LOGIN_DELAY_MS = 900L

        /** aTrust 门户自动推进的有界重试次数：勾选协议后按钮解禁有一个 tick 的延迟。 */
        private const val TRUST_PORTAL_AUTO_LOGIN_ATTEMPTS = 4

        /** 单次登录内自动提交 CAS 表单的上限，避免密码失效时反复提交换来风控。 */
        private const val MAX_AUTO_SUBMIT = 2
        /**
         * 教务浏览器路由只认桌面 UA；移动 UA 会被服务端按「浏览器已过时」拦截。
         *
         * 版本号必须与数据层 [cn.limpu.hita.data.source.web.eas.EASWebSource] /
         * [cn.limpu.hita.data.source.web.eas.graduate.GraduateHttpTransport] 一致：
         * 那些请求已经用 146 正常拿到研究生教务内容，是服务端实测接受的值。
         * 两边不一致时，WebView 可能被拦而数据层正常，表现为「登录一直失败但刷新有数据」。
         */
        private const val EAS_DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/146.0.0.0 Safari/537.36"

        /** 「浏览器已过时」拦截页兜底：第 1 次退回登录入口，第 2 次明确失败收尾。 */
        private const val MAX_OUTDATED_BROWSER_FALLBACK = 1
        private val BENBU_REQUIRED_COOKIES = setOf("JSESSIONID", "HIT")
        private val BENBU_PORTAL_HOSTS = setOf(
            "i-hit-edu-cn.vpn.hit.edu.cn",
            "i-hit-edu-cn.ivpn.hit.edu.cn"
        )
        private val SHENZHEN_DIRECT_SESSION_COOKIES = setOf("JSESSIONID", "route")
        private const val SHENZHEN_PROXY_SESSION_COOKIE = "SESSION"
        private const val WEIHAI_TICKET_COOKIE_PREFIX = "wengine_vpn_ticket"
        private val WEIHAI_EAS_SESSION_COOKIE_HINTS = listOf("JSESSIONID", "HIT", "TWFID")
    }

    private data class CampusWebConfig(
        val campus: EASToken.Campus,
        val loginUrl: String,
        val jwtsUrl: String,
        val cookieProbeUrls: List<String>
    )

    private var finished = false
    private var cookieRetryCount = 0
    private var cookiePollingGeneration = 0
    // 登录卡住兜底：统一认证页出现过，且之后停在非成功页时，允许重新进入 EAS 入口
    private var weihaiSawAuthPage = false
    private var weihaiStuckNudgeCount = 0
    private var shenzhenSawAuthPage = false
    private var shenzhenStuckNudgeCount = 0
    private var autoOpeningJwts = false
    private var vpnResumeAttempted = false
    private var leftForExternalVpn = false
    private var silentMode = false
    private lateinit var config: CampusWebConfig
    /**
     * 威海本次登录选中的入口。默认 VPN（与历史行为一致），探测成功后可能切成直连。
     * 成功判定必须读这个值，否则直连会被旧规则（要求 VPN 票据）判成未登录。
     */
    private var weihaiEntry: WeihaiEasEntryPolicy.Entry = WeihaiEasEntryPolicy.Entry.VPN
    private var navigatingToEelab = false
    private var collectedEasCookies: Map<String, String>? = null
    private var eelabTokenFetching = false
    private var lastPageHadError = false
    private lateinit var webView: WebView
    private var progressVisible by mutableStateOf(false)
    private var progressValue by mutableIntStateOf(0)

    private var showBenbuMfaHint by mutableStateOf(false)
    private var mfaState by mutableStateOf(MfaOverlayState())
    private var mfaError by mutableStateOf<String?>(null)
    private var mfaInputValue by mutableStateOf("")
    private var mfaDetectionGeneration = 0
    private var easMobileUserAgent = ""
    private var easDesktopUserAgentApplied = false
    private var outdatedBrowserPageHits = 0
    private var shenzhenForceWebModeApplied = false
    private var shenzhenPreferredStudentType = ShenzhenWebAutoLogin.UNDERGRAD
    private val isWeihaiGraduate: Boolean
        get() = config.campus == EASToken.Campus.WEIHAI &&
            shenzhenPreferredStudentType == ShenzhenWebAutoLogin.POSTGRAD
    private var shenzhenAutoAdvanceGeneration = 0
    private var shenzhenAutoAdvanceScheduledGeneration = -1
    private var shenzhenUnifiedLoginClicked = false
    private var shenzhenRoleSelectionClicked = false
    private var shenzhenReauthenticationStarted = false
    private var credentialCaptureState = EasWebCredentialCaptureState()
    private val credentialBridgeName = "hitaCredentialBridge"
    /** 本次登录里已自动提交过的 CAS 页面，避免验证失败后无限重试触发风控。 */
    private val autoSubmittedLoginPages = mutableSetOf<String>()
    /** aTrust 门户登录页是否已自动推进（每张页面最多一次）。 */
    private var trustPortalAutoLoginPage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val campus = runCatching {
            EASToken.Campus.valueOf(
                intent?.getStringExtra(EXTRA_CAMPUS) ?: EASToken.Campus.BENBU.name
            )
        }.getOrDefault(EASToken.Campus.BENBU)
        silentMode = intent?.getBooleanExtra(EXTRA_SILENT_MODE, false) == true
        shenzhenPreferredStudentType = ShenzhenWebAutoLogin.normalizeStudentType(
            intent?.getStringExtra(EXTRA_STUDENT_TYPE)
        )
        // 初始按默认入口（VPN）建立配置；威海会在 initViews 里探测后按结果重建。
        config = configFor(campus, WeihaiEasEntryPolicy.Entry.VPN)

        setTheme(
            if (silentMode) {
                R.style.WebViewLoginSilentTheme
            } else {
                R.style.Theme_HITA_WebViewLogin
            }
        )
        super.onCreate(savedInstanceState)

        if (silentMode) {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setDimAmount(0f)
        } else {
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
        }

        setContent {
            HitaComposeTheme() {
                WebViewLoginScreen(
                    silentMode = silentMode,
                    progressVisible = progressVisible,
                    progressValue = progressValue,
                    showBenbuMfaHint = showBenbuMfaHint,
                    mfaState = mfaState,
                    mfaError = mfaError,
                    mfaInputValue = mfaInputValue,
                    onMfaInputChange = { mfaInputValue = it },
                    onMfaSubmit = { submitNativeMfaInput(mfaInputValue) },
                    onMfaSendCode = { triggerNativeMfaSendCode() },
                    onMfaSwitchMethod = { switchNativeMfaMethod() },
                    onMfaDismiss = { dismissNativeMfa() },
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onWebViewReady = { createdWebView ->
                        webView = createdWebView
                        initViews()
                    },
                )
            }
        }
        LogUtils.d( "onCreate silentMode=$silentMode campus=${config.campus}")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initViews() {
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        setupWebView()
        installCredentialBridge()
        if (silentMode) {
            webView.postDelayed({
                if (!finished) {
                    LogUtils.w( "silent web login timeout campus=${config.campus}")
                    finishWithCancelledResult()
                }
            }, SILENT_TIMEOUT_MS)
        }
        if (config.campus == EASToken.Campus.WEIHAI) {
            // 校园网内直连教务比走 WebVPN 少一层认证；先探测再决定加载哪个入口。
            // 探测失败一律回落 VPN（与历史行为一致），不会把用户挡在门外。
            lifecycleScope.launch {
                val entry = resolveWeihaiEntry()
                if (finished) return@launch
                applyWeihaiEntry(entry)
                LogUtils.d(
                    "weihai entry resolved=${entry} url=${config.loginUrl}"
                )
                webView.loadUrl(config.loginUrl)
            }
        } else {
            LogUtils.d( "load login url=${config.loginUrl} campus=${config.campus}")
            webView.loadUrl(config.loginUrl)
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    /**
     * 探测并选定威海教务入口。
     *
     * 只探测直连：VPN 可达性不影响决策（VPN 是兜底），少一次网络往返也少一次等待。
     */
    private suspend fun resolveWeihaiEntry(): WeihaiEasEntryPolicy.Entry {
        val graduate = shenzhenPreferredStudentType == ShenzhenWebAutoLogin.POSTGRAD
        val directUrl = WeihaiEasEntryPolicy.probeUrl(WeihaiEasEntryPolicy.Entry.DIRECT, graduate)
        val directReachable = runCatching {
            HttpCampusReachabilityProbe().isReachable(directUrl)
        }.getOrDefault(false)
        return WeihaiEasEntryPolicy.choose(
            directReachable = directReachable,
            vpnReachable = true,
        )
    }

    /** 把选定的入口应用到 config，并记住它以便成功判定使用同一套规则。 */
    private fun applyWeihaiEntry(entry: WeihaiEasEntryPolicy.Entry) {
        weihaiEntry = entry
        config = configFor(config.campus, entry)
    }

    private fun configFor(
        campus: EASToken.Campus,
        weihaiEntry: WeihaiEasEntryPolicy.Entry,
    ): CampusWebConfig {
        return when (campus) {
            EASToken.Campus.BENBU -> CampusWebConfig(
                campus = campus,
                loginUrl = CampusUrls.BENBU_LOGIN,
                jwtsUrl = CampusUrls.BENBU_JWTS,
                cookieProbeUrls = CampusUrls.BENBU_PROBE_URLS
            )
            EASToken.Campus.WEIHAI -> {
                // 校园网内直连教务，校外回落 WebVPN：两者的登录页、教务入口与
                // 成功判定都不同，必须成套切换，不能只换其中一个。
                val graduate = shenzhenPreferredStudentType == ShenzhenWebAutoLogin.POSTGRAD
                val loginUrl = WeihaiEasEntryPolicy.loginUrl(weihaiEntry, graduate)
                CampusWebConfig(
                    campus = campus,
                    loginUrl = loginUrl,
                    jwtsUrl = loginUrl,
                    cookieProbeUrls = if (weihaiEntry == WeihaiEasEntryPolicy.Entry.DIRECT) {
                        listOf(loginUrl)
                    } else if (graduate) {
                        listOf(
                            cn.limpu.hita.data.source.web.eas.WeihaiGraduateSessionPolicy.BASE_URL,
                            cn.limpu.hita.data.source.web.eas.WeihaiGraduateSessionPolicy.BASE_URL +
                                "/authentication/main",
                            "https://webvpn.hitwh.edu.cn/",
                        )
                    } else {
                        CampusUrls.WEIHAI_PROBE_URLS
                    }
                )
            }
            EASToken.Campus.SHENZHEN -> CampusWebConfig(
                campus = campus,
                // Enter the authenticated application directly. If Trust/CAS is still
                // remembered this completes without showing the proxy landing page; if
                // authentication is required the proxy redirects to the normal flow.
                loginUrl = CampusUrls.SHENZHEN_JWTS,
                jwtsUrl = CampusUrls.SHENZHEN_JWTS,
                cookieProbeUrls = CampusUrls.SHENZHEN_PROBE_URLS
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun setupWebView() {
        webView.apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // 认证页只加载 http(s) 资源，不需要任何本地文件/内容访问能力。
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = false
            // 登录页不会提交表单到本地保存；关掉可减少凭据在 WebView 数据库中的残留。
            @Suppress("DEPRECATION")
            settings.saveFormData = false
            @Suppress("DEPRECATION")
            settings.savePassword = false
            if (needsEasDesktopUserAgent(config.loginUrl)) {
                // 教务浏览器路由只认桌面 UA：深圳 aTrust 门户、威海研究生 yjsgl 教务页
                // 都会按移动 UA 下发「浏览器已过时」拦截页。统一身份认证（CAS）登录页
                // 由 switchEasUserAgentForNavigation 切回移动 UA，登录表单渲染与自动
                // 填充行为不随本次改动变化。
                easMobileUserAgent = settings.userAgentString
                settings.userAgentString = EAS_DESKTOP_USER_AGENT
                easDesktopUserAgentApplied = true
                if (config.campus == EASToken.Campus.SHENZHEN) {
                    // The aTrust mobile route requires its native UEM client. Use its browser
                    // route here; software rendering is an aTrust-route requirement only.
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    LogUtils.d("using desktop user agent and software layer for Shenzhen aTrust portal")
                } else {
                    LogUtils.d("using desktop user agent for Weihai WebVPN EAS pages")
                }
            }
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                settings.forceDark = WebSettings.FORCE_DARK_OFF
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                settings.isAlgorithmicDarkeningAllowed = false
            }
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress == 100) {
                        progressVisible = false
                        logWebViewRenderMarker("progress-100", view)
                    } else {
                        progressVisible = true
                        progressValue = newProgress
                    }
                }

                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    val level = consoleMessage?.messageLevel()
                    if (isExpectedShenzhenBrowserConsoleMessage(consoleMessage?.message())) {
                        return true
                    }
                    if (level == android.webkit.ConsoleMessage.MessageLevel.ERROR ||
                        level == android.webkit.ConsoleMessage.MessageLevel.WARNING
                    ) {
                        LogUtils.w(
                            "web console level=$level line=${consoleMessage?.lineNumber()} " +
                                "source=${safeUrl(consoleMessage?.sourceId())} " +
                                "message=${consoleMessage?.message()?.take(500).orEmpty()}"
                        )
                    }
                    return true
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    val sourceView = view ?: return false
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    LogUtils.d("onCreateWindow: forwarding popup navigation into main WebView isDialog=$isDialog")
                    var forwarded = false
                    val popupWebView = WebView(sourceView.context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                popup: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                val target = request.url?.toString()
                                // 弹窗只用于把认证跳转交回主 WebView：目标必须在校区
                                // 白名单内，否则丢弃，避免任意页面劫持主 WebView。
                                if (!forwarded && WebViewSecurityPolicy.isAllowedPopupForward(config.campus, target)) {
                                    forwarded = true
                                    this@WebViewLoginActivity.webView.loadUrl(checkNotNull(target))
                                    popup.post { popup.destroy() }
                                } else if (!forwarded) {
                                    LogUtils.w("popup navigation blocked: ${safeUrl(target)}")
                                }
                                return true
                            }
                        }
                    }
                    transport.webView = popupWebView
                    resultMsg?.sendToTarget()
                    return true
                }

                override fun onCloseWindow(window: WebView?) {
                    window?.destroy()
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    if (!request.isForMainFrame) return false
                    val target = request.url?.toString()
                    if (WebViewSecurityPolicy.isAllowedNavigation(config.campus, target)) return false
                    LogUtils.w("main frame navigation blocked: ${safeUrl(target)} campus=${config.campus}")
                    return true
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    if (WebViewSecurityPolicy.isAllowedNavigation(config.campus, url)) return false
                    LogUtils.w("main frame navigation blocked: ${safeUrl(url)} campus=${config.campus}")
                    return true
                }

                /**
                 * 证书错误一律取消加载：登录凭据在 POST body 里，忽略证书错误等于把学号密码
                 * 交给中间人。这里不提供「继续访问」入口，只把失败原因暴露给日志与静默续期判断。
                 */
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    handler?.cancel()
                    progressVisible = false
                    LogUtils.e(
                        "webview ssl error canceled: primary=${error?.primaryError} " +
                            "url=${safeUrl(error?.url)} campus=${config.campus}"
                    )
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    shenzhenAutoAdvanceGeneration++
                    if (config.campus == EASToken.Campus.SHENZHEN) {
                        LogUtils.d(
                            "SHENZHEN_AUTO navigation-start generation=$shenzhenAutoAdvanceGeneration " +
                                "url=${safeUrl(url)}"
                        )
                    }
                    if (view != null && url != null && switchEasUserAgentForNavigation(view, url)) {
                        return
                    }
                    mfaDetectionGeneration++
                    mfaState = MfaOverlayState()
                    mfaError = null
                    mfaInputValue = ""
                    progressVisible = true
                    progressValue = 0
                    showBenbuMfaHint = config.campus == EASToken.Campus.BENBU &&
                        url != null && WebLoginSuccessPolicy.isBenbuMfaPage(url)
                    if (url != null && (isAuthenticationPage(url) || showBenbuMfaHint)) {
                        stopCookiePolling()
                    }
                    logWebViewRenderMarker("page-started", view, url)
                }

                override fun onPageCommitVisible(view: WebView, url: String) {
                    super.onPageCommitVisible(view, url)
                    logWebViewRenderMarker("page-commit-visible", view, url)
                    if (isMfaPage(url)) {
                        applyMfaViewportUnitWorkaround(view)
                    } else if (isTrustPortalPage(url)) {
                        applyTrustPortalViewportUnitWorkaround(view)
                    } else if (isShenzhenProxyJwPage(url)) {
                        applyShenzhenJwDesktopViewportWorkaround(view)
                        scheduleShenzhenAutoAdvance(view, url)
                    }
                    scheduleMfaDetection(view, url)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    progressVisible = false

                    if (finished) {
                        return
                    }

                    prepareLoginPageCredentials(view, url)

                    // Handle eelabinfo navigation for JWT token
                    if (navigatingToEelab) {
                        if (url.contains("eelabinfo") && !url.contains("ids.hit.edu.cn") && !eelabTokenFetching) {
                            eelabTokenFetching = true
                            LogUtils.d("eelabinfo page loaded, fetching JWT token...")
                            webView.postDelayed({ fetchEelabTokenViaHttp() }, 1000)
                        } else if (!url.contains("eelabinfo") && !eelabTokenFetching) {
                            LogUtils.w("navigated away from eelabinfo, finishing without token, url=${safeUrl(url)}")
                            navigatingToEelab = false
                            finishWithCookies(collectedEasCookies ?: collectCookies())
                        }
                        return
                    }

                    val uri = Uri.parse(url)
                    if (config.campus == EASToken.Campus.SHENZHEN) {
                        LogUtils.d(
                            "SHENZHEN_AUTO navigation-finished generation=$shenzhenAutoAdvanceGeneration " +
                                "url=${safeUrl(url)}"
                        )
                    }
                    LogUtils.d("onPageFinished: host=${uri.host} path=${uri.path} autoOpeningJwts=$autoOpeningJwts")
                    logWebViewRenderMarker("page-finished", view, url)
                    if (applyShenzhenForceWebMode(view, url)) {
                        return
                    }
                    if (redirectExpiredShenzhenSession(view, url)) {
                        return
                    }
                    schedulePageDiagnostics(view, url)
                    if (ensureEasDesktopUserAgentForProxy(view, url)) {
                        return
                    }
                    detectOutdatedBrowserPage(view, url)
                    if (isMfaPage(url)) {
                        applyMfaViewportUnitWorkaround(view)
                        scheduleMfaDetection(view, url)
                    } else if (isTrustPortalPage(url)) {
                        if (config.campus == EASToken.Campus.SHENZHEN) {
                            // 深圳走 aTrust 认证：到过信任门户即视为进入认证环节
                            shenzhenSawAuthPage = true
                        }
                        applyTrustPortalViewportUnitWorkaround(view)
                    } else if (isShenzhenProxyJwPage(url)) {
                        applyShenzhenJwDesktopViewportWorkaround(view)
                        scheduleShenzhenAutoAdvance(view, url)
                    }

                    when {
                        config.campus == EASToken.Campus.SHENZHEN && isTrustPortalPage(url) -> {
                            // 深圳教务入口无会话时会被 302 到 aTrust 门户：shortcut.html → #/login。
                            // 这些页面说明"还没连上"，继续回跳教务入口会形成无限重定向
                            // （页面永远停在 shortcut.html 的 Loading），所以只停在认证页等用户操作。
                            autoOpeningJwts = false
                            stopCookiePolling()
                            if (WebLoginSuccessPolicy.isShenzhenTrustPortalAuthPage(url)) {
                                if (silentMode && !hasStoredCredential()) {
                                    LogUtils.d("aTrust auth page in silent mode, need user interaction")
                                    finishWithCancelledResult()
                                } else {
                                    LogUtils.d(
                                        "shenzhen aTrust auth page reached, waiting for user: " +
                                            safeUrl(url)
                                    )
                                    autoAdvanceTrustPortalLogin(view, url)
                                }
                            } else if (resumeAcademicLoginAfterVpn(view, url)) {
                                LogUtils.success("shenzhen aTrust connected, opening academic login")
                            } else {
                                LogUtils.d("shenzhen aTrust portal reached, waiting for user interaction")
                            }
                        }
                        isPortalHomePage(url) -> {
                            LogUtils.d("portal home detected, auto open jwts campus=${config.campus}")
                            autoOpenJwts(view)
                        }
                        config.campus == EASToken.Campus.BENBU && WebLoginSuccessPolicy.isBenbuMfaPage(url) -> {
                            autoOpeningJwts = false
                            stopCookiePolling()
                            if (silentMode) finishWithCancelledResult()
                        }
                        isIvpnRedirectPage(url) -> {
                            autoOpeningJwts = false
                            stopCookiePolling()
                            if (resumeAcademicLoginAfterVpn(view, url)) {
                                LogUtils.d("benbu vpn connected, opening academic login")
                            } else if (silentMode) {
                                LogUtils.d("ivpn redirect page in silent mode, need user interaction")
                                finishWithCancelledResult()
                            } else {
                                LogUtils.d("ivpn redirect page, waiting for CAS login: path=${uri.path}")
                            }
                        }
                        isAuthenticationPage(url) -> {
                            autoOpeningJwts = false
                            stopCookiePolling()
                            if (config.campus == EASToken.Campus.WEIHAI) {
                                weihaiSawAuthPage = true
                            } else if (config.campus == EASToken.Campus.SHENZHEN) {
                                shenzhenSawAuthPage = true
                            }
                            if (silentMode && !canAutoSubmitCredentials(url)) {
                                LogUtils.d("authentication page in silent mode, need user interaction")
                                finishWithCancelledResult()
                            } else {
                                // 静默续期在 CAS 页不中止：prepareLoginPageCredentials 已调度
                                // 自动填充+提交，提前 finish 会让它永远来不及执行。
                                LogUtils.d(
                                    "authentication page detected, waiting for auto submit " +
                                        "silent=$silentMode"
                                )
                            }
                        }
                        isSuccessPage(url) -> {
                            autoOpeningJwts = false
                            stopCookiePolling()
                            LogUtils.success("login success page detected campus=${config.campus}")
                            handleSuccessPage()
                        }
                        autoOpeningJwts && uri.host?.contains("jwts") == true -> {
                            autoOpeningJwts = false
                            LogUtils.d("jwts domain (auto-open), starting cookie polling: path=${uri.path}")
                            startCookiePolling()
                        }
                        isJwtsPage(url) -> {
                            LogUtils.d("jwts login page detected, polling for session cookies")
                            startCookiePolling()
                        }
                        config.campus == EASToken.Campus.WEIHAI &&
                            weihaiEntry == WeihaiEasEntryPolicy.Entry.DIRECT &&
                            !isAuthenticationPage(url) &&
                            !isSuccessPage(url) -> {
                            // 直连同样需要兜底：教务系统的成功落点不止一个，
                            // 若只靠 URL 判定，用户会在已登录的页面上卡住。
                            LogUtils.d(
                                "weihai direct page, starting cookie polling: path=${uri.path} " +
                                    "host=${uri.host}"
                            )
                            startCookiePolling()
                        }
                        config.campus == EASToken.Campus.WEIHAI &&
                            weihaiSawAuthPage &&
                            uri.host == "webvpn.hitwh.edu.cn" &&
                            !isAuthenticationPage(url) -> {
                            // 威海兜底：CAS 验完票回跳后落在不在成功判定白名单内的页面时，
                            // isSuccessPage 永远不会触发，用户卡死。这里改用 cookie 轮询：
                            // 只要 VPN ticket + JSESSIONID 就绪，checkCookiesAndFinish 会
                            // 主动拉取 EAS cookies 并 finishWithCookies，不依赖落点 URL。
                            LogUtils.d(
                                "weihai post-auth page, starting cookie polling: path=${uri.path} " +
                                    "cookieNames=${LogRedaction.cookieNames(collectCookies().keys)}"
                            )
                            startCookiePolling()
                        }
                        else -> {
                            if (resumeAcademicLoginAfterVpn(view, url)) {
                                LogUtils.d("vpn page returned, continuing academic login campus=${config.campus}")
                            } else {
                                LogUtils.d("unhandled page: host=${uri.host} path=${uri.path}")
                                nudgeStuckCampusLogin(url)
                            }
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        progressVisible = false
                    }
                    if (isExpectedShenzhenClientProbe(request)) {
                        return
                    }
                    LogUtils.w( "onReceivedError url=${safeUrl(request?.url?.toString())} code=${error?.errorCode} desc=${error?.description} campus=${config.campus}")
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request?.isForMainFrame == true) {
                        LogUtils.w(
                            "main frame HTTP error status=${errorResponse?.statusCode} reason=${errorResponse?.reasonPhrase} url=${safeUrl(request.url?.toString())}"
                        )
                    }
                }

                override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                    super.onScaleChanged(view, oldScale, newScale)
                    if (!silentMode) {
                        LogUtils.d("webview scale changed old=$oldScale new=$newScale url=${safeUrl(view?.url)}")
                    }
                }

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    LogUtils.e(
                        "webview render process gone didCrash=${detail?.didCrash()} priority=${detail?.rendererPriorityAtExit()} url=${safeUrl(view?.url)}"
                    )
                    return super.onRenderProcessGone(view, detail)
                }
            }
        }
    }

    private fun applyMfaViewportUnitWorkaround(view: WebView) {
        val script = """
            (function() {
              function applyHitaMfaViewportFix() {
                var height = Math.round(window.innerHeight ||
                  (document.documentElement && document.documentElement.clientHeight) || 0);
                if (height <= 0) return {ok:false, error:'ZERO_VIEWPORT_HEIGHT'};
                var style = document.getElementById('hita-mfa-viewport-fix');
                if (!style) {
                  style = document.createElement('style');
                  style.id = 'hita-mfa-viewport-fix';
                  (document.head || document.documentElement).appendChild(style);
                }
                var centerHeight = Math.max(0, height - 44);
                var whiteBoxHeight = Math.max(0, height - 112);
                style.textContent =
                  'html,body{min-height:' + height + 'px!important;}' +
                  '.mobile-page-center-box{height:' + centerHeight + 'px!important;}' +
                  '.mobile-page-content-box{max-height:' + centerHeight + 'px!important;}' +
                  '.mobile-page-white-box{min-height:' + whiteBoxHeight + 'px!important;}';
                if (document.documentElement) document.documentElement.offsetHeight;
                var center = document.querySelector('.mobile-page-center-box');
                var rect = center ? center.getBoundingClientRect() : null;
                return {
                  ok: true,
                  innerHeight: height,
                  centerHeight: rect ? Math.round(rect.height) : -1
                };
              }
              var result = applyHitaMfaViewportFix();
              if (!window.__hitaMfaViewportFixInstalled) {
                window.__hitaMfaViewportFixInstalled = true;
                window.addEventListener('resize', applyHitaMfaViewportFix);
                window.addEventListener('orientationchange', applyHitaMfaViewportFix);
                if (window.visualViewport) {
                  window.visualViewport.addEventListener('resize', applyHitaMfaViewportFix);
                }
              }
              return JSON.stringify(result);
            })();
        """.trimIndent()
        view.evaluateJavascript(script) { raw ->
            try {
                val json = parseJavascriptJson(raw)
                if (json.optBoolean("ok", false)) {
                    LogUtils.success(
                        "MFA viewport fix applied: innerHeight=${json.optInt("innerHeight")} " +
                            "centerHeight=${json.optInt("centerHeight")}"
                    )
                    view.invalidate()
                } else {
                    LogUtils.w("MFA viewport fix deferred: ${json.optString("error", "unknown")}")
                }
            } catch (e: Exception) {
                LogUtils.e("MFA viewport fix parse error", e)
            }
        }
    }

    private fun applyTrustPortalViewportUnitWorkaround(view: WebView) {
        val script = """
            (function() {
              function applyHitaTrustViewportFix() {
                var height = Math.round(window.innerHeight ||
                  (document.documentElement && document.documentElement.clientHeight) || 0);
                if (height <= 0) return {ok:false, error:'ZERO_VIEWPORT_HEIGHT'};
                var style = document.getElementById('hita-trust-viewport-fix');
                if (!style) {
                  style = document.createElement('style');
                  style.id = 'hita-trust-viewport-fix';
                  (document.head || document.documentElement).appendChild(style);
                }
                style.textContent =
                  'html,body,#app{height:' + height + 'px!important;' +
                  'min-height:' + height + 'px!important;}' +
                  '.h-screen,.login{height:' + height + 'px!important;' +
                  'min-height:' + height + 'px!important;}' +
                  '.login{width:100%!important;min-width:0!important;' +
                  'overflow-x:hidden!important;}' +
                  '.login-head,.login-body{width:100%!important;}' +
                  '.login-head__content{width:auto!important;}' +
                  '.login-content{left:0!important;right:0!important;width:100%!important;' +
                  'margin-left:0!important;margin-right:0!important;' +
                  'justify-content:center!important;}' +
                  '.login-notice{display:none!important;}' +
                  '.login-panel{box-sizing:border-box!important;' +
                  'max-width:calc(100vw - 24px)!important;}';
                if (document.documentElement) document.documentElement.offsetHeight;
                var login = document.querySelector('.login');
                var rect = login ? login.getBoundingClientRect() : null;
                return {
                  ok: true,
                  innerHeight: height,
                  loginHeight: rect ? Math.round(rect.height) : -1
                };
              }
              var result = applyHitaTrustViewportFix();
              if (!window.__hitaTrustViewportFixInstalled) {
                window.__hitaTrustViewportFixInstalled = true;
                window.addEventListener('resize', applyHitaTrustViewportFix);
                window.addEventListener('orientationchange', applyHitaTrustViewportFix);
                if (window.visualViewport) {
                  window.visualViewport.addEventListener('resize', applyHitaTrustViewportFix);
                }
              }
              return JSON.stringify(result);
            })();
        """.trimIndent()
        view.evaluateJavascript(script) { raw ->
            if (raw.isNullOrBlank() || raw == "null") return@evaluateJavascript
            try {
                val json = parseJavascriptJson(raw)
                if (json.optBoolean("ok", false)) {
                    LogUtils.success(
                        "trust viewport fix applied: innerHeight=${json.optInt("innerHeight")} " +
                            "loginHeight=${json.optInt("loginHeight")}"
                    )
                    view.invalidate()
                } else {
                    LogUtils.w("trust viewport fix deferred: ${json.optString("error", "unknown")}")
                }
            } catch (e: Exception) {
                LogUtils.e("trust viewport fix parse error", e)
            }
        }
    }

    private fun applyShenzhenJwDesktopViewportWorkaround(view: WebView) {
        val script = """
            (function() {
              var desktopWidth = 1200;

              function applyHitaShenzhenJwHeight() {
                var height = Math.round(window.innerHeight ||
                  (document.documentElement && document.documentElement.clientHeight) || 0);
                if (height <= 0) return {ok:false, error:'ZERO_VIEWPORT_HEIGHT'};
                var style = document.getElementById('hita-shenzhen-jw-viewport-fix');
                if (!style) {
                  style = document.createElement('style');
                  style.id = 'hita-shenzhen-jw-viewport-fix';
                  (document.head || document.documentElement).appendChild(style);
                }
                style.textContent =
                  'html,body,#app,.towlg_body,.towlg_submain{' +
                  'height:' + height + 'px!important;' +
                  'min-height:' + height + 'px!important;}';
                if (document.documentElement) document.documentElement.offsetHeight;
                var main = document.querySelector('.towlg_main');
                var rect = main ? main.getBoundingClientRect() : null;
                return {
                  ok: true,
                  innerWidth: Math.round(window.innerWidth),
                  innerHeight: height,
                  mainX: rect ? Math.round(rect.x) : -1,
                  mainY: rect ? Math.round(rect.y) : -1,
                  mainWidth: rect ? Math.round(rect.width) : -1
                };
              }

              function applyHitaShenzhenJwViewportFix() {
                var deviceWidth = Math.round(
                  (window.screen && (window.screen.availWidth || window.screen.width)) ||
                  (window.visualViewport && window.visualViewport.width) ||
                  window.innerWidth || desktopWidth
                );
                var initialScale = Math.min(1, Math.max(0.2, deviceWidth / desktopWidth));
                var content = 'width=' + desktopWidth +
                  ', initial-scale=' + initialScale.toFixed(4) +
                  ', minimum-scale=0.2, maximum-scale=3.0, user-scalable=yes';
                var viewport = document.querySelector('meta[name=viewport]');
                if (!viewport) {
                  viewport = document.createElement('meta');
                  viewport.name = 'viewport';
                  (document.head || document.documentElement).appendChild(viewport);
                }
                if (viewport.content !== content) viewport.content = content;

                var result = applyHitaShenzhenJwHeight();
                window.requestAnimationFrame(applyHitaShenzhenJwHeight);
                window.setTimeout(applyHitaShenzhenJwHeight, 100);
                result.initialScale = initialScale;
                return result;
              }

              var result = applyHitaShenzhenJwViewportFix();
              if (!window.__hitaShenzhenJwViewportFixInstalled) {
                window.__hitaShenzhenJwViewportFixInstalled = true;
                window.addEventListener('resize', applyHitaShenzhenJwViewportFix);
                window.addEventListener('orientationchange', applyHitaShenzhenJwViewportFix);
                if (window.visualViewport) {
                  window.visualViewport.addEventListener('resize', applyHitaShenzhenJwHeight);
                }
              }
              return JSON.stringify(result);
            })();
        """.trimIndent()
        view.evaluateJavascript(script) { raw ->
            try {
                val json = parseJavascriptJson(raw)
                if (json.optBoolean("ok", false)) {
                    LogUtils.success(
                        "Shenzhen JW desktop viewport fix applied: " +
                            "inner=${json.optInt("innerWidth")}x${json.optInt("innerHeight")} " +
                            "scale=${json.optDouble("initialScale")} " +
                            "main=${json.optInt("mainX")},${json.optInt("mainY")}," +
                            "${json.optInt("mainWidth")}"
                    )
                    view.invalidate()
                } else {
                    LogUtils.w(
                        "Shenzhen JW desktop viewport fix deferred: " +
                            json.optString("error", "unknown")
                    )
                }
            } catch (e: Exception) {
                LogUtils.e("Shenzhen JW desktop viewport fix parse error", e)
            }
        }
    }

    /**
     * Plan D: Detect MFA page and extract DOM state for native UI bridge.
     * Instead of trying to fix WebView's broken flexbox rendering,
     * we read the page state, show native Compose UI, and inject user input back.
     */
    private fun scheduleMfaDetection(view: WebView, url: String) {
        val host = Uri.parse(url).host.orEmpty()
        val path = Uri.parse(url).path.orEmpty()
        if (!host.contains("ids") && !path.contains("authserver")) return
        val generation = ++mfaDetectionGeneration
        detectMfaAndBridge(view, url, generation, attempt = 0)
    }

    private fun detectMfaAndBridge(
        view: WebView,
        url: String,
        generation: Int,
        attempt: Int
    ) {
        val detectScript = """
            (function() {
              function isVisible(el) {
                if (!el || el.disabled) return false;
                var style = window.getComputedStyle(el);
                var rect = el.getBoundingClientRect();
                return style.display !== 'none' && style.visibility !== 'hidden' &&
                  style.opacity !== '0' && rect.width > 0 && rect.height > 0;
              }
              function plainText(value) {
                var container = document.createElement('div');
                container.innerHTML = String(value || '');
                return String(container.textContent || container.innerText || '').trim();
              }
              var params = (typeof reAuthParams === 'object' && reAuthParams) ? reAuthParams : {};
              var methodBtn = document.getElementById('changeReAuthTypeButton');
              var submitButtons = Array.prototype.slice.call(document.querySelectorAll('#reAuthSubmitBtn, [id*=reAuthSubmit]'));
              var submitButton = submitButtons.find(isVisible) || null;
              var specificInput = document.querySelector('#dynamicCode, input[name=dynamicCode], input[name=otpCode]');
              var pathLooksLikeMfa = /\/authserver\/reAuthCheck\//i.test(location.pathname);
              if (!pathLooksLikeMfa && !submitButton && !specificInput) {
                return JSON.stringify({mfa:false, ready:document.readyState});
              }
              var inputCandidates = Array.prototype.slice.call(document.querySelectorAll(
                '#dynamicCode, input[name=dynamicCode], input[name=otpCode], #password, input:not([type=hidden])'
              ));
              var visibleInput = inputCandidates.find(isVisible) || null;
              if (!submitButton && !visibleInput) {
                return JSON.stringify({mfa:false, ready:document.readyState});
              }
              var reAuthType = String(params.reAuthType || '');
              var methodNames = {
                '2':'统一身份认证密码', '7':'统一身份认证密码',
                '3':'手机号验证码', '4':'企业微信验证码', '5':'HIT APP 验证码',
                '10':'安全令牌C', '11':'邮箱验证码', '12':'钉钉验证码',
                '13':'哈工大 APP 验证码'
              };
              var descriptionKey = reAuthType ? ('reAuthDec' + reAuthType) : '';
              var visiblePromptEl = Array.prototype.slice.call(
                document.querySelectorAll('[id^=reAuthDec]')
              ).find(isVisible) || null;
              var promptEl = visiblePromptEl ||
                (descriptionKey && document.getElementById(descriptionKey)) ||
                document.getElementById('reAuthDec');
              var visibleTypeMatch = promptEl && promptEl.id ? promptEl.id.match(/^reAuthDec(\d+)$/) : null;
              var effectiveType = visibleTypeMatch ? visibleTypeMatch[1] : reAuthType;
              var method = methodNames[effectiveType] ||
                (methodBtn ? methodBtn.textContent.trim() : '') || '二次验证';
              var effectiveDescriptionKey = effectiveType ? ('reAuthDec' + effectiveType) : descriptionKey;
              var prompt = plainText((promptEl && promptEl.textContent) ||
                (effectiveDescriptionKey && params[effectiveDescriptionKey]) || '');
              var sendIds = ['getDynamicCode', 'getImprovePhoneCodeId_otp', 'getImproveEmailCodeId_otp'];
              var sendButton = null;
              for (var i = 0; i < sendIds.length && !sendButton; i++) {
                var candidate = document.getElementById(sendIds[i]);
                if (isVisible(candidate)) sendButton = candidate;
              }
              if (!sendButton) {
                sendButton = Array.prototype.slice.call(document.querySelectorAll(
                  '[onclick*=DynamicCode], [onclick*=dynamicCode], [id*=DynamicCode], [id*=dynamicCode]'
                )).find(function(el) { return isVisible(el) && el !== visibleInput; }) || null;
              }
              var result = {
                mfa: true,
                method: method,
                methodType: effectiveType,
                prompt: prompt,
                inputs: [],
                submitId: submitButton ? (submitButton.id || 'visible-submit') : '',
                canSendCode: !!sendButton,
                ready: document.readyState
              };
              if (visibleInput) {
                result.inputs.push({
                  id: visibleInput.id || '',
                  name: visibleInput.name || '',
                  type: visibleInput.type || 'text',
                  placeholder: visibleInput.placeholder || ''
                });
              }
              return JSON.stringify(result);
            })();
        """.trimIndent()
        view.postDelayed({
            if (finished || generation != mfaDetectionGeneration || view.url != url) {
                return@postDelayed
            }
            view.evaluateJavascript(detectScript) { raw ->
                try {
                    if (generation != mfaDetectionGeneration) return@evaluateJavascript
                    val json = parseJavascriptJson(raw)
                    if (!json.optBoolean("mfa", false)) {
                        if (isMfaPage(url) && attempt < MFA_DETECTION_MAX_RETRIES) {
                            detectMfaAndBridge(view, url, generation, attempt + 1)
                        }
                        return@evaluateJavascript
                    }
                    if (!MFA_NATIVE_OVERLAY_ENABLED) {
                        mfaState = MfaOverlayState()
                        mfaError = null
                        LogUtils.d(
                            "MFA_RAW_DIAG native overlay disabled; showing school page directly " +
                                "method=${json.optString("method", "")} " +
                                "methodType=${json.optString("methodType", "")}"
                        )
                        scheduleRawMfaDiagnostics(view, url, generation)
                        return@evaluateJavascript
                    }
                    val method = json.optString("method", "")
                    val prompt = json.optString("prompt", "")
                    val inputsArr = json.optJSONArray("inputs")
                    val submitId = json.optString("submitId", "")
                    var inputId = ""
                    var inputType = "number"
                    var inputPlaceholder = "验证码"
                    var hasVisibleInput = false
                    if (inputsArr != null && inputsArr.length() > 0) {
                        val first = inputsArr.getJSONObject(0)
                        inputId = first.optString("id", "")
                        inputType = first.optString("type", "number")
                        inputPlaceholder = first.optString("placeholder", "").ifBlank {
                            if (inputType.equals("password", ignoreCase = true)) "统一身份认证密码" else "验证码"
                        }
                        hasVisibleInput = true
                    }
                    mfaState = MfaOverlayState(
                        visible = true,
                        promptTitle = "多因子认证",
                        promptText = prompt,
                        verifyMethod = method,
                        verifyMethodType = json.optString("methodType", ""),
                        hasVisibleInput = hasVisibleInput,
                        inputId = inputId,
                        inputType = inputType,
                        inputPlaceholder = inputPlaceholder,
                        submitButtonId = submitId,
                        canSendCode = json.optBoolean("canSendCode", false),
                        switchMethodJs = "mobileChangeOtherType()"
                    )
                    mfaError = null
                    LogUtils.success(
                        "MFA detected: method=$method hasInput=$hasVisibleInput " +
                            "inputId=$inputId submitId=$submitId canSend=${mfaState.canSendCode}"
                    )
                } catch (e: Exception) {
                    LogUtils.e("MFA detect parse error", e)
                }
            }
        }, if (attempt == 0) MFA_DETECTION_INITIAL_DELAY_MS else MFA_DETECTION_RETRY_DELAY_MS)
    }

    private fun scheduleRawMfaDiagnostics(view: WebView, url: String, generation: Int) {
        listOf(0L to "detected", 800L to "after-800ms", 2500L to "after-2500ms").forEach {
            (delayMs, marker) ->
            view.postDelayed({
                if (finished || generation != mfaDetectionGeneration || view.url != url) {
                    return@postDelayed
                }
                logRawMfaNativeMetrics(view, marker)
                evaluateRawMfaPageMetrics(view, marker)
            }, delayMs)
        }
    }

    @Suppress("DEPRECATION")
    private fun logRawMfaNativeMetrics(view: WebView, marker: String) {
        val metrics = resources.displayMetrics
        val rect = Rect()
        val hasGlobalRect = view.getGlobalVisibleRect(rect)
        LogUtils.d(
            "MFA_RAW_DIAG native marker=$marker " +
                "size=${view.width}x${view.height} measured=${view.measuredWidth}x${view.measuredHeight} " +
                "global=$hasGlobalRect:$rect density=${metrics.density} densityDpi=${metrics.densityDpi} " +
                "screenPx=${metrics.widthPixels}x${metrics.heightPixels} scale=${view.scale} " +
                "contentHeight=${view.contentHeight} scroll=${view.scrollX},${view.scrollY} " +
                "layer=${layerName(view.layerType)} shown=${view.isShown} attached=${view.isAttachedToWindow}"
        )
    }

    private fun evaluateRawMfaPageMetrics(view: WebView, marker: String) {
        val script = """
            (function() {
              function number(value) {
                return Math.round((Number(value) || 0) * 100) / 100;
              }
              function elementInfo(el) {
                if (!el) return null;
                var rect = el.getBoundingClientRect();
                var style = window.getComputedStyle(el);
                return {
                  tag: el.tagName,
                  id: el.id || '',
                  cls: String(el.className || '').slice(0, 100),
                  type: el.getAttribute('type') || '',
                  rect: [number(rect.x), number(rect.y), number(rect.width), number(rect.height)],
                  display: style.display,
                  visibility: style.visibility,
                  opacity: style.opacity,
                  position: style.position,
                  overflow: style.overflow + '/' + style.overflowY,
                  transform: style.transform === 'none' ? '' : style.transform,
                  zoom: style.zoom || ''
                };
              }
              function uniqueElements(selectors, limit) {
                var seen = [];
                selectors.forEach(function(selector) {
                  Array.prototype.slice.call(document.querySelectorAll(selector)).forEach(function(el) {
                    if (seen.indexOf(el) < 0 && seen.length < limit) seen.push(el);
                  });
                });
                return seen.map(elementInfo);
              }
              var viewportMeta = document.querySelector('meta[name=viewport]');
              var center = document.elementFromPoint(
                Math.floor(window.innerWidth / 2), Math.floor(window.innerHeight / 2)
              );
              var visual = window.visualViewport;
              return JSON.stringify({
                marker: ${JSONObject.quote(marker)},
                ready: document.readyState,
                viewportMeta: viewportMeta ? viewportMeta.content : '',
                viewport: {
                  inner: [window.innerWidth, window.innerHeight],
                  outer: [window.outerWidth, window.outerHeight],
                  dpr: window.devicePixelRatio,
                  visual: visual ? [
                    number(visual.width), number(visual.height), number(visual.scale),
                    number(visual.offsetLeft), number(visual.offsetTop)
                  ] : null
                },
                screen: [screen.width, screen.height, screen.availWidth, screen.availHeight],
                document: [
                  document.documentElement.clientWidth, document.documentElement.clientHeight,
                  document.documentElement.scrollWidth, document.documentElement.scrollHeight
                ],
                body: elementInfo(document.body),
                html: elementInfo(document.documentElement),
                center: elementInfo(center),
                styleSheets: document.styleSheets.length,
                fonts: document.fonts ? document.fonts.status : 'unsupported',
                roots: uniqueElements([
                  'form', '.container', '.content-box', '.cotent-box', '.main',
                  '[class*="reauth" i]', '[class*="auth" i]'
                ], 10),
                controls: uniqueElements([
                  '#dynamicCode', '#reAuthSubmitBtn', '#changeReAuthTypeButton',
                  '[id^=reAuthDec]', 'input:not([type=hidden])', 'button', '[role=button]'
                ], 14)
              });
            })();
        """.trimIndent()
        view.evaluateJavascript(script) { result ->
            LogUtils.d("MFA_RAW_DIAG page ${sanitizeJsResult(result)}")
        }
    }

    /** Called from native Compose UI: find and click "send code" button in WebView. */
    fun triggerNativeMfaSendCode() {
        LogUtils.d("triggerNativeMfaSendCode")
        val sendScript = """
            (function() {
              try {
                function isVisible(el) {
                  if (!el || el.disabled) return false;
                  var style = window.getComputedStyle(el);
                  var rect = el.getBoundingClientRect();
                  return style.display !== 'none' && style.visibility !== 'hidden' &&
                    rect.width > 0 && rect.height > 0;
                }
                var targetIds = ['getDynamicCode', 'getImprovePhoneCodeId_otp', 'getImproveEmailCodeId_otp'];
                var target = null;
                for (var i = 0; i < targetIds.length; i++) {
                  var btn = document.getElementById(targetIds[i]);
                  if (isVisible(btn)) {
                    target = btn;
                    break;
                  }
                }
                if (!target) {
                  target = Array.prototype.slice.call(document.querySelectorAll(
                    '[onclick*=DynamicCode], [onclick*=dynamicCode], [id*=DynamicCode], [id*=dynamicCode]'
                  )).find(function(el) {
                    return isVisible(el) && el.tagName !== 'INPUT';
                  }) || null;
                }
                if (target) {
                  target.click();
                  return JSON.stringify({ok:true, id:target.id || 'dynamic-code-action'});
                }
                return JSON.stringify({ok:false, error:'NOT_FOUND'});
              } catch(e) {
                return JSON.stringify({ok:false, error: e.message});
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(sendScript) { raw ->
            try {
                val json = parseJavascriptJson(raw)
                if (json.optBoolean("ok", false)) {
                    LogUtils.success("MFA send code triggered: ${json.optString("id", "")}")
                } else {
                    LogUtils.w("MFA send code button not found: ${json.optString("error", "")}")
                    mfaError = "未找到发送按钮，请尝试切换验证方式"
                }
            } catch (e: Exception) {
                LogUtils.e("MFA send code parse error", e)
            }
        }
    }

    /** Called from native Compose UI: inject user input into WebView and submit. */
    fun submitNativeMfaInput(value: String) {
        val state = mfaState
        if (!state.visible) return
        LogUtils.d("submitNativeMfaInput hasInput=${state.hasVisibleInput} inputId=${state.inputId} submitId=${state.submitButtonId}")
        val codeValue = JSONObject.quote(value)
        val injectScript = """
            (function() {
              var result = {ok:false, error:''};
              // Find the visible code input field
              var input = null;
              var preferredId = ${JSONObject.quote(mfaState.inputId)};
              if (preferredId) {
                var preferred = document.getElementById(preferredId);
                if (preferred && preferred.offsetParent !== null) input = preferred;
              }
              var codeIds = ['dynamicCode','captcha_code','smsCode','otpCode','verifyCode'];
              for (var i=0; !input && i<codeIds.length;i++) {
                var el = document.getElementById(codeIds[i]);
                if (el && el.offsetParent !== null) { input = el; break; }
              }
              if (!input) {
                var inputs = document.querySelectorAll('input:not([type=hidden])');
                for (var i=0;i<inputs.length;i++) {
                  if (inputs[i].offsetParent !== null && inputs[i].type !== 'password') {
                    input = inputs[i]; break;
                  }
                }
              }
              if (!input) {
                var pwd = document.getElementById('password');
                if (pwd) input = pwd;
              }
              if (input) {
                input.value = $codeValue;
                input.dispatchEvent(new Event('input', {bubbles:true}));
                input.dispatchEvent(new Event('change', {bubbles:true}));
                result.inputId = input.id || input.name || 'unknown';
              }
              // Click submit button by exact ID
              var submitBtn = Array.prototype.slice.call(document.querySelectorAll('#reAuthSubmitBtn, [id*=reAuthSubmit], .submit_btn')).find(function(el) {
                var rect = el.getBoundingClientRect();
                var style = window.getComputedStyle(el);
                return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0;
              });
              if (submitBtn) {
                submitBtn.click();
                result.ok = true;
              } else {
                result.error = 'reAuthSubmitBtn not found';
              }
              return JSON.stringify(result);
            })();
        """.trimIndent()
        webView.evaluateJavascript(injectScript) { raw ->
            try {
                val json = parseJavascriptJson(raw)
                if (!json.optBoolean("ok", false)) {
                    val err = json.optString("error", "unknown")
                    LogUtils.w("MFA inject failed: $err")
                    mfaError = "提交失败，请重试"
                } else {
                    LogUtils.success("MFA inject OK, waiting for page transition")
                    mfaError = null
                    mfaState = mfaState.copy(visible = false)
                }
            } catch (e: Exception) {
                LogUtils.e("MFA inject parse error", e)
                mfaState = mfaState.copy(visible = false)
            }
        }
    }

    /** Called from native Compose UI: open the school's method picker and prefer SMS verification. */
    fun switchNativeMfaMethod() {
        val originalType = mfaState.verifyMethodType
        val generation = ++mfaDetectionGeneration
        val url = webView.url ?: return
        mfaState = mfaState.copy(visible = false)
        mfaError = null
        mfaInputValue = ""
        val script = """
            (function() {
              try {
                if (typeof mobileChangeOtherType !== 'function') {
                  return JSON.stringify({ok:false, error:'METHOD_PICKER_NOT_FOUND'});
                }
                mobileChangeOtherType();
                return JSON.stringify({ok:true});
              } catch (e) {
                return JSON.stringify({ok:false, error:String(e && e.message || e)});
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { raw ->
            if (generation != mfaDetectionGeneration) return@evaluateJavascript
            try {
                val json = parseJavascriptJson(raw)
                if (!json.optBoolean("ok", false)) {
                    LogUtils.w("MFA method picker failed: ${json.optString("error", "unknown")}")
                    mfaError = "无法打开验证方式选择，请返回后重试"
                    mfaState = mfaState.copy(visible = true)
                    return@evaluateJavascript
                }
                LogUtils.d("MFA method picker opened, currentType=$originalType")
                pollMfaMethodChange(url, originalType, generation, attempt = 0)
            } catch (e: Exception) {
                LogUtils.e("MFA method picker parse error", e)
                mfaError = "无法打开验证方式选择，请返回后重试"
                mfaState = mfaState.copy(visible = true)
            }
        }
    }

    private fun pollMfaMethodChange(
        url: String,
        originalType: String,
        generation: Int,
        attempt: Int,
        phoneSelectionAttempted: Boolean = false
    ) {
        val originalTypeJson = JSONObject.quote(originalType)
        val shouldSelectPhone = !phoneSelectionAttempted
        val script = """
            (function() {
              function isVisible(el) {
                if (!el || el.disabled) return false;
                var style = window.getComputedStyle(el);
                var rect = el.getBoundingClientRect();
                return style.display !== 'none' && style.visibility !== 'hidden' &&
                  style.opacity !== '0' && rect.width > 0 && rect.height > 0;
              }
              function textOf(el) {
                return String(el && (el.textContent || el.innerText) || '').replace(/\s+/g, ' ').trim();
              }
              function effectiveType() {
                var visiblePrompt = Array.prototype.slice.call(
                  document.querySelectorAll('[id^=reAuthDec]')
                ).find(isVisible);
                var match = visiblePrompt && visiblePrompt.id
                  ? visiblePrompt.id.match(/^reAuthDec(\d+)$/) : null;
                var params = (typeof reAuthParams === 'object' && reAuthParams) ? reAuthParams : {};
                return match ? match[1] : String(params.reAuthType || '');
              }

              var currentType = effectiveType();
              var clickedPhone = false;
              var clickedText = '';
              if (currentType === $originalTypeJson && $shouldSelectPhone) {
                var candidates = Array.prototype.slice.call(document.querySelectorAll(
                  'button, a, label, li, [role=button], [onclick], div, span'
                )).filter(function(el) {
                  if (!isVisible(el) || /^reAuthDec\d+$/.test(el.id || '')) return false;
                  var text = textOf(el);
                  if (text.length === 0 || text.length > 40) return false;
                  return /手机(?:号|短信)?(?:验证码|动态码)|短信(?:验证码|动态码)/.test(text);
                }).sort(function(a, b) {
                  function score(el) {
                    var score = textOf(el).length;
                    if (el.matches('button, a, label, [role=button], [onclick]')) score -= 100;
                    return score;
                  }
                  return score(a) - score(b);
                });
                if (candidates.length) {
                  var candidate = candidates[0];
                  var target = candidate.closest('button, a, label, li, [role=button], [onclick]') || candidate;
                  clickedText = textOf(candidate);
                  target.click();
                  clickedPhone = true;
                  currentType = effectiveType();
                }
              }
              return JSON.stringify({
                type: currentType,
                clickedPhone: clickedPhone,
                clickedText: clickedText
              });
            })();
        """.trimIndent()
        webView.postDelayed({
            if (finished || generation != mfaDetectionGeneration || webView.url != url) {
                return@postDelayed
            }
            webView.evaluateJavascript(script) { raw ->
                if (generation != mfaDetectionGeneration) return@evaluateJavascript
                try {
                    val json = parseJavascriptJson(raw)
                    val currentType = json.optString("type", "")
                    val clickedPhone = json.optBoolean("clickedPhone", false)
                    if (clickedPhone) {
                        LogUtils.d(
                            "MFA phone method selected from web picker: ${json.optString("clickedText", "")}"
                        )
                    }
                    if (currentType.isNotBlank() && currentType != originalType) {
                        LogUtils.success("MFA method changed: $originalType -> $currentType")
                        scheduleMfaDetection(webView, url)
                    } else if (attempt < MFA_METHOD_SWITCH_MAX_POLLS) {
                        pollMfaMethodChange(
                            url = url,
                            originalType = originalType,
                            generation = generation,
                            attempt = attempt + 1,
                            phoneSelectionAttempted = phoneSelectionAttempted || clickedPhone
                        )
                    } else {
                        LogUtils.w("MFA method picker left open: no method change detected")
                    }
                } catch (e: Exception) {
                    LogUtils.e("MFA method switch poll error", e)
                }
            }
        }, MFA_METHOD_SWITCH_POLL_DELAY_MS)
    }

    /** Called from native Compose UI: dismiss MFA overlay. */
    fun dismissNativeMfa() {
        mfaState = MfaOverlayState()
        mfaError = null
    }

    private fun schedulePageDiagnostics(view: WebView, url: String) {
        if (silentMode) return
        val host = Uri.parse(url).host.orEmpty()
        val path = Uri.parse(url).path.orEmpty()
        val shouldProbe = host.contains("vpn.hit.edu.cn") ||
            host.contains("ids") ||
            path.contains("authserver") ||
            (config.campus == EASToken.Campus.SHENZHEN && isShenzhenProxyJwPage(url))
        if (!shouldProbe) return

        view.postDelayed({
            if (finished || !view.isAttachedToWindow) return@postDelayed
            logWebViewRenderMarker("diag-800ms", view, url)
            evaluatePageDiagnostics(view, "diag-800ms")
        }, 800)
        view.postDelayed({
            if (finished || !view.isAttachedToWindow) return@postDelayed
            logWebViewRenderMarker("diag-2500ms", view, url)
            evaluatePageDiagnostics(view, "diag-2500ms")
        }, 2500)
    }

    @Suppress("DEPRECATION")
    private fun evaluatePageDiagnostics(view: WebView, marker: String) {
        if (finished || !view.isAttachedToWindow) return
        val script = """
            (function() {
              function visibleStyle(el) {
                var r = el.getBoundingClientRect();
                var s = window.getComputedStyle(el);
                return {
                  tag: el.tagName,
                  type: el.getAttribute('type') || '',
                  id: el.id || '',
                  name: el.getAttribute('name') || '',
                  cls: (el.className || '').toString().slice(0, 80),
                  rect: {
                    x: Math.round(r.x),
                    y: Math.round(r.y),
                    w: Math.round(r.width),
                    h: Math.round(r.height)
                  },
                  display: s.display,
                  visibility: s.visibility,
                  opacity: s.opacity,
                  zIndex: s.zIndex,
                  disabled: !!el.disabled
                };
              }
              var inputs = Array.prototype.slice.call(document.querySelectorAll('input, textarea, select'));
              var forms = Array.prototype.slice.call(document.querySelectorAll('form'));
              var buttons = Array.prototype.slice.call(document.querySelectorAll('button, input[type=button], input[type=submit], .login, [class*=login], [id*=login]'));
              var frames = Array.prototype.slice.call(document.querySelectorAll('iframe'));
              var allElements = Array.prototype.slice.call(document.querySelectorAll('*'));
              function compactText(el) {
                return String(el && (el.innerText || el.textContent || el.value) || '')
                  .replace(/\s+/g, ' ').trim();
              }
              function relevantElement(el) {
                var text = compactText(el);
                if (!text || text.length > 100 || !/(统一|身份|登录|本科|研究生)/.test(text)) {
                  return null;
                }
                var r = el.getBoundingClientRect();
                var ownerWindow = el.ownerDocument && el.ownerDocument.defaultView || window;
                var s = ownerWindow.getComputedStyle(el);
                return {
                  tag: el.tagName,
                  id: el.id || '',
                  cls: (el.className || '').toString().slice(0, 100),
                  role: el.getAttribute('role') || '',
                  tabindex: el.getAttribute('tabindex') || '',
                  hasOnClick: !!(el.onclick || el.getAttribute('onclick')),
                  pointer: s.cursor === 'pointer',
                  visible: s.display !== 'none' && s.visibility !== 'hidden' &&
                    r.width > 0 && r.height > 0,
                  rect: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)],
                  text: text.slice(0, 100)
                };
              }
              function safeFrameSrc(frame) {
                try {
                  var parsed = new URL(frame.src || '', location.href);
                  return parsed.protocol + '//' + parsed.host + parsed.pathname;
                } catch (e) {
                  return '';
                }
              }
              var relevant = allElements.map(relevantElement).filter(Boolean)
                .sort(function(a, b) { return a.text.length - b.text.length; }).slice(0, 30);
              var frameInfo = frames.slice(0, 10).map(function(frame) {
                var accessible = false;
                var relevantInside = [];
                try {
                  var doc = frame.contentDocument;
                  accessible = !!doc;
                  if (doc) {
                    relevantInside = Array.prototype.slice.call(doc.querySelectorAll('*'))
                      .map(relevantElement).filter(Boolean).slice(0, 15);
                  }
                } catch (e) {}
                return {
                  id: frame.id || '',
                  name: frame.name || '',
                  cls: (frame.className || '').toString().slice(0, 80),
                  src: safeFrameSrc(frame),
                  accessible: accessible,
                  relevant: relevantInside
                };
              });
              var centerEl = document.elementFromPoint(Math.floor(window.innerWidth / 2), Math.floor(window.innerHeight / 2));
              var bodyStyle = document.body ? window.getComputedStyle(document.body) : null;
              var htmlStyle = document.documentElement ? window.getComputedStyle(document.documentElement) : null;
              return JSON.stringify({
                marker: '$marker',
                ready: document.readyState,
                title: document.title || '',
                urlHost: location.host,
                path: location.pathname,
                viewport: {
                  innerWidth: window.innerWidth,
                  innerHeight: window.innerHeight,
                  devicePixelRatio: window.devicePixelRatio,
                  scrollX: window.scrollX,
                  scrollY: window.scrollY
                },
                document: {
                  clientWidth: document.documentElement ? document.documentElement.clientWidth : 0,
                  clientHeight: document.documentElement ? document.documentElement.clientHeight : 0,
                  scrollWidth: document.documentElement ? document.documentElement.scrollWidth : 0,
                  scrollHeight: document.documentElement ? document.documentElement.scrollHeight : 0
                },
                body: document.body ? {
                  childCount: document.body.children.length,
                  textLength: (document.body.innerText || '').length,
                  background: bodyStyle ? bodyStyle.backgroundColor : '',
                  color: bodyStyle ? bodyStyle.color : '',
                  display: bodyStyle ? bodyStyle.display : '',
                  visibility: bodyStyle ? bodyStyle.visibility : '',
                  overflow: bodyStyle ? (bodyStyle.overflow + '/' + bodyStyle.overflowY) : ''
                } : null,
                html: htmlStyle ? {
                  background: htmlStyle.backgroundColor,
                  overflow: htmlStyle.overflow + '/' + htmlStyle.overflowY
                } : null,
                counts: {
                  forms: forms.length,
                  inputs: inputs.length,
                  buttons: buttons.length,
                  elements: allElements.length,
                  iframes: frames.length
                },
                firstInputs: inputs.slice(0, 8).map(visibleStyle),
                firstButtons: buttons.slice(0, 8).map(visibleStyle),
                centerElement: centerEl ? visibleStyle(centerEl) : null,
                bodyTextStart: ((document.body && document.body.innerText) || '').replace(/\s+/g, ' ').slice(0, 300),
                relevant: relevant,
                frames: frameInfo
              });
            })();
        """.trimIndent()
        view.evaluateJavascript(script) { result ->
            logShenzhenAutoPayload("DOM $marker", result)
        }
    }

    @Suppress("DEPRECATION")
    private fun logWebViewRenderMarker(marker: String, view: WebView?, url: String? = view?.url) {
        if (silentMode || finished || view == null || !view.isAttachedToWindow) return
        val rect = Rect()
        val hasGlobalRect = view.getGlobalVisibleRect(rect)
        LogUtils.d(
            "WEBVIEW_DIAG marker=$marker url=${safeUrl(url)} size=${view.width}x${view.height} " +
                "measured=${view.measuredWidth}x${view.measuredHeight} global=$hasGlobalRect:$rect " +
                "shown=${view.isShown} attached=${view.isAttachedToWindow} focused=${view.hasFocus()} " +
                "alpha=${view.alpha} layer=${layerName(view.layerType)} scale=${view.scale} " +
                "scroll=${view.scrollX},${view.scrollY} progress=${view.progress}"
        )
    }

    private fun sanitizeJsResult(result: String?): String {
        return result
            ?.replace("\\u003C", "<")
            ?.replace(Regex("(?i)(value|password|pwd|pass|token)[^,}]{0,80}"), "$1=***")
            ?.take(12000)
            ?: "null"
    }

    private fun logShenzhenAutoPayload(label: String, result: String?) {
        val chunks = sanitizeJsResult(result).chunked(2800).ifEmpty { listOf("null") }
        chunks.forEachIndexed { index, chunk ->
            LogUtils.d("SHENZHEN_AUTO $label part=${index + 1}/${chunks.size} $chunk")
        }
    }

    /** 只保留 scheme/host/port/path；集中规则见 LogRedaction，避免各页面各写一套。 */
    private fun safeUrl(url: String?): String = LogRedaction.url(url)

    private fun layerName(layerType: Int): String {
        return when (layerType) {
            View.LAYER_TYPE_HARDWARE -> "hardware"
            View.LAYER_TYPE_SOFTWARE -> "software"
            View.LAYER_TYPE_NONE -> "none"
            else -> layerType.toString()
        }
    }

    private fun isPortalHomePage(url: String): Boolean {
        val uri = Uri.parse(url)
        val normalizedPath = uri.path?.trimEnd('/') ?: ""
        return when (config.campus) {
            EASToken.Campus.BENBU -> uri.host in BENBU_PORTAL_HOSTS &&
                (normalizedPath == "/portal/home" || normalizedPath == "/portal")
            EASToken.Campus.WEIHAI -> WebLoginSuccessPolicy.shouldOpenWeihaiTeachingEntry(
                url,
                if (isWeihaiGraduate) cn.limpu.hita.data.source.web.eas.WeihaiGraduateSessionPolicy.BASE_URL
                else CampusUrls.WEIHAI_EAS_PREFIX
            )
            EASToken.Campus.SHENZHEN -> ShenzhenWebAutoLogin.isProxyRoot(
                url,
                CampusUrls.SHENZHEN_PROXY_BASE
            )
        }
    }

    private fun isIvpnRedirectPage(url: String): Boolean {
        if (config.campus != EASToken.Campus.BENBU) return false
        val uri = Uri.parse(url)
        return uri.host == "vpn.hit.edu.cn" || uri.host == "ivpn.hit.edu.cn"
    }

    private fun isAuthenticationPage(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.path.orEmpty().contains("/authserver/login", ignoreCase = true)
    }

    private fun isMfaPage(url: String): Boolean {
        return Uri.parse(url).path.orEmpty().contains("/authserver/reAuthCheck/", ignoreCase = true)
    }

    private fun isTrustPortalPage(url: String): Boolean =
        WebLoginSuccessPolicy.isShenzhenTrustPortal(url)

    /**
     * VPN 或信任门户连上后继续打开教务网页登录，不结束 Activity。
     * 每个登录过程只自动跳一次，避免门户和教务入口互相来回。
     */
    private fun resumeAcademicLoginAfterVpn(view: WebView, url: String): Boolean {
        // Silent renewal must walk the same VPN/portal hop; it only gives up on pages that
        // actually demand credentials (checked by the callers). The one-shot guard below keeps
        // portal <-> entry bouncing bounded.
        if (finished || isSuccessPage(url)) return false
        if (isAuthenticationPage(url) || isMfaPage(url)) return false
        // 已经在教务页时不再续跳，避免 aTrust 与教务入口来回打转。
        if (config.campus == EASToken.Campus.SHENZHEN &&
            WebLoginSuccessPolicy.isShenzhenAcademicEntry(url)
        ) {
            return false
        }
        val connected = when (config.campus) {
            EASToken.Campus.BENBU -> isIvpnRedirectPage(url)
            EASToken.Campus.WEIHAI -> isWeihaiVpnShell(url)
            // 深圳只有"门户已不在认证/引导页"才算连通：shortcut.html 与 #/login 是
            // 未连通的入口，把它们当信号会形成 门户→教务→verify→门户 的无限环。
            EASToken.Campus.SHENZHEN -> WebLoginSuccessPolicy.isShenzhenTrustPortalConnected(url)
        }
        if (!connected) return false
        if (url.substringBefore('?') == config.jwtsUrl.substringBefore('?')) return false
        // 三校区统一：每次续跳后必须重新经过一次"连通页面"才允许再跳，
        // 避免 aTrust/教务入口互相无限重定向。
        if (vpnResumeAttempted) return false
        vpnResumeAttempted = true
        autoOpeningJwts = false
        LogUtils.d("vpn connected, continuing academic login campus=${config.campus}")
        view.loadUrl(config.jwtsUrl)
        return true
    }

    private fun isWeihaiVpnShell(url: String): Boolean {
        val uri = Uri.parse(url)
        if (!uri.host.equals("webvpn.hitwh.edu.cn", ignoreCase = true)) return false
        if (isPortalHomePage(url) || isAuthenticationPage(url) || isSuccessPage(url)) return false
        return !uri.path.orEmpty().lowercase().contains("/http/")
    }

    private fun isVpnConnectionPage(url: String): Boolean {
        return when (config.campus) {
            EASToken.Campus.BENBU -> isIvpnRedirectPage(url)
            EASToken.Campus.WEIHAI -> isWeihaiVpnShell(url) || isPortalHomePage(url)
            EASToken.Campus.SHENZHEN -> isTrustPortalPage(url)
        }
    }

    /**
     * The aTrust shortcut page defaults to probing a locally installed Sangfor client.
     * A normal Android WebView has no such client listening on localhost, so that path
     * ends at the UEM authorization page instead of the browser authentication flow.
     *
     * aTrust's own web client checks this session-storage flag before probing localhost.
     * Set it on the aTrust origin and reload the signed shortcut URL once so the original
     * return URL and verification token remain intact.
     */
    private fun applyShenzhenForceWebMode(view: WebView, url: String): Boolean {
        if (
            config.campus != EASToken.Campus.SHENZHEN ||
            shenzhenForceWebModeApplied
        ) {
            return false
        }

        val uri = Uri.parse(url)
        if (
            !uri.host.equals("trust.hitsz.edu.cn", ignoreCase = true) ||
            !uri.path.orEmpty().equals("/portal/shortcut.html", ignoreCase = true)
        ) {
            return false
        }

        shenzhenForceWebModeApplied = true
        val script = """
            (function() {
              try {
                sessionStorage.setItem('forceWeb', 'true');
                // Newer aTrust pages namespace session data per SDP address while
                // shortcut.html still reads the legacy, unprefixed key.
                var mapKey = '__Prefix_map__';
                var prefixMap = JSON.parse(sessionStorage.getItem(mapKey) || '[]');
                if (!Array.isArray(prefixMap)) prefixMap = [];
                var origin = window.location.origin;
                var entry = prefixMap.find(function(item) { return item && item.addr === origin; });
                if (!entry) {
                  var maxPrefix = prefixMap.reduce(function(max, item) {
                    var match = item && String(item.pre || '').match(/^\[(\d+)\]$/);
                    return match ? Math.max(max, Number(match[1])) : max;
                  }, 0);
                  entry = {pre: '[' + (maxPrefix + 1) + ']', addr: origin};
                  prefixMap.unshift(entry);
                }
                sessionStorage.setItem(mapKey, JSON.stringify(prefixMap));
                sessionStorage.setItem(entry.pre + 'forceWeb', 'true');
                return true;
              } catch (e) {
                return false;
              }
            })();
        """.trimIndent()
        view.evaluateJavascript(script) { result ->
            if (finished) return@evaluateJavascript
            if (result == "true") {
                LogUtils.success("enabled aTrust force-web mode; reloading shortcut")
            } else {
                LogUtils.w("could not confirm aTrust force-web mode; retrying shortcut once")
            }
            view.loadUrl(url)
        }
        return true
    }

    private fun isShenzhenProxyJwPage(url: String): Boolean {
        return Uri.parse(url).host.equals(
            Uri.parse(CampusUrls.SHENZHEN_PROXY_BASE).host,
            ignoreCase = true
        )
    }

    private fun redirectExpiredShenzhenSession(view: WebView, url: String): Boolean {
        if (config.campus != EASToken.Campus.SHENZHEN || shenzhenReauthenticationStarted) {
            return false
        }
        val destination = ShenzhenWebAutoLogin.reauthenticationUrl(
            currentUrl = url,
            directBaseUrl = CampusUrls.SHENZHEN_DIRECT_BASE,
            proxyBaseUrl = CampusUrls.SHENZHEN_PROXY_BASE
        ) ?: return false
        shenzhenReauthenticationStarted = true
        LogUtils.d("expired Shenzhen session page detected; opening CAS reauthentication")
        view.loadUrl(destination)
        return true
    }

    private fun scheduleShenzhenAutoAdvance(view: WebView, url: String) {
        LogUtils.d(
            "SHENZHEN_AUTO schedule requested generation=$shenzhenAutoAdvanceGeneration " +
                "url=${safeUrl(url)} finished=$finished"
        )
        if (
            config.campus != EASToken.Campus.SHENZHEN ||
            !isShenzhenProxyJwPage(url) ||
            // 会话已失效的页面由 redirectExpiredShenzhenSession 负责转 CAS，
            // 这里再注入点击脚本只会干扰它的跳转。
            WebLoginSuccessPolicy.isShenzhenReauthenticationPage(url) ||
            finished
        ) {
            return
        }
        val generation = shenzhenAutoAdvanceGeneration
        if (shenzhenAutoAdvanceScheduledGeneration == generation) {
            LogUtils.d("SHENZHEN_AUTO schedule deduplicated generation=$generation")
            return
        }
        shenzhenAutoAdvanceScheduledGeneration = generation
        LogUtils.d(
            "SHENZHEN_AUTO schedule accepted generation=$generation " +
                "studentType=$shenzhenPreferredStudentType"
        )

        fun attempt(index: Int) {
            val currentUrl = view.url.orEmpty()
            if (
                finished ||
                generation != shenzhenAutoAdvanceGeneration ||
                !isShenzhenProxyJwPage(currentUrl) ||
                // 重定向到失效页/认证页后再注入点击脚本没有任何意义，还会拖慢 CAS 跳转。
                WebLoginSuccessPolicy.isShenzhenReauthenticationPage(currentUrl)
            ) {
                return
            }
            val allowUnified = !shenzhenUnifiedLoginClicked
            val allowRole = !shenzhenRoleSelectionClicked
            if (!allowUnified && !allowRole) return
            LogUtils.d(
                "SHENZHEN_AUTO attempt=$index generation=$generation " +
                    "allowUnified=$allowUnified allowRole=$allowRole url=${safeUrl(view.url)}"
            )

            view.evaluateJavascript(
                ShenzhenWebAutoLogin.buildClickScript(
                    studentType = shenzhenPreferredStudentType,
                    allowUnifiedLogin = allowUnified,
                    allowRoleSelection = allowRole
                )
            ) { raw ->
                if (finished || generation != shenzhenAutoAdvanceGeneration) {
                    return@evaluateJavascript
                }
                val result = runCatching { parseJavascriptJson(raw ?: "") }.getOrNull()
                LogUtils.d(
                    "SHENZHEN_AUTO result attempt=$index parsed=${result != null} " +
                        "payload=${sanitizeJsResult(raw)}"
                )
                if (result?.optBoolean("clicked", false) == true) {
                    when (val action = result.optString("action")) {
                        "unified-login" -> shenzhenUnifiedLoginClicked = true
                        "undergrad-role", "postgrad-role" -> shenzhenRoleSelectionClicked = true
                    }
                    LogUtils.success(
                        "Shenzhen Web auto advance action=${result.optString("action")} " +
                            "studentType=$shenzhenPreferredStudentType"
                    )
                    return@evaluateJavascript
                }
                if (index + 1 < SHENZHEN_AUTO_ADVANCE_MAX_RETRIES) {
                    view.postDelayed(
                        { attempt(index + 1) },
                        SHENZHEN_AUTO_ADVANCE_RETRY_DELAY_MS
                    )
                } else {
                    LogUtils.d("SHENZHEN_AUTO exhausted without matching action")
                }
            }
        }

        attempt(0)
    }

    /**
     * 该 URL 是否必须以桌面 UA 请求：
     * - 深圳 aTrust 浏览器路由/教务代理域只认桌面 UA；
     * - 威海研究生 yjsgl 教务页（WebVPN 反代）按移动 UA 下发「浏览器已过时」拦截页，
     *   本科 jwts 链路无拦截页报告，维持移动 UA 不变。
     * 统一身份认证（CAS，路径含 /authserver/）一律保持移动 UA：登录表单渲染与
     * 自动填充行为不随本次改动变化。
     */
    private fun needsEasDesktopUserAgent(url: String): Boolean {
        return when (config.campus) {
            EASToken.Campus.WEIHAI -> isWeihaiGraduate && isWeihaiGraduateAcademicPage(url)
            EASToken.Campus.SHENZHEN -> {
                val host = Uri.parse(url).host.orEmpty()
                host.equals(Uri.parse(CampusUrls.SHENZHEN_PROXY_BASE).host.orEmpty(), ignoreCase = true) ||
                    host.equals("trust.hitsz.edu.cn", ignoreCase = true)
            }
            else -> false
        }
    }

    /**
     * 威海研究生教务系统（yjsgl.hitwh.edu.cn 经 WebVPN 反代）的页面。CAS/统一认证**表单页**
     * （路径含 /authserver/）必须留在移动 UA 上，否则原生输入法光标与 HTML input 错位；
     * 其余研究生教务页面（含登录入口 `/cas` 与登录后的业务页）都要桌面 UA，否则服务端
     * 按 UA 判定浏览器过时并下发拦截页。判定本身由 [WeihaiGraduateSessionPolicy] 承担，
     * 便于离线测试覆盖。
     */
    private fun isWeihaiGraduateAcademicPage(url: String): Boolean =
        cn.limpu.hita.data.source.web.eas.WeihaiGraduateSessionPolicy
            .needsDesktopUserAgentFor(url)

    private fun switchEasUserAgentForNavigation(view: WebView, url: String): Boolean {
        if (config.campus != EASToken.Campus.SHENZHEN &&
            config.campus != EASToken.Campus.WEIHAI
        ) return false
        val host = Uri.parse(url).host.orEmpty()
        val needsDesktopUserAgent = needsEasDesktopUserAgent(url)
        // CAS service ticket 之类的单程票参数不能整页重载：票已被消费，重放只会换来
        // 重新认证或错误页。这里只切换后续请求的 UA，让本页自然走完；落点若仍是
        // 「浏览器已过时」拦截页，由 detectOutdatedBrowserPage 兜底。
        val reloadable = !url.contains("ticket=", ignoreCase = true)

        if (!needsDesktopUserAgent && easDesktopUserAgentApplied) {
            easDesktopUserAgentApplied = false
            view.settings.userAgentString = easMobileUserAgent
            // Software rendering is required by the desktop aTrust route, but on the mobile CAS
            // form it offsets Chromium's native IME caret from the HTML input. Restore the normal
            // hardware-composited WebView layer before reloading the mobile page.
            if (config.campus == EASToken.Campus.SHENZHEN) {
                view.setLayerType(View.LAYER_TYPE_NONE, null)
            }
            LogUtils.d(
                "switching EAS login navigation back to mobile user agent: " +
                    "campus=${config.campus} host=$host"
            )
            if (reloadable) {
                view.stopLoading()
                view.post { if (!finished) view.loadUrl(url) }
            }
            return reloadable
        }

        if (needsDesktopUserAgent && !easDesktopUserAgentApplied) {
            easDesktopUserAgentApplied = true
            view.settings.userAgentString = EAS_DESKTOP_USER_AGENT
            if (config.campus == EASToken.Campus.SHENZHEN) {
                view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
            LogUtils.d(
                "switching EAS login navigation to desktop user agent: " +
                    "campus=${config.campus} host=$host"
            )
            if (reloadable) {
                view.stopLoading()
                view.post { if (!finished) view.loadUrl(url) }
            }
            return reloadable
        }
        return false
    }

    private fun isExpectedShenzhenClientProbe(request: WebResourceRequest?): Boolean {
        if (config.campus != EASToken.Campus.SHENZHEN || request?.isForMainFrame == true) {
            return false
        }
        val uri = request?.url ?: return false
        val host = uri.host.orEmpty()
        return uri.path.orEmpty().equals("/v1/detect", ignoreCase = true) &&
            (host == "127.0.0.1" || host.equals("localhost.sangfor.com.cn", ignoreCase = true))
    }

    private fun isExpectedShenzhenBrowserConsoleMessage(message: String?): Boolean {
        if (config.campus != EASToken.Campus.SHENZHEN) return false
        return message.orEmpty().let {
            it.contains("UEM授权不足") || it.contains("clientInstallMode undefined")
        }
    }

    private fun ensureEasDesktopUserAgentForProxy(view: WebView, url: String): Boolean {
        if (
            (config.campus != EASToken.Campus.SHENZHEN &&
                config.campus != EASToken.Campus.WEIHAI) ||
            !needsEasDesktopUserAgent(url) ||
            // 失效页马上会被转到 /cas，这里再 reload 一次只会打断它的跳转。
            WebLoginSuccessPolicy.isShenzhenReauthenticationPage(url) ||
            easDesktopUserAgentApplied
        ) {
            return false
        }
        easDesktopUserAgentApplied = true
        view.settings.userAgentString = EAS_DESKTOP_USER_AGENT
        if (config.campus == EASToken.Campus.SHENZHEN) {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        LogUtils.d("reloading EAS page with desktop user agent campus=${config.campus}")
        view.post { if (!finished) view.reload() }
        return true
    }

    /**
     * 教务服务端按 UA 下发「浏览器已过时」拦截页（特征文案见 eas_outdated_browser_marker）：
     * 页内「关闭」只会 window.close()/跳空白页，WebView 里表现为白屏死路。这里在页面
     * 加载完成后探测拦截页：命中即退回登录入口重来；再命中说明服务端仍不接受当前 UA，
     * 明确失败收尾，禁止静默白屏。
     */
    private fun detectOutdatedBrowserPage(view: WebView, url: String) {
        if (finished) return
        // 「关闭」按钮走 window.close()/跳空白页：空白落点视为拦截页余波，直接兜底，
        // 不让用户停在白屏上。
        if (url == "about:blank" || url.isBlank()) {
            if (outdatedBrowserPageHits > 0) handleOutdatedBrowserPage(view)
            return
        }
        val pageKey = url.substringBefore('#')
        val marker = getString(R.string.eas_outdated_browser_marker)
        val markerEn = getString(R.string.eas_outdated_browser_marker_en)
        val markerUpgrade = getString(R.string.eas_outdated_browser_marker_upgrade)
        view.evaluateJavascript(
            "(function(){try{var t=(document.body&&document.body.innerText||'').toLowerCase();" +
                "return t.indexOf('$marker')>=0||t.indexOf('$markerEn')>=0||" +
                "t.indexOf('$markerUpgrade')>=0}catch(e){return false}})()"
        ) { raw ->
            if (finished || raw != "true") return@evaluateJavascript
            val current = view.url.orEmpty()
            // 回调到达时用户可能已点「关闭」落到空白页：此时同样兜底，不能因为
            // URL 对不上就放弃，否则又回到静默白屏。
            if (current.isNotBlank() && current != "about:blank" &&
                current.substringBefore('#') != pageKey
            ) {
                return@evaluateJavascript
            }
            handleOutdatedBrowserPage(view)
        }
    }

    private fun handleOutdatedBrowserPage(view: WebView) {
        outdatedBrowserPageHits++
        LogUtils.w(
            "outdated browser interstitial detected campus=${config.campus} " +
                "hit=$outdatedBrowserPageHits/${MAX_OUTDATED_BROWSER_FALLBACK + 1} " +
                "url=${safeUrl(view.url.orEmpty())}"
        )
        if (outdatedBrowserPageHits > MAX_OUTDATED_BROWSER_FALLBACK) {
            if (!silentMode) {
                Toast.makeText(this, R.string.eas_outdated_browser_persistent, Toast.LENGTH_LONG).show()
            }
            finishWithCancelledResult()
            return
        }
        if (!silentMode) {
            Toast.makeText(this, R.string.eas_outdated_browser_retry_hint, Toast.LENGTH_LONG).show()
        }
        // 重试前必须确认 UA 已是桌面版：拦截页本身可能是在「单程票跳转」这一跳里
        // 用旧 UA 加载出来的（ticket= 页面不可重载，只切 UA 不重放），
        // 否则退回入口后仍会重演同一次拦截。
        if (needsEasDesktopUserAgent(config.loginUrl) && !easDesktopUserAgentApplied) {
            easDesktopUserAgentApplied = true
            view.settings.userAgentString = EAS_DESKTOP_USER_AGENT
            LogUtils.d("forcing desktop user agent before retrying outdated browser page")
        }
        view.loadUrl(config.loginUrl)
    }

    private fun parseJavascriptJson(raw: String): JSONObject {
        val trimmed = raw.trim()
        val decoded = if (trimmed.startsWith('"')) {
            JSONArray("[$trimmed]").getString(0)
        } else {
            trimmed
        }
        return JSONObject(decoded)
    }

    private fun isJwtsPage(url: String): Boolean {
        if (config.campus == EASToken.Campus.WEIHAI) {
            return false
        }
        val uri = Uri.parse(url)
        return uri.host?.contains("jwts") == true &&
            (url.lowercase().contains("logincas") || url.lowercase().contains("login"))
    }

    private fun isSuccessPage(url: String): Boolean {
        val uri = Uri.parse(url) ?: return false
        val path = uri.path?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()

        return when (config.campus) {
            EASToken.Campus.BENBU ->
                WebLoginSuccessPolicy.isBenbuAuthenticatedPage(url, collectCookies())
            EASToken.Campus.WEIHAI -> {
                val cookies = collectCookies()
                val authenticated = if (isWeihaiGraduate) {
                    if (weihaiEntry == WeihaiEasEntryPolicy.Entry.DIRECT) {
                        // 研究生直连时落点是 yjsgl 自己的 landing 页，且没有 VPN 票据，
                        // 不能复用依赖 wengine_vpn_ticket 的判定。
                        WeihaiEasEntryPolicy.isDirectGraduateLanding(url, cookies)
                    } else {
                        cn.limpu.hita.data.source.web.eas.WeihaiGraduateSessionPolicy
                            .isAuthenticatedLanding(url, cookies)
                    }
                } else {
                    WeihaiEasEntryPolicy.isAuthenticated(weihaiEntry, url, cookies)
                }
                LogUtils.d(
                    "isSuccessPage(weihai): entry=$weihaiEntry path=$path authenticated=$authenticated " +
                        "hasVpnTicket=${hasWeihaiVpnTicket(cookies)} " +
                        "hasJsession=${WebLoginSuccessPolicy.hasJsessionIdCookie(cookies)} " +
                        "keys=${cookies.keys.sorted()}"
                )
                authenticated
            }
            EASToken.Campus.SHENZHEN -> {
                WebLoginSuccessPolicy.isShenzhenAuthenticatedPage(url, collectCookies())
            }
        }
    }

    private fun approvedCredentialOrigins(): Set<String> = when (config.campus) {
        EASToken.Campus.BENBU -> setOf(
            "http://ids-hit-edu-cn-s.vpn.hit.edu.cn",
            "http://ids-hit-edu-cn-s.ivpn.hit.edu.cn",
            "https://ids.hit.edu.cn"
        )
        EASToken.Campus.WEIHAI -> setOf(
            "https://webvpn.hitwh.edu.cn",
            "https://ids.hit.edu.cn",
            // 校园网直连时登录表单由教务自己渲染，走的是这两个宿主
            // （与 EasWebCredentialPolicy 的直连白名单同一口径）。漏掉它们会让直连
            // 场景下手动输入的账号密码永远捕获不到，下次无感续期只能重新手输。
            "https://yjsgl.hitwh.edu.cn",
            "http://yjsgl.hitwh.edu.cn",
            "https://jwts.hitwh.edu.cn",
            "http://jwts.hitwh.edu.cn",
        )
        // 深圳统一身份认证 CAS：三校区都要能捕获用户手动输入的账号密码，
        // 否则深圳 Web 会话一旦过期就只能每次重输。
        EASToken.Campus.SHENZHEN -> setOf("https://ids-hit-edu-cn-s.hitsz.edu.cn")
    }

    private fun installCredentialBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        runCatching {
            WebViewCompat.addWebMessageListener(
                webView,
                credentialBridgeName,
                approvedCredentialOrigins()
            ) { view, message, sourceOrigin, isMainFrame, _ ->
                if (!isMainFrame || !EasWebCredentialPolicy.matchesLoginOrigin(
                        config.campus,
                        sourceOrigin.toString()
                    ) || !EasWebCredentialPolicy.matchesLoginPage(
                        config.campus,
                        view.url.orEmpty(),
                        isMainFrame = true
                    )
                ) return@addWebMessageListener

                runCatching {
                    val payload = JSONObject(message.data ?: return@runCatching)
                    val username = payload.optString("username").trim()
                    val password = payload.optString("password")
                    if (username.isNotEmpty() && password.isNotEmpty()) {
                        credentialCaptureState.stage(EasCredential(config.campus, username, password))
                    }
                }
            }
        }.onFailure {
            LogUtils.w("credential bridge unavailable; manual login remains available")
        }
    }

    private fun prepareLoginPageCredentials(view: WebView, url: String) {
        // 免登录勾选必须对三端都执行：不勾选时 CAS 只会下发会话级 CASTGC。
        // 页面可能在 onPageFinished 之后才渲染出勾选框，因此多轮尝试。
        if (isCasLoginPage(url)) {
            CAS_REMEMBER_ME_DELAYS_MS.forEachIndexed { index, delay ->
                view.postDelayed({
                    if (finished) return@postDelayed
                    view.evaluateJavascript(EasWebCredentialPolicy.rememberMeScript()) { raw ->
                        LogUtils.d(
                            "rememberMe round=$index url=${safeUrl(url)} result=${raw?.take(80) ?: "null"}"
                        )
                    }
                }, delay)
            }
        }
        // 深圳的 CAS 页在 ids-hit-edu-cn-s.hitsz.edu.cn，与本部/威海同一套表单结构，
        // 因此三校区共用这条自动填充 + 自动提交路径。
        if (!EasWebCredentialPolicy.matchesLoginPage(config.campus, url, isMainFrame = true)) return
        view.evaluateJavascript(EasWebCredentialPolicy.captureScript(), null)
        val credential = EasWebCredentialPolicy.readOptionalCredential {
            easCredentialStore.get(config.campus)
        } ?: return
        if (credential.username.isBlank() || credential.password.isBlank()) return
        val loginKey = url.substringBefore('#')
        if (loginKey in autoSubmittedLoginPages || autoSubmittedLoginPages.size > MAX_AUTO_SUBMIT) {
            // 同一张登录页只自动提交一次；累计到上限（例如密码已改）就把控制权还给用户，
            // 避免反复提交触发学校风控。这里不写入集合，否则集合会随页面数量无限增长。
            view.evaluateJavascript(
                EasWebCredentialPolicy.autofillScript(credential.username, credential.password),
                null
            )
            return
        }
        autoSubmittedLoginPages.add(loginKey)
        view.postDelayed({
            if (finished || view.url.orEmpty().substringBefore('#') != loginKey) return@postDelayed
            view.evaluateJavascript(
                EasWebCredentialPolicy.autofillAndSubmitScript(
                    credential.username,
                    credential.password
                )
            ) { raw ->
                LogUtils.d("auto submit login (${config.campus}): ${raw?.take(120) ?: "null"}")
                // 静默续期没有用户在场：填不上或提交不了就别空等到超时，立刻交回宿主提示。
                if (silentMode && !EasWebCredentialPolicy.submittedFromResult(raw)) {
                    LogUtils.w("silent auto submit failed, cancelling: ${raw?.take(80) ?: "null"}")
                    finishWithCancelledResult()
                }
            }
        }, CAS_AUTO_SUBMIT_DELAY_MS)
    }

    /**
     * 静默续期能否在这张认证页上自行完成：需要已保存的账号密码，且当前页仍是
     * 可自动填充的登录表单。MFA、滑块等交互页一律返回 false，交回用户。
     */
    private fun canAutoSubmitCredentials(url: String): Boolean {
        if (!EasWebCredentialPolicy.matchesLoginPage(config.campus, url, isMainFrame = true)) {
            return false
        }
        return hasStoredCredential()
    }

    /** 本地是否已保存该校区可用的账号密码。 */
    private fun hasStoredCredential(): Boolean {
        val credential = EasWebCredentialPolicy.readOptionalCredential {
            easCredentialStore.get(config.campus)
        } ?: return false
        return credential.username.isNotBlank() && credential.password.isNotBlank()
    }

    /** 三端统一身份认证登录页（含深圳），路径均以 `/authserver/login` 结尾。 */
    private fun isCasLoginPage(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val path = uri.path?.lowercase() ?: return false
        return path.trimEnd('/').endsWith("/authserver/login")
    }

    private fun autoOpenJwts(webView: WebView) {
        if (autoOpeningJwts) return
        autoOpeningJwts = true
        LogUtils.d("auto opening JWTS campus=${config.campus}")
        webView.loadUrl(config.jwtsUrl)
    }

    private fun startCookiePolling() {
        val generation = ++cookiePollingGeneration
        cookieRetryCount = 0
        webView.postDelayed({
            checkCookiesAndFinish(generation)
        }, COOKIE_RETRY_DELAY_MS)
    }

    private fun stopCookiePolling() {
        cookiePollingGeneration++
    }

    private fun checkCookiesAndFinish(generation: Int) {
        if (finished || generation != cookiePollingGeneration) return

        val cookies = collectCookies()
        val currentUrl = webView.url ?: ""
        val hasVpnTicket = hasWeihaiVpnTicket(cookies)
        // 大小写不敏感，避免与 isWeihaiAuthenticatedPage 的判定不一致。
        val hasJsessionid = WebLoginSuccessPolicy.hasJsessionIdCookie(cookies)

        if (cookieRetryCount == 0 || cookieRetryCount % 10 == 0) {
            LogUtils.d("checkCookies: retry=$cookieRetryCount cookieNames=${LogRedaction.cookieNames(cookies.keys)} host=${Uri.parse(currentUrl).host}")
        }

        if (isWeihaiGraduate) {
            val graduateDone = if (weihaiEntry == WeihaiEasEntryPolicy.Entry.DIRECT) {
                WeihaiEasEntryPolicy.isDirectGraduateLanding(currentUrl, cookies)
            } else {
                cn.limpu.hita.data.source.web.eas.WeihaiGraduateSessionPolicy
                    .isAuthenticatedLanding(currentUrl, cookies)
            }
            if (graduateDone) {
                finishWithCookies(cookies)
                return
            }
        }
        if (config.campus == EASToken.Campus.WEIHAI && !isWeihaiGraduate) {
            if (weihaiEntry == WeihaiEasEntryPolicy.Entry.DIRECT) {
                // 直连没有反代票据：业务会话就绪即可收尾，不能等 wengine_vpn_ticket。
                if (hasJsessionid) {
                    finishWithCookies(cookies)
                    return
                }
            } else if (hasVpnTicket && hasJsessionid) {
                fetchVpnEasCookies { vpnCookies ->
                    val mergedCookies = LinkedHashMap(cookies)
                    vpnCookies.forEach { (key, value) -> mergedCookies.putIfAbsent(key, value) }
                    finishWithCookies(mergedCookies)
                }
                return
            }
        }

        if (config.campus == EASToken.Campus.BENBU
            && WebLoginSuccessPolicy.canCollectBenbuSession(currentUrl)
            && hasRequiredCookies(cookies, currentUrl)) {
            handleSuccessPage()
            return
        }

        if (config.campus == EASToken.Campus.SHENZHEN
            && hasRequiredCookies(cookies, currentUrl)) {
            finishWithCookies(cookies)
            return
        }

        if (cookieRetryCount >= COOKIE_RETRY_COUNT) {
            LogUtils.w("cookie polling timeout campus=${config.campus}")
            return
        }

        cookieRetryCount++
        webView.postDelayed({
            checkCookiesAndFinish(generation)
        }, COOKIE_RETRY_DELAY_MS)
    }

    /**
     * 登录卡住兜底（威海/深圳）：统一认证完成后，若 WebView 停在成功判定覆盖不到的页面
     * （如渲染空白的 loginCAS 页，或白名单之外的深圳教务页），成功判定永远不会触发，
     * 用户只能干等。此时重新加载 EAS 入口，让 CAS 借助仍有效的统一认证会话（SSO）再走一遍，
     * 大多能落到正常的成功页。每个 Activity 实例每校区最多触发 2 次，避免异常循环。
     */
    private fun nudgeStuckCampusLogin(url: String) {
        // Allowed in silent mode too: it only re-drives an EAS entry page on a teaching host,
        // never filling credentials; without it renewal stalls on blank pages.
        if (finished) return
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val attempt = when (config.campus) {
            EASToken.Campus.WEIHAI -> {
                if (!weihaiSawAuthPage || weihaiStuckNudgeCount >= MAX_STUCK_NUDGE) return
                if (uri.host != "webvpn.hitwh.edu.cn") return
                if (isAuthenticationPage(url) || isPortalHomePage(url) || isSuccessPage(url)) return
                ++weihaiStuckNudgeCount
            }
            EASToken.Campus.SHENZHEN -> {
                if (!shenzhenSawAuthPage || shenzhenStuckNudgeCount >= MAX_STUCK_NUDGE) return
                // 只在教务 host 上推一把：认证页/信任门户本身需要用户交互，不能拽走
                val host = uri.host.orEmpty()
                if (host != "jw.hitsz.edu.cn" && host != "jw-hitsz-edu-cn.hitsz.edu.cn") return
                if (isAuthenticationPage(url) || isTrustPortalPage(url) ||
                    isPortalHomePage(url) || isSuccessPage(url) ||
                    // 失效页已由 redirectExpiredShenzhenSession 转 CAS，再推会打断它的跳转。
                    WebLoginSuccessPolicy.isShenzhenReauthenticationPage(url)
                ) return
                ++shenzhenStuckNudgeCount
            }
            else -> return
        }
        LogUtils.w(
            "campus=${config.campus} login stuck on unhandled page, re-driving EAS entry " +
                "(attempt $attempt/$MAX_STUCK_NUDGE): path=${uri.path}"
        )
        webView.loadUrl(config.jwtsUrl)
    }

    private fun handleSuccessPage() {
        if (finished) return
        if (isWeihaiGraduate) {
            finishWithCookies(collectCookies())
            return
        }

        credentialCaptureState.commitOnSuccess { credential ->
            runCatching {
                easCredentialStore.save(credential.campus, credential.username, credential.password)
            }
        }

        val cookies = collectCookies()

        if (config.campus == EASToken.Campus.WEIHAI && hasWeihaiVpnTicket(cookies)) {
            fetchVpnEasCookies { vpnCookies ->
                val mergedCookies = LinkedHashMap(cookies)
                vpnCookies.forEach { (key, value) -> mergedCookies.putIfAbsent(key, value) }
                finishWithCookies(mergedCookies)
            }
        } else if (config.campus == EASToken.Campus.BENBU) {
            navigatingToEelab = true
            eelabTokenFetching = false
            collectedEasCookies = cookies
            webView.postDelayed({
                if (navigatingToEelab && !finished) {
                    LogUtils.w("eelabinfo timeout, finishing without token")
                    navigatingToEelab = false
                    finishWithCookies(cookies)
                }
            }, 10000)
            webView.loadUrl(CampusUrls.EELABINFO_URL + "/api/cas/loginSuccess")
        } else {
            finishWithCookies(cookies)
        }
    }

    private fun fetchEelabTokenViaHttp() {
        if (finished || !navigatingToEelab) return

        Thread {
            try {
                val cookieManager = CookieManager.getInstance()
                val eelabCookies = cookieManager.getCookie(CampusUrls.EELABINFO_URL)

                if (eelabCookies.isNullOrBlank() || !eelabCookies.contains("JSESSIONID")) {
                    LogUtils.w("fetchEelabToken: JSESSIONID not found, student likely has no eelab access")
                    webView.post {
                        if (!finished && navigatingToEelab) {
                            navigatingToEelab = false
                            finishWithCookies(collectedEasCookies ?: collectCookies())
                        }
                    }
                    return@Thread
                }

                val allCookies = mutableMapOf<String, String>()
                eelabCookies.split(";").forEach { part ->
                    val trimmed = part.trim()
                    if (trimmed.contains("=")) {
                        val idx = trimmed.indexOf('=')
                        allCookies[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
                    }
                }

                val url = CampusUrls.EELABINFO_URL + "/api/cas/login?sf_request_type=ajax"
                val response = org.jsoup.Jsoup.connect(url)
                    .cookies(allCookies)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 16; sdk_gphone64_arm64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.135 Mobile Safari/537.36")
                    .header("Accept", "*/*")
                    .header("Origin", CampusUrls.EELABINFO_URL)
                    .header("Referer", CampusUrls.EELABINFO_URL + "/login.html?t=suc")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept-Language", "zh-CN,zh-Hans;q=0.9")
                    .timeout(5000)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .method(org.jsoup.Connection.Method.POST)
                    .execute()

                if (response.statusCode() == 200) {
                    try {
                        val json = JSONObject(response.body())
                        val code = json.optInt("code", -1)
                        if (code == 0) {
                            val data = json.optJSONObject("data")
                            val token = data?.optString("token", "") ?: ""
                            if (token.length >= 50) {
                                LogUtils.success("fetchEelabToken: got JWT token, length=${token.length}")
                                webView.post {
                                    if (!finished && navigatingToEelab) {
                                        navigatingToEelab = false
                                        finishWithCookies(collectedEasCookies ?: collectCookies(), token)
                                    }
                                }
                                return@Thread
                            }
                        }
                        LogUtils.w("fetchEelabToken: unexpected response code=$code")
                    } catch (e: Exception) {
                        LogUtils.e("fetchEelabToken: parse response failed", e)
                    }
                } else {
                    LogUtils.w("fetchEelabToken: HTTP ${response.statusCode()}")
                }

                webView.post {
                    if (!finished && navigatingToEelab) {
                        navigatingToEelab = false
                        finishWithCookies(collectedEasCookies ?: collectCookies())
                    }
                }
            } catch (e: Exception) {
                LogUtils.e("fetchEelabToken: HTTP request failed", e)
                webView.post {
                    if (!finished && navigatingToEelab) {
                        navigatingToEelab = false
                        finishWithCookies(collectedEasCookies ?: collectCookies())
                    }
                }
            }
        }.start()
    }

    private fun hasRequiredCookies(cookies: Map<String, String>, currentUrl: String): Boolean {
        return when (config.campus) {
            EASToken.Campus.BENBU -> {
                val hasJsession = cookies.containsKey("JSESSIONID") || hasUrlJsession(currentUrl)
                val hasHit = cookies.containsKey("HIT")
                hasJsession && hasHit
            }
            EASToken.Campus.SHENZHEN ->
                EASToken.hasShenzhenWebSessionCookies(cookies)
            EASToken.Campus.WEIHAI -> {
                if (isWeihaiGraduate) {
                    // 研究生两条入口的凭据不同，不能沿用本科的「VPN 票据 + JSESSIONID」。
                    return if (weihaiEntry == WeihaiEasEntryPolicy.Entry.DIRECT) {
                        WeihaiEasEntryPolicy.isDirectGraduateLanding(currentUrl, cookies)
                    } else {
                        cn.limpu.hita.data.source.web.eas.WeihaiGraduateSessionPolicy
                            .isAuthenticatedLanding(currentUrl, cookies)
                    }
                }
                if (weihaiEntry == WeihaiEasEntryPolicy.Entry.DIRECT) {
                    // 直连没有反代票据：业务会话就绪即可收尾。
                    return WebLoginSuccessPolicy.hasJsessionIdCookie(cookies)
                }
                // 威海本科走 WebVPN：需要 VPN ticket + JSESSIONID
                val hasVpnTicket = hasWeihaiVpnTicket(cookies)
                val hasJsession = WebLoginSuccessPolicy.hasJsessionIdCookie(cookies)
                hasVpnTicket && hasJsession
            }
        }
    }

    private fun hasWeihaiVpnTicket(cookies: Map<String, String>): Boolean {
        return cookies.keys.any { key ->
            key.startsWith(WEIHAI_TICKET_COOKIE_PREFIX, ignoreCase = true) ||
                key.contains(WEIHAI_TICKET_COOKIE_PREFIX, ignoreCase = true)
        }
    }

    private fun hasUrlJsession(url: String): Boolean {
        return url.contains(";jsessionid=", ignoreCase = true) ||
            url.contains("jsessionid=", ignoreCase = true)
    }

    private fun collectCookies(): LinkedHashMap<String, String> {
        val cookieManager = CookieManager.getInstance()
        val cookies = LinkedHashMap<String, String>()

        config.cookieProbeUrls.forEach { url ->
            val parsed = parseCookies(cookieManager.getCookie(url))
            parsed.forEach { (key, value) ->
                cookies.putIfAbsent(key, value)
            }
        }

        val currentUrl = webView.url
        if (!currentUrl.isNullOrBlank() && currentUrl.startsWith("http")) {
            val parsed = parseCookies(cookieManager.getCookie(currentUrl))
            parsed.forEach { (key, value) ->
                cookies.putIfAbsent(key, value)
            }

            if (!cookies.containsKey("JSESSIONID")) {
                val jsessionid = extractJsessionidFromUrl(currentUrl)
                if (jsessionid != null) {
                    cookies["JSESSIONID"] = jsessionid
                }
            }
        }

        return cookies
    }

    private fun fetchVpnEasCookies(callback: (Map<String, String>) -> Unit) {
        // 在主线程上捕获 WebView 数据
        val currentUrl = webView.url ?: ""
        val cookieHeader = buildCookieHeader(currentUrl)

        Thread {
            val result = mutableMapOf<String, String>()
            try {
                // 威海校区的 EAS 系统域名和登录页面路径
                val easHost = "jwts.hitwh.edu.cn"
                val easPath = "/loginCAS"  // 使用登录页面路径

                // 调用 VPN cookie API
                val cookieApiUrl = "https://webvpn.hitwh.edu.cn/wengine-vpn/cookie?method=get&host=$easHost&scheme=http&path=$easPath&vpn_timestamp=${System.currentTimeMillis()}"

                // 使用同步 HTTP 请求
                val connection = URL(cookieApiUrl).openConnection() as javax.net.ssl.HttpsURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("Cookie", cookieHeader)
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.setRequestProperty("Accept", "*/*")

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    LogUtils.d("VPN cookie API response: ${LogRedaction.cookieHeader(response)}")

                    // 解析返回的 cookies（格式：name=value; JSESSIONID=xxx; HIT=yyy）
                    val parts = response.split(";")
                    for (part in parts) {
                        val trimmed = part.trim()
                        if (trimmed.contains("=")) {
                            val idx = trimmed.indexOf('=')
                            val key = trimmed.substring(0, idx).trim()
                            val value = trimmed.substring(idx + 1).trim()
                            if (key.isNotEmpty() && value.isNotEmpty()) {
                                // 只关心 EAS 相关的 cookies
                                if (key == "JSESSIONID" || key == "HIT" || key == "TWFID") {
                                    result[key] = value
                                    LogUtils.d("parsed VPN cookie: $key=${LogRedaction.secret(value)}")
                                }
                            }
                        }
                    }
                } else {
                    LogUtils.w("VPN cookie API failed with code: $responseCode")
                }
            } catch (e: Exception) {
                LogUtils.e("fetchVpnEasCookies: failed", e)
            }

            webView.post {
                callback(result)
            }
        }.start()
    }

    private fun buildCookieHeader(currentUrl: String): String {
        val cookieManager = CookieManager.getInstance()

        // 从当前 URL 和 VPN 主域获取 cookies
        val cookies = mutableSetOf<String>()

        // 添加当前页面的 cookies
        if (currentUrl.isNotEmpty()) {
            val currentCookies = cookieManager.getCookie(currentUrl)
            if (!currentCookies.isNullOrBlank()) {
                cookies.add(currentCookies)
            }
        }

        // 添加 VPN 主域的 cookies
        val vpnCookies = cookieManager.getCookie("https://webvpn.hitwh.edu.cn/")
        if (!vpnCookies.isNullOrBlank()) {
            cookies.add(vpnCookies)
        }

        return cookies.joinToString("; ")
    }

    private fun extractJsessionidFromUrl(url: String): String? {
        // 匹配 URL 中的 ;jsessionid=XXX 或 jsessionid=XXX 参数
        val patterns = listOf(
            ";jsessionid=([^;&?]*)",
            "[?&]jsessionid=([^;&]*)"
        )

        for (pattern in patterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val match = regex.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return null
    }

    private fun fingerprintSummary(cookies: Map<String, String>): String {
        if (isWeihaiGraduate) return "[graduate session redacted]"
        return when (config.campus) {
            EASToken.Campus.BENBU -> {
                BENBU_REQUIRED_COOKIES.sorted().joinToString(prefix = "[", postfix = "]") { key ->
                    val value = cookies[key]
                    "$key=${value?.take(8) ?: "-"}"
                }
            }
            EASToken.Campus.SHENZHEN -> {
                (SHENZHEN_DIRECT_SESSION_COOKIES + SHENZHEN_PROXY_SESSION_COOKIE)
                    .sorted()
                    .joinToString(prefix = "[", postfix = "]") { key ->
                    val value = cookies[key]
                    "$key=${value?.take(8) ?: "-"}"
                }
            }
            EASToken.Campus.WEIHAI -> {
                val ticketKey = cookies.keys.firstOrNull { it.startsWith(WEIHAI_TICKET_COOKIE_PREFIX) }
                val ticketValue = ticketKey?.let { cookies[it] }
                val easSummary = WEIHAI_EAS_SESSION_COOKIE_HINTS.joinToString(prefix = "[", postfix = "]") { key ->
                    "$key=${cookies[key]?.take(8) ?: "-"}"
                }
                "[$WEIHAI_TICKET_COOKIE_PREFIX=${ticketValue?.take(8) ?: "-"}]$easSummary"
            }
        }
    }

    private fun finishWithCookies(cookies: Map<String, String>, eelabToken: String? = null) {
        if (finished) return
        // 收尾前的最后一道闸：必须用到与本次入口一致的落地判据。
        // 直连时落点在 yjsgl 自己的 `/authentication/main`（没有反代票据），
        // 若沿用反代判据（要求 wengine_vpn_ticket 且路径在反代前缀下）会永远拦住直连。
        if (isWeihaiGraduate) {
            val landingOk = if (weihaiEntry == WeihaiEasEntryPolicy.Entry.DIRECT) {
                WeihaiEasEntryPolicy.isDirectGraduateLanding(webView.url.orEmpty(), cookies)
            } else {
                cn.limpu.hita.data.source.web.eas.WeihaiGraduateSessionPolicy
                    .isAuthenticatedLanding(webView.url.orEmpty(), cookies)
            }
            if (!landingOk) return
        }
        finished = true
        stopCookiePolling()
        // 只要本次登录成功，无论从哪条路径收尾（URL 判定 / cookie 轮询 / eelab 回调），
        // 都把用户手动输入的账号密码落盘，供下次无感登录自动填充。
        credentialCaptureState.commitOnSuccess { credential ->
            runCatching {
                easCredentialStore.save(credential.campus, credential.username, credential.password)
            }
        }

        val cookiesJson = JSONObject(cookies as Map<*, *>).toString()
        val intent = Intent().apply {
            putExtra("cookies", cookiesJson)
            putExtra(EXTRA_CAMPUS, config.campus.name)
            putExtra(EXTRA_STUDENT_TYPE, shenzhenPreferredStudentType)
            if (isWeihaiGraduate) {
                // 数据层必须用与登录时相同的入口，否则「登录成功但拉不到课表」。
                // 直连时 webView.url 是具体 landing 路径，这里取策略给出的 base。
                putExtra(
                    "web_base_url",
                    WeihaiEasEntryPolicy.timetableBaseUrl(weihaiEntry, graduate = true),
                )
            } else if (config.campus == EASToken.Campus.WEIHAI &&
                weihaiEntry == WeihaiEasEntryPolicy.Entry.DIRECT
            ) {
                // 威海本科直连：把 base 交给数据层，它会在同一个槽位里被识别。
                putExtra(
                    "web_base_url",
                    WeihaiEasEntryPolicy.timetableBaseUrl(weihaiEntry, graduate = false),
                )
            }
            if (config.campus == EASToken.Campus.SHENZHEN) {
                putExtra(
                    "web_base_url",
                    WebLoginSuccessPolicy.shenzhenWebBaseUrl(
                        host = Uri.parse(webView.url.orEmpty()).host,
                        proxyBaseUrl = CampusUrls.SHENZHEN_PROXY_BASE,
                        directBaseUrl = CampusUrls.SHENZHEN_DIRECT_BASE
                    )
                )
            }
            if (!eelabToken.isNullOrBlank()) {
                putExtra("electronic_exp_token", eelabToken)
            }
        }
        LogUtils.success("login complete campus=${config.campus} cookies=${cookies.size} eelabToken=${eelabToken != null}")
        // Identity cookies (CAS CASTGC, aTrust tokens, WebVPN tickets) live in the shared cookie
        // jar; flush them now so a later process death cannot drop the session that silent renewal
        // depends on.
        CookieManager.getInstance().flush()
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    private fun finishWithCancelledResult() {
        if (finished) return
        finished = true
        credentialCaptureState.discard()
        stopCookiePolling()
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun parseCookies(cookieString: String?): Map<String, String> {
        if (cookieString.isNullOrBlank()) return emptyMap()
        return cookieString.split(";")
            .mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isBlank() || !trimmed.contains("=")) return@mapNotNull null
                val idx = trimmed.indexOf('=')
                val key = trimmed.substring(0, idx).trim()
                val value = trimmed.substring(idx + 1).trim()
                if (key.isBlank()) null else key to value
            }
            .toMap()
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val current = if (::webView.isInitialized) webView.url.orEmpty() else ""
            if (leftForExternalVpn && ::webView.isInitialized &&
                resumeAcademicLoginAfterVpn(webView, current)
            ) {
                leftForExternalVpn = false
                return
            }
            // 用户在 aTrust 主界面（已连上门户服务页）按返回时，回到教务登录入口而不是
            // 直接退出 HITA：门户只是去教务的中转页，退回后 aTrust 票据往往已就绪。
            // 停在门户认证页（#/login、shortcut.html）时说明还没连上，返回照旧退出登录。
            if (::webView.isInitialized && !finished &&
                isShenzhenPortalBackTarget(current)
            ) {
                LogUtils.d("back from aTrust portal, returning to academic login: ${safeUrl(current)}")
                vpnResumeAttempted = false
                webView.loadUrl(config.jwtsUrl)
                return
            }
            LogUtils.d(
                "web login back pressed, cancelling campus=${config.campus} " +
                    "canGoBack=${if (::webView.isInitialized) webView.canGoBack() else false}"
            )
            finishWithCancelledResult()
        }
    }

    /** 深圳：当前停在已连通的 aTrust 主界面，返回应回教务入口。 */
    private fun isShenzhenPortalBackTarget(url: String): Boolean =
        config.campus == EASToken.Campus.SHENZHEN &&
            WebLoginSuccessPolicy.isShenzhenTrustPortalConnected(url)

    /**
     * aTrust 门户登录页只有一个「统一身份认证」按钮：勾选协议并点一次，才会跳到
     * `ids-hit-edu-cn-s.hitsz.edu.cn/authserver/login`。这里自动完成这一步，
     * 让用户不必在 WebView 里手点；每张页面最多推进一次，失败仍可手动操作。
     */
    private fun autoAdvanceTrustPortalLogin(view: WebView, url: String) {
        if (!ShenzhenTrustPortalAutoLogin.shouldAutoLogin(url)) return
        val pageKey = url.substringBefore('#')
        if (trustPortalAutoLoginPage == pageKey) return
        trustPortalAutoLoginPage = pageKey
        // 勾选协议后按钮的 disabled 是在下一个 tick 才解除的，单次注入必然撞上
        // 「按钮仍禁用」而放弃。给一个有界重试窗口，成功推进后自然离开本页。
        advanceTrustPortalLoginAttempt(view, pageKey, attempt = 0)
    }

    private fun advanceTrustPortalLoginAttempt(view: WebView, pageKey: String, attempt: Int) {
        view.postDelayed({
            if (finished || view.url.orEmpty().substringBefore('#') != pageKey) return@postDelayed
            if (attempt >= TRUST_PORTAL_AUTO_LOGIN_ATTEMPTS) {
                LogUtils.d("aTrust portal auto login gave up after $attempt attempts")
                return@postDelayed
            }
            view.evaluateJavascript(ShenzhenTrustPortalAutoLogin.buildLoginScript()) { raw ->
                LogUtils.d("aTrust portal auto login result: ${raw?.take(120) ?: "null"}")
                val clicked = raw.orEmpty().contains("\"clicked\":true")
                if (!clicked) {
                    advanceTrustPortalLoginAttempt(view, pageKey, attempt + 1)
                }
            }
        }, TRUST_PORTAL_AUTO_LOGIN_DELAY_MS)
    }

    override fun onPause() {
        super.onPause()
        // 静默续期窗口页不会真正外跳 aTrust 客户端；若在这里置位，回到前台时会
        // 多触发一次续跳，和自动填充路径抢跳转。
        if (!silentMode && ::webView.isInitialized &&
            isVpnConnectionPage(webView.url.orEmpty())
        ) {
            leftForExternalVpn = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (!leftForExternalVpn || !::webView.isInitialized || finished) return
        if (resumeAcademicLoginAfterVpn(webView, webView.url.orEmpty())) {
            leftForExternalVpn = false
        }
    }

    override fun onDestroy() {
        finished = true
        credentialCaptureState.discard()
        stopCookiePolling()
        if (::webView.isInitialized) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                runCatching { WebViewCompat.removeWebMessageListener(webView, credentialBridgeName) }
            }
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
        super.onDestroy()
    }
}

@HiltViewModel
class WebViewLoginViewModel @Inject constructor() : androidx.lifecycle.ViewModel()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebViewLoginScreen(
    silentMode: Boolean,
    progressVisible: Boolean,
    progressValue: Int,
    showBenbuMfaHint: Boolean,
    mfaState: MfaOverlayState,
    mfaError: String?,
    mfaInputValue: String,
    onMfaInputChange: (String) -> Unit,
    onMfaSubmit: () -> Unit,
    onMfaSendCode: () -> Unit,
    onMfaSwitchMethod: () -> Unit,
    onMfaDismiss: () -> Unit,
    onBack: () -> Unit,
    onWebViewReady: (WebView) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (silentMode) ComposeColor.Transparent else ComposeColor.White)
    ) {
        if (!silentMode) {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.webview_login_title))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.rotate(180f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
        if (!silentMode) {
            Box(modifier = Modifier.fillMaxWidth().height(3.dp)) {
                if (progressVisible) {
                    LinearProgressIndicator(
                        progress = { (progressValue.coerceIn(0, 100)) / 100f },
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        if (!silentMode && showBenbuMfaHint) {
            Text(
                text = stringResource(R.string.benbu_mfa_trust_device_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).also(onWebViewReady)
                }
            )
            // Plan D: Native MFA overlay
            if (mfaState.visible) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ComposeColor.Black.copy(alpha = 0.5f)),
                    contentAlignment = androidx.compose.ui.Alignment.BottomCenter
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = mfaState.promptTitle.ifBlank { "多因子认证" },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (mfaState.verifyMethod.isNotBlank()) {
                                Text(
                                    text = "当前方式：${mfaState.verifyMethod}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (mfaState.promptText.isNotBlank()) {
                                Text(
                                    text = mfaState.promptText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (mfaState.hasVisibleInput) {
                                OutlinedTextField(
                                    value = mfaInputValue,
                                    onValueChange = onMfaInputChange,
                                    label = { Text(mfaState.inputPlaceholder) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = if (mfaState.inputType.equals("password", ignoreCase = true)) {
                                            KeyboardType.Password
                                        } else {
                                            KeyboardType.Number
                                        }
                                    ),
                                    visualTransformation = if (mfaState.inputType.equals("password", ignoreCase = true)) {
                                        PasswordVisualTransformation()
                                    } else {
                                        VisualTransformation.None
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (mfaState.canSendCode) {
                                Button(
                                    onClick = onMfaSendCode,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                ) {
                                    Text("获取验证码")
                                }
                            }
                            mfaError?.let { err ->
                                Text(
                                    text = err,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (mfaState.verifyMethodType != "3") {
                                TextButton(
                                    onClick = onMfaSwitchMethod,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("切换到手机验证码")
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = onMfaDismiss) {
                                    Text("取消")
                                }
                                Button(
                                    onClick = onMfaSubmit,
                                    enabled = !mfaState.hasVisibleInput || mfaInputValue.isNotBlank()
                                ) {
                                    Text("确认登录")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
