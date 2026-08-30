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
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.limpu.hita.data.model.eas.EASToken
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
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

    protected val viewModel: WebViewLoginViewModel by viewModels()

    companion object {
        const val EXTRA_SILENT_MODE = "silent_mode"
        const val EXTRA_CAMPUS = "campus"
        const val EXTRA_STUDENT_TYPE = "student_type"

        // 校园网络URL常量
        private object CampusUrls {
            // 本部校区URL
            private const val BENBU_BASE = "http://i-hit-edu-cn.ivpn.hit.edu.cn:1080"
            private const val JWTS_BASE = "http://jwts-hit-edu-cn.ivpn.hit.edu.cn:1080"

            // 威海校区URL
            private const val WEIHAI_BASE = "https://webvpn.hitwh.edu.cn"
            private const val WEIHAI_EAS_PREFIX = "$WEIHAI_BASE/http/77726476706e69737468656265737421fae0558f693861446900c7a99c406d3667"

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

            const val EELABINFO_URL = "http://eelabinfo-hit-edu-cn.ivpn.hit.edu.cn:1080"
        }

        private const val COOKIE_RETRY_COUNT = 30
        private const val COOKIE_RETRY_DELAY_MS = 500L
        private const val SILENT_TIMEOUT_MS = 18000L
        private const val MFA_DETECTION_INITIAL_DELAY_MS = 800L
        private const val MFA_DETECTION_RETRY_DELAY_MS = 700L
        private const val MFA_DETECTION_MAX_RETRIES = 4
        private const val MFA_NATIVE_OVERLAY_ENABLED = false
        private const val MFA_METHOD_SWITCH_POLL_DELAY_MS = 400L
        private const val MFA_METHOD_SWITCH_MAX_POLLS = 75
        private const val SHENZHEN_AUTO_ADVANCE_RETRY_DELAY_MS = 300L
        private const val SHENZHEN_AUTO_ADVANCE_MAX_RETRIES = 12
        private const val SHENZHEN_DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/134.0.0.0 Safari/537.36"
        private val BENBU_REQUIRED_COOKIES = setOf("JSESSIONID", "HIT")
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
    private var autoOpeningJwts = false
    private var silentMode = false
    private lateinit var config: CampusWebConfig
    private var navigatingToEelab = false
    private var collectedEasCookies: Map<String, String>? = null
    private var eelabTokenFetching = false
    private var lastPageHadError = false
    private lateinit var webView: WebView
    private var progressVisible by mutableStateOf(false)
    private var progressValue by mutableIntStateOf(0)

    private var mfaState by mutableStateOf(MfaOverlayState())
    private var mfaError by mutableStateOf<String?>(null)
    private var mfaInputValue by mutableStateOf("")
    private var mfaDetectionGeneration = 0
    private var shenzhenMobileUserAgent = ""
    private var shenzhenDesktopUserAgentApplied = false
    private var shenzhenForceWebModeApplied = false
    private var shenzhenPreferredStudentType = ShenzhenWebAutoLogin.UNDERGRAD
    private var shenzhenAutoAdvanceGeneration = 0
    private var shenzhenAutoAdvanceScheduledGeneration = -1
    private var shenzhenUnifiedLoginClicked = false
    private var shenzhenRoleSelectionClicked = false
    private var shenzhenReauthenticationStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val campus = runCatching {
            EASToken.Campus.valueOf(
                intent?.getStringExtra(EXTRA_CAMPUS) ?: EASToken.Campus.BENBU.name
            )
        }.getOrDefault(EASToken.Campus.BENBU)
        config = configFor(campus)
        silentMode = intent?.getBooleanExtra(EXTRA_SILENT_MODE, false) == true
        shenzhenPreferredStudentType = ShenzhenWebAutoLogin.normalizeStudentType(
            intent?.getStringExtra(EXTRA_STUDENT_TYPE)
        )

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
        if (silentMode) {
            webView.postDelayed({
                if (!finished) {
                    LogUtils.w( "silent web login timeout campus=${config.campus}")
                    finishWithCancelledResult()
                }
            }, SILENT_TIMEOUT_MS)
        }
        LogUtils.d( "load login url=${config.loginUrl} campus=${config.campus}")
        webView.loadUrl(config.loginUrl)
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    private fun configFor(campus: EASToken.Campus): CampusWebConfig {
        return when (campus) {
            EASToken.Campus.BENBU -> CampusWebConfig(
                campus = campus,
                loginUrl = CampusUrls.BENBU_LOGIN,
                jwtsUrl = CampusUrls.BENBU_JWTS,
                cookieProbeUrls = CampusUrls.BENBU_PROBE_URLS
            )
            EASToken.Campus.WEIHAI -> CampusWebConfig(
                campus = campus,
                loginUrl = CampusUrls.WEIHAI_LOGIN,
                jwtsUrl = CampusUrls.WEIHAI_JWTS,
                cookieProbeUrls = CampusUrls.WEIHAI_PROBE_URLS
            )
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
            if (config.campus == EASToken.Campus.SHENZHEN) {
                // The aTrust mobile route requires its native UEM client. Use its browser
                // route here, then switch back to the mobile UA on the university CAS page.
                shenzhenMobileUserAgent = settings.userAgentString
                settings.userAgentString = SHENZHEN_DESKTOP_USER_AGENT
                shenzhenDesktopUserAgentApplied = true
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                LogUtils.d("using browser user agent and software layer for Shenzhen aTrust portal")
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
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                popup: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                val target = request.url?.toString().orEmpty()
                                if (!forwarded && target.startsWith("http")) {
                                    forwarded = true
                                    this@WebViewLoginActivity.webView.loadUrl(target)
                                    popup.post { popup.destroy() }
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
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    shenzhenAutoAdvanceGeneration++
                    if (config.campus == EASToken.Campus.SHENZHEN) {
                        LogUtils.d(
                            "SHENZHEN_AUTO navigation-start generation=$shenzhenAutoAdvanceGeneration " +
                                "url=${safeUrl(url)}"
                        )
                    }
                    if (view != null && url != null && switchShenzhenUserAgentForNavigation(view, url)) {
                        return
                    }
                    mfaDetectionGeneration++
                    mfaState = MfaOverlayState()
                    mfaError = null
                    mfaInputValue = ""
                    progressVisible = true
                    progressValue = 0
                    if (url != null && isAuthenticationPage(url)) {
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

                    // Handle eelabinfo navigation for JWT token
                    if (navigatingToEelab) {
                        if (url.contains("eelabinfo") && !url.contains("ids.hit.edu.cn") && !eelabTokenFetching) {
                            eelabTokenFetching = true
                            LogUtils.d("eelabinfo page loaded, fetching JWT token...")
                            webView.postDelayed({ fetchEelabTokenViaHttp() }, 1000)
                        } else if (!url.contains("eelabinfo") && !eelabTokenFetching) {
                            LogUtils.w("navigated away from eelabinfo, finishing without token, url=$url")
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
                    if (ensureShenzhenDesktopUserAgentForProxy(view, url)) {
                        return
                    }
                    if (isMfaPage(url)) {
                        applyMfaViewportUnitWorkaround(view)
                        scheduleMfaDetection(view, url)
                    } else if (isTrustPortalPage(url)) {
                        applyTrustPortalViewportUnitWorkaround(view)
                    } else if (isShenzhenProxyJwPage(url)) {
                        applyShenzhenJwDesktopViewportWorkaround(view)
                        scheduleShenzhenAutoAdvance(view, url)
                    }

                    when {
                        isPortalHomePage(url) -> {
                            LogUtils.d("portal home detected, auto open jwts campus=${config.campus}")
                            autoOpenJwts(view)
                        }
                        isIvpnRedirectPage(url) -> {
                            autoOpeningJwts = false
                            stopCookiePolling()
                            if (silentMode) {
                                LogUtils.d("ivpn redirect page in silent mode, need user interaction")
                                finishWithCancelledResult()
                            } else {
                                LogUtils.d("ivpn redirect page, waiting for CAS login: path=${uri.path}")
                            }
                        }
                        isAuthenticationPage(url) -> {
                            autoOpeningJwts = false
                            stopCookiePolling()
                            if (silentMode) {
                                LogUtils.d("authentication page in silent mode, need user interaction")
                                finishWithCancelledResult()
                            } else {
                                LogUtils.d("authentication page detected, waiting for user interaction")
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
                        else -> {
                            LogUtils.d("unhandled page: host=${uri.host} path=${uri.path}")
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
                    LogUtils.w( "onReceivedError url=${request?.url} code=${error?.errorCode} desc=${error?.description} campus=${config.campus}")
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
        val shouldProbe = host.contains("ivpn.hit.edu.cn") ||
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

    private fun safeUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val uri = Uri.parse(url)
        return buildString {
            append(uri.scheme.orEmpty())
            append("://")
            append(uri.host.orEmpty())
            if (uri.port != -1) append(":").append(uri.port)
            append(uri.path.orEmpty())
        }
    }

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
            EASToken.Campus.BENBU -> uri.host == "i-hit-edu-cn.ivpn.hit.edu.cn" &&
                (normalizedPath == "/portal/home" || normalizedPath == "/portal")
            EASToken.Campus.WEIHAI -> uri.host == "webvpn.hitwh.edu.cn" && (normalizedPath.isBlank() || normalizedPath == "/portal/home")
            EASToken.Campus.SHENZHEN -> ShenzhenWebAutoLogin.isProxyRoot(
                url,
                CampusUrls.SHENZHEN_PROXY_BASE
            )
        }
    }

    private fun isIvpnRedirectPage(url: String): Boolean {
        if (config.campus != EASToken.Campus.BENBU) return false
        val uri = Uri.parse(url)
        return uri.host == "ivpn.hit.edu.cn"
    }

    private fun isAuthenticationPage(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.path.orEmpty().contains("/authserver/login", ignoreCase = true)
    }

    private fun isMfaPage(url: String): Boolean {
        return Uri.parse(url).path.orEmpty().contains("/authserver/reAuthCheck/", ignoreCase = true)
    }

    private fun isTrustPortalPage(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.host.equals("trust.hitsz.edu.cn", ignoreCase = true) &&
            uri.path.orEmpty().startsWith("/portal", ignoreCase = true)
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
            if (
                finished ||
                generation != shenzhenAutoAdvanceGeneration ||
                !isShenzhenProxyJwPage(view.url.orEmpty())
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

    private fun switchShenzhenUserAgentForNavigation(view: WebView, url: String): Boolean {
        if (config.campus != EASToken.Campus.SHENZHEN) return false
        val host = Uri.parse(url).host.orEmpty()
        val proxyHost = Uri.parse(CampusUrls.SHENZHEN_PROXY_BASE).host.orEmpty()
        val needsDesktopUserAgent =
            host.equals(proxyHost, ignoreCase = true) ||
                host.equals("trust.hitsz.edu.cn", ignoreCase = true)

        if (!needsDesktopUserAgent && shenzhenDesktopUserAgentApplied) {
            shenzhenDesktopUserAgentApplied = false
            view.settings.userAgentString = shenzhenMobileUserAgent
            // Software rendering is required by the desktop aTrust route, but on the mobile CAS
            // form it offsets Chromium's native IME caret from the HTML input. Restore the normal
            // hardware-composited WebView layer before reloading the mobile page.
            view.setLayerType(View.LAYER_TYPE_NONE, null)
            LogUtils.d(
                "switching Shenzhen login navigation back to mobile user agent and default layer: " +
                    "host=$host"
            )
            view.stopLoading()
            view.post { if (!finished) view.loadUrl(url) }
            return true
        }

        if (needsDesktopUserAgent && !shenzhenDesktopUserAgentApplied) {
            shenzhenDesktopUserAgentApplied = true
            view.settings.userAgentString = SHENZHEN_DESKTOP_USER_AGENT
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            LogUtils.d("switching Shenzhen browser portal navigation to desktop user agent and software layer")
            view.stopLoading()
            view.post { if (!finished) view.loadUrl(url) }
            return true
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

    private fun ensureShenzhenDesktopUserAgentForProxy(view: WebView, url: String): Boolean {
        if (
            config.campus != EASToken.Campus.SHENZHEN ||
            !isShenzhenProxyJwPage(url) ||
            shenzhenDesktopUserAgentApplied
        ) {
            return false
        }
        shenzhenDesktopUserAgentApplied = true
        view.settings.userAgentString = SHENZHEN_DESKTOP_USER_AGENT
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        LogUtils.d("reloading Shenzhen proxy with desktop user agent")
        view.post { if (!finished) view.reload() }
        return true
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
            EASToken.Campus.BENBU -> {
                val isLoginPage = path.contains("login")
                val isOnJwtsDomain = host.contains("jwts")

                // Only check cookies on JWTS domain — IVPN portal pages can have
                // IVPN session cookies that don't represent an authenticated JWTS session
                if (isOnJwtsDomain && !isLoginPage) {
                    val cookies = collectCookies()
                    val hasRequiredCookies = cookies.containsKey("JSESSIONID") &&
                                             cookies.containsKey("HIT")

                    if (hasRequiredCookies) {
                        return true
                    }
                }

                (host.contains("jwts") || host.contains("hit.edu.cn")) &&
                (path.contains("kbcx") || path.contains("cjcx") || path.contains("kjscx") ||
                 path.contains("xswh") || path.contains("query") || path.contains("index"))
            }
            EASToken.Campus.WEIHAI -> {
                WebLoginSuccessPolicy.isWeihaiAuthenticatedPage(url, collectCookies())
            }
            EASToken.Campus.SHENZHEN -> {
                WebLoginSuccessPolicy.isShenzhenAuthenticatedPage(url, collectCookies())
            }
        }
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
        val hasJsessionid = cookies.containsKey("JSESSIONID")

        if (cookieRetryCount == 0 || cookieRetryCount % 10 == 0) {
            LogUtils.d("checkCookies: retry=$cookieRetryCount keys=${cookies.keys.sorted()} host=${Uri.parse(currentUrl).host}")
        }

        if (config.campus == EASToken.Campus.WEIHAI && hasVpnTicket && hasJsessionid) {
            fetchVpnEasCookies { vpnCookies ->
                val mergedCookies = LinkedHashMap(cookies)
                vpnCookies.forEach { (key, value) -> mergedCookies.putIfAbsent(key, value) }
                finishWithCookies(mergedCookies)
            }
            return
        }

        if (config.campus == EASToken.Campus.BENBU
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

    private fun handleSuccessPage() {
        if (finished) return

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
                // 威海校区：需要 VPN ticket + JSESSIONID
                val hasVpnTicket = hasWeihaiVpnTicket(cookies)
                val hasJsession = cookies.containsKey("JSESSIONID")
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
                    LogUtils.d("VPN cookie API response: $response")

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
                                    LogUtils.d("parsed VPN cookie: $key=$value")
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
        finished = true
        stopCookiePolling()

        val cookiesJson = JSONObject(cookies as Map<*, *>).toString()
        val intent = Intent().apply {
            putExtra("cookies", cookiesJson)
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
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    private fun finishWithCancelledResult() {
        if (finished) return
        finished = true
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
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        finished = true
        stopCookiePolling()
        if (::webView.isInitialized) {
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
