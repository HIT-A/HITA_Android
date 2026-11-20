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
import cn.limpu.hita.data.source.web.grader.GraderCasLoginPolicy
import cn.limpu.hita.data.source.web.grader.GraderSessionPolicy
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaPasswordField
import dagger.hilt.android.AndroidEntryPoint
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

    private class BrowserAttempt(
        val view: WebView,
        val cookies: CookieManager,
        val isolated: Boolean,
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

    private fun beginLogin(expectedAccountId: String? = null) {
        if (!canSignIn()) return
        resetBrowser()
        currentFocus?.clearFocus()
        val view = try {
            WebView(this)
        } catch (_: RuntimeException) {
            error = R.string.grader_login_error
            return
        } catch (_: LinkageError) {
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
            BrowserAttempt(view, cookies, isolated, username.trim(), password, expectedAccountId, proofToken).also {
                configureBrowser(it)
            }
        } catch (_: RuntimeException) {
            view.destroy()
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
                    GraderSessionPolicy.ORIGIN, "sessionid=; Path=/; Max-Age=0; Secure; HttpOnly",
                ) { startNavigation(current) }
            }
        } catch (_: RuntimeException) {
            failBrowser(current)
        }
    }

    private fun startNavigation(current: BrowserAttempt) {
        if (!isCurrent(current)) return
        try {
            current.view.loadUrl(GraderSessionPolicy.LOGIN_URL)
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
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        @Suppress("DEPRECATION")
        settings.saveFormData = false
        current.cookies.setAcceptCookie(true)
        current.cookies.setAcceptThirdPartyCookies(this, false)
        if (current.proofToken != null) {
            WebViewCompat.addWebMessageListener(
                this, GraderCasLoginPolicy.BRIDGE_NAME, setOf(GraderCasLoginPolicy.CAS_ORIGIN),
            ) { _, message, origin, mainFrame, _ ->
                if (isCurrent(current) && mainFrame && origin.toString() == GraderCasLoginPolicy.CAS_ORIGIN) {
                    val formUrl = current.formUrl ?: return@addWebMessageListener
                    val proof = message.data ?: return@addWebMessageListener
                    val prefix = "${current.proofToken}:matched:"
                    when {
                        proof == "${current.proofToken}:changed" -> current.exactSubmission = false
                        proof.startsWith(prefix) -> current.exactSubmission =
                            GraderCasLoginPolicy.matchesFormAction(formUrl, proof.substring(prefix.length))
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
            current.navigation += 1
            error = null
            waitForNavigation(current)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (!isCurrent(current) || view.url != url || !GraderCasLoginPolicy.mayNavigate(url) ||
                current.failedNavigation == current.navigation
            ) return
            if (GraderSessionPolicy.isCandidatePage(url)) {
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
        if (GraderCasLoginPolicy.mayNavigate(url)) return false
        // Defer destruction when the navigation callback is still on the stack.
        current.view.post { failBrowser(current) }
        return true
    }

    private fun inspectOfficialPage(current: BrowserAttempt, url: String) {
        val service = GraderCasLoginPolicy.loginService(url)
        val previousForm = current.formNavigation
        if (previousForm != null) {
            if (previousForm != current.navigation && GraderCasLoginPolicy.isLoginEndpoint(url)) {
                // A subsequent account form may be a retry with different inputs. Never attribute
                // that login to the credential we filled into the original document.
                current.exactSubmission = false
                current.formUrl = null
            }
            if (previousForm != current.navigation) showOfficial()
            return
        }
        if (service == null) {
            showOfficial()
            return
        }
        val navigation = current.navigation
        current.formNavigation = navigation // Reserve once, before JavaScript can click.
        current.formUrl = url
        current.view.evaluateJavascript(
            GraderCasLoginPolicy.fillScript(service, current.username, current.password, current.proofToken),
        ) { result ->
            if (!isCurrent(current) || current.navigation != navigation) return@evaluateJavascript
            val accountTab = if (result == "\"accountTab\"" && !current.accountTabOpened) {
                GraderCasLoginPolicy.accountTabUrl(url)
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

    private fun retryCookie(current: BrowserAttempt) {
        current.lastCookie = null
        verifyCandidate(current)
    }

    private fun verifyCandidate(current: BrowserAttempt) {
        val url = current.view.url ?: return
        if (!isCurrent(current) || verifying || !GraderSessionPolicy.isCandidatePage(url)) return
        val rawCookie = current.cookies.getCookie(GraderSessionPolicy.ORIGIN).orEmpty()
        val cookie = GraderSessionPolicy.sessionCookie(rawCookie)
        if (cookie == null) {
            showOfficial(R.string.grader_login_error)
            return
        }
        if (cookie == current.lastCookie) {
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
                val connected = repository.connect(cookie, current.expectedAccountId)
                val accountId = repository.state.value?.takeUnless { it.needsLogin }?.accountId
                if (!isCurrent(current)) return@launch
                if (!connected || accountId.isNullOrBlank() ||
                    (current.expectedAccountId != null && current.expectedAccountId != accountId)
                ) {
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
    }
}
