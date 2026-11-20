package cn.limpu.hita.data.source.web.campus

import cn.limpu.hita.data.model.campus.LibraryPeoples
import cn.limpu.hita.data.model.campus.LibraryRoomNums
import cn.limpu.hita.data.model.campus.LibrarySmartWallSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 大学城图书馆智慧墙的只读客户端。
 *
 * 两个接口在校园网内可匿名直连（免读者登录），但 aTrust 网关在未放行时会把它拦成
 * 登录页或 502 HTML，因此失败必须分成三类：需要登录、网关不可用、接口没有数据。
 *
 * 客户端只负责取数并把解析器的异常原样传出，不产出 UI 状态。
 */
internal class LibrarySmartWallClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) {
    /**
     * 同时取在馆人数与静音仓占用。
     *
     * [cookieHeader] 可选：用户在门户 WebView 登录过就带上，否则匿名访问。
     * 任一分区失败都整体失败——半新半旧的「一面墙」比没有更误导。
     */
    suspend fun fetch(
        cookieHeader: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): LibrarySmartWallSnapshot = coroutineScope {
        val peoples = async { fetchPeoples(cookieHeader) }
        val rooms = async { fetchRooms(cookieHeader) }
        LibrarySmartWallSnapshot(
            peoples = peoples.await(),
            rooms = rooms.await(),
            fetchedAtMillis = nowMillis,
        )
    }

    private suspend fun fetchPeoples(cookieHeader: String?): LibraryPeoples =
        LibrarySmartWallParser.parsePeoples(get("smart-walls/peoples", cookieHeader))

    private suspend fun fetchRooms(cookieHeader: String?): LibraryRoomNums =
        LibrarySmartWallParser.parseRoomNums(get("smart-walls/room-nums", cookieHeader))

    private suspend fun get(path: String, cookieHeader: String?): String = withContext(Dispatchers.IO) {
        // 每次请求都换新 nonce：服务端校验签名，重放同一个 nonce 会被拒。
        val url = LibrarySmartWallSigner.signedUrl(baseUrl, path, UUID.randomUUID().toString())
        val builder = Request.Builder()
            .url(url)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/plain, */*")
            .get()
        if (!cookieHeader.isNullOrBlank()) builder.header("Cookie", cookieHeader)
        val (code, finalUrl, body) = try {
            client.newCall(builder.build()).execute().use { response ->
                Triple(response.code, response.request.url.toString(), response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            throw LibrarySmartWallException("连接图书馆服务失败，请检查校园网后重试")
        }
        if (code == 502) throw LibrarySmartWallException("图书馆网关暂时不可用（502），请稍后重试")
        if (code == 401 || code == 403 || code in 300..399) {
            throw LibrarySmartWallSignInRequiredException()
        }
        if (code !in 200..299) throw LibrarySmartWallException("图书馆服务返回状态码 $code")
        // 部分部署把 aTrust 错误页塞进 200，这里再判一次，否则会被当成 JSON 解析失败。
        if (LibrarySmartWallParser.isGatewayErrorPage(body)) {
            throw LibrarySmartWallException("图书馆网关暂时不可用（502），请稍后重试")
        }
        // 网关拦截也可能回 200 的登录页：只有签名接口的正常响应才是 JSON。
        if (!body.trimStart().startsWith("{") || finalUrl.contains("authserver")) {
            throw LibrarySmartWallSignInRequiredException()
        }
        body
    }

    companion object {
        /** 门户首页：用户在这里完成登录后，Cookie 才会被带上。 */
        const val PORTAL_URL = "https://www.utszlib.edu.cn/"

        /** 智慧墙接口基址。 */
        const val DEFAULT_BASE_URL = "https://www.utszlib.edu.cn/smart-wall_api"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // 302 到统一身份认证是「未登录」的信号，不能自动跟随。
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** 智慧墙接口被网关拦成登录页：需要用户先在门户登录。 */
internal class LibrarySmartWallSignInRequiredException : Exception("图书馆门户需要登录")
