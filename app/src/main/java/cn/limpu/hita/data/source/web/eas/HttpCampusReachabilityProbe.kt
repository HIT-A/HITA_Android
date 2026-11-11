package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 用一次轻量 HEAD/GET 判断入口是否可达。
 *
 * 判据刻意宽松：**只要拿到任意 HTTP 响应就算可达**，包括 302、401、403。
 * 目的是区分「网络层能不能通」而不是「有没有登录」——未登录的教务页与 VPN 登录页
 * 都会返回重定向或错误码，那恰恰证明路是通的。
 *
 * 超时很短（[TIMEOUT_MS]）：这是登录前的同步前置步骤，宁可判为不可达（回落 VPN，
 * 与历史行为一致）也不能让用户干等。
 */
class HttpCampusReachabilityProbe(
    private val client: OkHttpClient = defaultClient(),
) : CampusReachabilityProbe {

    override suspend fun isReachable(url: String): Boolean = withContext(Dispatchers.IO) {
        val request = runCatching {
            Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", DESKTOP_USER_AGENT)
                .build()
        }.getOrNull() ?: return@withContext false
        try {
            client.newCall(request).execute().use { response ->
                // 任何 HTTP 响应都说明网络层可达（含 302 / 401 / 403）。
                if (response.code < 500) return@withContext true
            }
            // 5xx 或网关拒绝 HEAD（405/501）时用 GET 复核，避免把可达误判为不可达。
            val fallback = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", DESKTOP_USER_AGENT)
                .build()
            client.newCall(fallback).execute().use { response -> response.code < 500 }
        } catch (e: IOException) {
            LogUtils.d("weihai entry probe unreachable: ${e.javaClass.simpleName}")
            false
        } catch (e: IllegalStateException) {
            LogUtils.d("weihai entry probe failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private companion object {
        const val TIMEOUT_MS = 2_500L

        /** 与服务端实测接受的桌面 UA 保持一致，避免被按 UA 拦成不可达。 */
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/146.0.0.0 Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
