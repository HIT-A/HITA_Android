package cn.limpu.hita.ui.resource

import android.annotation.SuppressLint
import android.os.Bundle
import android.graphics.Bitmap
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import cn.limpu.hita.R
import cn.limpu.hita.ui.design.HitaComposeTheme
import com.limpu.style.ThemeTools
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class InternalWebActivity : AppCompatActivity() {
    private var webView: WebView? = null
    private var failedUrl by mutableStateOf<String?>(null)

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(url: String, history: Bundle?): WebView = WebView(this).apply {
        webView = this
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                // A start callback for the same navigation must not erase its HTTP error.
                if (url != null && url != failedUrl) failedUrl = null
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) failedUrl = request.url.toString()
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (request.isForMainFrame) failedUrl = request.url.toString()
            }
        }
        settings.javaScriptEnabled = true
        settings.loadWithOverviewMode = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        if (history == null || restoreState(history) == null) loadUrl(url)
    }

    private fun retryPage() {
        val reader = webView ?: return
        val target = failedUrl ?: return
        failedUrl = null
        if (reader.url == target) reader.reload() else reader.loadUrl(target)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val mode = when (ThemeTools.getThemeMode(this)) {
            ThemeTools.MODE.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeTools.MODE.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val reader = webView
                if (reader != null && reader.canGoBack()) {
                    failedUrl = null
                    reader.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val title = intent.getStringExtra("title").orEmpty()
        val url = intent.getStringExtra("url") ?: "about:blank"
        val history = savedInstanceState?.getBundle(WEB_HISTORY)
        setContent {
            HitaComposeTheme {
                InternalWebScreen(
                    title = title,
                    onBack = onBackPressedDispatcher::onBackPressed,
                    pageFailed = failedUrl != null,
                    onRetry = ::retryPage,
                ) {
                    AndroidView(
                        factory = { createWebView(url, history) },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { view ->
                            if (webView === view) webView = null
                            view.destroy()
                        },
                        update = { view ->
                            // Retain navigation history without leaving the failed page focusable under the error UI.
                            view.visibility = if (failedUrl != null) View.INVISIBLE else View.VISIBLE
                        },
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.let { reader ->
            val history = Bundle()
            if (reader.saveState(history) != null) outState.putBundle(WEB_HISTORY, history)
        }
    }

    private companion object {
        const val WEB_HISTORY = "internalWebHistory"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InternalWebScreen(
    title: String,
    onBack: () -> Unit,
    pageFailed: Boolean,
    onRetry: () -> Unit,
    webContent: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                        contentDescription = stringResource(R.string.today_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.rotate(180f)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        )
        Box(Modifier.weight(1f).fillMaxSize()) {
            webContent()
            if (pageFailed) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.load_failed))
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.stale_data_retry)) }
                    }
                }
            }
        }
    }
}

