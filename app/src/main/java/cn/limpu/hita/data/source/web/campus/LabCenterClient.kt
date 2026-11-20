package cn.limpu.hita.data.source.web.campus

import cn.limpu.hita.data.model.campus.LabCourseProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 实验中心（GvSun LIMS）个人课表的只读客户端。
 *
 * 两步取数：
 * 1. `GET /hitszlims/schedule/myScheduleInfo` → 当前学期 `termId` 与当前教学周；
 * 2. `GET /hitszlims/schedule/myScheduleInfoAjax?termId=<id>&week=`（week 留空取整学期）
 *    → 二维数组，交给 [LabCenterScheduleParser]。
 *
 * 整学期课表实测需要 10 秒以上，因此超时给到 35 秒（与 iOS 客户端一致）。
 * 客户端只负责取数与判会话，不产出 UI 状态。
 */
internal class LabCenterClient(
    private val client: OkHttpClient = defaultClient(),
) {
    /**
     * 抓取本学期实验课进度。
     *
     * 会话不可用时抛 [LabCenterSessionExpiredException]，其余失败抛 [LabCenterException]。
     */
    suspend fun fetch(cookieHeader: String, nowMillis: Long = System.currentTimeMillis()): LabCourseProgress {
        val cookie = LabCenterSessionPolicy.cookieHeader(cookieHeader)
            ?: throw LabCenterSessionExpiredException()
        val pageHtml = get(LabCenterSessionPolicy.SCHEDULE_URL, cookie, accept = HTML_ACCEPT)
        if (!LabCenterSessionPolicy.isSchedulePage(pageHtml) || LabCenterSessionPolicy.isLoginPage(pageHtml)) {
            throw LabCenterSessionExpiredException()
        }
        val term = LabCenterHtmlParser.selectedTerm(pageHtml)
            ?: throw LabCenterException("实验中心课表页没有学期选项，无法确定当前学期")
        val currentWeek = LabCenterHtmlParser.selectedWeek(pageHtml)

        val ajaxBase = LabCenterSessionPolicy.SCHEDULE_AJAX_URL.toHttpUrlOrNull()
            ?: throw LabCenterException("实验中心课表接口地址无效")
        val ajaxUrl = ajaxBase.newBuilder()
            .addQueryParameter("termId", term.id)
            // week 留空 = 整学期。带上 "week=" 而不是省略，服务端两种写法行为不同。
            .addQueryParameter("week", "")
            .build()
        val body = get(ajaxUrl.toString(), cookie, accept = "*/*", referer = LabCenterSessionPolicy.SCHEDULE_URL)
        val rows = parseRows(body)
        return LabCourseProgress(
            termName = term.name,
            currentWeek = currentWeek,
            sessions = LabCenterScheduleParser.parseSessions(rows),
            fetchedAtMillis = nowMillis,
        )
    }

    /**
     * 轻量探测会话是否已建立：只取课表页并判页面标志，不拉整学期课表。
     *
     * 供登录 WebView 判定「可以结束登录了」使用，避免每次进入页面都等 10 秒以上。
     */
    suspend fun probeSession(cookieHeader: String): Boolean {
        val cookie = LabCenterSessionPolicy.cookieHeader(cookieHeader) ?: return false
        return try {
            val html = get(LabCenterSessionPolicy.SCHEDULE_URL, cookie, accept = HTML_ACCEPT)
            LabCenterSessionPolicy.isSchedulePage(html) && !LabCenterSessionPolicy.isLoginPage(html)
        } catch (_: LabCenterSessionExpiredException) {
            false
        } catch (_: LabCenterException) {
            false
        }
    }

    /**
     * 把课表响应映射成数据或失败。
     *
     * 平台的外壳不统一，但课表接口只返回裸二维数组；空体是空课表（合法），
     * 非数组的响应说明已被登录页/网关替换。
     */
    private fun parseRows(body: String): List<List<Any?>> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (LabCenterSessionPolicy.isLoginPage(trimmed)) throw LabCenterSessionExpiredException()
        return try {
            LabCenterScheduleParser.parseRows(trimmed)
        } catch (_: IllegalArgumentException) {
            throw LabCenterSessionExpiredException()
        } catch (_: Exception) {
            throw LabCenterException("实验中心课表返回了无法识别的数据")
        }
    }

    private suspend fun get(
        url: String,
        cookie: String,
        accept: String,
        referer: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("Accept", accept)
            .header("X-Requested-With", "XMLHttpRequest")
            .get()
        if (referer != null) builder.header("Referer", referer)
        val (code, finalUrl, body) = try {
            // 禁用自动重定向：302 到登录页是会话失效的唯一可靠信号。
            client.newCall(builder.build()).execute().use { response ->
                Triple(response.code, response.request.url.toString(), response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            throw LabCenterException("连接实验中心失败，请检查校园网后重试")
        }
        if (code in 300..399 || LabCenterSessionPolicy.isLoginTransitPage(finalUrl)) {
            throw LabCenterSessionExpiredException()
        }
        if (code == 401 || code == 403) throw LabCenterSessionExpiredException()
        if (code !in 200..299) throw LabCenterException("实验中心返回状态码 $code")
        body
    }

    private companion object {
        const val HTML_ACCEPT = "text/html,application/xhtml+xml,*/*;q=0.8"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

/** 实验中心需要重新登录（会话失效或从未登录）。 */
internal class LabCenterSessionExpiredException : Exception("实验中心会话已失效")

/** 实验中心查询失败；[message] 已可读，UI 直接展示。 */
internal class LabCenterException(message: String) : Exception(message)
