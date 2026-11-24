package cn.limpu.hita.ui.main.homework

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import cn.limpu.hita.R
import cn.limpu.hita.data.repository.grader.GraderRepository
import cn.limpu.hita.data.source.preference.TodoLoginCredential
import cn.limpu.hita.data.source.preference.TodoLoginProvider
import cn.limpu.hita.data.source.preference.TodoPlatformCredentialStore
import cn.limpu.hita.data.source.web.eas.HttpCampusReachabilityProbe
import cn.limpu.hita.data.source.web.grader.GraderCasLoginPolicy
import cn.limpu.hita.data.source.web.grader.GraderSessionPolicy
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaPasswordField
import cn.limpu.hita.ui.eas.login.ShenzhenTrustPortalAutoLogin
import cn.limpu.hita.BuildConfig
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** An official browser session is only a candidate until Grader verifies its account. */
@AndroidEntryPoint
class GraderLoginActivity : AppCompatActivity() {
    @Inject lateinit var repository: GraderRepository
    @Inject lateinit var credentialStore: TodoPlatformCredentialStore

    /** 只回答「直连通不通」，超时 2.5s；不通即按校外处理。 */
    private val reachability = HttpCampusReachabilityProbe()

    private var username by mutableStateOf("")
    private var password by mutableStateOf("")
    private var remember by mutableStateOf(false)
    private var hasRemembered by mutableStateOf(false)
    private var edited = false
    private var showingRemembered = false
    private var forgetting by mutableStateOf(false)

    private var attempt by mutableStateOf<BrowserAttempt?>(null)
    private var officialPage by mutableStateOf(false)
    private var loading by mutableStateOf(false)
    private var verifying by mutableStateOf(false)
    private var error by mutableStateOf<Int?>(null)
    private var navigationTimeout: Job? = null
    private var verification: Job? = null

    /** 每次重开登录流程递增；用来作废还在探测入口的那一次尝试。 */
    private var loginGeneration = 0

    private class BrowserAttempt(
        val view: WebView,
        val cookies: CookieManager,
        val isolated: Boolean,
        val entry: GraderSessionPolicy.Entry,
        val username: String,
        val password: String,
        val expectedAccountId: String?,
        val proofToken: String?,
    ) {
        var navigation = 0
        var failedNavigation: Int? = null
        var formNavigation: Int? = null
        var formUrl: String? = null
        var accountTabOpened = false
        var exactSubmission = false
        var lastCookie: String? = null
        var trustPortalNavigation: Int? = null
        var trustPortalAttempts = 0
        var forceWebModeApplied = false
        var ssoNavigationStarted = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        title = getString(R.string.grader_login_title)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContent {
            HitaComposeTheme {
                CompositionLocalProvider(LocalSaveableStateRegistry provides null) { LoginContent() }
            }
        }

        lifecycleScope.launch {
            val remembered = credentialStore.read(TodoLoginProvider.GRADER) ?: return@launch
            if (isFinishing) return@launch
            hasRemembered = true
            if (edited || attempt != null) return@launch
            username = remembered.username
            password = remembered.password
            remember = true
            showingRemembered = true
            // A recreated activity can prefill again, but must not trigger another automatic attempt.
            if (savedInstanceState == null) beginLogin(remembered.accountId)
        }
    }

    @Composable
    private fun LoginContent() {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Text(
                    stringResource(R.string.grader_login_title),
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val message = error ?: if (loading) R.string.grader_loading else null
                if (message != null) {
                    Text(
                        stringResource(message),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val current = attempt
                    if (current != null) key(current) {
                        AndroidView(
                            factory = { context ->
                                FrameLayout(context).apply {
                                    addView(current.view, FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                                    ))
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (!officialPage) Surface(Modifier.fillMaxSize()) { CredentialFields() }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { finish() }) { Text(stringResource(R.string.homework_cancel)) }
                    if (officialPage) {
                        TextButton(onClick = { resetBrowser() }, enabled = !verifying) {
                            Text(stringResource(R.string.todo_platform_edit_login))
                        }
                        val current = attempt
                        if (current?.lastCookie != null && error != null) {
                            TextButton(onClick = { retryCookie(current) }, enabled = !loading) {
                                Text(stringResource(R.string.homework_retry))
                            }
                        }
                    } else {
                        Button(onClick = { beginLogin() }, enabled = canSignIn()) {
                            Text(stringResource(R.string.homework_platform_sign_in))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CredentialFields() {
        val enabled = !loading && !forgetting
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = username,
                onValueChange = { markEdited(); username = it },
                label = { Text(stringResource(R.string.todo_platform_username)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            HitaPasswordField(
                value = password,
                onValueChange = { markEdited(); password = it },
                label = { Text(stringResource(R.string.password)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                imeAction = ImeAction.Done,
                keyboardActions = KeyboardActions(onDone = { if (canSignIn()) beginLogin() }),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = remember,
                    enabled = enabled,
                    onCheckedChange = {
                        edited = true
                        showingRemembered = false
                        remember = it
                    },
                )
                Text(stringResource(R.string.todo_platform_remember_password))
            }
            if (hasRemembered) {
                TextButton(onClick = ::forgetCredential, enabled = enabled) {
                    Text(stringResource(R.string.todo_platform_forget_password))
                }
            }
        }
    }

    private fun canSignIn(): Boolean =
        !loading && !forgetting && username.isNotBlank() && password.isNotEmpty() && !isFinishing

    private fun markEdited() {
        edited = true
        if (showingRemembered) {
            showingRemembered = false
            remember = false
        }
        error = null
    }

    private fun forgetCredential() {
        edited = true
        forgetting = true
        lifecycleScope.launch {
            if (credentialStore.forget(TodoLoginProvider.GRADER)) {
                hasRemembered = false
                if (showingRemembered) password = ""
                showingRemembered = false
                remember = false
                error = null
            } else {
                error = R.string.todo_platform_credential_error
            }
            forgetting = false
        }
    }

    /**
     * 先选入口再开浏览器。
     *
     * `grader.hitsz.edu.cn` 只在内网可达（公网 DNS 给的是 `10.249.12.224`），校外必须走
     * aTrust 网关域。判据与威海的入口选择一致：探测不通就回落网关，宁可多走一层，
     * 也不把用户挡在门外。
     */
    private fun beginLogin(expectedAccountId: String? = null) {
        if (!canSignIn()) return
        resetBrowser()
        currentFocus?.clearFocus()
        loading = true
        // 探测期间用户可能点了「编辑登录」或取消：那一侧的 resetBrowser 会推进代号，
        // 让这次尚未落地的探测作废，而不是过一会儿又弹出一个浏览器。
        val generation = ++loginGeneration
        lifecycleScope.launch {
            val entry = chooseEntry()
            if (isFinishing || generation != loginGeneration) return@launch
            openBrowser(entry, expectedAccountId)
        }
    }

    private suspend fun chooseEntry(): GraderSessionPolicy.Entry {
        val direct = GraderSessionPolicy.Entry.DIRECT
        val reachable = reachability.isReachable("${direct.origin}/home")
        trace("probe direct reachable=$reachable")
        return if (reachable) direct else GraderSessionPolicy.Entry.GATEWAY
    }

    private fun openBrowser(entry: GraderSessionPolicy.Entry, expectedAccountId: String?) {
        val view = try {
            WebView(this)
        } catch (_: RuntimeException) {
            loading = false
            error = R.string.grader_login_error
            return
        } catch (_: LinkageError) {
            loading = false
            error = R.string.grader_login_error
            return
        }
        val current = try {
            val isolated = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
            // Set the profile before any operation that can initialize the browser context.
            val cookies = if (isolated) {
                WebViewCompat.setProfile(view, GRADER_PROFILE)
                WebViewCompat.getProfile(view).cookieManager
            } else CookieManager.getInstance()
            val proofToken = if (remember && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                UUID.randomUUID().toString()
            } else null
            BrowserAttempt(view, cookies, isolated, entry, username.trim(), password, expectedAccountId, proofToken).also {
                configureBrowser(it)
            }
        } catch (_: RuntimeException) {
            view.destroy()
            loading = false
            error = R.string.grader_login_error
            return
        }
        attempt = current
        waitForNavigation(current)
        try {
            if (expectedAccountId != null) {
                startNavigation(current)
            } else if (current.isolated) {
                // Fresh manual credentials must not silently pick up an old Grader-profile CAS login.
                // This profile belongs only to Grader; do not clear the app's normal WebView profile.
                current.cookies.removeAllCookies { startNavigation(current) }
            } else {
                current.cookies.setCookie(
                    entry.origin, "sessionid=; Path=/; Max-Age=0; Secure; HttpOnly",
                ) { startNavigation(current) }
            }
        } catch (_: RuntimeException) {
            failBrowser(current)
        }
    }

    private fun startNavigation(current: BrowserAttempt) {
        if (!isCurrent(current)) return
        try {
            current.view.loadUrl(current.entry.startUrl)
        } catch (_: RuntimeException) {
            failBrowser(current)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureBrowser(current: BrowserAttempt) = with(current.view) {
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // aTrust 门户页面会内嵌 http 子资源，校外入口必须放宽；直连保持最严格。
        settings.mixedContentMode = if (current.entry == GraderSessionPolicy.Entry.GATEWAY) {
            WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        } else {
            WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        @Suppress("DEPRECATION")
        settings.saveFormData = false
        current.cookies.setAcceptCookie(true)
        current.cookies.setAcceptThirdPartyCookies(this, false)
        if (current.proofToken != null) {
            val casOrigins = GraderCasLoginPolicy.casOrigins(current.entry)
            WebViewCompat.addWebMessageListener(
                this, GraderCasLoginPolicy.BRIDGE_NAME, casOrigins,
            ) { _, message, origin, mainFrame, _ ->
                if (isCurrent(current) && mainFrame && origin.toString() in casOrigins) {
                    val formUrl = current.formUrl ?: return@addWebMessageListener
                    val proof = message.data ?: return@addWebMessageListener
                    val prefix = "${current.proofToken}:matched:"
                    when {
                        proof == "${current.proofToken}:changed" -> current.exactSubmission = false
                        proof.startsWith(prefix) -> current.exactSubmission = GraderCasLoginPolicy.matchesFormAction(
                            formUrl, proof.substring(prefix.length), current.entry,
                        )
                    }
                }
            }
        }
        webViewClient = GraderBrowserClient(current)
    }

    private inner class GraderBrowserClient(private val current: BrowserAttempt) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return request.url.scheme != "https"
            return rejectNavigation(current, request.url.toString())
        }

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            rejectNavigation(current, url)

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (!isCurrent(current)) return
            if (rejectNavigation(current, url)) return
            // 校外链路多跳，出问题时首先要看完整链条（debug 包才输出）。
            trace("nav[${current.navigation}] ${url.take(300)}")
            current.navigation += 1
            error = null
            waitForNavigation(current)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (!isCurrent(current) || view.url != url || !GraderCasLoginPolicy.mayNavigate(url, current.entry) ||
                current.failedNavigation == current.navigation
            ) return
            trace("finish ${url.take(300)}")
            if (GraderSessionPolicy.isCandidatePage(url, current.entry)) {
                verifyCandidate(current)
            } else {
                inspectOfficialPage(current, url)
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) failBrowser(current)
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (!isCurrent(current) || !request.isForMainFrame) return
            trace("http ${errorResponse.statusCode}: ${request.url.toString().take(240)}")
            current.failedNavigation = current.navigation
            current.exactSubmission = false
            showOfficial(R.string.grader_login_error)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            failBrowser(current)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            failBrowser(current)
            return true
        }
    }

    private fun rejectNavigation(current: BrowserAttempt, url: String): Boolean {
        if (!isCurrent(current)) return true
        if (GraderCasLoginPolicy.mayNavigate(url, current.entry)) return false
        // Defer destruction when the navigation callback is still on the stack.
        trace("reject ${url.take(240)}")
        current.view.post { failBrowser(current) }
        return true
    }

    private fun inspectOfficialPage(current: BrowserAttempt, url: String) {
        // 网关先把请求送到 aTrust 的客户端探测页：不置 forceWeb 它不会前进。
        if (applyTrustForceWebMode(current, url)) return
        // 校外先过 aTrust 门户：门户登录页要勾协议再点「登录」才会跳统一认证。
        if (current.entry == GraderSessionPolicy.Entry.GATEWAY && ShenzhenTrustPortalAutoLogin.shouldAutoLogin(url)) {
            advanceTrustPortal(current)
            return
        }
        val service = GraderCasLoginPolicy.loginService(url, current.entry)
        val previousForm = current.formNavigation
        if (previousForm != null) {
            if (previousForm != current.navigation && GraderCasLoginPolicy.isLoginEndpoint(url, current.entry)) {
                // A subsequent account form may be a retry with different inputs. Never attribute
                // that login to the credential we filled into the original document.
                current.exactSubmission = false
                current.formUrl = null
            }
            if (previousForm != current.navigation) showOfficial()
            return
        }
        if (service == null) {
            if (current.entry == GraderSessionPolicy.Entry.GATEWAY && url.contains("/authserver/")) {
                // 看得见统一认证页却认不出它的 service：把实际地址打出来，下一次就能对齐规则。
                trace("CAS unmatched: ${url.take(400)}")
            }
            showOfficial()
            return
        }
        val navigation = current.navigation
        current.formNavigation = navigation // Reserve once, before JavaScript can click.
        current.formUrl = url
        current.view.evaluateJavascript(
            GraderCasLoginPolicy.fillScript(service, current.username, current.password, current.proofToken, current.entry),
        ) { result ->
            if (!isCurrent(current) || current.navigation != navigation) return@evaluateJavascript
            trace("CAS fill: $result")
            val accountTab = if (result == "\"accountTab\"" && !current.accountTabOpened) {
                GraderCasLoginPolicy.accountTabUrl(url, current.entry)
            } else null
            if (accountTab != null) {
                current.accountTabOpened = true
                // The probe found no form and did not fill or submit anything.
                current.formNavigation = null
                current.formUrl = null
                current.view.loadUrl(accountTab)
            } else {
                showOfficial()
            }
        }
    }

    /**
     * aTrust 的 shortcut 页默认探测本机客户端；WebView 里没有客户端，只能置 forceWeb
     * 让它改走网页认证。只注入一次，并重载同一个签名 URL（令牌与回跳地址都在 query 里）。
     */
    private fun applyTrustForceWebMode(current: BrowserAttempt, url: String): Boolean {
        if (current.entry != GraderSessionPolicy.Entry.GATEWAY || current.forceWebModeApplied) return false
        if (!ShenzhenTrustPortalAutoLogin.shouldForceWebMode(url)) return false
        current.forceWebModeApplied = true
        trace("forceWeb applied")
        current.view.evaluateJavascript(ShenzhenTrustPortalAutoLogin.buildForceWebScript()) {
            if (isCurrent(current)) current.view.loadUrl(url)
        }
        return true
    }

    /**
     * aTrust 门户登录页是 SPA，脚本自带可见性判断，这里只做有界重试：
     * 页面渲染慢时再试几次，始终只操作当前这张官方页；重试用尽就交还用户手动操作。
     */
    private fun advanceTrustPortal(current: BrowserAttempt) {
        if (!isCurrent(current)) return
        if (current.trustPortalNavigation != current.navigation) {
            current.trustPortalNavigation = current.navigation
            current.trustPortalAttempts = 0
        }
        if (current.trustPortalAttempts >= TRUST_PORTAL_AUTO_LOGIN_ATTEMPTS) {
            showOfficial()
            return
        }
        val navigation = current.navigation
        current.trustPortalAttempts += 1
        trace("portal attempt ${current.trustPortalAttempts}")
        current.view.postDelayed({
            if (!isCurrent(current) || current.navigation != navigation) return@postDelayed
            current.view.evaluateJavascript(ShenzhenTrustPortalAutoLogin.buildLoginScript()) { }
            if (current.trustPortalAttempts < TRUST_PORTAL_AUTO_LOGIN_ATTEMPTS) {
                current.view.postDelayed({
                    if (isCurrent(current) && current.navigation == navigation) advanceTrustPortal(current)
                }, TRUST_PORTAL_AUTO_LOGIN_RETRY_DELAY_MS)
            } else {
                showOfficial()
            }
        }, TRUST_PORTAL_AUTO_LOGIN_DELAY_MS)
    }

    /**
     * 浏览器已经回到 Grader 的页面上，但 Grader 自己还没有会话：走一次它的 SSO 入口。
     *
     * 校外 aTrust 只解决网络可达性，Grader 的业务会话仍要靠它自己的 OAuth 回调建立。
     * aTrust 刚用统一认证登录过，CAS 会话还在，这一跳通常无感通过。只走一次，
     * 避免登录真的失败时在门户与 SSO 之间来回重定向。
     */
    private fun startSsoNavigation(current: BrowserAttempt): Boolean {
        if (current.ssoNavigationStarted) return false
        current.ssoNavigationStarted = true
        trace("sso navigation")
        current.view.loadUrl("${current.entry.origin}/api/login/sso")
        return true
    }

    /**
     * 诊断轨迹。
     *
     * vivo 设备默认关闭应用日志（`persist.sys.vivolog.state=off`，且改不了），Logcat 里
     * 收不到 `Log.d`，所以 debug 包额外写一份到应用私有目录：
     * `adb shell run-as cn.limpu.hita.diag cat files/grader-login-trace.log`
     */
    private fun trace(message: String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val file = File(filesDir, TRACE_FILE)
            if (file.length() > TRACE_MAX_BYTES) file.delete()
            file.appendText("${TRACE_TIME_FORMAT.format(Date())} $message\n")
        }
    }

    private fun retryCookie(current: BrowserAttempt) {
        current.lastCookie = null
        verifyCandidate(current)
    }

    private fun verifyCandidate(current: BrowserAttempt) {
        val url = current.view.url ?: return
        if (!isCurrent(current) || verifying || !GraderSessionPolicy.isCandidatePage(url, current.entry)) return
        val rawCookie = current.cookies.getCookie(current.entry.origin).orEmpty()
        val cookie = GraderSessionPolicy.cookieHeader(current.entry, rawCookie)
        if (cookie == null) {
            trace("verify: no session cookie on ${current.entry.name}")
            if (startSsoNavigation(current)) return
            showOfficial(R.string.grader_login_error)
            return
        }
        if (cookie == current.lastCookie) {
            trace("verify: unchanged cookie")
            showOfficial(R.string.grader_login_error)
            return
        }
        current.lastCookie = cookie
        val submittedWithCurrentInput = current.exactSubmission
        stopNavigationTimeout()
        loading = true
        verifying = true
        error = null
        verification = lifecycleScope.launch {
            try {
                val connected = repository.connect(cookie, current.expectedAccountId, current.entry)
                val accountId = repository.state.value?.takeUnless { it.needsLogin }?.accountId
                trace("verify: connected=$connected account=$accountId")
                if (!isCurrent(current)) return@launch
                if (!connected || accountId.isNullOrBlank() ||
                    (current.expectedAccountId != null && current.expectedAccountId != accountId)
                ) {
                    if (startSsoNavigation(current)) return@launch
                    showOfficial(R.string.grader_login_error)
                    return@launch
                }
                if (submittedWithCurrentInput) {
                    val credential = TodoLoginCredential(current.username, current.password, accountId)
                    if (!credentialStore.save(TodoLoginProvider.GRADER, credential)) {
                        Toast.makeText(this@GraderLoginActivity, R.string.todo_platform_credential_error, Toast.LENGTH_LONG).show()
                    }
                }
                setResult(Activity.RESULT_OK)
                finish()
            } finally {
                if (isCurrent(current)) {
                    verifying = false
                    loading = false
                }
            }
        }
    }

    private fun waitForNavigation(current: BrowserAttempt) {
        if (verifying) return
        loading = true
        if (navigationTimeout?.isActive == true) return
        navigationTimeout = lifecycleScope.launch {
            delay(NAVIGATION_TIMEOUT_MS)
            failBrowser(current)
        }
    }

    private fun stopNavigationTimeout() {
        navigationTimeout?.cancel()
        navigationTimeout = null
    }

    private fun showOfficial(message: Int? = null) {
        stopNavigationTimeout()
        officialPage = true
        if (!verifying) loading = false
        error = message
    }

    private fun failBrowser(current: BrowserAttempt) {
        if (isCurrent(current)) resetBrowser(R.string.grader_login_error)
    }

    private fun isCurrent(current: BrowserAttempt): Boolean = !isFinishing && attempt === current

    private fun resetBrowser(message: Int? = null) {
        stopNavigationTimeout()
        loginGeneration++
        verification?.cancel()
        verification = null
        val current = attempt
        attempt = null
        officialPage = false
        loading = false
        verifying = false
        error = message
        current?.view?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
    }

    override fun onDestroy() {
        resetBrowser()
        username = ""
        password = ""
        super.onDestroy()
    }

    private companion object {
        const val GRADER_PROFILE = "hita_grader_login"
        const val NAVIGATION_TIMEOUT_MS = 25_000L

        /** aTrust 门户登录页是 SPA，等它渲染出「登录」按钮再自动点击。 */
        const val TRUST_PORTAL_AUTO_LOGIN_DELAY_MS = 900L

        /** 勾选协议后按钮解禁有一个 tick 的延迟，重试次数与 EAS 的 aTrust 流程一致。 */
        const val TRUST_PORTAL_AUTO_LOGIN_ATTEMPTS = 4
        const val TRUST_PORTAL_AUTO_LOGIN_RETRY_DELAY_MS = 700L

        /** 设备侧应用日志可能被关闭，debug 包的诊断轨迹落在应用私有目录。 */
        const val TRACE_FILE = "grader-login-trace.log"
        const val TRACE_MAX_BYTES = 256L * 1024
        val TRACE_TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}
