package cn.limpu.hita.data.source.web.eas

import android.annotation.SuppressLint
import android.util.Base64
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.limpu.hita.utils.LogUtils
import com.limpu.component.data.DataState
import cn.limpu.hita.data.model.eas.CourseSelectionCourseResult
import cn.limpu.hita.data.model.eas.CourseSelectionCourseStatus
import cn.limpu.hita.data.model.eas.CourseSelectionJob
import cn.limpu.hita.data.model.eas.CourseSelectionJobCourse
import cn.limpu.hita.data.model.eas.CourseItem
import cn.limpu.hita.data.model.eas.CourseScoreItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.eas.ScoreQueryResult
import cn.limpu.hita.data.model.eas.ScoreSummary
import cn.limpu.hita.data.model.eas.ScoreSummaryScope
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogPage
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogSource
import cn.limpu.hita.data.model.eas.ShenzhenCourseAttachment
import cn.limpu.hita.data.model.eas.ShenzhenGradeAnalysis
import cn.limpu.hita.data.model.eas.ShenzhenGradeAnalysisScope
import cn.limpu.hita.data.model.eas.ShenzhenGradeCourse
import cn.limpu.hita.data.model.eas.ShenzhenGradeStatus
import cn.limpu.hita.data.model.eas.ShenzhenHistoricalFailureReport
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogItem
import cn.limpu.hita.data.model.eas.ShenzhenCourseRecommendationResult
import cn.limpu.hita.data.model.eas.ShenzhenCreditProgress
import cn.limpu.hita.data.model.eas.ShenzhenCreditRequirement
import cn.limpu.hita.data.model.eas.ShenzhenRecommendationOptions
import cn.limpu.hita.data.model.eas.ShenzhenSelectionPool
import cn.limpu.hita.data.model.eas.ShenzhenTrainingPlan
import cn.limpu.hita.data.model.eas.ShenzhenTrainingPlanCourse
import cn.limpu.hita.data.model.eas.ShenzhenTrainingPlanDetail
import cn.limpu.hita.data.model.eas.ShenzhenTrainingPlanLevel
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.source.web.service.EASService
import cn.limpu.hita.data.repository.EasCredentialReloginPolicy
import cn.limpu.hita.data.source.preference.EasCredential
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.utils.JsonUtils
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher

internal fun interface ShenzhenCourseSelectionTransport {
    @Throws(IOException::class)
    fun execute(request: Connection): Connection.Response
}

internal object ShenzhenSelectedCourseIdentityParser {
    private val rawIdentityKeys = setOf("id", "rwid", "rwh", "selectionrequestid", "taskid")

    fun parse(body: String): Set<String> {
        val root = try {
            JsonParser().parse(body)
        } catch (_: JsonParseException) {
            return emptySet()
        }
        if (!root.isJsonObject) return emptySet()
        val rows = findSelectedRows(root) ?: return emptySet()
        return buildSet {
            rows.forEach { element ->
                val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                row.entrySet().forEach { (key, value) ->
                    val normalizedKey = key.replace("_", "").lowercase(Locale.ROOT)
                    if (normalizedKey in rawIdentityKeys) {
                        rawString(value)?.let(::add)
                    }
                }
            }
        }
    }

    private fun findSelectedRows(element: JsonElement): JsonArray? {
        if (!element.isJsonObject) return null
        val objectValue = element.asJsonObject
        objectValue.entrySet()
            .firstOrNull { (key, _) -> key.replace("_", "").equals("yxkclist", ignoreCase = true) }
            ?.value
            ?.let(::rowsFromContainer)
            ?.let { return it }
        for (wrapper in listOf("content", "data", "result")) {
            objectValue.get(wrapper)?.let(::findSelectedRows)?.let { return it }
        }
        return null
    }

    private fun rowsFromContainer(element: JsonElement): JsonArray? = when {
        element.isJsonArray -> element.asJsonArray
        element.isJsonObject -> {
            val objectValue = element.asJsonObject
            sequenceOf("list", "rows")
                .mapNotNull { key -> objectValue.get(key)?.takeIf { it.isJsonArray }?.asJsonArray }
                .firstOrNull()
        }
        else -> null
    }

    private fun rawString(value: JsonElement): String? = value
        .takeIf { it.isJsonPrimitive }
        ?.asJsonPrimitive
        ?.asString
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
}

/**
 * 教务系统 API 数据源 —— 新版接口 (mjw.hitsz.edu.cn/incoSpringBoot)
 *
 * 认证方式：
 *  - 登录三步之前用 Basic Auth = "Basic aW5jb246MTIzNDU="
 *  - 登录成功后所有请求用 Authorization: bearer <access_token>
 *  - route / JSESSIONID cookie 由 Jsoup session 维护；每次新建请求时手动注入已保存的 cookies
 */
class EASWebSource internal constructor(
    private val onTokenRefreshed: ((EASToken) -> Unit)? = null,
    private val courseSelectionHostOverride: String? = null,
    private val courseSelectionTimeoutMillis: Int? = null,
    private val courseSelectionTransport: ShenzhenCourseSelectionTransport =
        ShenzhenCourseSelectionTransport { request -> request.execute() },
    private val credentialProvider: (EASToken.Campus, String?) -> EasCredential? = { _, _ -> null },
    private val apiHostOverride: String? = null,
    private val webHostOverride: String? = null,
) : EASService {

    private val hostName = apiHostOverride?.trimEnd('/') ?: "https://mjw.hitsz.edu.cn/incoSpringBoot"
    private val jwDirectHostName = "https://jw.hitsz.edu.cn"
    private val jwProxyHostName = "https://jw-hitsz-edu-cn.hitsz.edu.cn"
    private val basicAuth = "Basic aW5jb246MTIzNDU="
    private val timeout = 15000
    private val slowQueryTimeout = 60000
    private val DEBUG_WEEK = 6
    private val DEBUG_DOW = 1
    private val overviewTermDatesCache = ConcurrentHashMap<String, List<String>>()
    private val timetableSource = ShenzhenTimetableWebSource(
        jwFormPost = ::jwFormPost,
        jsonPost = ::jsonPost,
        isJwAuthenticationExpired = ::isJwAuthenticationExpired,
        debugWeek = DEBUG_WEEK,
        debugDow = DEBUG_DOW,
    )

    private fun jwHostName(token: EASToken): String =
        webHostOverride?.trimEnd('/')
            ?: if (token.webBaseUrl?.trimEnd('/') == jwProxyHostName) jwProxyHostName else jwDirectHostName

    private fun jwRequestTimeout(path: String): Int = if (
        listOf(
            "/Xsxk/",
            "/Xsxktz/",
            "/cjgl/grcjcx/",
            "/cjgl/cjzhtjcx/",
            "/faxq/",
            "/Njpyfakc/",
            "/Zdxpyfakz/"
        ).any(path::contains)
    ) {
        slowQueryTimeout
    } else {
        timeout
    }

    // ---------------------------------------------------------------- 公共头
    private fun baseHeaders(authorization: String, rolecode: String = "06"): Map<String, String> =
        mapOf(
            "authorization" to authorization,
            "rolecode" to rolecode,
            "_lang" to "cn",
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 15; V2183A Build/AP3A.240905.015.A2; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 Mobile Safari/537.36 uni-app Html5Plus/1.0 (Immersed/38.0)"
        )

    /** Form POST（登录流程，session 级别，维护 cookies） */
    private fun formPost(
        session: Connection,
        path: String,
        authorization: String,
        rolecode: String = "06",
        data: Map<String, String> = emptyMap()
    ): Connection.Response {
        val req = session.newRequest("$hostName$path")
            .headers(baseHeaders(authorization, rolecode))
            .timeout(timeout)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .method(Connection.Method.POST)
        data.forEach { (k, v) -> req.data(k, v) }
        return req.execute()
    }

    /** JSON POST（登录后） */
    private fun jsonPost(
        token: EASToken,
        path: String,
        body: String,
        rolecode: String = "06"
    ): Connection.Response {
        fun executeOnce(): Connection.Response {
            val resp = Jsoup.newSession()
                .url("$hostName$path")
                .headers(baseHeaders("bearer ${token.accessToken}", rolecode))
                .header("Content-Type", "application/json")
                .cookies(token.cookies)
                .requestBody(body)
                .timeout(timeout)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .method(Connection.Method.POST)
                .execute()
            token.cookies.putAll(resp.cookies())
            return resp
        }

        var resp = executeOnce()
        if (isAuthExpiredResponse(resp) && tryRelogin(token)) {
            resp = executeOnce()
        }
        return resp
    }

    /** Form POST（登录后，带已存 cookies） */
    private fun authedFormPost(
        token: EASToken,
        path: String,
        data: Map<String, String> = emptyMap()
    ): Connection.Response {
        fun executeOnce(): Connection.Response {
            val req = Jsoup.newSession()
                .url("$hostName$path")
                .headers(baseHeaders("bearer ${token.accessToken}"))
                .cookies(token.cookies)
                .timeout(timeout)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .method(Connection.Method.POST)
            data.forEach { (k, v) -> req.data(k, v) }
            val resp = req.execute()
            token.cookies.putAll(resp.cookies())
            return resp
        }

        var resp = executeOnce()
        if (isAuthExpiredResponse(resp)) {
            LogUtils.w("authedFormPost: auth expired, path=$path, attempting relogin...")
            if (tryRelogin(token)) {
                LogUtils.d("authedFormPost: relogin successful, retrying path=$path")
                resp = executeOnce()
            } else {
                LogUtils.e("authedFormPost: relogin failed, path=$path")
            }
        }
        return resp
    }


    /** 新教务网页接口（xszykb），依赖登录后会话 cookies */
    private fun jwFormPost(
        token: EASToken,
        path: String,
        data: Map<String, String> = emptyMap(),
        refererPath: String = "/authentication/main"
    ): Connection.Response {
        val jwHostName = jwHostName(token)
        val cookieJar = token.webCookies.takeIf { it.isNotEmpty() } ?: token.cookies
        if (token.webCookies.isEmpty()) {
            warmupJwSession(token)
        }

        fun executeOnce(): Connection.Response {
            val req = Jsoup.newSession()
                .url("$jwHostName$path")
                .header("Accept", "*/*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("RoleCode", "01")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", jwHostName)
                .header("Referer", "$jwHostName$refererPath")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
                .cookies(cookieJar)
                .timeout(jwRequestTimeout(path))
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .method(Connection.Method.POST)
            data.forEach { (k, v) -> req.data(k, v) }
            return req.execute().also { response -> cookieJar.putAll(response.cookies()) }
        }

        var resp = executeOnce()

        // jw 会话可能独立过期：检测 401 后尝试静默重登一次并重试同一请求
        if (token.webCookies.isEmpty() && resp.statusCode() == 401 && tryRelogin(token)) {
            warmupJwSession(token)
            resp = executeOnce()
        }
        onTokenRefreshed?.invoke(token)
        return resp
    }

    private fun jwSelectionFormPostOnce(
        token: EASToken,
        path: String,
        data: Map<String, String>,
        refererPath: String = "/Xsxk/query/1"
    ): Connection.Response {
        require(token.hasShenzhenWebSession()) { "A Shenzhen Web session is required" }
        val jwHostName = courseSelectionHostOverride?.trimEnd('/') ?: jwHostName(token)
        val cookieSnapshot = synchronized(token) { HashMap(token.webCookies) }
        val request = Jsoup.newSession()
            .url("$jwHostName$path")
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("RoleCode", "01")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", jwHostName)
            .header("Referer", "$jwHostName$refererPath")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
            .cookies(cookieSnapshot)
            .timeout(courseSelectionTimeoutMillis ?: jwRequestTimeout(path))
            .followRedirects(false)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .method(Connection.Method.POST)
        data.forEach { (key, value) -> request.data(key, value) }

        return courseSelectionTransport.execute(request)
    }

    private fun mergeShenzhenCourseSelectionCookies(
        token: EASToken,
        response: Connection.Response
    ) {
        synchronized(token) {
            token.webCookies.putAll(response.cookies())
        }
        onTokenRefreshed?.invoke(token)
    }

    private fun jwJsonPost(
        token: EASToken,
        path: String,
        body: String,
        refererPath: String = "/authentication/main"
    ): Connection.Response {
        val jwHostName = jwHostName(token)
        val cookieJar = token.webCookies.takeIf { it.isNotEmpty() } ?: token.cookies
        if (token.webCookies.isEmpty()) {
            warmupJwSession(token)
        }

        fun executeOnce(): Connection.Response {
            return Jsoup.newSession()
                .url("$jwHostName$path")
                .header("Accept", "*/*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("RoleCode", "01")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", jwHostName)
                .header("Referer", "$jwHostName$refererPath")
                .header("Content-Type", "application/json;charset=UTF-8")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
                .cookies(cookieJar)
                .requestBody(body)
                .timeout(jwRequestTimeout(path))
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .method(Connection.Method.POST)
                .execute()
                .also { response -> cookieJar.putAll(response.cookies()) }
        }

        var response = executeOnce()
        if (token.webCookies.isEmpty() && response.statusCode() == 401 && tryRelogin(token)) {
            warmupJwSession(token)
            response = executeOnce()
        }
        onTokenRefreshed?.invoke(token)
        return response
    }

    private fun isAuthExpiredResponse(resp: Connection.Response): Boolean {
        val body = resp.body()
        val expired = ShenzhenWebAuthenticationClassifier.isExpired(
            statusCode = resp.statusCode(),
            responseUrl = resp.url().toString(),
            body = body
        )
        if (expired) {
            val responseUrl = resp.url()
            LogUtils.w(
                "isAuthExpiredResponse: expired status=${resp.statusCode()} " +
                    "url=${responseUrl.protocol}://${responseUrl.host}${responseUrl.path} " +
                    "bodyLength=${body.length}"
            )
        }
        return expired
    }

    private fun isJwAuthenticationExpired(resp: Connection.Response): Boolean =
        isAuthExpiredResponse(resp)

    private fun isAuthExpiredJson(jo: JSONObject?): Boolean {
        return jo != null && ShenzhenWebAuthenticationClassifier.isExpired(
            statusCode = 200,
            responseUrl = null,
            body = jo.toString()
        )
    }

    private fun tryRelogin(token: EASToken): Boolean {
        val username = token.username?.trim().orEmpty()
        val password = EasCredentialReloginPolicy.passwordFor(
            tokenCampus = token.campus,
            tokenUsername = username,
            credential = runCatching {
                credentialProvider(EASToken.Campus.SHENZHEN, username)
            }.getOrNull()
        ).orEmpty()
        LogUtils.d("tryRelogin: hasUsername=${username.isNotBlank()}, hasPassword=${password.isNotBlank()}")
        if (username.isBlank() || password.isBlank()) {
            LogUtils.e("tryRelogin: failed, username or password blank")
            return false
        }
        val refreshed = loginCore(username, password)
        if (refreshed == null) {
            LogUtils.e("tryRelogin: failed, loginCore returned null")
            return false
        }
        LogUtils.d("tryRelogin: success, newToken=${refreshed.accessToken?.take(8)}, cookies=${refreshed.cookies.size}")
        token.accessToken = refreshed.accessToken
        token.refreshToken = refreshed.refreshToken
        refreshed.password = null
        token.cookies.clear()
        token.cookies.putAll(refreshed.cookies)
        onTokenRefreshed?.invoke(token)
        LogUtils.d("tryRelogin: token refreshed callback invoked")
        return true
    }

    private fun warmupJwSession(token: EASToken) {
        val jwHostName = jwHostName(token)
        val cookieJar = token.webCookies.takeIf { it.isNotEmpty() } ?: token.cookies
        try {
            val resp = Jsoup.newSession()
                .url("$jwHostName/authentication/main")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
                .cookies(cookieJar)
                .timeout(timeout)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .method(Connection.Method.GET)
                .execute()
            cookieJar.putAll(resp.cookies())
        } catch (_: Exception) {
        }
    }

    private fun buildCookieMap(vararg responses: Connection.Response): HashMap<String, String> {
        val cookies = HashMap<String, String>()
        responses.forEach { resp ->
            resp.cookies().forEach { (k, v) -> cookies[k] = v }
        }
        return cookies
    }

    private fun buildTokenFromPayload(
        payload: JSONObject?,
        username: String,
        password: String,
        cookies: Map<String, String>
    ): EASToken? {
        val accessToken = payload?.optString("access_token")
        if (accessToken.isNullOrEmpty()) return null
        val token = EASToken()
        token.campus = EASToken.Campus.SHENZHEN
        token.accessToken = accessToken
        token.refreshToken = payload.optString("refresh_token")
        token.username = username
        token.password = password
        token.cookies = HashMap(cookies)
        val dataObj = payload.optJSONObject("data")
        val infoObj = payload.optJSONObject("info")
        token.stutype =
            if (dataObj?.optString("pylx") == "1") EASToken.TYPE.UNDERGRAD else EASToken.TYPE.GRAD
        token.phone = dataObj?.optString("lxdh")
        token.name = dataObj?.optString("yhxm") ?: infoObj?.optString("xm")
        token.school = dataObj?.optString("bmmc")
        token.stuId = infoObj?.optString("yhdm")
        return token
    }

    private fun parseRsaPublicKeysFromRaskey(body: String): List<PublicKey> {
        val keys = mutableListOf<PublicKey>()
        val jo = JsonUtils.getJsonObject(body) ?: return keys
        val keyBase64 = jo.optString("CLIENT_RSA_EXPONENT")
        if (keyBase64.isNotBlank()) {
            try {
                val keyBytes = Base64.decode(keyBase64, Base64.DEFAULT)
                val keySpec = X509EncodedKeySpec(keyBytes)
                keys.add(KeyFactory.getInstance("RSA").generatePublic(keySpec))
            } catch (_: Exception) {
                // fall back to modulus/exponent parsing below
            }
        }
        val modulusRaw = jo.optString("CLIENT_RSA_MODULUS")
        val exponentRaw = jo.optString("CLIENT_RSA_EXPONENT")
        if (modulusRaw.isBlank() || exponentRaw.isBlank()) return keys
        val candidates = listOf(
            parseBigInteger(modulusRaw, preferHex = true) to parseBigInteger(exponentRaw, preferHex = true),
            parseBigInteger(modulusRaw, preferHex = false) to parseBigInteger(exponentRaw, preferHex = false)
        )
        for ((modulus, exponent) in candidates) {
            if (modulus == null || exponent == null) continue
            runCatching {
                val spec = RSAPublicKeySpec(modulus, exponent)
                keys.add(KeyFactory.getInstance("RSA").generatePublic(spec))
            }.getOrNull()
        }
        return keys
    }

    private fun parseBigInteger(raw: String, preferHex: Boolean): BigInteger? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("0x", ignoreCase = true)) {
            return runCatching { BigInteger(trimmed.substring(2), 16) }.getOrNull()
        }
        val isNumeric = trimmed.all { it.isDigit() }
        if (preferHex) {
            if (isNumeric && trimmed.length <= 5) {
                // Common exponent "10001" usually means 0x10001 (65537)
                return runCatching { BigInteger(trimmed, 16) }.getOrNull()
            }
            return runCatching { BigInteger(trimmed, 16) }.getOrNull()
        }
        return runCatching { BigInteger(trimmed, if (isNumeric) 10 else 16) }.getOrNull()
    }

    private fun encryptPasswordWithRsa(password: String, publicKey: PublicKey): String? {
        return runCatching {
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        }.getOrNull()
    }

    // ================================================================ 登录
    override fun login(
        username: String,
        password: String,
        code: String?
    ): LiveData<DataState<EASToken>> {
        val res = MutableLiveData<DataState<EASToken>>()
        Thread {
            try {
                if (username.trim().startsWith("{")) {
                    val webCookies = JsonUtils.jsonStringToMap(username)
                    val webToken = EASToken().apply {
                        campus = EASToken.Campus.SHENZHEN
                        this.webCookies.putAll(webCookies)
                        webBaseUrl = password.trimEnd('/').takeIf {
                            it == jwProxyHostName || it == jwDirectHostName
                        }
                    }
                    val enriched = fetchShenzhenWebPersonalInfo(webToken)
                    if (enriched == null) {
                        res.postValue(DataState(DataState.STATE.FETCH_FAILED, "深圳 Web 会话验证失败"))
                    } else {
                        res.postValue(DataState(enriched, DataState.STATE.SUCCESS))
                    }
                    return@Thread
                }
                val token = loginCore(username, password)
                if (token == null) {
                    res.postValue(DataState(DataState.STATE.FETCH_FAILED, "登录失败"))
                    return@Thread
                }
                res.postValue(DataState(token, DataState.STATE.SUCCESS))
            } catch (e: Exception) {
                LogUtils.e("login: failed, error=${e.message}", e)
                res.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return res
    }

    private fun loginCore(username: String, password: String): EASToken? {
        val session = Jsoup.newSession()
            .headers(
                mapOf(
                    "_lang" to "cn",
                    "Accept" to "*/*",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/144.0 Mobile Safari/537.36 uni-app",
                    "Accept-Encoding" to "gzip",
                    "Connection" to "Keep-Alive"
                )
            )

        val rsaResp = formPost(session, "/component/queryApplicationSetting/rsa", basicAuth, rolecode = "01")
        LogUtils.d("loginCore: RSA step status=${rsaResp.statusCode()}, body=${rsaResp.body().take(300)}")

        val raskeyResp = formPost(session, "/c_raskey", basicAuth, rolecode = "06")
        LogUtils.d("loginCore: raskey step status=${raskeyResp.statusCode()}, body=${raskeyResp.body().take(300)}")

        val rsaPublicKeys = parseRsaPublicKeysFromRaskey(raskeyResp.body())
        LogUtils.d("loginCore: parsed ${rsaPublicKeys.size} RSA public keys")
        val rolecodes = listOf("06", "01")

        var payload: JSONObject? = null
        var token: EASToken? = null
        for (role in rolecodes) {
            val ldapResp = formPost(
                session, "/authentication/ldap", basicAuth, rolecode = role,
                data = mapOf("username" to username, "password" to password)
            )
            LogUtils.d("loginCore: LDAP plain role=$role status=${ldapResp.statusCode()}, body=${ldapResp.body().take(300)}")
            payload = JsonUtils.getJsonObject(ldapResp.body())
            token = buildTokenFromPayload(
                payload,
                username,
                password,
                buildCookieMap(rsaResp, raskeyResp, ldapResp)
            )
            if (token != null) {
                LogUtils.d("loginCore: plain-text login SUCCESS with role=$role")
                return token
            }
        }
        if (rsaPublicKeys.isNotEmpty()) {
            LogUtils.d("loginCore: trying RSA-encrypted login with ${rsaPublicKeys.size} keys")
            loop@ for (role in rolecodes) {
                for ((keyIdx, publicKey) in rsaPublicKeys.withIndex()) {
                    val encrypted = encryptPasswordWithRsa(password, publicKey) ?: continue
                    val ldapResp = formPost(
                        session, "/authentication/ldap", basicAuth, rolecode = role,
                        data = mapOf("username" to username, "password" to encrypted)
                    )
                    LogUtils.d("loginCore: LDAP RSA role=$role key=$keyIdx status=${ldapResp.statusCode()}, body=${ldapResp.body().take(300)}")
                    payload = JsonUtils.getJsonObject(ldapResp.body())
                    token = buildTokenFromPayload(
                        payload,
                        username,
                        password,
                        buildCookieMap(rsaResp, raskeyResp, ldapResp)
                    )
                    if (token != null) {
                        LogUtils.d("loginCore: RSA login SUCCESS with role=$role key=$keyIdx")
                        break@loop
                    }
                }
            }
        }
        LogUtils.e("loginCore: ALL login attempts failed, username=$username")
        return token
    }

    // ================================================================ 检查登录状态
    override fun loginCheck(token: EASToken): LiveData<DataState<Pair<Boolean, EASToken>>> {
        val res = MutableLiveData<DataState<Pair<Boolean, EASToken>>>()
        Thread {
            try {
                if (token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()) {
                    val enriched = fetchShenzhenWebPersonalInfo(token)
                    res.postValue(DataState(Pair(enriched != null, enriched ?: token)))
                    return@Thread
                }
                val resp = authedFormPost(token, "/app/commapp/queryxnxqlist")
                val jo = JsonUtils.getJsonObject(resp.body())
                val valid = jo?.optInt("code", -1) == 200
                res.postValue(DataState(Pair(valid, token)))
            } catch (e: Exception) {
                res.postValue(DataState(DataState.STATE.FETCH_FAILED))
            }
        }.start()
        return res
    }

    // ================================================================ 学年学期列表
    override fun getAllTerms(token: EASToken): LiveData<DataState<List<TermItem>>> {
        if (token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()) {
            return getShenzhenWebTerms(token)
        }
        val res = MutableLiveData<DataState<List<TermItem>>>()
        Thread {
            val terms = arrayListOf<TermItem>()
            try {
                val resp = authedFormPost(token, "/app/commapp/queryxnxqlist")
                val jo = JsonUtils.getJsonObject(resp.body())
                val content = jo?.optJSONArray("content")
                if (content == null) {
                    res.postValue(DataState(DataState.STATE.NOT_LOGGED_IN))
                    return@Thread
                }
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i) ?: continue
                    val term = TermItem(
                        yearCode = item.optString("XN"),
                        yearName = item.optString("XN"),
                        termCode = item.optString("XQ"),
                        termName = item.optString("XNXQMC")
                    )
                    term.name = buildTermDisplayName(term.yearName, term.termName)
                    term.isCurrent = item.optString("SFDQXQ") == "1"
                    terms.add(term)
                }
                res.postValue(DataState(terms, DataState.STATE.SUCCESS))
} catch (e: Exception) {
                LogUtils.e("getAllTerms: failed, error=${e.message}", e)
                res.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return res
    }

    fun getShenzhenWebTerms(token: EASToken): LiveData<DataState<List<TermItem>>> {
        val result = MutableLiveData<DataState<List<TermItem>>>()
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            try {
                // The course-selection module has its own complete term dropdown. Unlike the
                // generic historical-term endpoint, it includes a newly opened next term while
                // the academic calendar is still in the preceding summer term.
                var response = jwFormPost(
                    token,
                    "/component/queryXnxq",
                    refererPath = "/Xsxk/query/1"
                )
                if (isJwAuthenticationExpired(response)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                var terms = ShenzhenCourseCatalogParser.parseTerms(response.body())
                if (response.statusCode() != 200 || terms == null) {
                    // Keep compatibility with the previous portal while deployments are rolling
                    // between versions.
                    response = jwFormPost(token, "/component/queryxnxqdata")
                    if (isJwAuthenticationExpired(response)) {
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                        return@Thread
                    }
                    terms = ShenzhenCourseCatalogParser.parseTerms(response.body())
                }
                if (response.statusCode() != 200 || terms == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "学期列表解析失败"))
                } else {
                    val selectionResponse = jwFormPost(
                        token,
                        "/Xsxk/queryXkdqXnxq",
                        mapOf("p_pylx" to token.getStudentType()),
                        "/Xsxk/query/1"
                    )
                    val selectionTermId = if (isJwAuthenticationExpired(selectionResponse)) {
                        null
                    } else {
                        ShenzhenCourseCatalogParser.parseSelectionTermId(selectionResponse.body())
                    }
                    if (selectionTermId != null && terms.any { it.id == selectionTermId }) {
                        terms.forEach { it.isCurrent = it.id == selectionTermId }
                    }
                    LogUtils.d(
                        "getShenzhenWebTerms: count=${terms.size}, selectionTerm=$selectionTermId, " +
                            "selectedPresent=${terms.any { it.id == selectionTermId }}"
                    )
                    result.postValue(DataState(terms, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    fun queryShenzhenAvailableCourses(
        token: EASToken,
        term: TermItem,
        pool: ShenzhenSelectionPool,
        keyword: String,
        page: Int,
        pageSize: Int
    ): LiveData<DataState<ShenzhenCourseCatalogPage>> {
        val form = linkedMapOf(
            "cxsfmt" to "0",
            "p_pylx" to token.getStudentType(),
            "mxpylx" to token.getStudentType(),
            "p_sfgldjr" to "0",
            "p_sfredis" to "0",
            "p_sfsyxkgwc" to "0",
            "p_xktjz" to "",
            "p_chaxunxh" to "",
            "p_gjz" to keyword,
            "p_skjs" to "",
            "p_xn" to term.yearCode,
            "p_xq" to term.termCode,
            "p_xnxq" to term.getCode(),
            "p_xkfsdm" to pool.code,
            "p_xiaoqu" to "",
            "p_kkyx" to "",
            "p_kclb" to "",
            "p_xkxs" to "",
            "p_dyc" to "",
            "p_kkxnxq" to "",
            "p_id" to "",
            "p_sfhlctkc" to "0",
            "p_sfhllrlkc" to "0",
            "p_kxsj_xqj" to "",
            "p_kxsj_ksjc" to "",
            "p_kxsj_jsjc" to "",
            "p_kcdm_js" to "",
            "p_kcdm_cxrw" to "",
            "p_kcdm_cxrw_zckc" to "",
            "p_kc_gjz" to keyword,
            "p_xzcxtjz_nj" to "",
            "p_xzcxtjz_yx" to "",
            "p_xzcxtjz_zy" to "",
            "p_xzcxtjz_zyfx" to "",
            "p_xzcxtjz_bj" to "",
            "p_sfxsgwckb" to "1",
            "p_skyy" to "",
            "p_sfmxzj" to "0",
            "p_chaxunxkfsdm" to "",
            "pageNum" to page.toString(),
            "pageSize" to pageSize.toString()
        )
        return queryShenzhenCourseCatalog(
            token = token,
            path = "/Xsxk/queryKxrw?sf_request_type=ajax",
            refererPath = "/Xsxk/query/1",
            form = form,
            source = ShenzhenCourseCatalogSource.AVAILABLE,
            selectionPoolName = pool.name
        )
    }

    @WorkerThread
    fun submitShenzhenCourseOnce(
        token: EASToken,
        job: CourseSelectionJob,
        course: CourseSelectionJobCourse
    ): CourseSelectionCourseResult {
        require(token.hasShenzhenWebSession()) { "A Shenzhen Web session is required" }
        val submittedAtMillis = System.currentTimeMillis()
        val response = try {
            jwSelectionFormPostOnce(
                token = token,
                path = "/Xsxk/addGouwuche",
                data = ShenzhenCourseSelectionForm.build(
                    studentType = token.getStudentType(),
                    termYearCode = job.termYearCode,
                    termCode = job.termCode,
                    poolCode = course.poolCode,
                    requestId = course.requestId
                )
            )
        } catch (_: IOException) {
            return CourseSelectionCourseResult(
                courseId = course.courseId,
                status = CourseSelectionCourseStatus.UNKNOWN,
                message = "",
                submittedAtMillis = submittedAtMillis
            )
        }
        mergeShenzhenCourseSelectionCookies(token, response)
        val parsed = ShenzhenCourseSelectionResponseParser.parse(
            statusCode = response.statusCode(),
            responseUrl = response.url().toString(),
            body = response.body()
        )
        return CourseSelectionCourseResult(
            courseId = course.courseId,
            status = parsed.status,
            message = parsed.message,
            submittedAtMillis = submittedAtMillis
        )
    }

    @WorkerThread
    fun getShenzhenSelectedRequestIdsOnce(
        token: EASToken,
        job: CourseSelectionJob
    ): Set<String> {
        require(token.hasShenzhenWebSession()) { "A Shenzhen Web session is required" }
        val response = try {
            jwSelectionFormPostOnce(
                token = token,
                path = "/Xsxk/queryYxkc?sf_request_type=ajax",
                data = shenzhenSelectedSubjectsForm(
                    token,
                    TermItem(job.termYearCode, job.termYearCode, job.termCode, "")
                )
            )
        } catch (_: IOException) {
            return emptySet()
        }
        mergeShenzhenCourseSelectionCookies(token, response)
        if (isJwAuthenticationExpired(response) || response.statusCode() !in 200..299) {
            return emptySet()
        }
        return ShenzhenSelectedCourseIdentityParser.parse(response.body())
    }

    fun queryShenzhenSchoolCourses(
        token: EASToken,
        term: TermItem,
        studentType: String,
        keyword: String,
        page: Int,
        pageSize: Int
    ): LiveData<DataState<ShenzhenCourseCatalogPage>> {
        val form = shenzhenSchoolCourseForm(term, studentType, keyword, page, pageSize)
        return queryShenzhenCourseCatalog(
            token = token,
            path = "/Xsxktz/queryRwxxcxList?sf_request_type=ajax",
            refererPath = "/Xsxktz/queryRwxxcx",
            form = form,
            source = ShenzhenCourseCatalogSource.SCHOOL,
            studentType = studentType
        )
    }

    private fun shenzhenSchoolCourseForm(
        term: TermItem,
        studentType: String,
        keyword: String,
        page: Int,
        pageSize: Int
    ) = linkedMapOf(
            "p_chapylx" to "",
            "ordertext_0" to "",
            "p_xn" to term.yearCode,
            "p_xq" to term.termCode,
            "p_xnxq" to term.getCode(),
            "p_gjz" to keyword,
            "p_xiaoqu" to "",
            "p_kkyx" to "",
            "p_rwlx" to "",
            "p_kclb" to "",
            "p_kcxz" to "",
            "p_chaxungjz" to keyword,
            "p_chaxunxiaoqu" to "",
            "p_chaxunkkyx" to "",
            "p_chaxunnj" to "",
            "p_chaxunglyx" to "",
            "p_chaxunzy" to "",
            "p_chaxunxdm" to "",
            "p_chaxunpylx" to studentType,
            "mxpylx" to studentType,
            "p_zc" to "",
            "p_xqj" to "",
            "p_ksjc" to "",
            "p_jsjc" to "",
            "p_skjs" to "",
            "p_ids" to "",
            "p_id" to "",
            "p_sfhltsxx" to "0",
            "file" to "",
            "pageNum" to page.toString(),
            "pageSize" to pageSize.toString()
        )

    private fun queryShenzhenCourseCatalog(
        token: EASToken,
        path: String,
        refererPath: String,
        form: Map<String, String>,
        source: ShenzhenCourseCatalogSource,
        studentType: String = token.getStudentType(),
        selectionPoolName: String = ""
    ): LiveData<DataState<ShenzhenCourseCatalogPage>> {
        val result = MutableLiveData<DataState<ShenzhenCourseCatalogPage>>()
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            try {
                if (source == ShenzhenCourseCatalogSource.AVAILABLE) {
                    // The portal binds queryKxrw to state initialized by queryYxkc for the same
                    // term and selection mode. Calling queryKxrw directly can return jg=-1 or an
                    // empty list even while courses are available.
                    val initialization = jwFormPost(
                        token,
                        "/Xsxk/queryYxkc?sf_request_type=ajax",
                        form,
                        "/Xsxk/query/1"
                    )
                    if (isJwAuthenticationExpired(initialization)) {
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                        return@Thread
                    }
                    if (initialization.statusCode() != 200) {
                        result.postValue(DataState(DataState.STATE.FETCH_FAILED, "选课查询初始化失败"))
                        return@Thread
                    }
                }
                val response = jwFormPost(token, path, form, refererPath)
                if (isJwAuthenticationExpired(response)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val page = ShenzhenCourseCatalogParser.parsePage(
                    response.body(),
                    source,
                    studentType,
                    selectionPoolName
                )
                if (response.statusCode() != 200 || page == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "课程数据解析失败"))
                } else {
                    result.postValue(DataState(page, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    fun getShenzhenCourseRecommendations(
        token: EASToken,
        term: TermItem,
        pools: List<ShenzhenSelectionPool>,
        options: ShenzhenRecommendationOptions,
        requirements: List<ShenzhenCreditRequirement> = emptyList()
    ): LiveData<DataState<ShenzhenCourseRecommendationResult>> {
        val result = MutableLiveData(
            DataState<ShenzhenCourseRecommendationResult>(DataState.STATE.NOTHING)
        )
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            try {
                val selectedResponse = requestShenzhenWebSelectedSubjects(token, term)
                if (isJwAuthenticationExpired(selectedResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val selectedPage = selectedResponse.takeIf { it.statusCode() == 200 }?.let {
                    ShenzhenCourseCatalogParser.parsePage(
                        it.body(),
                        ShenzhenCourseCatalogSource.AVAILABLE,
                        token.getStudentType()
                    )
                }
                if (selectedPage == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "已选课程读取失败"))
                    return@Thread
                }
                val selected = selectedPage.items
                val effectivePools = ShenzhenCourseCatalogParser
                    .parseSelectionPools(selectedResponse.body())
                    .ifEmpty { pools }

                val candidates = mutableListOf<ShenzhenCourseCatalogItem>()
                effectivePools.forEach { pool ->
                    val initializationForm = recommendationAvailableCourseForm(
                        token = token,
                        term = term,
                        pool = pool,
                        page = 1,
                        pageSize = 200
                    )
                    val initialization = jwFormPost(
                        token = token,
                        path = "/Xsxk/queryYxkc?sf_request_type=ajax",
                        data = initializationForm,
                        refererPath = "/Xsxk/query/1"
                    )
                    if (isJwAuthenticationExpired(initialization)) {
                        result.postValue(
                            DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效")
                        )
                        return@Thread
                    }
                    if (initialization.statusCode() != 200) {
                        result.postValue(
                            DataState(
                                DataState.STATE.FETCH_FAILED,
                                "选课池“${pool.name}”初始化失败"
                            )
                        )
                        return@Thread
                    }
                    var pageNumber = 1
                    var hasNext: Boolean
                    do {
                        val response = jwFormPost(
                            token = token,
                            path = "/Xsxk/queryKxrw?sf_request_type=ajax",
                            data = recommendationAvailableCourseForm(
                                token = token,
                                term = term,
                                pool = pool,
                                page = pageNumber,
                                pageSize = 200
                            ),
                            refererPath = "/Xsxk/query/1"
                        )
                        if (isJwAuthenticationExpired(response)) {
                            result.postValue(
                                DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效")
                            )
                            return@Thread
                        }
                        val page = if (response.statusCode() == 200) {
                            ShenzhenCourseCatalogParser.parsePage(
                                response.body(),
                                ShenzhenCourseCatalogSource.AVAILABLE,
                                token.getStudentType(),
                                pool.name
                            )
                        } else null
                        if (page == null) {
                            result.postValue(
                                DataState(
                                    DataState.STATE.FETCH_FAILED,
                                    "选课池“${pool.name}”读取失败"
                                )
                            )
                            return@Thread
                        }
                        candidates += page.items
                        hasNext = page.hasNextPage
                        pageNumber++
                    } while (hasNext)
                }

                val recommendation = ShenzhenCourseRecommendationEngine.recommend(
                    selected = selected,
                    candidates = candidates,
                    options = options,
                    requirements = requirements
                )
                result.postValue(DataState(recommendation, DataState.STATE.SUCCESS))
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    private fun recommendationAvailableCourseForm(
        token: EASToken,
        term: TermItem,
        pool: ShenzhenSelectionPool,
        page: Int,
        pageSize: Int
    ) = linkedMapOf(
        "cxsfmt" to "0",
        "p_pylx" to token.getStudentType(),
        "mxpylx" to token.getStudentType(),
        "p_sfgldjr" to "0",
        "p_sfredis" to "0",
        "p_sfsyxkgwc" to "0",
        "p_xktjz" to "",
        "p_chaxunxh" to "",
        "p_gjz" to "",
        "p_skjs" to "",
        "p_xn" to term.yearCode,
        "p_xq" to term.termCode,
        "p_xnxq" to term.getCode(),
        "p_xkfsdm" to pool.code,
        "p_xiaoqu" to "",
        "p_kkyx" to "",
        "p_kclb" to "",
        "p_xkxs" to "",
        "p_dyc" to "",
        "p_kkxnxq" to "",
        "p_id" to "",
        "p_sfhlctkc" to "0",
        "p_sfhllrlkc" to "0",
        "p_kxsj_xqj" to "",
        "p_kxsj_ksjc" to "",
        "p_kxsj_jsjc" to "",
        "p_kcdm_js" to "",
        "p_kcdm_cxrw" to "",
        "p_kcdm_cxrw_zckc" to "",
        "p_kc_gjz" to "",
        "p_xzcxtjz_nj" to "",
        "p_xzcxtjz_yx" to "",
        "p_xzcxtjz_zy" to "",
        "p_xzcxtjz_zyfx" to "",
        "p_xzcxtjz_bj" to "",
        "p_sfxsgwckb" to "1",
        "p_skyy" to "",
        "p_sfmxzj" to "0",
        "p_chaxunxkfsdm" to "",
        "pageNum" to page.toString(),
        "pageSize" to pageSize.toString()
    )

    fun getShenzhenCourseAttachments(
        token: EASToken,
        courseId: String,
        taskNumber: String
    ): LiveData<DataState<List<ShenzhenCourseAttachment>>> {
        val result = MutableLiveData<DataState<List<ShenzhenCourseAttachment>>>()
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            if (courseId.isBlank() || taskNumber.isBlank()) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, "课程缺少详情标识"))
                return@Thread
            }
            try {
                val response = jwFormPost(
                    token = token,
                    path = "/kck/kcxxwh/xsckViewByxk?sf_request_type=ajax",
                    data = mapOf(
                        "kcid" to courseId,
                        "kcsqid" to "",
                        "rwh" to taskNumber
                    ),
                    refererPath = "/Xsxk/query/1"
                )
                if (isJwAuthenticationExpired(response)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val attachments = ShenzhenCourseCatalogParser.parseAttachments(
                    response.body(),
                    courseId
                )
                if (response.statusCode() != 200 || attachments == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "课程附件解析失败"))
                } else {
                    result.postValue(DataState(attachments, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    fun getShenzhenGradeCourses(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<List<ShenzhenGradeCourse>>> {
        val result = MutableLiveData<DataState<List<ShenzhenGradeCourse>>>()
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            try {
                val publishedResponse = jwJsonPost(
                    token = token,
                    path = "/cjgl/grcjcx/grcjcx",
                    body = JSONObject()
                        .put("xn", term.yearCode)
                        .put("xq", term.termCode)
                        .put("kcmc", JSONObject.NULL)
                        .put("cxbj", "-1")
                        .put("pylx", token.getStudentType())
                        .put("current", 1)
                        .put("pageSize", 1000)
                        .put("xscjlb", JSONObject.NULL)
                        .put("sffx", JSONObject.NULL)
                        .toString(),
                    refererPath = "/cjgl/grcjcx"
                )
                val selectedResponse = requestShenzhenWebSelectedSubjects(token, term)
                if (isJwAuthenticationExpired(publishedResponse) ||
                    isJwAuthenticationExpired(selectedResponse)
                ) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                if (publishedResponse.statusCode() != 200 || selectedResponse.statusCode() != 200) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "课程成绩列表请求失败"))
                    return@Thread
                }

                var earlyBody: String? = null
                val identityResponse = jwFormPost(
                    token = token,
                    path = "/UserManager/queryxsxx",
                    refererPath = "/cjgl/cjzhtjcx/cjcx"
                )
                if (isJwAuthenticationExpired(identityResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val identity = identityResponse.takeIf { it.statusCode() == 200 }
                    ?.let { ShenzhenCreditProgressParser.parseIdentity(it.body()) }
                val studentNumber = identity?.studentNumber.orEmpty()
                    .ifBlank { token.stuId.orEmpty() }
                    .ifBlank { token.username.orEmpty() }
                var studentRecordId = identity?.studentRecordId.orEmpty()
                if (studentNumber.isNotBlank()) {
                    if (studentRecordId.isBlank()) {
                        val studentResponse = jwJsonPost(
                            token = token,
                            path = "/cjgl/cjzhtjcx/cjcx/getXs",
                            body = JSONObject().put("xjidorxh", studentNumber).toString(),
                            refererPath = "/cjgl/cjzhtjcx/cjcx"
                        )
                        if (isJwAuthenticationExpired(studentResponse)) {
                            result.postValue(
                                DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效")
                            )
                            return@Thread
                        }
                        studentRecordId = if (studentResponse.statusCode() == 200) {
                            ShenzhenGradeParser.parseStudentRecordId(studentResponse.body())
                                .orEmpty()
                        } else ""
                    }
                    studentRecordId = studentRecordId.ifBlank { token.id.orEmpty() }
                    if (studentRecordId.isNotBlank()) {
                        token.stuId = studentNumber
                        token.id = studentRecordId
                        identity?.studentType?.takeIf { it == "1" || it == "2" }?.let {
                            token.stutype = if (it == "1") {
                                EASToken.TYPE.UNDERGRAD
                            } else EASToken.TYPE.GRAD
                        }
                        onTokenRefreshed?.invoke(token)
                        val earlyResponse = jwJsonPost(
                            token = token,
                            path = "/cjgl/cjzhtjcx/cjcx/queryZxcjPage",
                            body = JSONObject()
                                .put("current", 1)
                                .put("pageSize", 200)
                                .put("xh", studentNumber)
                                .put("xjid", studentRecordId)
                                .put("pylx", token.getStudentType())
                                .toString(),
                            refererPath = "/cjgl/cjzhtjcx/cjcx"
                        )
                        if (earlyResponse.statusCode() == 200 &&
                            !isJwAuthenticationExpired(earlyResponse)
                        ) {
                            earlyBody = earlyResponse.body()
                        }
                    }
                }
                LogUtils.w(
                    "getShenzhenGradeCourses: identity=${identity != null}, " +
                        "studentNumber=${studentNumber.isNotBlank()}, " +
                        "studentRecordId=${studentRecordId.isNotBlank()}, " +
                        "personalScores=${earlyBody != null}"
                )
                val earlyDiagnostics = ShenzhenGradeParser.earlyScoreDiagnostics(earlyBody)
                LogUtils.d(
                    "getShenzhenGradeCourses: personalScoreRows=${earlyDiagnostics.first}, " +
                        "numericPersonalScores=${earlyDiagnostics.second}"
                )

                val courses = ShenzhenGradeParser.parseCourses(
                    publishedBody = publishedResponse.body(),
                    selectedBody = selectedResponse.body(),
                    earlyBody = earlyBody,
                    term = term
                )
                LogUtils.d(
                    "getShenzhenGradeCourses: courses=${courses?.size ?: 0}, " +
                        "earlyScores=${courses?.count {
                            it.status == ShenzhenGradeStatus.EARLY && it.myScore != null
                        } ?: 0}"
                )
                if (courses == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "课程成绩列表解析失败"))
                } else {
                    result.postValue(DataState(courses, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    fun getShenzhenGradeAnalysis(
        token: EASToken,
        course: ShenzhenGradeCourse
    ): LiveData<DataState<ShenzhenGradeAnalysis>> {
        val result = MutableLiveData<DataState<ShenzhenGradeAnalysis>>()
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            if (course.taskId.isBlank()) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, "该课程缺少教学任务标识"))
                return@Thread
            }
            if (course.recordId.isBlank()) {
                result.postValue(
                    DataState(
                        DataState.STATE.FETCH_FAILED,
                        "该课程尚未提供可查询的个人成绩分项"
                    )
                )
                return@Thread
            }
            try {
                val response = jwFormPost(
                    token = token,
                    path = "/cjgl/grcjcx/seeFx?sf_request_type=ajax",
                    data = mapOf(
                        "rwid" to course.taskId,
                        "cjid" to course.recordId
                    ),
                    refererPath = "/cjgl/grcjcx/go/1"
                )
                val diagnostics = ShenzhenGradeParser.analysisResponseDiagnostics(response.body())
                LogUtils.d(
                    "getShenzhenGradeAnalysis: HTTP ${response.statusCode()}, " +
                        "bodyLength=${response.body().length}, ${diagnostics.logSummary()}"
                )
                val permissionRestricted = isGradeAnalysisPermissionRestricted(
                    response.statusCode(),
                    diagnostics
                )
                if (!permissionRestricted && isJwAuthenticationExpired(response)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val analysis = if (response.statusCode() == 200) {
                    ShenzhenGradeParser.analyze(
                        course,
                        response.body(),
                        ShenzhenGradeAnalysisScope.PERSONAL
                    )
                } else null
                if (analysis == null) {
                    result.postValue(
                        DataState(
                            DataState.STATE.FETCH_FAILED,
                            gradeAnalysisFailureMessage(
                                response.statusCode(),
                                diagnostics,
                                permissionRestricted
                            )
                        )
                    )
                } else {
                    result.postValue(DataState(analysis, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    fun getShenzhenHistoricalTeacherFailureRates(
        token: EASToken,
        referenceTerm: TermItem,
        studentType: String,
        referenceCourse: ShenzhenCourseCatalogItem,
        yearsBack: Int = 2
    ): LiveData<DataState<ShenzhenHistoricalFailureReport>> {
        val result = MutableLiveData<DataState<ShenzhenHistoricalFailureReport>>()
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            // 旧实现依赖 seeFx 在不传 cjid 时泄露整个教学班成绩。2026-07 后端已修复：
            // 接口必须携带当前学生自己的成绩记录 ID，且只返回该学生的分项。全校课表
            // 只提供教学任务 ID，不提供也不应尝试推测其他学生的 cjid，因此教师/班型统计
            // 已没有合法可靠的数据源。
            if (!supportsClassWideGradeAnalysis()) {
                result.postValue(
                    DataState(
                        DataState.STATE.FETCH_FAILED,
                        "新版教务已将成绩分项限制为本人记录，无法再生成其他教学班或教师的成绩统计"
                    )
                )
                return@Thread
            }

            val targetTerm = ShenzhenHistoricalGradeAnalyzer.termYearsBefore(referenceTerm, yearsBack)
            if (targetTerm == null) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, "无法识别当前课程学年"))
                return@Thread
            }
            try {
                val keyword = referenceCourse.courseName.ifBlank { referenceCourse.courseCode }
                val matchedCourses = mutableListOf<ShenzhenCourseCatalogItem>()
                var pageNumber = 1
                var hasNextPage: Boolean
                do {
                    val response = jwFormPost(
                        token = token,
                        path = "/Xsxktz/queryRwxxcxList?sf_request_type=ajax",
                        data = shenzhenSchoolCourseForm(
                            term = targetTerm,
                            studentType = studentType,
                            keyword = keyword,
                            page = pageNumber,
                            pageSize = 200
                        ),
                        refererPath = "/Xsxktz/queryRwxxcx"
                    )
                    if (isJwAuthenticationExpired(response)) {
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                        return@Thread
                    }
                    val page = if (response.statusCode() == 200) {
                        ShenzhenCourseCatalogParser.parsePage(
                            response.body(),
                            ShenzhenCourseCatalogSource.SCHOOL,
                            studentType
                        )
                    } else null
                    if (page == null) {
                        result.postValue(DataState(DataState.STATE.FETCH_FAILED, "历史全校课表查询失败"))
                        return@Thread
                    }
                    matchedCourses += page.items.filter {
                        ShenzhenHistoricalGradeAnalyzer.matches(referenceCourse, it)
                    }
                    hasNextPage = page.hasNextPage
                    pageNumber++
                } while (hasNextPage)

                val distinctMatches = matchedCourses.distinctBy {
                    it.taskId.ifBlank { it.id }
                }
                val uniqueCourses = distinctMatches
                    .filter { it.taskId.isNotBlank() }
                val classStats = mutableListOf<ShenzhenHistoricalClassStats>()
                var skipped = distinctMatches.size - uniqueCourses.size
                uniqueCourses.forEach { course ->
                    val response = jwFormPost(
                        token = token,
                        path = "/cjgl/grcjcx/seeFx",
                        data = mapOf("rwid" to course.taskId),
                        refererPath = "/cjgl/grcjcx"
                    )
                    val diagnostics = ShenzhenGradeParser.analysisResponseDiagnostics(response.body())
                    LogUtils.w(
                        "getShenzhenHistoricalTeacherFailureRates: HTTP ${response.statusCode()}, " +
                            "bodyLength=${response.body().length}, ${diagnostics.logSummary()}"
                    )
                    val permissionRestricted = isGradeAnalysisPermissionRestricted(
                        response.statusCode(),
                        diagnostics
                    )
                    if (permissionRestricted) {
                        result.postValue(
                            DataState(
                                DataState.STATE.FETCH_FAILED,
                                gradeAnalysisFailureMessage(
                                    response.statusCode(),
                                    diagnostics,
                                    permissionRestricted = true
                                )
                            )
                        )
                        return@Thread
                    }
                    if (isJwAuthenticationExpired(response)) {
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                        return@Thread
                    }
                    val analysis = if (response.statusCode() == 200) {
                        ShenzhenGradeParser.analyze(
                            ShenzhenGradeCourse(
                                taskId = course.taskId,
                                taskNumber = course.taskNumber,
                                courseCode = course.courseCode,
                                courseName = course.courseName,
                                termCode = targetTerm.getCode(),
                                teacher = course.teacher
                            ),
                            response.body()
                        )
                    } else null
                    if (analysis == null) {
                        skipped++
                    } else {
                        classStats += ShenzhenHistoricalClassStats(
                            teacher = course.teacher,
                            scores = analysis.students.map { it.total },
                            excludedIncompleteStudentCount =
                                analysis.excludedIncompleteStudentCount
                        )
                    }
                }

                if (uniqueCourses.isNotEmpty() && classStats.isEmpty()) {
                    result.postValue(
                        DataState(
                            DataState.STATE.FETCH_FAILED,
                            "教务未返回这些教学班的可用成绩汇总，可能尚未录入或接口已调整"
                        )
                    )
                    return@Thread
                }

                val report = ShenzhenHistoricalFailureReport(
                    courseName = referenceCourse.courseName,
                    courseCode = referenceCourse.courseCode,
                    targetTerm = targetTerm,
                    matchedClassCount = distinctMatches.size,
                    analyzedClassCount = classStats.size,
                    skippedClassCount = skipped,
                    teacherRates = ShenzhenHistoricalGradeAnalyzer.aggregate(classStats)
                )
                result.postValue(DataState(report, DataState.STATE.SUCCESS))
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    private fun supportsClassWideGradeAnalysis(): Boolean = false

    private fun isGradeAnalysisPermissionRestricted(
        statusCode: Int,
        diagnostics: ShenzhenGradeParser.AnalysisResponseDiagnostics
    ): Boolean = statusCode == 403 || diagnostics.serverCode.trim() == "403" ||
        diagnostics.serverMessage.contains("无权限") ||
        diagnostics.serverMessage.contains("权限不足") ||
        diagnostics.serverMessage.contains("forbidden", ignoreCase = true)

    private fun gradeAnalysisFailureMessage(
        statusCode: Int,
        diagnostics: ShenzhenGradeParser.AnalysisResponseDiagnostics,
        permissionRestricted: Boolean
    ): String = when {
        permissionRestricted -> "教务拒绝访问该课程的个人成绩分项"
        statusCode == 404 -> "教务成绩分析接口已调整，当前版本暂不兼容"
        statusCode !in 200..299 -> "个人成绩分项请求失败（HTTP $statusCode）"
        diagnostics.structure == "html" -> "教务返回了页面而不是成绩数据，接口可能已经调整"
        diagnostics.rowCount == 0 -> "教务未返回该课程的个人分项，可能尚未录入"
        diagnostics.serverMessage.isNotBlank() -> diagnostics.serverMessage
        else -> "个人成绩分项响应结构已变化，当前版本暂时无法解析"
    }

    fun getShenzhenTrainingPlans(
        token: EASToken
    ): LiveData<DataState<List<ShenzhenTrainingPlan>>> {
        val result = MutableLiveData<DataState<List<ShenzhenTrainingPlan>>>()
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            try {
                val identityResponse = jwFormPost(
                    token,
                    "/UserManager/queryxsxx",
                    refererPath = "/authentication/main"
                )
                if (isJwAuthenticationExpired(identityResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val identity = if (identityResponse.statusCode() == 200) {
                    ShenzhenTrainingPlanParser.parseIdentity(identityResponse.body())
                } else null
                val major = identity?.major.orEmpty().ifBlank { token.major.orEmpty() }
                val grade = identity?.grade.orEmpty().ifBlank { token.grade.orEmpty() }
                    .let { Regex("20\\d{2}").find(it)?.value.orEmpty() }
                val studentType = identity?.studentType.orEmpty().ifBlank { token.getStudentType() }
                if (major.isBlank()) {
                    result.postValue(
                        DataState(
                            DataState.STATE.FETCH_FAILED,
                            "教务系统未返回你的专业信息，暂时无法匹配个人培养方案"
                        )
                    )
                    return@Thread
                }
                if (studentType == "1" && grade.isBlank()) {
                    result.postValue(
                        DataState(
                            DataState.STATE.FETCH_FAILED,
                            "教务系统未返回你的入学年级，暂时无法查询本科培养方案"
                        )
                    )
                    return@Thread
                }
                token.major = major
                token.grade = grade.ifBlank { token.grade }
                token.stutype = if (studentType == "1") EASToken.TYPE.UNDERGRAD else EASToken.TYPE.GRAD
                onTokenRefreshed?.invoke(token)

                val level = if (studentType == "1") {
                    ShenzhenTrainingPlanLevel.UNDERGRADUATE
                } else ShenzhenTrainingPlanLevel.POSTGRADUATE
                val baseForm = linkedMapOf(
                    "sf_request_type" to "ajax",
                    "key" to "",
                    "xkdm" to "",
                    "yxdm" to "",
                    "zydm" to "",
                    "zyfxdm" to "",
                    "bbh" to if (level == ShenzhenTrainingPlanLevel.POSTGRADUATE) "202603" else "",
                    "ywdm" to "",
                    "falx" to if (level == ShenzhenTrainingPlanLevel.UNDERGRADUATE) "3" else "2",
                    "njdm" to if (level == ShenzhenTrainingPlanLevel.UNDERGRADUATE) grade else "",
                    "cxby" to "",
                    "pylb" to if (level == ShenzhenTrainingPlanLevel.UNDERGRADUATE) "1" else "2",
                    "order1" to "",
                    "order2" to "",
                    "falxdm" to "",
                    "kzsjqx" to "0",
                    "py_xssfcxzj_zx" to "1",
                    "py_xssfcxzj_fx" to "1",
                    "sfdl" to "",
                    "pageNum" to "1",
                    "pageSize" to "500"
                )
                val plans = mutableListOf<ShenzhenTrainingPlan>()
                var page = 1
                var pages = 1
                do {
                    val response = jwFormPost(
                        token,
                        "/faxq/query?sf_request_type=ajax",
                        baseForm + ("pageNum" to page.toString()),
                        "/faxq/query"
                    )
                    if (isJwAuthenticationExpired(response)) {
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                        return@Thread
                    }
                    if (response.statusCode() != 200) {
                        result.postValue(DataState(DataState.STATE.FETCH_FAILED, "培养方案列表请求失败"))
                        return@Thread
                    }
                    plans += ShenzhenTrainingPlanParser.parsePlans(response.body(), level).orEmpty()
                    if (page == 1) pages = ShenzhenTrainingPlanParser.parsePageCount(response.body())
                    page++
                } while (page <= pages)

                val matched = ShenzhenTrainingPlanParser.matchPersonalPlans(plans, major)
                if (matched.isEmpty()) {
                    result.postValue(
                        DataState(
                            DataState.STATE.FETCH_FAILED,
                            "未找到与“$major”匹配的个人培养方案"
                        )
                    )
                } else {
                    result.postValue(DataState(matched, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    fun getShenzhenCreditProgress(
        token: EASToken,
        selectedTerm: TermItem? = null,
        includeDetails: Boolean = true,
        includeCourseRecords: Boolean = true,
        trackCoursesOnly: Boolean = false
    ): LiveData<DataState<ShenzhenCreditProgress>> {
        val result = MutableLiveData(
            DataState<ShenzhenCreditProgress>(DataState.STATE.NOTHING)
        )
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            try {
                val referer = "/cjgl/grcjcx/cjxqList"
                val identityResponse = jwFormPost(
                    token,
                    "/UserManager/queryxsxx",
                    refererPath = "/authentication/main"
                )
                if (isJwAuthenticationExpired(identityResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val parsedIdentity = identityResponse.takeIf { it.statusCode() == 200 }
                    ?.let { ShenzhenCreditProgressParser.parseIdentity(it.body()) }
                val studentNumber = parsedIdentity?.studentNumber.orEmpty()
                    .ifBlank { token.stuId.orEmpty() }
                    .ifBlank { token.username.orEmpty() }
                val studentType = parsedIdentity?.studentType.orEmpty()
                    .ifBlank { token.getStudentType() }
                val grade = parsedIdentity?.grade.orEmpty()
                    .ifBlank { Regex("(?:19|20)\\d{2}").find(token.grade.orEmpty())?.value.orEmpty() }
                var recordId = parsedIdentity?.studentRecordId.orEmpty()
                if (studentNumber.isBlank() || grade.isBlank()) {
                    result.postValue(
                        DataState(DataState.STATE.FETCH_FAILED, "教务系统未返回学号或入学年级")
                    )
                    return@Thread
                }
                if (recordId.isBlank()) {
                    val lookupResponse = jwJsonPost(
                        token,
                        "/cjgl/cjzhtjcx/cjcx/getXs",
                        JSONObject().put("xjidorxh", studentNumber).toString(),
                        referer
                    )
                    if (isJwAuthenticationExpired(lookupResponse)) {
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                        return@Thread
                    }
                    recordId = lookupResponse.takeIf { it.statusCode() == 200 }
                        ?.let { ShenzhenGradeParser.parseStudentRecordId(it.body()) }.orEmpty()
                }
                if (recordId.isBlank()) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "教务系统未返回学籍标识"))
                    return@Thread
                }

                token.stuId = studentNumber
                token.id = recordId
                token.grade = grade
                token.stutype = if (studentType == "1") EASToken.TYPE.UNDERGRAD else EASToken.TYPE.GRAD
                onTokenRefreshed?.invoke(token)

                val termResponse = jwFormPost(
                    token,
                    "/cjgl/cjzhtjcx/cjcx/queryqxnxq?sf_request_type=ajax",
                    refererPath = referer
                )
                if (isJwAuthenticationExpired(termResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val currentTerm = selectedTerm?.getCode()
                    ?: termResponse.takeIf { it.statusCode() == 200 }
                        ?.let { ShenzhenCreditProgressParser.parseCurrentTerm(it.body()) }
                if (currentTerm.isNullOrBlank()) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "当前成绩学期读取失败"))
                    return@Thread
                }
                val courseRecordTotal = ShenzhenCreditProgressParser.parseCourseRecordTotal(
                    termResponse.body()
                )

                val planResponse = jwFormPost(
                    token,
                    "/cjgl/cjzhtjcx/cjcx/queryfahHljdx?sf_request_type=ajax",
                    mapOf(
                        "xh" to studentNumber,
                        "pylx" to studentType,
                        "falxdm" to "1"
                    ),
                    referer
                )
                if (isJwAuthenticationExpired(planResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                var plan = planResponse.takeIf { it.statusCode() == 200 }
                    ?.let { ShenzhenCreditProgressParser.parsePlanContext(it.body()) }
                if (plan == null) {
                    val fallbackPlanResponse = jwFormPost(
                        token,
                        "/cjgl/cjzhtjcx/cjcx/queryfah?sf_request_type=ajax",
                        mapOf(
                            "xh" to studentNumber,
                            "pylx" to studentType,
                            "falxdm" to "1"
                        ),
                        referer
                    )
                    if (isJwAuthenticationExpired(fallbackPlanResponse)) {
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                        return@Thread
                    }
                    plan = fallbackPlanResponse.takeIf { it.statusCode() == 200 }
                        ?.let { ShenzhenCreditProgressParser.parsePlanContext(it.body()) }
                }
                if (plan == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "个人培养方案读取失败"))
                    return@Thread
                }

                val commonBody = JSONObject()
                    .put("xjid", recordId)
                    .put("zyfxdm", JSONObject.NULL)
                    .put("pylx", studentType)
                    .put("fah", plan.planId)
                val summaryResponse = jwJsonPost(
                    token,
                    "/cjgl/cjzhtjcx/cjcx/queryBxkqk?sf_request_type=ajax",
                    JSONObject(commonBody.toString())
                        .put("xh", studentNumber)
                        .put("nj", grade)
                        .put("jzxnxq", currentTerm)
                        .toString(),
                    referer
                )
                if (isJwAuthenticationExpired(summaryResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val summaryUnavailable = summaryResponse.statusCode() == 404
                if (summaryResponse.statusCode() != 200 && !summaryUnavailable) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "培养方案完成度请求失败"))
                    return@Thread
                }

                val emptyListBody = """{"content":[]}"""
                val summaryBody = if (summaryUnavailable) "" else summaryResponse.body()
                val initialProgress = ShenzhenCreditProgressParser.parseProgress(
                    summaryBody = summaryBody,
                    categoriesBody = "",
                    groupsBody = emptyListBody,
                    courseRecordBodies = emptyList(),
                    currentTerm = currentTerm,
                    allowMissingSummary = summaryUnavailable
                )
                if (initialProgress == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "培养方案完成度解析失败"))
                    return@Thread
                }
                if (includeCourseRecords || !includeDetails) {
                    result.postValue(DataState(initialProgress, DataState.STATE.SUCCESS))
                }
                LogUtils.d(
                    "getShenzhenCreditProgress: summary categories=${initialProgress.categories.size}"
                )
                if (!includeDetails && initialProgress.categories.isNotEmpty()) return@Thread

                var categoryBody = ""
                if (initialProgress.categories.isEmpty()) {
                    val categoryResponse = runCatching {
                        jwJsonPost(
                            token,
                            "/cjgl/cjzhtjcx/cjcx/queryXflbyq1?sf_request_type=ajax",
                            JSONObject(commonBody.toString())
                                .put("current", 1)
                                .put("pageSize", 200)
                                .toString(),
                            referer
                        )
                    }.onFailure {
                        LogUtils.w("credit progress: category details unavailable: ${it.message}")
                    }.getOrNull()
                    categoryBody = categoryResponse
                        ?.takeIf { it.statusCode() == 200 && !isJwAuthenticationExpired(it) }
                        ?.body()
                        .orEmpty()
                }
                if (!includeDetails) {
                    val progressWithCategories = ShenzhenCreditProgressParser.parseProgress(
                        summaryBody = summaryBody,
                        categoriesBody = categoryBody,
                        groupsBody = emptyListBody,
                        courseRecordBodies = emptyList(),
                        currentTerm = currentTerm
                    ) ?: initialProgress
                    result.postValue(DataState(progressWithCategories, DataState.STATE.SUCCESS))
                    return@Thread
                }

                val groupResponse = runCatching {
                    jwJsonPost(
                        token,
                        "/cjgl/cjzhtjcx/cjcx/queryMkyq?sf_request_type=ajax",
                        commonBody.toString(),
                        referer
                    )
                }.onFailure {
                    LogUtils.w("credit progress: group details unavailable: ${it.message}")
                }.getOrNull()
                val groupBody = groupResponse
                    ?.takeIf { it.statusCode() == 200 && !isJwAuthenticationExpired(it) }
                    ?.body()
                    ?: emptyListBody

                val progressWithGroups = ShenzhenCreditProgressParser.parseProgress(
                    summaryBody = summaryBody,
                    categoriesBody = categoryBody,
                    groupsBody = groupBody,
                    courseRecordBodies = emptyList(),
                    currentTerm = currentTerm
                ) ?: initialProgress
                if (includeCourseRecords) {
                    result.postValue(DataState(progressWithGroups, DataState.STATE.SUCCESS))
                }

                val parsedGroups = progressWithGroups.groups
                val parentIds = parsedGroups.mapTo(hashSetOf()) { it.parentId }
                val courseGroups = parsedGroups.filter { group ->
                    if (trackCoursesOnly) {
                        group.name.contains("轨道")
                    } else {
                        group.depth > 0 && group.id !in parentIds
                    }
                }
                val groupCourseBodies = linkedMapOf<String, String>()
                courseGroups.forEach { group ->
                    val courseResponse = runCatching {
                        jwJsonPost(
                            token,
                            "/cjgl/cjzhtjcx/cjcx/queryFaKzkc?sf_request_type=ajax",
                            JSONObject()
                                .put("current", 1)
                                .put("pageSize", 200)
                                .put("xn", JSONObject.NULL)
                                .put("xq", JSONObject.NULL)
                                .put("yxdm", JSONObject.NULL)
                                .put("kzid", group.id)
                                .put("kzlx", "0")
                                .put("kcxzdm", JSONObject.NULL)
                                .put("kclbdm", JSONObject.NULL)
                                .put("kzmc", group.name)
                                .put("kcmc", "")
                                .put("orderby", JSONObject.NULL)
                                .put("sxjx", JSONObject.NULL)
                                .put("xjid", recordId)
                                .put("nj", grade)
                                .put("pylx", studentType)
                                .put("fah", plan.planId)
                                .put("zyfxdm", JSONObject.NULL)
                                .toString(),
                            referer
                        )
                    }.onFailure {
                        LogUtils.w(
                            "credit progress: courses unavailable for group ${group.id}: ${it.message}"
                        )
                    }.getOrNull()
                    if (courseResponse?.statusCode() == 200 &&
                        !isJwAuthenticationExpired(courseResponse)
                    ) {
                        groupCourseBodies[group.id] = courseResponse.body()
                    }
                }

                if (!includeCourseRecords) {
                    val progressWithCourses = ShenzhenCreditProgressParser.parseProgress(
                        summaryBody = summaryBody,
                        categoriesBody = categoryBody,
                        groupsBody = groupBody,
                        groupCourseBodies = groupCourseBodies,
                        courseRecordBodies = emptyList(),
                        currentTerm = currentTerm
                    ) ?: progressWithGroups
                    result.postValue(DataState(progressWithCourses, DataState.STATE.SUCCESS))
                    LogUtils.d(
                        "getShenzhenCreditProgress: groups=${progressWithCourses.groups.size}, " +
                            "groupCourseResponses=${groupCourseBodies.size}, courseRecords=skipped"
                    )
                    return@Thread
                }

                val courseRecordBodies = mutableListOf<String>()
                var page = 1
                var pages = 1
                do {
                    val earnedResponse = runCatching {
                        jwFormPost(
                            token,
                            "/cjgl/grcjcx/dyxwList?sf_request_type=ajax",
                            mapOf(
                                "pageNum" to page.toString(),
                                "pageSize" to "200",
                                "total" to courseRecordTotal.toString(),
                                "xjid" to recordId,
                                "sfgld" to "1",
                                "pxzd" to "",
                                "pxfx" to "",
                                "xn" to "",
                                "xq" to "",
                                "kcxz" to "",
                                "kclb" to "",
                                "key" to "",
                                "pylx" to studentType,
                                "sffx" to "",
                                "sfcxfxcj" to "0",
                                "sfsjqx" to "0"
                            ),
                            referer
                        )
                    }.onFailure {
                        LogUtils.w("credit progress: earned course page $page unavailable: ${it.message}")
                    }.getOrNull()
                    if (earnedResponse == null || earnedResponse.statusCode() != 200 ||
                        isJwAuthenticationExpired(earnedResponse)
                    ) {
                        break
                    }
                    courseRecordBodies += earnedResponse.body()
                    if (page == 1) {
                        pages = ShenzhenCreditProgressParser.parseCourseRecordPageCount(
                            earnedResponse.body()
                        )
                    }
                    page++
                } while (page <= pages)

                val progress = ShenzhenCreditProgressParser.parseProgress(
                    summaryBody = summaryBody,
                    categoriesBody = categoryBody,
                    groupsBody = groupBody,
                    groupCourseBodies = groupCourseBodies,
                    courseRecordBodies = courseRecordBodies,
                    currentTerm = currentTerm
                )
                val finalProgress = progress ?: progressWithGroups
                result.postValue(DataState(finalProgress, DataState.STATE.SUCCESS))
                LogUtils.d(
                    "getShenzhenCreditProgress: groups=${finalProgress.groups.size}, " +
                        "groupCourseResponses=${groupCourseBodies.size}, " +
                        "courseRecords=${finalProgress.courseRecords.size}"
                )
            } catch (error: Exception) {
                LogUtils.e("getShenzhenCreditProgress failed", error)
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    fun getShenzhenTrainingPlanCourses(
        token: EASToken,
        plan: ShenzhenTrainingPlan
    ): LiveData<DataState<ShenzhenTrainingPlanDetail>> {
        val result = MutableLiveData<DataState<ShenzhenTrainingPlanDetail>>()
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            try {
                val groups = if (plan.level == ShenzhenTrainingPlanLevel.POSTGRADUATE) {
                    val response = jwFormPost(
                        token,
                        "/Zdxpyfakz/queryKzTree?sf_request_type=ajax",
                        mapOf(
                            "fah" to plan.id,
                            "bgid" to plan.changeId,
                            "pylb" to "2",
                            "sfcx" to ""
                        ),
                        "/Zdxpyfakz/query"
                    )
                    if (isJwAuthenticationExpired(response)) {
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                        return@Thread
                    }
                    if (response.statusCode() != 200) {
                        result.postValue(DataState(DataState.STATE.FETCH_FAILED, "培养方案课组请求失败"))
                        return@Thread
                    }
                    ShenzhenTrainingPlanParser.parseGroups(response.body()).orEmpty()
                } else emptyList()

                val path: String
                val referer: String
                val baseForm: Map<String, String>
                if (plan.level == ShenzhenTrainingPlanLevel.UNDERGRADUATE) {
                    path = "/Njpyfakc/queryList?sf_request_type=ajax"
                    referer = "/Njpyfakc/query"
                    baseForm = linkedMapOf(
                        "bglx" to "",
                        "multiple" to "false",
                        "sfcx" to "",
                        "pylx" to "1",
                        "pylb" to "1",
                        "fah" to plan.id,
                        "bgid" to plan.changeId,
                        "kcmc" to "",
                        "yxdm" to "",
                        "xqdm" to "",
                        "kclbdm" to "",
                        "kcxzdm" to "",
                        "order1" to "",
                        "order2" to "",
                        "pageNum" to "1",
                        "pageSize" to "500"
                    )
                } else {
                    path = "/Zdxpyfakz/queryFaKzkc?sf_request_type=ajax"
                    referer = "/Zdxpyfakz/query"
                    baseForm = linkedMapOf(
                        "sfcx" to "",
                        "pylx" to "2",
                        "pylb" to "2",
                        "fah" to plan.id,
                        "bgid" to plan.changeId,
                        "kzid" to "",
                        "kcmc" to "",
                        "zyfx" to plan.majorCode,
                        "yxdm" to "",
                        "xqdm" to "",
                        "order1" to "",
                        "order2" to "",
                        "pageNum" to "1",
                        "pageSize" to "500"
                    )
                }

                val courses = mutableListOf<ShenzhenTrainingPlanCourse>()
                var page = 1
                var pages = 1
                do {
                    val response = jwFormPost(
                        token,
                        path,
                        baseForm + ("pageNum" to page.toString()),
                        referer
                    )
                    if (isJwAuthenticationExpired(response)) {
                        result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                        return@Thread
                    }
                    if (response.statusCode() != 200) {
                        result.postValue(DataState(DataState.STATE.FETCH_FAILED, "培养方案课程请求失败"))
                        return@Thread
                    }
                    courses += ShenzhenTrainingPlanParser.parseCourses(response.body()).orEmpty()
                    if (page == 1) pages = ShenzhenTrainingPlanParser.parsePageCount(response.body())
                    page++
                } while (page <= pages)

                if (courses.isEmpty()) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "该培养方案暂未返回课程"))
                } else {
                    result.postValue(
                        DataState(
                            ShenzhenTrainingPlanParser.combine(
                                plan,
                                groups,
                                courses.distinctBy {
                                    listOf(it.groupId, it.courseCode, it.courseName, it.recommendedTerm)
                                }
                            ),
                            DataState.STATE.SUCCESS
                        )
                    )
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    // ================================================================ 课表入口
    override fun getStartDate(token: EASToken, term: TermItem): LiveData<DataState<Calendar>> =
        timetableSource.getStartDate(token, term)

    fun getShenzhenCoursePlanningStartDate(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<Calendar>> =
        timetableSource.getShenzhenCoursePlanningStartDate(token, term)

    fun getShenzhenCoursePlanningScheduleStructure(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<MutableList<TimePeriodInDay>>> =
        timetableSource.getShenzhenCoursePlanningScheduleStructure(token, term)

    override fun getSubjectsOfTerm(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<MutableList<TermSubject>>> =
        timetableSource.getSubjectsOfTerm(token, term)

    fun getShenzhenSelectedCourses(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<List<ShenzhenCourseCatalogItem>>> =
        timetableSource.getShenzhenSelectedCourses(token, term)

    override fun getTimetableOfTerm(
        term: TermItem,
        token: EASToken
    ): LiveData<DataState<List<CourseItem>>> =
        timetableSource.getTimetableOfTerm(term, token)

    override fun getScheduleStructure(
        term: TermItem,
        isUndergraduate: Boolean?,
        token: EASToken
    ): LiveData<DataState<MutableList<TimePeriodInDay>>> =
        timetableSource.getScheduleStructure(term, isUndergraduate, token)

    // 学年学期、选课和课程推荐仍共用这些辅助入口。
    private fun requestShenzhenWebSelectedSubjects(
        token: EASToken,
        term: TermItem
    ): Connection.Response = timetableSource.requestShenzhenWebSelectedSubjects(token, term)

    private fun shenzhenSelectedSubjectsForm(
        token: EASToken,
        term: TermItem
    ): Map<String, String> = timetableSource.shenzhenSelectedSubjectsForm(token, term)

    private fun buildTermDisplayName(yearName: String?, termName: String?): String =
        timetableSource.buildTermDisplayName(yearName, termName)

    // ================================================================ 成绩
    override fun getPersonalScores(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType
    ): LiveData<DataState<List<CourseScoreItem>>> {
        if (token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()) {
            return getShenzhenWebPersonalScores(term, token, testType)
        }
        val res = MutableLiveData<DataState<List<CourseScoreItem>>>()
        Thread {
            val result: MutableList<CourseScoreItem> = ArrayList()
            try {
                val qzqmFlag = when (testType) {
                    EASService.TestType.NORMAL -> "qm"
                    EASService.TestType.RESIT  -> "qz"
                    else -> "qm"
                }
                val body = """{"xn":"${term.yearCode}","xq":"${term.termCode}","qzqmFlag":"$qzqmFlag","type":"json"}"""
                val resp = jsonPost(token, "/app/cjgl/xscjList?_lang=zh_CN", body)
                val jo = JsonUtils.getJsonObject(resp.body())
                if (isAuthExpiredResponse(resp) || isAuthExpiredJson(jo)) {
                    res.postValue(DataState(DataState.STATE.NOT_LOGGED_IN))
                    return@Thread
                }
                val content = jo?.optJSONArray("content")
                content?.let {
                    for (i in 0 until it.length()) {
                        val tp = it.optJSONObject(i) ?: continue
                        val item = CourseScoreItem()
                        item.courseCode = tp.optString("kcdm")
                        item.courseName = tp.optString("kcmc")
                        item.credits = tp.optString("xf").toFloatOrNull() ?: 0f
                        item.finalScoresText = tp.optString("zf").trim().ifBlank { null }
                        item.finalScores = item.finalScoresText?.toIntOrNull() ?: -1
                        item.courseProperty = tp.optString("kcxz")
                        item.courseCategory = tp.optString("kclb", tp.optString("kclbmc", ""))
                        item.termName = tp.optString("xnxq", term.yearCode + term.termCode)
                        item.assessMethod = tp.optString("khfs", "")
                        result.add(item)
                    }
                    res.postValue(DataState(result))
                } ?: run {
                    res.postValue(DataState(DataState.STATE.FETCH_FAILED))
                }
            } catch (e: Exception) {
                LogUtils.e("getPersonalScores: failed, error=${e.message}", e)
                res.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return res
    }

    fun getPersonalScoresWithSummary(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType
    ): LiveData<DataState<ScoreQueryResult>> {
        if (token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()) {
            return getShenzhenWebPersonalScoresWithSummary(term, token, testType)
        }
        val res = MutableLiveData<DataState<ScoreQueryResult>>()
        Thread {
            val result: MutableList<CourseScoreItem> = ArrayList()
            try {
                val qzqmFlag = when (testType) {
                    EASService.TestType.NORMAL -> "qm"
                    EASService.TestType.RESIT  -> "qz"
                    else -> "qm"
                }
                val body = """{"xn":"${term.yearCode}","xq":"${term.termCode}","qzqmFlag":"$qzqmFlag","type":"json"}"""
                val resp = jsonPost(token, "/app/cjgl/xscjList?_lang=zh_CN", body)
                val jo = JsonUtils.getJsonObject(resp.body())
                if (isAuthExpiredResponse(resp) || isAuthExpiredJson(jo)) {
                    res.postValue(DataState(DataState.STATE.NOT_LOGGED_IN))
                    return@Thread
                }
                val content = jo?.optJSONArray("content")
                content?.let {
                    for (i in 0 until it.length()) {
                        val tp = it.optJSONObject(i) ?: continue
                        val item = CourseScoreItem()
                        item.courseCode = tp.optString("kcdm")
                        item.courseName = tp.optString("kcmc")
                        item.credits = tp.optString("xf").toFloatOrNull() ?: 0f
                        item.finalScoresText = tp.optString("zf").trim().ifBlank { null }
                        item.finalScores = item.finalScoresText?.toIntOrNull() ?: -1
                        item.courseProperty = tp.optString("kcxz")
                        item.courseCategory = tp.optString("kclb", tp.optString("kclbmc", ""))
                        item.termName = tp.optString("xnxq", term.yearCode + term.termCode)
                        item.assessMethod = tp.optString("khfs", "")
                        result.add(item)
                    }
                    // 课程成绩先返回，汇总接口较慢或超时时不能阻塞列表展示。
                    val stableItems = result.toList()
                    var latestResult = ScoreQueryResult(stableItems, extractScoreSummary(jo))
                    LogUtils.d(
                        "getPersonalScoresWithSummary: API items=${stableItems.size} " +
                            "summary=${latestResult.summary != null}"
                    )
                    res.postValue(DataState(latestResult))

                    fetchLegacyCumulativeScoreSummary(token)?.let { cumulative ->
                        latestResult = ScoreQueryResult(stableItems, cumulative)
                        res.postValue(DataState(latestResult))
                    }
                    when (val official = fetchOfficialWebScoreSummary(term, token)) {
                        is OfficialWebScoreSummaryResult.Available -> {
                            latestResult = ScoreQueryResult(stableItems, official.summary)
                            res.postValue(DataState(latestResult))
                        }
                        OfficialWebScoreSummaryResult.AuthExpired -> {
                            LogUtils.w(
                                "getPersonalScoresWithSummary: Web summary session expired; " +
                                    "preserving ${stableItems.size} API items"
                            )
                            res.postValue(ScoreQueryStatePolicy.sessionExpired(latestResult))
                        }
                        OfficialWebScoreSummaryResult.Unavailable -> Unit
                    }
                } ?: run {
                    res.postValue(DataState(DataState.STATE.FETCH_FAILED))
                }
            } catch (e: Exception) {
                LogUtils.e("getPersonalScoresWithSummary: failed, error=${e.message}", e)
                res.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return res
    }

    private data class ShenzhenWebScoreResult(
        val items: List<CourseScoreItem>,
        val authExpired: Boolean = false
    )

    private sealed class OfficialWebScoreSummaryResult {
        data class Available(val summary: ScoreSummary) : OfficialWebScoreSummaryResult()
        object AuthExpired : OfficialWebScoreSummaryResult()
        object Unavailable : OfficialWebScoreSummaryResult()
    }

    private fun getShenzhenWebPersonalScores(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType
    ): LiveData<DataState<List<CourseScoreItem>>> {
        val result = MutableLiveData<DataState<List<CourseScoreItem>>>()
        Thread {
            try {
                val fetched = fetchShenzhenWebScoreItems(term, token, testType)
                if (fetched.authExpired) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                } else {
                    result.postValue(DataState(fetched.items, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    private fun getShenzhenWebPersonalScoresWithSummary(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType
    ): LiveData<DataState<ScoreQueryResult>> {
        val result = MutableLiveData<DataState<ScoreQueryResult>>()
        Thread {
            try {
                val fetched = fetchShenzhenWebScoreItems(term, token, testType)
                if (fetched.authExpired) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                var latestResult = ScoreQueryResult(fetched.items, null)
                result.postValue(DataState(latestResult, DataState.STATE.SUCCESS))
                when (val official = fetchOfficialWebScoreSummary(term, token)) {
                    is OfficialWebScoreSummaryResult.Available -> {
                        latestResult = ScoreQueryResult(fetched.items, official.summary)
                        result.postValue(DataState(latestResult, DataState.STATE.SUCCESS))
                    }
                    OfficialWebScoreSummaryResult.AuthExpired -> {
                        result.postValue(ScoreQueryStatePolicy.sessionExpired(latestResult))
                    }
                    OfficialWebScoreSummaryResult.Unavailable -> Unit
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    private fun fetchShenzhenWebScoreItems(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType
    ): ShenzhenWebScoreResult {
        val isMidterm = testType == EASService.TestType.RESIT
        val path = if (isMidterm) "/cjgl/grcjcx/qzcjcx" else "/cjgl/grcjcx/grcjcx"
        val body = if (isMidterm) {
            JSONObject()
                .put("xn", term.yearCode)
                .put("xq", term.termCode)
                .put("kcmc", "")
                .put("pylx", token.getStudentType())
                .put("current", 1)
                .put("pageSize", 1000)
                .toString()
        } else {
            JSONObject()
                .put("xn", term.yearCode)
                .put("xq", term.termCode)
                .put("kcmc", JSONObject.NULL)
                .put("cxbj", "-1")
                .put("pylx", token.getStudentType())
                .put("current", 1)
                .put("pageSize", 1000)
                .put("xscjlb", JSONObject.NULL)
                .put("sffx", JSONObject.NULL)
                .toString()
        }
        val response = jwJsonPost(token, path, body, "/cjgl/grcjcx")
        if (isJwAuthenticationExpired(response)) return ShenzhenWebScoreResult(emptyList(), authExpired = true)
        if (response.statusCode() != 200) {
            throw IllegalStateException("成绩接口 HTTP ${response.statusCode()}")
        }
        val parsed = ShenzhenWebScoreParser.parse(response.body(), term)
            ?: throw IllegalStateException("成绩接口返回非 JSON 数据")
        if (parsed.code != 0 && parsed.code != 200) {
            throw IllegalStateException(parsed.message.ifBlank { "成绩查询失败" })
        }
        return ShenzhenWebScoreResult(parsed.items)
    }

    /** 深圳 Web 教务的官方 GPA / 平均学分绩汇总，参数与当前所选学期一致。 */
    private fun fetchOfficialWebScoreSummary(
        term: TermItem,
        token: EASToken
    ): OfficialWebScoreSummaryResult {
        return try {
            val body = JSONObject()
                .put("xn", term.yearCode)
                .put("xq", term.termCode)
                .put("pylx", token.getStudentType())
                .toString()
            val resp = jwJsonPost(
                token = token,
                path = "/cjgl/grcjcx/getgpa",
                body = body,
                refererPath = "/cjgl/grcjcx"
            )
            if (isJwAuthenticationExpired(resp)) {
                return OfficialWebScoreSummaryResult.AuthExpired
            }
            if (resp.statusCode() != 200) return OfficialWebScoreSummaryResult.Unavailable
            val jo = JsonUtils.getJsonObject(resp.body())
                ?: return OfficialWebScoreSummaryResult.Unavailable
            val values = ScoreSummaryParser.shenzhenKeys().associateWith { key ->
                jo.optString(key, "")
            }
            ScoreSummaryParser.fromShenzhenGpa(values)
                ?.let(OfficialWebScoreSummaryResult::Available)
                ?: OfficialWebScoreSummaryResult.Unavailable
        } catch (_: Exception) {
            OfficialWebScoreSummaryResult.Unavailable
        }
    }

    /** 旧 App API 只提供累计学分绩与排名，不包含可验证的 GPA。 */
    private fun fetchLegacyCumulativeScoreSummary(token: EASToken): ScoreSummary? {
        return try {
            val body = """{"type":"json","ksxnxq":"-1-1","jsxnxq":"-1-1","pylx":"${token.getStudentType()}"}"""
            val resp = jsonPost(token, "/app/cjgl/xfj", body, rolecode = "06")
            val jo = JsonUtils.getJsonObject(resp.body())
            if (isAuthExpiredResponse(resp) || isAuthExpiredJson(jo)) {
                return null
            }
            val xfj = jo?.optJSONObject("content")?.optJSONObject("xfj") ?: return null
            val weightedAverage = xfj.optString("XFJ", "").trim()
            val rank = xfj.optString("RANK", "").trim()
            val total = xfj.optString("ZYZRS", "").trim()
            if (weightedAverage.isBlank() && rank.isBlank() && total.isBlank()) null
            else ScoreSummary(
                weightedAverage = weightedAverage,
                rank = rank,
                total = total,
                scope = ScoreSummaryScope.CUMULATIVE
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractScoreSummary(jo: JSONObject?): ScoreSummary? {
        if (jo == null) return null
        val weightedAverageKeys = listOf("xfjd", "pjxfjd", "avgxfjd", "xuefenji")
        val gpaKeys = listOf("gpa", "GPA")
        val rankKeys = listOf("pm", "rank", "paiming", "pmj")
        val totalKeys = listOf("pmrs", "rank_total", "total", "zrs", "rs")
        val objects = buildList {
            add(jo)
            jo.optJSONObject("summary")?.let { add(it) }
            jo.optJSONObject("statistics")?.let { add(it) }
            jo.optJSONObject("data")?.let { add(it) }
            jo.optJSONObject("extra")?.let { add(it) }
        }
        val weightedAverage = objects.firstNotNullOfOrNull {
            findFirstString(it, weightedAverageKeys)
        }.orEmpty()
        val gpa = objects.firstNotNullOfOrNull { findFirstString(it, gpaKeys) }.orEmpty()
        val rank = objects.firstNotNullOfOrNull { findFirstString(it, rankKeys) }.orEmpty()
        val total = objects.firstNotNullOfOrNull { findFirstString(it, totalKeys) }.orEmpty()
        if (weightedAverage.isBlank() && gpa.isBlank() && rank.isBlank() && total.isBlank()) return null
        return ScoreSummary(
            weightedAverage = weightedAverage,
            gpa = gpa,
            rank = rank,
            total = total,
            scope = ScoreSummaryScope.UNKNOWN
        )
    }

    private fun findFirstString(obj: JSONObject, keys: List<String>): String? {
        for (key in keys) {
            val value = obj.optString(key, "").trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    // ================================================================ 教学楼列表
    override fun getTeachingBuildings(token: EASToken): LiveData<DataState<List<BuildingItem>>> {
        if (token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()) {
            return getShenzhenWebTeachingBuildings(token)
        }
        val result = MutableLiveData<DataState<List<BuildingItem>>>()
        Thread {
            val res = mutableListOf<BuildingItem>()
            try {
                val resp = authedFormPost(token, "/app/commapp/queryjxllist")
                val jo = JsonUtils.getJsonObject(resp.body())
                val content = jo?.optJSONArray("content")
                for (i in 0 until (content?.length() ?: 0)) {
                    val item = content?.optJSONObject(i) ?: continue
                    val b = BuildingItem()
                    b.name = item.optString("MC")
                    b.id = item.optString("DM")
                    res.add(b)
                }
                result.postValue(DataState(res))
            } catch (e: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED))
            }
        }.start()
        return result
    }

    private fun getShenzhenWebTeachingBuildings(
        token: EASToken
    ): LiveData<DataState<List<BuildingItem>>> {
        val result = MutableLiveData<DataState<List<BuildingItem>>>()
        Thread {
            try {
                initializeShenzhenWebClassroomContext(token)
                val response = jwFormPost(token, "/pksd/queryjxlList", refererPath = "/cdkb/querycdzy")
                if (isJwAuthenticationExpired(response)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val buildings = ShenzhenWebAcademicParser.parseBuildings(response.body())
                if (response.statusCode() != 200 || buildings == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "教学楼列表解析失败"))
                } else {
                    result.postValue(DataState(buildings, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    private fun initializeShenzhenWebClassroomContext(token: EASToken) {
        listOf("/pksd/queryxiaoquList", "/pksd/querycdlbList", "/kbfbsz/querydqxnxq").forEach { path ->
            val response = jwFormPost(token, path, refererPath = "/cdkb/querycdzy")
            if (isJwAuthenticationExpired(response)) return
        }
    }

    // ================================================================ 空教室查询（按天）
    override fun queryEmptyClassroom(
        token: EASToken,
        term: TermItem,
        building: BuildingItem,
        weeks: List<String>
    ): LiveData<DataState<List<ClassroomItem>>> {
        if (token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()) {
            return queryShenzhenWebEmptyClassroom(token, term, building, weeks)
        }
        val result = MutableLiveData<DataState<List<ClassroomItem>>>()
        Thread {
            val resMap = linkedMapOf<String, ClassroomItem>()
            try {
                val week = (weeks.firstOrNull()?.trim() ?: "").ifBlank { "1" }
                val dates = resolveWeekDates(token, term, week)
                if (dates.isEmpty()) {
                    throw IllegalStateException("未获取到 ${term.name} 第${week}周的日期")
                }
                val datePairs = dates.mapIndexed { index, date -> index + 1 to date }

                for ((dow, date) in datePairs) {
                    val resp = authedFormPost(
                        token, "/app/kbrcbyapp/querycdzyxx",
                        mapOf("nyr" to date, "jxl" to building.id)
                    )
                    val jo = JsonUtils.getJsonObject(resp.body())
                    val content = jo?.optJSONArray("content")
                    for (i in 0 until (content?.length() ?: 0)) {
                        val item = content?.optJSONObject(i) ?: continue
                        val name = item.optString("CDMC").trim()
                        if (name.isBlank()) continue
                        val classroom = resMap.getOrPut(name) {
                            ClassroomItem().apply {
                                this.name = name
                                this.id = name
                            }
                        }
                        for (dj in 1..6) {
                            val v = item.optString("DJ$dj", "0").trim()
                            if (v.isEmpty() || v == "0") continue
                            val start = (dj - 1) * 2 + 1
                            val end = start + 1
                            for (period in start..end) {
                                val scheduleJson = JSONObject()
                                scheduleJson.put("XQJ", dow)
                                scheduleJson.put("XJ", period)
                                scheduleJson.put("PKBJ", "占用")
                                classroom.scheduleList.add(scheduleJson)
                            }
                        }
                    }
                }
                result.postValue(DataState(resMap.values.toList()))
            } catch (e: Exception) {
                LogUtils.e("queryEmptyClassroom: failed, error=${e.message}", e)
                result.postValue(DataState(DataState.STATE.FETCH_FAILED))
            }
        }.start()
        return result
    }

    private fun queryShenzhenWebEmptyClassroom(
        token: EASToken,
        term: TermItem,
        building: BuildingItem,
        weeks: List<String>
    ): LiveData<DataState<List<ClassroomItem>>> {
        val result = MutableLiveData<DataState<List<ClassroomItem>>>()
        Thread {
            try {
                val week = weeks.firstOrNull()?.toIntOrNull()?.coerceIn(1, 34) ?: 1
                val weekMask = buildString(34) {
                    repeat(34) { index -> append(if (index == week - 1) '1' else '0') }
                }
                val form = linkedMapOf(
                    "pxn" to term.yearCode,
                    "pxq" to term.termCode,
                    "dmmc" to "",
                    "xiaoqu" to "",
                    "jxl" to building.id,
                    "cdlb" to "",
                    "zc" to weekMask,
                    "wpksfxs" to "0",
                    "qsjsz" to "16",
                    "kjs" to "0",
                    "xsbkycd" to "0",
                    "zws" to "",
                    "lc" to ""
                )
                val leftResponse = jwFormPost(
                    token,
                    "/cdkb/querycdzyleftzhou?sf_request_type=ajax",
                    form + mapOf("pageNum" to "1", "pageSize" to "500"),
                    "/cdkb/querycdzy"
                )
                if (isJwAuthenticationExpired(leftResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val rightResponse = jwFormPost(
                    token,
                    "/cdkb/querycdzyrightzhou?sf_request_type=ajax",
                    form,
                    "/cdkb/querycdzy"
                )
                if (isJwAuthenticationExpired(rightResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val classrooms = ShenzhenWebAcademicParser.parseClassrooms(
                    leftResponse.body(),
                    rightResponse.body()
                )
                if (leftResponse.statusCode() != 200 || rightResponse.statusCode() != 200 || classrooms == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "空教室数据解析失败"))
                } else {
                    result.postValue(DataState(classrooms, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    private fun resolveWeekDates(token: EASToken, term: TermItem, week: String): List<String> {
        val matrixDates = try {
            val kbBody = """{"xn":"${term.yearCode}","xq":"${term.termCode}","zc":"$week","type":"json"}"""
            val kbResp = jsonPost(token, "/app/Kbcx/query", kbBody)
            val kbJo = JsonUtils.getJsonObject(kbResp.body())
            val contentArr = kbJo?.optJSONArray("content")
            val dates = mutableListOf<String>()
            for (i in 0 until (contentArr?.length() ?: 0)) {
                val obj = contentArr?.optJSONObject(i) ?: continue
                val rqList = obj.optJSONArray("rqList") ?: continue
                for (j in 0 until rqList.length()) {
                    val rq = rqList.optJSONObject(j)?.optString("RQ")?.trim().orEmpty()
                    if (rq.isNotBlank()) dates.add(rq)
                }
                if (dates.isNotEmpty()) break
            }
            dates
        } catch (_: Exception) {
            emptyList()
        }
        if (matrixDates.isNotEmpty()) {
            // rqList 在部分周只返回一个或少数日期，不能把数组下标直接当星期。
            // 请求本身已经限定了目标周，因此从其中任一日期恢复该周完整 7 天即可。
            return ShenzhenAcademicWeekResolver.resolveQueryDates(
                matrixDates = matrixDates,
                overviewTermDates = emptyList(),
                requestedWeek = week.toIntOrNull() ?: 1
            )
        }

        val targetWeek = week.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val termDates = overviewTermDatesCache[term.id]
            ?: fetchOverviewTermDates(token, term).also { dates ->
                if (dates.isNotEmpty()) overviewTermDatesCache[term.id] = dates
            }
        return ShenzhenAcademicWeekResolver.resolveQueryDates(
            matrixDates = emptyList(),
            overviewTermDates = termDates,
            requestedWeek = targetWeek
        )
    }

    /**
     * 周课表矩阵在尚无个人课程时可能不给 rqList。总览接口按日期查询且返回 XN/XQ，
     * 因而可用于定位该学期真正的第一周；这里只在矩阵日期缺失时调用。
     */
    private fun fetchOverviewTermDates(token: EASToken, term: TermItem): List<String> {
        val dates = linkedSetOf<String>()
        var stagnantCount = 0
        val anchor = ShenzhenAcademicWeekResolver.approximateAnchor(term)
        for (weekOffset in 0 until 26) {
            val queryDate = anchor.plusWeeks(weekOffset.toLong()).toString()
            val response = authedFormPost(
                token,
                "/app/kbrcbyapp/querykbrczong",
                mapOf("nyr" to queryDate)
            )
            val content = JsonUtils.getJsonObject(response.body())?.optJSONArray("content")
            val sizeBefore = dates.size
            for (index in 0 until (content?.length() ?: 0)) {
                val row = content?.optJSONObject(index) ?: continue
                if (row.optString("XN").trim() != term.yearCode) continue
                if (row.optString("XQ").trim() != term.termCode) continue
                val date = row.optString("RQ").trim()
                if (date.isNotEmpty()) dates.add(date)
            }
            stagnantCount = if (dates.size == sizeBefore) stagnantCount + 1 else 0
            if (dates.isNotEmpty() && stagnantCount >= 4) break
        }
        return dates.sorted()
    }


    // ================================================================ 考试信息（深圳校区新接口）
    override fun getExamItems(token: EASToken, term: TermItem?): LiveData<DataState<List<ExamItem>>> {
        if (token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()) {
            return getShenzhenWebExamItems(token, term)
        }
        val res = MutableLiveData<DataState<List<ExamItem>>>()
        Thread {
            try {
                LogUtils.d("getExamItems: term=${term?.name}, yearCode=${term?.yearCode}, termCode=${term?.termCode}, campus=${token.campus}")

                // 使用传入的学期参数，如果没有则使用当前学期
                val xn = term?.yearCode ?: run {
                    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                    val nextYear = currentYear + 1
                    "$currentYear-$nextYear"
                }
                val xq = term?.termCode ?: "2" // 默认查询第2学期

                // 构建请求体
                val requestBody = org.json.JSONObject().apply {
                    put("type", "json")
                    put("xn", xn)
                    put("xq", xq)
                    put("kssjddm", "")
                    put("kcdmorkcmc", "")
                    put("pageNum", 1)
                    put("pageSize", 20)
                }.toString()


                // 发送POST请求到深圳校区考试查询接口
                val examHost = "https://mjw.hitsz.edu.cn"
                val url = "$examHost/incoSpringBoot/app/kscx/queryKsxxByXs"


                val req = Jsoup.newSession()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; V2183A Build/AP3A.240905.015.A2; wv) AppleWebKit/537.36")
                    .header("Accept", "*/*")
                    .header("Content-Type", "application/json")
                    .header("_lang", "cn")
                    .cookies(token.cookies)
                    .requestBody(requestBody)
                    .timeout(timeout)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .method(Connection.Method.POST)

                // 添加Bearer Token认证
                if (!token.accessToken.isNullOrEmpty()) {
                    req.header("authorization", "bearer ${token.accessToken}")
                    req.header("rolecode", "06")
                } else {
                }

                val response = req.execute()

                LogUtils.d("getExamItems: HTTP ${response.statusCode()}, body length=${response.body().length}")

                if (isAuthExpiredResponse(response)) {
                    res.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳教务会话已失效"))
                    return@Thread
                }

                if (response.statusCode() == 200) {
                    val responseBody = response.body()
                    val jsonResponse = org.json.JSONObject(responseBody)

                    LogUtils.d("getExamItems: API code=${jsonResponse.optInt("code")}")

                    if (jsonResponse.optInt("code") == 200) {
                        val content = jsonResponse.optJSONObject("content")
                        val examList = mutableListOf<ExamItem>()

                        if (content != null) {
                            LogUtils.d("getExamItems: total=${content.optInt("total")}, pageSize=${content.optInt("pageSize")}")
                        }

                        content?.optJSONArray("list")?.let { list ->
                            for (i in 0 until list.length()) {
                                try {
                                    val examObj = list.getJSONObject(i)
                                    val examItem = ExamItem().apply {
                                        courseName = examObj.optString("KCMC")
                                        examDate = examObj.optString("KSRQ")
                                        examTime = examObj.optString("KSJTSJ")
                                        examType = examObj.optString("KSSJDMC")
                                        examLocation = "${examObj.optString("JXLMC")} ${examObj.optString("JXCDMC")}"
                                        termName = examObj.optString("XNXQMC")
                                        campusName = examObj.optString("XIAOQUBMC")
                                        termId = if (term != null) {
                                            "${term.yearCode}-${term.termCode}"
                                        } else {
                                            "$xn-$xq"
                                        }
                                    }
                                    examList.add(examItem)
                                } catch (e: Exception) {
                                    LogUtils.e("getExamItems: parse failed at item $i", e)
                                }
                            }
                        }

                        LogUtils.d("getExamItems: count=${examList.size}")
                        res.postValue(DataState(examList, DataState.STATE.SUCCESS))
                    } else {
                        val errorMsg = jsonResponse.optString("msg", "查询失败")
                        LogUtils.e("getExamItems: API error, code=${jsonResponse.optInt("code")}, msg=$errorMsg")
                        res.postValue(DataState(DataState.STATE.FETCH_FAILED, errorMsg))
                    }
                } else {
                    LogUtils.e("getExamItems: HTTP ${response.statusCode()}")
                    res.postValue(DataState(DataState.STATE.FETCH_FAILED, "网络请求失败 HTTP ${response.statusCode()}"))
                }
            } catch (e: Exception) {
                LogUtils.e("getExamItems: ${e.javaClass.simpleName} ${e.message}", e)
                res.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message ?: "查询考试失败"))
            }
        }.start()
        return res
    }

    private fun getShenzhenWebExamItems(
        token: EASToken,
        term: TermItem?
    ): LiveData<DataState<List<ExamItem>>> {
        val result = MutableLiveData<DataState<List<ExamItem>>>()
        Thread {
            if (term == null) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, "请选择学期后再查询考试安排"))
                return@Thread
            }
            try {
                val response = jwFormPost(
                    token,
                    "/kscxtj/queryXsksByxhList",
                    mapOf(
                        "pxn" to term.yearCode,
                        "pxq" to term.termCode,
                        "pageNum" to "1",
                        "pageSize" to "500"
                    ),
                    "/kscxtj/queryXsksByxh"
                )
                if (isJwAuthenticationExpired(response)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val exams = ShenzhenWebAcademicParser.parseExams(response.body(), term)
                if (response.statusCode() != 200 || exams == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "考试数据解析失败"))
                } else {
                    result.postValue(DataState(exams, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    override fun getSafePersonalInfo(token: EASToken): LiveData<DataState<EASToken>> {
        // API login already supplies the account identity. Do not make an expired optional Web
        // session part of API login validation or account restoration.
        if (!ShenzhenCoreDataSourcePolicy.shouldUseWebFallback(token)) {
            return MutableLiveData(DataState(token, DataState.STATE.SUCCESS))
        }
        val result = MutableLiveData<DataState<EASToken>>()
        Thread {
            val enriched = fetchShenzhenWebPersonalInfo(token)
            if (enriched == null) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
            } else {
                result.postValue(DataState(enriched, DataState.STATE.SUCCESS))
            }
        }.start()
        return result
    }

    private fun fetchShenzhenWebPersonalInfo(token: EASToken): EASToken? {
        return try {
            val response = jwFormPost(token, "/user/me")
            if (response.statusCode() != 200) return null
            val payload = JsonUtils.getJsonObject(response.body()) ?: return null
            val studentId = payload.optString("yhdm", payload.optString("username", "")).trim()
            val name = payload.optString("xm", "").trim()
            if (studentId.isBlank() && name.isBlank()) return null

            token.campus = EASToken.Campus.SHENZHEN
            token.username = studentId.takeIf { it.isNotBlank() } ?: token.username
            token.stuId = studentId.takeIf { it.isNotBlank() } ?: token.stuId
            token.name = name.takeIf { it.isNotBlank() } ?: token.name
            token.id = payload.optString("id", "").trim().takeIf { it.isNotBlank() } ?: token.id
            token.school = payload.optString("bmmc", "").trim().takeIf { it.isNotBlank() } ?: token.school
            token.phone = payload.optString("lxdh", "").trim().takeIf { it.isNotBlank() } ?: token.phone
            val studentType = payload.optString("pylx", payload.optString("pyccm", "")).trim()
            if (studentType.isNotBlank()) {
                token.stutype = if (studentType == "1") EASToken.TYPE.UNDERGRAD else EASToken.TYPE.GRAD
            }
            onTokenRefreshed?.invoke(token)
            token
        } catch (error: Exception) {
            LogUtils.e("fetchShenzhenWebPersonalInfo: failed, error=${error.message}", error)
            null
        }
    }

    // ================================================================ 课程名合成（iOS 策略移植）
}
