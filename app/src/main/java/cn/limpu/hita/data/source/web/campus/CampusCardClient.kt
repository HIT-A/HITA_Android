package cn.limpu.hita.data.source.web.campus

import cn.limpu.hita.data.model.campus.CampusCardSpending
import cn.limpu.hita.data.model.campus.CampusCardSummary
import cn.limpu.hita.data.model.campus.CampusCardTransactions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 网络层失败：连接、超时、DNS、5xx、无法识别的跳转。 */
internal class CampusCardNetworkException(message: String) : Exception(message)

/**
 * 深圳信息门户校园卡客户端。
 *
 * 鉴权：统一身份认证回跳 `portal-api/login_cas` → 门户前端地址携带 `t` →
 * `GET /portal-api/ssologin?t=` 换取请求头 `token`。事实来源是 iOS
 * `CampusCardPortalClient.swift`。本类不自带 Cookie 存储：Cookie 由调用方
 * 按 origin 提供（`CampusCookieJar`，即 WebView 的统一身份认证会话）。
 */
internal class CampusCardClient(
    private val http: OkHttpClient = defaultClient(),
) {

    /**
     * 跟随 CAS 登录重定向链取出 `t`，再换取 `token`。
     *
     * @param cookieHeader 传入 origin（`https://dxcdp.hitsz.edu.cn` 或
     *   `https://ids.hit.edu.cn`），返回该 origin 的 Cookie 原文
     */
    suspend fun acquireToken(
        cookieHeader: (String) -> String,
        portalTicket: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val hopCookies = mutableListOf<Cookie>()
        val ticket = portalTicket ?: followLoginRedirects(cookieHeader, hopCookies)
        exchangeToken(ticket, cookieHeader, hopCookies)
    }

    /** The mandatory balance request can render without waiting for supplementary data. */
    suspend fun fetchBalance(token: String): CampusCardSummary = withContext(Dispatchers.IO) {
        CampusCardParser.billSummary(getText("$API_BASE/index/getBillSummaryBySno", token))
    }

    suspend fun fetchSpending(token: String): CampusCardSpending? = withContext(Dispatchers.IO) {
        CampusCardParser.spending(getText("$API_BASE/index/getCountYkt", token))
    }

    /** 消费流水：`status=1`（全部）+ 指定分页。 */
    suspend fun fetchTransactions(
        token: String,
        page: Int = 1,
        pageSize: Int = 20,
        range: CampusCardHistoryRange = CampusCardHistoryRange.MONTH,
        now: java.time.Instant = java.time.Instant.now(),
        since: java.time.Instant? = null,
        until: java.time.Instant? = null,
    ): CampusCardTransactions =
        withContext(Dispatchers.IO) {
            val safePage = page.coerceAtLeast(1)
            val safeSize = pageSize.coerceAtLeast(1)
            val builder = "$API_BASE/index/getBillDetailList".toHttpUrl().newBuilder()
                .addQueryParameter("status", "1")
                .addQueryParameter("pageNum", safePage.toString())
                .addQueryParameter("pageSize", safeSize.toString())
            range.parameters(now).forEach { (name, value) -> builder.addQueryParameter(name, value) }
            val format = java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", java.util.Locale.ROOT)
                .withZone(java.time.ZoneId.of("Asia/Shanghai"))
            since?.let { builder.setQueryParameter("beginTime", format.format(it)) }
            until?.let { builder.setQueryParameter("endTime", format.format(it)) }
            val url = builder.build().toString()
            CampusCardParser.transactions(getText(url, token), safePage)
        }

    private fun followLoginRedirects(cookieHeader: (String) -> String, hopCookies: MutableList<Cookie>): String {
        var currentUrl = CampusCardSessionPolicy.CAS_LOGIN_URL
        repeat(MAX_REDIRECT_HOPS) {
            CampusCardSessionPolicy.portalTicket(currentUrl)?.let { return it }
            execute(currentUrl, token = null, cookieHeader = mergeCookies(baseCookie(currentUrl, cookieHeader), hopCookies, currentUrl))
                .use { response ->
                    Cookie.parseAll(response.request.url, response.headers).forEach { cookie ->
                        hopCookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                        hopCookies.add(cookie)
                    }
                    val location = response.header("Location")
                    if (response.code in 300..399 && !location.isNullOrBlank()) {
                        val next = resolve(currentUrl, location)
                        CampusCardSessionPolicy.portalTicket(next)?.let { return it }
                        currentUrl = next
                        return@repeat
                    }
                    // 200：只有页面里带 `t` 才算登录成功；停在统一认证表单页说明会话已失效。
                    val html = response.body?.string().orEmpty()
                    CampusCardSessionPolicy.portalTicketInHtml(html)?.let { return it }
                    throw CampusCardSignInRequiredException("统一身份认证登录已失效，请重新登录")
                }
        }
        throw CampusCardNetworkException("登录重定向次数过多，无法完成门户登录")
    }

    private fun exchangeToken(ticket: String, cookieHeader: (String) -> String, hopCookies: List<Cookie>): String {
        val url = "$API_BASE/ssologin?t=${URLEncoder.encode(ticket, "UTF-8")}"
        return CampusCardParser.token(getText(url, token = null, cookieHeader = mergeCookies(baseCookie(url, cookieHeader), hopCookies, url)))
    }

    private fun getText(url: String, token: String?, cookieHeader: String? = null): String =
        execute(url, token, cookieHeader).use { response ->
            if (response.code == 401 || response.code == 403) {
                throw CampusCardSignInRequiredException("门户登录已失效，请重新登录")
            }
            if (!response.isSuccessful) throw CampusCardNetworkException("门户返回 HTTP ${response.code}")
            response.body?.string().orEmpty()
        }

    private fun execute(url: String, token: String?, cookieHeader: String?): Response {
        val isLoginHop = token == null
        val request = Request.Builder()
            .url(url)
            .header(
                "Accept",
                if (isLoginHop) "text/html,application/xhtml+xml,*/*;q=0.8" else "application/json, text/plain, */*",
            )
            .header("User-Agent", USER_AGENT)
            .header("Referer", CampusCardSessionPolicy.PORTAL_WEB_URL)
        if (!cookieHeader.isNullOrEmpty()) request.header("Cookie", cookieHeader)
        if (token != null) request.header("token", token)
        return try {
            http.newCall(request.build()).execute()
        } catch (_: IOException) {
            throw CampusCardNetworkException("网络连接失败，请检查网络后重试")
        }
    }

    /** 只对已知的两个 origin 取 Cookie；其他跳转（如 trust 页）不转发任何凭证。 */
    private fun baseCookie(url: String, cookieHeader: (String) -> String): String? {
        val origin = CampusCardSessionPolicy.originOf(url)
        if (origin != CampusCardSessionPolicy.PORTAL_ORIGIN &&
            origin != CampusCardSessionPolicy.CAS_ORIGIN
        ) {
            return null
        }
        return cookieHeader(origin).ifEmpty { null }
    }

    private fun mergeCookies(base: String?, overlay: List<Cookie>, url: String): String? {
        val ordered = linkedMapOf<String, String>()
        base.orEmpty().split(';').forEach { segment ->
            val trimmed = segment.trim()
            if (!trimmed.contains('=')) return@forEach
            val name = trimmed.substringBefore('=').trim()
            val value = trimmed.substringAfter('=')
            if (name.isNotEmpty() && value.isNotEmpty()) ordered[name] = value
        }
        overlay.filter { it.matches(url.toHttpUrl()) && it.expiresAt > System.currentTimeMillis() }
            .sortedBy { it.path.length }
            .forEach { ordered[it.name] = it.value }
        if (ordered.isEmpty()) return null
        return ordered.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 解析相对 Location；只允许 https，避免把会话带到明文连接。 */
    private fun resolve(base: String, location: String): String {
        val resolved = runCatching { URI(base).resolve(location).toString() }.getOrElse {
            throw CampusCardNetworkException("登录跳转地址无法解析")
        }
        if (!resolved.startsWith("https://")) {
            throw CampusCardNetworkException("登录跳转到非 https 地址，已中止")
        }
        return resolved
    }

    companion object {
        const val API_BASE = "https://dxcdp.hitsz.edu.cn/portal-api"
        private const val MAX_REDIRECT_HOPS = 10
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Mobile Safari/537.36"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
