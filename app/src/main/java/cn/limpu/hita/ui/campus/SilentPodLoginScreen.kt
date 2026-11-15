package cn.limpu.hita.ui.campus

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import cn.limpu.hita.R
import cn.limpu.hita.data.source.web.campus.SilentPodParser
import cn.limpu.hita.data.source.web.campus.SilentPodSessionPolicy
import cn.limpu.hita.ui.design.HitaTheme

/**
 * 大学城图书馆空间预约的登录页（WebView）。
 *
 * 为什么是 WebView 而不是复用 CAS 表单：空间预约走图书馆自己的认证中心
 * （`cas.utszlib.edu.cn`），与本部教务、信息门户都不是同一套表单；本应用也没有
 * WebVPN 网关实现，因此把入口页交给用户手动登录，登录态从 CookieManager 取回。
 *
 * 判定交给纯函数 [SilentPodSessionPolicy]，这里只负责浏览器外壳与生命周期。
 */
@Composable
internal fun SilentPodLoginScreen(
    onCancel: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    val tokens = HitaTheme.tokens

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.silent_pod_login_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.silent_pod_login_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(R.string.silent_pod_login_cancel))
            }
        }
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (failed) {
            Text(
                text = stringResource(R.string.silent_pod_login_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = tokens.spacing.lg),
            )
        }
        Button(
            onClick = {
                failed = false
                if (hasSilentPodSession()) onLoggedIn()
            },
            modifier = Modifier.padding(horizontal = tokens.spacing.lg),
        ) {
            Text(stringResource(R.string.silent_pod_login_confirm))
        }
        SilentPodLoginWebView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onPageFinished = { loading = false },
            onError = { failed = true },
            onSessionReady = onLoggedIn,
        )
    }
}

/** 读取落定页内联的登录态；返回 JS 里 `null` 或字符串字面量。 */
private const val LOGIN_STATE_SCRIPT =
    "(function(){try{var s=window.__INITIAL_STATE__;" +
        "var v=s&&s.loginStatus?JSON.stringify(s.loginStatus):null;" +
        "return v||'null';}catch(e){return 'null';}})()"

/** 等 Cookie 落盘再读一次，避免落定页刚渲染完就判失败。 */
private const val COOKIE_SETTLE_DELAY_MS = 800L

/** `evaluateJavascript` 回调给的是 JSON 字面量；去掉外层引号与转义。 */
private fun unescapeJavascriptString(result: String?): String? {
    val raw = result?.trim().orEmpty()
    if (!raw.startsWith("\"")) return raw.takeIf { it.isNotEmpty() && it != "null" }
    val inner = raw.removePrefix("\"").removeSuffix("\"")
    val out = StringBuilder(inner.length)
    var index = 0
    while (index < inner.length) {
        val char = inner[index]
        if (char == '\\' && index + 1 < inner.length) {
            when (val next = inner[index + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                else -> out.append(next)
            }
            index += 2
        } else {
            out.append(char)
            index++
        }
    }
    return out.toString()
}

/**
 * 当前登录态：会话 Cookie 与它所属的接口 base。
 *
 * 两者必须成对取出：Cookie 挂在哪个域上，请求就只能发到那个域，
 * 分开读会在「登录态切换」时把直连 Cookie 打到代理域上。
 */
internal data class SilentPodSession(val cookieHeader: String, val baseUrl: String)

/** 已登录的会话；先看直连域，再看 aTrust 代理域。未登录返回 `null`。 */
internal fun readSilentPodSession(): SilentPodSession? {
    val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return null
    val direct = runCatching {
        manager.getCookie(SilentPodSessionPolicy.DIRECT_ORIGIN).orEmpty()
    }.getOrDefault("")
    SilentPodSessionPolicy.sessionCookie(direct)?.let {
        return SilentPodSession(it, SilentPodSessionPolicy.API_BASE_URL)
    }
    val gateway = runCatching {
        manager.getCookie(SilentPodSessionPolicy.GATEWAY_ORIGIN).orEmpty()
    }.getOrDefault("")
    SilentPodSessionPolicy.sessionCookie(gateway)?.let {
        return SilentPodSession(it, SilentPodSessionPolicy.GATEWAY_API_BASE_URL)
    }
    return null
}

/** 会话 Cookie 是否已出现（不区分域）。 */
internal fun hasSilentPodSession(): Boolean = readSilentPodSession() != null

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SilentPodLoginWebView(
    modifier: Modifier,
    onPageFinished: () -> Unit,
    onError: () -> Unit,
    onSessionReady: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { view ->
                view.stopLoading()
                view.webChromeClient = null
                view.webViewClient = WebViewClient()
                view.destroy()
            }
            webView = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.WHITE)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    @Suppress("DEPRECATION")
                    saveFormData = false
                }
                cookies.setAcceptThirdPartyCookies(this, false)
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        if (newProgress >= 100) onPageFinished()
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean = !SilentPodSessionPolicy.isSecureUrl(request?.url?.toString().orEmpty())

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                        !SilentPodSessionPolicy.isSecureUrl(url.orEmpty())

                    override fun onPageFinished(view: WebView?, url: String?) {
                        onPageFinished()
                        val web = view ?: return
                        if (hasSilentPodSession()) {
                            onSessionReady()
                            return
                        }
                        // Cookie 还没落盘时，用落定页内联的登录态做一次补充判定；
                        // 但只有 Cookie 真的出现才算完成（业务接口只认 Cookie）。
                        web.evaluateJavascript(LOGIN_STATE_SCRIPT) { result ->
                            if (SilentPodParser.loginStatusLoggedIn(unescapeJavascriptString(result)) == true) {
                                CookieManager.getInstance().flush()
                                web.postDelayed({
                                    if (hasSilentPodSession()) onSessionReady()
                                }, COOKIE_SETTLE_DELAY_MS)
                            }
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) onError()
                    }

                    /** 证书错误必须取消：账号密码不能交给中间人。 */
                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: android.webkit.SslErrorHandler?,
                        error: android.net.http.SslError?,
                    ) {
                        handler?.cancel()
                        onError()
                    }
                }
                loadUrl(SilentPodSessionPolicy.ENTRY_URL)
                webView = this
            }
        },
    )
}
