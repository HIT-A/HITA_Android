package cn.limpu.hita.data.source.web.campus

import cn.limpu.hita.data.model.campus.SilentPodContact
import cn.limpu.hita.data.model.campus.SilentPodContacts
import cn.limpu.hita.data.model.campus.SilentPodContent
import cn.limpu.hita.data.model.campus.SilentPodReader
import cn.limpu.hita.data.model.campus.SilentPodReservation
import cn.limpu.hita.data.model.campus.SilentPodSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 大学城图书馆空间预约客户端。
 *
 * 负责签名后的 GET/POST，以及把响应交给 [SilentPodParser]。
 * 它不持有 Cookie、不缓存、不决定 UI 状态——调用方每次传入当前会话，
 * 会话失效时能立刻反映到页面上。
 */
internal class SilentPodClient(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * 拉取某日空间列表 + 我的预约。
     *
     * @param cookieHeader `pima.space-reservation-fg.sid=...`（已由策略筛过）
     * @param baseUrl 接口基地址，直连域或 aTrust 代理域
     * @return 完整快照；任一必需请求失败都抛 [SilentPodException]
     */
    suspend fun fetchContent(
        cookieHeader: String,
        baseUrl: String = SilentPodSessionPolicy.API_BASE_URL,
        date: String = today(),
    ): SilentPodContent = withContext(Dispatchers.IO) {
        val cookie = SilentPodSessionPolicy.sessionCookie(cookieHeader) ?: throw SilentPodException(
            kind = SilentPodFailureKind.AUTHENTICATION,
        )
        val spaces = SilentPodParser.parseSpaces(get(urlForSpaces(baseUrl, date), cookie))
        // 我的预约失败不该让整页空白：空间列表是本页主体，预约列表降级为空。
        val reservations = try {
            SilentPodParser.parseReservations(get(urlForReservations(baseUrl), cookie))
        } catch (failure: SilentPodException) {
            if (failure.kind == SilentPodFailureKind.AUTHENTICATION) throw failure
            emptyList<SilentPodReservation>()
        }
        SilentPodContent(
            date = date,
            groups = SilentPodParser.group(spaces),
            reservations = reservations,
        )
    }

    /** 仅拉空间列表，供只需要分组的场景复用。 */
    suspend fun fetchSpaces(
        cookieHeader: String,
        baseUrl: String = SilentPodSessionPolicy.API_BASE_URL,
        date: String = today(),
    ): List<SilentPodSpace> = withContext(Dispatchers.IO) {
        val cookie = SilentPodSessionPolicy.sessionCookie(cookieHeader) ?: throw SilentPodException(
            kind = SilentPodFailureKind.AUTHENTICATION,
        )
        SilentPodParser.parseSpaces(get(urlForSpaces(baseUrl, date), cookie))
    }

    /**
     * 同行成员列表。
     *
     * 多人仓预约必须带 `subUserContactsIds`，所以对话框打开前要先拿到列表；
     * `defaultMobile` 是服务端记住的常用手机号，可用于预填。
     */
    suspend fun fetchContacts(
        cookieHeader: String,
        baseUrl: String = SilentPodSessionPolicy.API_BASE_URL,
    ): SilentPodContacts = withContext(Dispatchers.IO) {
        val cookie = requireSession(cookieHeader)
        val url = SilentPodSigner.signedUrl(
            baseUrl = baseUrl,
            path = "web/reservation-contacts",
            query = listOf("page" to "1", "limit" to "100"),
        )
        SilentPodParser.parseContacts(get(url, cookie))
    }

    /**
     * 用读者证号核验姓名，添加同行成员前调用。
     *
     * 走 `x-bdc-service-api`（与空间预约同一套签名，前缀不同）；
     * 查不到人时服务端返回 `NO_EXISTS`，由 [get] 抛成业务错误。
     */
    suspend fun verifyReader(
        cookieHeader: String,
        cardNo: String,
        baseUrl: String = SilentPodSessionPolicy.API_BASE_URL,
    ): SilentPodReader? = withContext(Dispatchers.IO) {
        val cookie = requireSession(cookieHeader)
        val trimmed = cardNo.trim()
        if (trimmed.isEmpty()) return@withContext null
        val url = SilentPodSigner.signedUrl(
            baseUrl = SilentPodSessionPolicy.bdcBaseUrlFor(baseUrl),
            path = "commons/readers/$trimmed",
            query = listOf("cardNo" to trimmed),
        )
        SilentPodParser.parseReader(get(url, cookie))
    }

    /** 保存同行成员；返回服务端分配的 id 与回填的姓名。 */
    suspend fun createContact(
        cookieHeader: String,
        name: String,
        cardNo: String,
        baseUrl: String = SilentPodSessionPolicy.API_BASE_URL,
    ): SilentPodContact = withContext(Dispatchers.IO) {
        val cookie = requireSession(cookieHeader)
        val fields = linkedMapOf(
            "contactUserName" to name.trim(),
            "contactUserNo" to cardNo.trim(),
        )
        val body = JSONObject().apply {
            put("contactUserName", fields.getValue("contactUserName"))
            put("contactUserNo", fields.getValue("contactUserNo"))
        }.toString()
        val url = SilentPodSigner.signedUrl(
            baseUrl = baseUrl,
            path = "web/reservation-contacts",
            query = emptyList(),
            bodyFields = fields,
        )
        val raw = post(url, cookie, body)
        val model = runCatching { JSONObject(raw).optJSONObject("model") }.getOrNull()
        val contact = model?.let(SilentPodParser::contactOf)
            ?: throw SilentPodException(SilentPodFailureKind.PARSE, detail = "contact")
        contact
    }

    /** 删除已保存的同行成员。 */
    suspend fun removeContact(
        cookieHeader: String,
        contactId: Int,
        baseUrl: String = SilentPodSessionPolicy.API_BASE_URL,
    ): Unit = withContext(Dispatchers.IO) {
        val cookie = requireSession(cookieHeader)
        val url = SilentPodSigner.signedUrl(
            baseUrl = baseUrl,
            path = "web/reservation-contacts/$contactId/remove",
            query = emptyList(),
        )
        post(url, cookie, "{}")
        Unit
    }

    /** 取已筛过的会话 Cookie；缺失说明调用方没登录，直接按鉴权失败处理。 */
    private fun requireSession(cookieHeader: String): String =
        SilentPodSessionPolicy.sessionCookie(cookieHeader)
            ?: throw SilentPodException(kind = SilentPodFailureKind.AUTHENTICATION)

    /**
     * 创建预约。
     *
     * POST 的顶层 JSON 字段与 Query 参数一样参与签名（文档 §4.3 第 2 条），
     * 因此 body 字段必须原样传给 [SilentPodSigner]，不能只在请求体里出现。
     *
     * @return 服务端返回的预约 id；返回 null 表示创建成功但没给出 id
     */
    suspend fun createReservation(
        cookieHeader: String,
        spaceId: Int,
        date: String,
        startTime: String,
        endTime: String,
        notes: String,
        contactIds: List<Int> = emptyList(),
        baseUrl: String = SilentPodSessionPolicy.API_BASE_URL,
    ): Int? = withContext(Dispatchers.IO) {
        val cookie = SilentPodSessionPolicy.sessionCookie(cookieHeader) ?: throw SilentPodException(
            kind = SilentPodFailureKind.AUTHENTICATION,
        )
        val fields = linkedMapOf(
            "reservationDate" to date,
            "startTime" to startTime,
            "endTime" to endTime,
            "spaceId" to spaceId.toString(),
            "subUserContactsIds" to contactIds.joinToString(prefix = "[", postfix = "]"),
            "notes" to notes,
        )
        // 用 JSONObject 生成请求体：字段值里的引号、换行由库转义，不再手写拼接。
        val body = JSONObject().apply {
            put("reservationDate", date)
            put("startTime", startTime)
            put("endTime", endTime)
            put("spaceId", spaceId)
            put("subUserContactsIds", org.json.JSONArray(contactIds))
            put("notes", notes)
        }.toString()
        val url = SilentPodSigner.signedUrl(
            baseUrl = baseUrl,
            path = "web/reservations",
            query = emptyList(),
            bodyFields = fields,
        )
        SilentPodParser.parseCreatedId(post(url, cookie, body))
    }

    /** 取消预约；[reason] 会被服务端记录，空白时用默认理由。 */
    suspend fun cancelReservation(
        cookieHeader: String,
        reservationId: Int,
        reason: String,
        baseUrl: String = SilentPodSessionPolicy.API_BASE_URL,
    ): Unit = withContext(Dispatchers.IO) {
        val cookie = SilentPodSessionPolicy.sessionCookie(cookieHeader) ?: throw SilentPodException(
            kind = SilentPodFailureKind.AUTHENTICATION,
        )
        val finalReason = reason.trim().ifEmpty { "个人行程调整" }.take(200)
        val fields = linkedMapOf("reason" to finalReason)
        val url = SilentPodSigner.signedUrl(
            baseUrl = baseUrl,
            path = "web/reservations/$reservationId/cancel",
            query = emptyList(),
            bodyFields = fields,
        )
        post(url, cookie, "{\"reason\":\"${finalReason.escapeJson()}\"}")
        Unit
    }

    /** JSON 字符串转义：只处理会破坏 JSON 结构的字符。 */
    private fun String.escapeJson(): String = buildString(length) {
        this@escapeJson.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append(String.format("\\u%04x", ch.code)) else append(ch)
            }
        }
    }

    /** 今日日期必须按北京时间算：服务端的 `selectedDate` 就是 Asia/Shanghai 日历日。 */
    fun today(): String = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()

    private fun urlForSpaces(baseUrl: String, date: String): String = SilentPodSigner.signedUrl(
        baseUrl = baseUrl,
        path = "web/spaces",
        query = listOf(
            "selectedDate" to date,
            "ruleIsNull" to "false",
            "page" to "1",
            "limit" to "1000",
        ),
    )

    private fun urlForReservations(baseUrl: String): String = SilentPodSigner.signedUrl(
        baseUrl = baseUrl,
        path = "web/reservations",
        query = listOf(
            "page" to "1",
            "limit" to "10",
        ),
    )

    /** 发一次 POST；网关 502 与超时的处理与 GET 一致。 */
    private fun post(url: String, cookie: String, body: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        var lastFailure: SilentPodException? = null
        for (attempt in 0..1) {
            if (attempt > 0) Thread.sleep(RETRY_DELAY_MS)
            try {
                client.newCall(request).execute().use { response ->
                    return readBody(url, response, allowEmpty = true)
                }
            } catch (failure: SilentPodException) {
                lastFailure = failure
                // 写操作只对网关错误重试：已经落库的请求重试可能产生重复预约。
                if (failure.kind != SilentPodFailureKind.GATEWAY) throw failure
            } catch (timeout: SocketTimeoutException) {
                throw SilentPodException(SilentPodFailureKind.TIMEOUT, cause = timeout)
            } catch (error: IOException) {
                throw SilentPodException(SilentPodFailureKind.NETWORK, cause = error)
            }
        }
        throw lastFailure ?: SilentPodException(SilentPodFailureKind.NETWORK)
    }

    /**
     * 发一次 GET，遇到 aTrust 502 退避重试一次。
     *
     * 网关返回的是 HTML `<title>aTrust</title>` 页面而不是 JSON，
     * 直接当解析失败会给出「数据异常」这种误导性提示，因此单列成网关失败。
     */
    private fun get(url: String, cookie: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("Accept", "application/json, text/plain, */*")
            .header("X-Requested-With", "XMLHttpRequest")
            .get()
            .build()
        var lastFailure: SilentPodException? = null
        for (attempt in 0..1) {
            if (attempt > 0) Thread.sleep(RETRY_DELAY_MS)
            try {
                client.newCall(request).execute().use { response ->
                    return readBody(url, response)
                }
            } catch (failure: SilentPodException) {
                lastFailure = failure
                if (failure.kind != SilentPodFailureKind.GATEWAY) throw failure
            } catch (timeout: SocketTimeoutException) {
                throw SilentPodException(SilentPodFailureKind.TIMEOUT, cause = timeout)
            } catch (error: IOException) {
                throw SilentPodException(SilentPodFailureKind.NETWORK, cause = error)
            }
        }
        throw lastFailure ?: SilentPodException(SilentPodFailureKind.NETWORK)
    }

    private fun readBody(url: String, response: Response, allowEmpty: Boolean = false): String {
        val code = response.code
        val body = response.body?.string().orEmpty()
        if (code == 401 || code == 403) {
            throw SilentPodException(SilentPodFailureKind.AUTHENTICATION)
        }
        if (code == 502 || body.contains("应用服务器连接建立失败")) {
            throw SilentPodException(SilentPodFailureKind.GATEWAY)
        }
        if (code in 300..399) {
            // 会话过期时服务端会 302 到登录页；followRedirects 已关闭，这里按未登录处理。
            throw SilentPodException(SilentPodFailureKind.AUTHENTICATION)
        }
        if (!response.isSuccessful) {
            throw SilentPodException(
                kind = SilentPodFailureKind.API,
                code = SilentPodParser.errorCodeOf(body),
                detail = SilentPodParser.errorMessageOnlyOf(body) ?: "HTTP $code",
            )
        }
        if (body.isBlank()) {
            if (allowEmpty) return ""
            throw SilentPodException(SilentPodFailureKind.PARSE, detail = url)
        }
        SilentPodParser.errorCodeOf(body)?.let { apiCode ->
            throw SilentPodException(
                kind = SilentPodFailureKind.API,
                code = apiCode,
                detail = SilentPodParser.errorMessageOnlyOf(body),
            )
        }
        return body
    }

    companion object {
        private const val RETRY_DELAY_MS = 600L

        /**
         * 专用客户端：不跟随重定向（302 就是会话失效信号），
         * 不共享全局 CookieJar、不带任何拦截器。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }
}
