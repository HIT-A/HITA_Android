package cn.limpu.hita.data.source.web.eas

import android.annotation.SuppressLint
import android.util.Base64
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.limpu.hita.utils.LogUtils
import com.limpu.component.data.DataState
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
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.source.web.service.EASService
import cn.limpu.hita.ui.eas.classroom.BuildingItem
import cn.limpu.hita.ui.eas.classroom.ClassroomItem
import cn.limpu.hita.utils.JsonUtils
import cn.limpu.hita.utils.CourseCodeUtils
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
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

/**
 * 教务系统 API 数据源 —— 新版接口 (mjw.hitsz.edu.cn/incoSpringBoot)
 *
 * 认证方式：
 *  - 登录三步之前用 Basic Auth = "Basic aW5jb246MTIzNDU="
 *  - 登录成功后所有请求用 Authorization: bearer <access_token>
 *  - route / JSESSIONID cookie 由 Jsoup session 维护；每次新建请求时手动注入已保存的 cookies
 */
class EASWebSource internal constructor(
    private val onTokenRefreshed: ((EASToken) -> Unit)? = null
) : EASService {

    private val hostName = "https://mjw.hitsz.edu.cn/incoSpringBoot"
    private val jwDirectHostName = "https://jw.hitsz.edu.cn"
    private val jwProxyHostName = "https://jw-hitsz-edu-cn.hitsz.edu.cn"
    private val basicAuth = "Basic aW5jb246MTIzNDU="
    private val timeout = 15000
    private val slowQueryTimeout = 60000
    private val DEBUG_WEEK = 6
    private val DEBUG_DOW = 1
    private val overviewTermDatesCache = ConcurrentHashMap<String, List<String>>()

    private fun jwHostName(token: EASToken): String =
        if (token.webBaseUrl?.trimEnd('/') == jwProxyHostName) jwProxyHostName else jwDirectHostName

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
        val password = token.password.orEmpty()
        LogUtils.d("tryRelogin: username=$username, hasPassword=${password.isNotBlank()}")
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

    // ================================================================ 学期开始日期
    @SuppressLint("SimpleDateFormat")
    override fun getStartDate(token: EASToken, term: TermItem): LiveData<DataState<Calendar>> {
        if (token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()) {
            return getShenzhenCoursePlanningStartDate(token, term)
        }
        val res = MutableLiveData<DataState<Calendar>>()
        Thread {
            try {
                val calendar = Calendar.getInstance()
                calendar.firstDayOfWeek = Calendar.MONDAY
                // 用第1周课表矩阵接口获取第1天日期
                val weekBody = """{"xn":"${term.yearCode}","xq":"${term.termCode}","zc":"1","type":"json"}"""
                val weekResp = jsonPost(token, "/app/Kbcx/query", weekBody)
                val weekJo = JsonUtils.getJsonObject(weekResp.body())
                val contentArr = weekJo?.optJSONArray("content")
                val df = SimpleDateFormat("yyyy-MM-dd")
                if (contentArr != null) {
                    for (i in 0 until contentArr.length()) {
                        val obj = contentArr.optJSONObject(i) ?: continue
                        val rqList = obj.optJSONArray("rqList") ?: continue
                        val firstDay = rqList.optJSONObject(0) ?: continue
                        val rq = firstDay.optString("RQ")
                        if (rq.isNotEmpty()) {
                            val parsed = df.parse(rq)
                            if (parsed != null) calendar.timeInMillis = parsed.time
                        }
                        break
                    }
                }
                res.postValue(DataState(calendar))
            } catch (e: Exception) {
                LogUtils.e("getStartDate: failed, error=${e.message}", e)
                res.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return res
    }

    fun getShenzhenCoursePlanningStartDate(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<Calendar>> {
        if (!token.hasShenzhenWebSession()) {
            return MutableLiveData<DataState<Calendar>>(
                DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务")
            )
        }
        val result = MutableLiveData<DataState<Calendar>>()
        Thread {
            try {
                val response = jwFormPost(
                    token,
                    "/component/queryRlZcSj",
                    mapOf("xn" to term.yearCode, "xq" to term.termCode, "djz" to "1"),
                    "/Xsxk/query/1"
                )
                if (isJwAuthenticationExpired(response)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val date = ShenzhenWebAcademicParser.parseStartDate(response.body())
                if (response.statusCode() != 200 || date == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "未获取到第一教学周日期"))
                    return@Thread
                }
                val calendar = Calendar.getInstance().apply {
                    clear()
                    firstDayOfWeek = Calendar.MONDAY
                    set(date.year, date.monthValue - 1, date.dayOfMonth)
                }
                result.postValue(DataState(calendar, DataState.STATE.SUCCESS))
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    fun getShenzhenCoursePlanningScheduleStructure(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<MutableList<TimePeriodInDay>>> {
        if (!token.hasShenzhenWebSession()) {
            return MutableLiveData<DataState<MutableList<TimePeriodInDay>>>(
                DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务")
            )
        }
        val result = MutableLiveData<DataState<MutableList<TimePeriodInDay>>>()
        Thread {
            try {
                val selectionResponse = requestShenzhenWebSelectedSubjects(token, term)
                if (isJwAuthenticationExpired(selectionResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val selectionSchedule = selectionResponse
                    .takeIf { it.statusCode() == 200 }
                    ?.let { ShenzhenWebAcademicParser.parseScheduleStructure(it.body()) }
                if (!selectionSchedule.isNullOrEmpty()) {
                    result.postValue(DataState(selectionSchedule, DataState.STATE.SUCCESS))
                    return@Thread
                }

                val response = jwFormPost(
                    token,
                    "/component/queryKbjg",
                    mapOf(
                        "xn" to term.yearCode,
                        "xq" to term.termCode,
                        "pylx" to token.getStudentType()
                    ),
                    "/Xsxk/query/1"
                )
                if (isJwAuthenticationExpired(response)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val schedule = ShenzhenWebAcademicParser.parseScheduleStructure(response.body())
                if (response.statusCode() != 200 || schedule.isNullOrEmpty()) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "节次结构解析失败"))
                } else {
                    result.postValue(DataState(schedule, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    // ================================================================ 已选课程
    override fun getSubjectsOfTerm(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<MutableList<TermSubject>>> {
        // Keep the bearer API as the primary source. A remembered Web cookie may already be
        // expired, so it must never replace a working API session for this core query.
        if (ShenzhenCoreDataSourcePolicy.shouldUseWebFallback(token)) {
            return getShenzhenWebSubjects(token, term)
        }
        val res = MutableLiveData<DataState<MutableList<TermSubject>>>()
        Thread {
            val result: MutableList<TermSubject> = ArrayList()
            try {
                val pylxRaw = token.getStudentType()
                val pylxPad = pylxRaw.padStart(2, '0')
                val pylxCandidates = linkedSetOf(pylxRaw, pylxPad)
                val roleCandidates = listOf("01", "06")
                val xnxqCandidates = linkedSetOf(
                    term.getCode(),
                    "${term.yearCode}-${term.termCode}",
                    term.yearCode + term.termCode.padStart(2, '0'),
                    "${term.yearCode}-${term.termCode.padStart(2, '0')}"
                )
                val xkfsCandidates = listOf("yixuan", "")
                var yxkc: org.json.JSONArray? = null
                for (roleHeader in roleCandidates) {
                    for (pylx in pylxCandidates) {
                        for (xnxq in xnxqCandidates) {
                            for (xkfs in xkfsCandidates) {
                                val body =
                                    """{"RoleCode":"$roleHeader","p_pylx":"$pylx","p_xn":"${term.yearCode}","p_xq":"${term.termCode}","p_xnxq":"$xnxq","p_gjz":"","p_kc_gjz":"","p_xkfsdm":"$xkfs"}"""
                                val resp = jsonPost(
                                    token,
                                    "/app/Xsxk/queryYxkc?_lang=zh_CN",
                                    body,
                                    rolecode = roleHeader
                                )
                                val jo = JsonUtils.getJsonObject(resp.body())
                                val list = extractYxkcList(jo)
                                if (list != null && list.length() > 0) {
                                    yxkc = list
                                    break
                                }
                            }
                            if (yxkc != null) break
                        }
                        if (yxkc != null) break
                    }
                    if (yxkc != null) break
                }
                yxkc?.let {
                    for (i in 0 until it.length()) {
                        val subject = it.optJSONObject(i) ?: continue
                        val s = TermSubject()
                        val rawCode = subject.optString("kcdm")
                        s.code = CourseCodeUtils.normalize(rawCode) ?: rawCode
                        s.name = subject.optString("kcmc", "")
                        s.school = subject.optString("kkyxmc")
                        s.teacher = extractSelectedTeacher(subject)
                        s.credit = subject.optString("xf").toFloatOrNull() ?: 0f
                        s.key = subject.optString("id")
                        s.field = optStringFirst(subject, listOf("kclbmc", "KCLBMC"))
                        s.selectCategory = optStringFirst(
                            subject,
                            listOf("rwlxmc", "RWLXMC", "xkfsmc", "XKFSMC", "xklbmc", "XKLBMC")
                        )
                        s.nature = optStringFirst(subject, listOf("kcxzmc", "KCXZMC"))
                        when (subject.optString("kcxzmc")) {
                            "必修" -> s.type = TermSubject.TYPE.COM_A
                            "限选" -> s.type = TermSubject.TYPE.OPT_A
                            "任选" -> s.type = TermSubject.TYPE.OPT_B
                        }
                        val rwlxmc = subject.optString("rwlxmc", "")
                        if (rwlxmc.contains("MOOC", ignoreCase = true))
                            s.type = TermSubject.TYPE.MOOC
                        result.add(s)
                    }
                }
                res.postValue(DataState(result))
            } catch (e: Exception) {
                LogUtils.e("getSubjectsOfTerm: failed, error=${e.message}", e)
                res.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return res
    }

    private fun getShenzhenWebSubjects(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<MutableList<TermSubject>>> {
        val result = MutableLiveData<DataState<MutableList<TermSubject>>>()
        Thread {
            try {
                val response = requestShenzhenWebSelectedSubjects(token, term)
                if (isJwAuthenticationExpired(response)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val subjects = ShenzhenWebAcademicParser.parseSelectedSubjects(response.body())
                if (response.statusCode() != 200 || subjects == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "已选课程解析失败"))
                } else {
                    result.postValue(DataState(subjects, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    private fun requestShenzhenWebSelectedSubjects(
        token: EASToken,
        term: TermItem
    ): Connection.Response = jwFormPost(
        token,
        "/Xsxk/queryYxkc?sf_request_type=ajax",
        mapOf(
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
            "p_xkfsdm" to "yixuan",
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
            "p_sfmxzj" to "",
            "p_chaxunxkfsdm" to "",
            "pageNum" to "1",
            "pageSize" to "200"
        ),
        "/Xsxk/query/1"
    )

    fun getShenzhenSelectedCourses(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<List<ShenzhenCourseCatalogItem>>> {
        val result = MutableLiveData<DataState<List<ShenzhenCourseCatalogItem>>>(
            DataState(DataState.STATE.NOTHING)
        )
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            runCatching {
                val response = requestShenzhenWebSelectedSubjects(token, term)
                if (isJwAuthenticationExpired(response)) {
                    error("深圳 Web 会话已失效")
                }
                check(response.statusCode() == 200) { "已选课程读取失败" }
                ShenzhenCourseCatalogParser.parsePage(
                    response.body(),
                    ShenzhenCourseCatalogSource.AVAILABLE,
                    token.getStudentType()
                )?.items ?: error("已选课程解析失败")
            }.onSuccess {
                result.postValue(DataState(it, DataState.STATE.SUCCESS))
            }.onFailure { error ->
                val state = if (error.message?.contains("会话已失效") == true) {
                    DataState.STATE.NOT_LOGGED_IN
                } else {
                    DataState.STATE.FETCH_FAILED
                }
                result.postValue(DataState(state, error.message))
            }
        }.start()
        return result
    }

    // ================================================================ 周课表（按周矩阵：/app/Kbcx/query）
    override fun getTimetableOfTerm(
        term: TermItem,
        token: EASToken
    ): LiveData<DataState<List<CourseItem>>> {
        if (token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()) {
            return getShenzhenWebTimetable(token, term)
        }
        val res = MutableLiveData<DataState<List<CourseItem>>>()
        Thread {
            try {
                // 策略1：先尝试 querykbrczong 总览接口
                val zongResult = fetchTimetableFromOverview(token, term)
                if (!zongResult.isNullOrEmpty()) {
                    LogUtils.d("getTimetableOfTerm: using overview result, count=${zongResult.size}")
                    res.postValue(DataState(zongResult, DataState.STATE.SUCCESS))
                    return@Thread
                }

                // 策略2：总览没结果，走 Kbcx/query + querykbrcbyday 周表路径
                LogUtils.d("getTimetableOfTerm: overview empty, falling back to week schedule")
                val merged = linkedMapOf<String, CourseItem>()
                val selectedCourseNameByCode = fetchSelectedCourseNameByCodeSync(token, term)
                val dayScheduleCache = java.util.concurrent.ConcurrentHashMap<String, List<DayScheduleItem>>()
                val pool = java.util.concurrent.Executors.newFixedThreadPool(4)

                // 收集所有需要富化的日期，然后并行请求
                val enrichmentNeeded = java.util.concurrent.ConcurrentLinkedQueue<String>()
                data class WeekData(val week: Int, val kcxxList: org.json.JSONArray, val weekDates: List<String>)
                val allWeekData = mutableListOf<WeekData>()

                // 并行获取所有周数据
                val weekFutures = (1..25).map { week ->
                    pool.submit(java.util.concurrent.Callable<WeekData?> {
                        val kbBody = """{"xn":"${term.yearCode}","xq":"${term.termCode}","zc":"$week","type":"json"}"""
                        val kbResp = jsonPost(token, "/app/Kbcx/query", kbBody)
                        val kbJo = JsonUtils.getJsonObject(kbResp.body()) ?: return@Callable null
                        if (kbJo.optInt("code", -1) != 200) return@Callable null

                        val contentArr = kbJo.optJSONArray("content") ?: return@Callable null
                        val kcxxList = extractKcxxListFromKbcxContent(contentArr)
                        val weekDates = extractWeekDatesFromKbcx(contentArr)
                        if (kcxxList.length() == 0) {
                            return@Callable null
                        }
                        WeekData(week, kcxxList, weekDates)
                    })
                }

                val fetchedWeekData = weekFutures.mapNotNull { it.get() }.sortedBy { it.week }

                // 第一遍：收集需要富化的日期
                for (wd in fetchedWeekData) {
                    val weekDates = wd.weekDates
                    for (i in 0 until wd.kcxxList.length()) {
                        val kc = wd.kcxxList.optJSONObject(i) ?: continue
                        val kbxx = kc.optString("KBXX", "")
                        if (kbxx.contains("...") || kbxx.contains("[") || kbxx.contains("【实验】")) {
                            val dow = kc.optInt("XQJ", -1)
                            val dateForDow = weekDates.getOrNull(dow - 1)
                            if (!dateForDow.isNullOrBlank() && !dayScheduleCache.containsKey(dateForDow)) {
                                enrichmentNeeded.add(dateForDow)
                            }
                        }
                    }
                    allWeekData.add(wd)
                }

                // 并行请求所有需要富化的日期
                if (enrichmentNeeded.isNotEmpty()) {
                    LogUtils.d("getTimetableOfTerm: fetching ${enrichmentNeeded.size} day schedules in parallel")
                    val futures = enrichmentNeeded.distinct().map { date ->
                        pool.submit(java.util.concurrent.Callable<Unit> {
                            dayScheduleCache[date] = fetchDaySchedule(token, date)
                        })
                    }
                    futures.forEach { it.get() }
                }

                // 第二遍：解析课程数据（日课表已在缓存中）
                for (wd in allWeekData) {
                    val week = wd.week
                    val kcxxList = wd.kcxxList
                    val weekDates = wd.weekDates
                    val debugWeekRows = mutableListOf<CourseItem>()
                    for (i in 0 until kcxxList.length()) {
                        val kc = kcxxList.optJSONObject(i) ?: continue
                        val dow = kc.optInt("XQJ", -1)
                        val ksjc = kc.optInt("KSJC", -1)
                        val jsjc = kc.optInt("JSJC", -1)
                        if (dow !in 1..7 || ksjc <= 0) continue

                        val kbxx = kc.optString("KBXX", "")
                        val rawCode = kc.optString("KCDM", "")
                        val code = CourseCodeUtils.normalize(rawCode) ?: rawCode
                        val fallbackName = normalizedCourseName(kbxx)
                        if (fallbackName.isBlank()) continue

                        val classroom = extractClassroomFromKbxx(kbxx) ?: ""
                        val sessionHint = extractSessionHint(kbxx)

                        // 只在 KBXX 包含 ... 或 [] 时才查日课表
                        val needsEnrichment = kbxx.contains("...") || kbxx.contains("[")
                        val dateForDow = weekDates.getOrNull(dow - 1)
                        val canonicalName = if (needsEnrichment && !dateForDow.isNullOrBlank()) {
                            val daySchedules = dayScheduleCache[dateForDow]
                            if (daySchedules != null) bestDayScheduleRawName(daySchedules, dow, ksjc, jsjc, classroom) else null
                        } else null

                        val displayName = composeCourseDisplayName(canonicalName, fallbackName, sessionHint)

                        val teacher = extractTeacher(kc, displayName, kbxx)
                        val last = if (jsjc >= ksjc) jsjc - ksjc + 1 else 1

                        val course = CourseItem().apply {
                            this.code = code
                            this.name = displayName
                            this.rawName = displayName
                            this.teacher = teacher
                            this.classroom = classroom
                            this.dow = dow
                            this.begin = ksjc
                            this.last = last
                            this.weeks = mutableListOf(week)
                        }
                        if (week == DEBUG_WEEK && dow == DEBUG_DOW) {
                            debugWeekRows.add(copyCourse(course))
                        }
                        upsertCourse(merged, course)
                    }
                    if (week == DEBUG_WEEK) {
                        val summary = debugWeekRows
                            .sortedWith(compareBy<CourseItem> { it.dow }.thenBy { it.begin })
                            .joinToString(" || ") { debugCourseIdentity(it) }
                        LogUtils.d("[DBG] raw week=$week dow=$DEBUG_DOW rows=${debugWeekRows.size} term=${term.getCode()} -> $summary")
                    }
                }
                pool.shutdown()

                val result = merged.values.toMutableList()
                result.forEach { it.weeks = it.weeks.distinct().sorted().toMutableList() }
                result.sortWith(compareBy<CourseItem> { it.dow }.thenBy { it.begin }.thenBy { it.name })
                val debugBeforeMerge = result
                    .filter { it.dow == DEBUG_DOW && it.weeks.contains(DEBUG_WEEK) }
                    .sortedWith(compareBy<CourseItem> { it.begin }.thenBy { it.name })
                LogUtils.d(
                    "[DBG] dedup week=$DEBUG_WEEK dow=$DEBUG_DOW count=${debugBeforeMerge.size} term=${term.getCode()} -> " +
                        debugBeforeMerge.joinToString(" || ") { debugCourseIdentity(it) }
                )
                val mergedAdjacent = mergeAdjacentCourses(result)
                val debugAfterMerge = mergedAdjacent
                    .filter { it.dow == DEBUG_DOW && it.weeks.contains(DEBUG_WEEK) }
                    .sortedWith(compareBy<CourseItem> { it.begin }.thenBy { it.name })
                LogUtils.d(
                    "[DBG] merged week=$DEBUG_WEEK dow=$DEBUG_DOW count=${debugAfterMerge.size} term=${term.getCode()} -> " +
                        debugAfterMerge.joinToString(" || ") { debugCourseIdentity(it) }
                )

                if (mergedAdjacent.isEmpty()) {
                    res.postValue(DataState(DataState.STATE.FETCH_FAILED, "未获取到课表数据"))
                } else {
                    res.postValue(DataState(mergedAdjacent, DataState.STATE.SUCCESS))
                }
            } catch (e: Exception) {
                LogUtils.e("getTimetableOfTerm: failed, error=${e.message}", e)
                res.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return res
    }

    private fun getShenzhenWebTimetable(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<List<CourseItem>>> {
        val result = MutableLiveData<DataState<List<CourseItem>>>()
        Thread {
            try {
                val timetableResponse = jwFormPost(
                    token,
                    "/xszykb/queryxszykbzong",
                    mapOf("xn" to term.yearCode, "xq" to term.termCode),
                    "/xszykb/queryxszykb"
                )
                if (isJwAuthenticationExpired(timetableResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val subjectResponse = requestShenzhenWebSelectedSubjects(token, term)
                val subjects = if (isJwAuthenticationExpired(subjectResponse)) {
                    emptyList()
                } else {
                    ShenzhenWebAcademicParser.parseSelectedSubjects(subjectResponse.body()).orEmpty()
                }
                val courses = ShenzhenWebAcademicParser.parseTimetable(timetableResponse.body(), subjects)
                if (timetableResponse.statusCode() != 200 || courses == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "个人课表解析失败"))
                } else {
                    result.postValue(DataState(courses, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    private fun extractKcxxListFromKbcxContent(contentArr: org.json.JSONArray): org.json.JSONArray {
        for (i in 0 until contentArr.length()) {
            val obj = contentArr.optJSONObject(i) ?: continue
            val arr = obj.optJSONArray("kcxxList") ?: continue
            return arr
        }
        return org.json.JSONArray()
    }

    private fun extractWeekDatesFromKbcx(contentArr: org.json.JSONArray): List<String> {
        val dates = mutableListOf<String>()
        for (i in 0 until contentArr.length()) {
            val obj = contentArr.optJSONObject(i) ?: continue
            val rqList = obj.optJSONArray("rqList") ?: continue
            for (j in 0 until rqList.length()) {
                val rq = rqList.optJSONObject(j)?.optString("RQ")?.trim().orEmpty()
                if (rq.isNotBlank()) dates.add(rq)
            }
        }
        return dates
    }

    private fun fetchSelectedCourseNameByCodeSync(
        token: EASToken,
        term: TermItem
    ): Map<String, String> {
        return runCatching {
            val mapping = linkedMapOf<String, String>()
            val pylxRaw = token.getStudentType()
            val pylxPad = pylxRaw.padStart(2, '0')
            val pylxCandidates = linkedSetOf(pylxRaw, pylxPad)
            val roleCandidates = listOf("01", "06")
            val xnxqCandidates = linkedSetOf(
                term.getCode(),
                "${term.yearCode}-${term.termCode}",
                term.yearCode + term.termCode.padStart(2, '0'),
                "${term.yearCode}-${term.termCode.padStart(2, '0')}"
            )
            val xkfsCandidates = listOf("yixuan", "")

            var yxkc: org.json.JSONArray? = null
            for (roleHeader in roleCandidates) {
                for (pylx in pylxCandidates) {
                    for (xnxq in xnxqCandidates) {
                        for (xkfs in xkfsCandidates) {
                            val body =
                                """{"RoleCode":"$roleHeader","p_pylx":"$pylx","p_xn":"${term.yearCode}","p_xq":"${term.termCode}","p_xnxq":"$xnxq","p_gjz":"","p_kc_gjz":"","p_xkfsdm":"$xkfs"}"""
                            val resp = jsonPost(
                                token,
                                "/app/Xsxk/queryYxkc?_lang=zh_CN",
                                body,
                                rolecode = roleHeader
                            )
                            val jo = JsonUtils.getJsonObject(resp.body())
                            val list = extractYxkcList(jo)
                            if (list != null && list.length() > 0) {
                                yxkc = list
                                break
                            }
                        }
                        if (yxkc != null) break
                    }
                    if (yxkc != null) break
                }
                if (yxkc != null) break
            }

            yxkc?.let { list ->
                for (i in 0 until list.length()) {
                    val subject = list.optJSONObject(i) ?: continue
                    val rawCode = subject.optString("kcdm").trim()
                    val normalizedCode = CourseCodeUtils.normalize(rawCode)
                    val name = subject.optString("kcmc", "").trim()
                    if (name.isBlank()) continue
                    if (!normalizedCode.isNullOrBlank()) {
                        mapping[normalizedCode] = name
                    }
                    if (rawCode.isNotBlank()) {
                        mapping[rawCode] = name
                    }
                }
            }

            mapping
        }.getOrDefault(emptyMap())
    }

    private fun resolveTimetableCourseName(
        rawName: String,
        code: String,
        selectedCourseNameByCode: Map<String, String>
    ): String {
        if (!rawName.contains("...")) return rawName
        val normalizedCode = CourseCodeUtils.normalize(code) ?: code
        if (normalizedCode.isBlank()) return rawName
        return selectedCourseNameByCode[normalizedCode] ?: selectedCourseNameByCode[code] ?: rawName
    }

    private fun extractCourseNameFromKbxx(kbxx: String): String {
        if (kbxx.isBlank()) return ""
        return kbxx
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun extractClassroomFromKbxx(kbxx: String): String? {
        if (kbxx.isBlank()) return null
        val lines = kbxx.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        val bracket = Regex("\\[([^\\]]+)]")
        for (line in lines.asReversed()) {
            val hit = bracket.find(line)?.groupValues?.getOrNull(1)?.trim()
            if (!hit.isNullOrBlank()) return hit
        }
        return null
    }

    @SuppressLint("SimpleDateFormat")
    private fun getTermStartDateSyncByKbcx(token: EASToken, term: TermItem): Calendar {
        val calendar = Calendar.getInstance()
        calendar.firstDayOfWeek = Calendar.MONDAY
        try {
            val weekBody = """{"xn":"${term.yearCode}","xq":"${term.termCode}","zc":"1","type":"json"}"""
            val weekResp = jsonPost(token, "/app/Kbcx/query", weekBody)
            val weekJo = JsonUtils.getJsonObject(weekResp.body())
            val contentArr = weekJo?.optJSONArray("content")
            val df = SimpleDateFormat("yyyy-MM-dd")
            if (contentArr != null) {
                for (i in 0 until contentArr.length()) {
                    val obj = contentArr.optJSONObject(i) ?: continue
                    val rqList = obj.optJSONArray("rqList") ?: continue
                    val firstDay = rqList.optJSONObject(0) ?: continue
                    val rq = firstDay.optString("RQ")
                    if (rq.isNotEmpty()) {
                        val parsed = df.parse(rq)
                        if (parsed != null) calendar.timeInMillis = parsed.time
                    }
                    break
                }
            }
        } catch (_: Exception) {
        }
        return calendar
    }

    private fun dayOfWeekFromDate(date: String): Int {
        return try {
            val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val c = Calendar.getInstance()
            c.time = df.parse(date) ?: return -1
            val dow = c.get(Calendar.DAY_OF_WEEK)
            if (dow == Calendar.SUNDAY) 7 else dow - 1
        } catch (_: Exception) {
            -1
        }
    }

    private fun weekIndexFromDate(termStart: Calendar, date: String): Int {
        return try {
            val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val target = Calendar.getInstance().apply {
                time = df.parse(date) ?: return -1
            }
            val start = Calendar.getInstance().apply {
                timeInMillis = termStart.timeInMillis
            }
            val diffDays = ((target.timeInMillis - start.timeInMillis) / (24L * 60L * 60L * 1000L)).toInt()
            if (diffDays < 0) -1 else diffDays / 7 + 1
        } catch (_: Exception) {
            -1
        }
    }

    private fun parseXszykbArray(raw: String): org.json.JSONArray? {
        if (raw.isBlank()) return null
        JsonUtils.getJsonArray(raw)?.let { return it }
        val jo = JsonUtils.getJsonObject(raw) ?: return null
        val keys = listOf("content", "data", "rows", "list", "result")
        for (key in keys) {
            val arr = jo.optJSONArray(key)
            if (arr != null) return arr
        }
        val dataObj = jo.optJSONObject("data")
        if (dataObj != null) {
            for (key in keys) {
                val arr = dataObj.optJSONArray(key)
                if (arr != null) return arr
            }
        }
        return null
    }

    private fun upsertCourse(target: MutableMap<String, CourseItem>, incoming: CourseItem) {
        val key = listOf(
            incoming.name.orEmpty().trim(),
            incoming.dow.toString(),
            incoming.begin.toString(),
            incoming.last.toString(),
            incoming.teacher.orEmpty().trim(),
            incoming.classroom.orEmpty().trim()
        ).joinToString("|")

        val existing = target[key]
        if (existing == null) {
            target[key] = incoming
            return
        }
        for (w in incoming.weeks) {
            if (!existing.weeks.contains(w)) existing.weeks.add(w)
        }
        if (existing.code.isNullOrBlank() && !incoming.code.isNullOrBlank()) {
            existing.code = incoming.code
        }
    }

    private fun mergeAdjacentCourses(courses: List<CourseItem>): List<CourseItem> {
        if (courses.isEmpty()) return courses

        var current = courses.map { copyCourse(it) }
        repeat(6) {
            val (next, changed) = mergeAdjacentCoursesSinglePass(current)
            current = next
            if (!changed) {
                return current.sortedWith(
                    compareBy<CourseItem> { it.dow }
                        .thenBy { it.begin }
                        .thenBy { normalized(it.name) }
                        .thenBy { it.weeks.sorted().joinToString(",") }
                )
            }
        }

        return current.sortedWith(
            compareBy<CourseItem> { it.dow }
                .thenBy { it.begin }
                .thenBy { normalized(it.name) }
                .thenBy { it.weeks.sorted().joinToString(",") }
        )
    }

    private fun mergeAdjacentCoursesSinglePass(courses: List<CourseItem>): Pair<List<CourseItem>, Boolean> {
        val sorted = courses.sortedWith(
            compareBy<CourseItem> { it.dow }
                .thenBy { it.begin }
                .thenBy { normalized(it.name) }
                .thenBy { normalized(it.teacher) }
                .thenBy { it.weeks.sorted().joinToString(",") }
        )

        if (sorted.isEmpty()) return Pair(emptyList(), false)

        val used = BooleanArray(sorted.size)
        val merged = mutableListOf<CourseItem>()
        val residualCourses = mutableListOf<CourseItem>()
        var changed = false

        for (index in sorted.indices) {
            if (used[index]) continue
            val base = copyCourse(sorted[index])
            used[index] = true

            while (true) {
                val expectedBegin = base.begin + base.last
                var mergedIndex = -1

                for (candidateIndex in (index + 1) until sorted.size) {
                    if (used[candidateIndex]) continue
                    val candidate = sorted[candidateIndex]

                    if (candidate.dow != base.dow) {
                        if (candidate.dow > base.dow) break
                        continue
                    }
                    if (candidate.begin < expectedBegin) continue
                    if (candidate.begin > expectedBegin) break

                    val weekSplitMerged = tryMergeWithWeekSplit(base, candidate, residualCourses)
                    if (weekSplitMerged) {
                        mergedIndex = candidateIndex
                        break
                    }

                    val canMerge = canMergeCourses(base, candidate)
                    if (canMerge) {
                        base.last += candidate.last
                        if (base.classroom.isNullOrBlank()) {
                            base.classroom = candidate.classroom
                        }
                        if (base.code.isNullOrBlank()) {
                            base.code = candidate.code
                        }
                        mergedIndex = candidateIndex
                        break
                    }

                    if (shouldDebug(base, candidate)) {
                        LogUtils.d(
                            "[DBG] no-merge week=$DEBUG_WEEK dow=$DEBUG_DOW reason=${mergeBlockReason(base, candidate)} left=${debugCourseIdentity(base)} right=${debugCourseIdentity(candidate)}"
                        )
                    }
                }

                if (mergedIndex == -1) break
                used[mergedIndex] = true
                changed = true
            }

            merged.add(base)
        }

        if (residualCourses.isNotEmpty()) {
            merged.addAll(residualCourses)
            changed = true
        }

        return Pair(merged, changed)
    }


    private fun tryMergeWithWeekSplit(
        left: CourseItem,
        right: CourseItem,
        residualCourses: MutableList<CourseItem>
    ): Boolean {
        if (!canMergeBaseIgnoringWeeks(left, right)) return false

        val leftWeeks = left.weeks.distinct().sorted()
        val rightWeeks = right.weeks.distinct().sorted()
        if (leftWeeks == rightWeeks) return false

        val sharedWeeks = leftWeeks.intersect(rightWeeks.toSet()).sorted()
        if (sharedWeeks.isEmpty()) return false

        val leftOnlyWeeks = leftWeeks.filter { it !in sharedWeeks }
        val rightOnlyWeeks = rightWeeks.filter { it !in sharedWeeks }

        if (leftOnlyWeeks.isNotEmpty()) {
            residualCourses.add(copyCourse(left).apply {
                weeks = leftOnlyWeeks.toMutableList()
            })
        }
        if (rightOnlyWeeks.isNotEmpty()) {
            residualCourses.add(copyCourse(right).apply {
                weeks = rightOnlyWeeks.toMutableList()
            })
        }

        left.weeks = sharedWeeks.toMutableList()
        left.last += right.last
        if (left.classroom.isNullOrBlank()) {
            left.classroom = right.classroom
        }
        if (left.code.isNullOrBlank()) {
            left.code = right.code
        }

        if (shouldDebug(left, right)) {
            LogUtils.d(
                "[DBG] week-split-merge shared=$sharedWeeks leftOnly=$leftOnlyWeeks rightOnly=$rightOnlyWeeks left=${debugCourseIdentity(left)} right=${debugCourseIdentity(right)}"
            )
        }

        return true
    }

    private fun canMergeCourses(left: CourseItem, right: CourseItem): Boolean {
        if (!canMergeBaseIgnoringWeeks(left, right)) return false
        return left.weeks.distinct().sorted() == right.weeks.distinct().sorted()
    }

    private fun canMergeBaseIgnoringWeeks(left: CourseItem, right: CourseItem): Boolean {
        if (left.dow != right.dow) return false
        if (!sameCourseIdentity(left, right)) return false
        if (!teacherCompatible(left, right)) return false

        val leftEndPeriod = left.begin + left.last - 1
        if (leftEndPeriod + 1 != right.begin) return false

        val leftClassroom = normalized(left.classroom)
        val rightClassroom = normalized(right.classroom)
        if (leftClassroom.isNotEmpty() && rightClassroom.isNotEmpty() && leftClassroom != rightClassroom) {
            return false
        }

        val leftCode = normalized(left.code)
        val rightCode = normalized(right.code)
        if (leftCode.isNotEmpty() && rightCode.isNotEmpty() && leftCode != rightCode) {
            return false
        }

        return true
    }

    private fun sameCourseIdentity(left: CourseItem, right: CourseItem): Boolean {
        val leftName = normalized(left.name)
        val rightName = normalized(right.name)
        if (leftName == rightName) return true

        val leftCode = normalized(left.code)
        val rightCode = normalized(right.code)
        if (leftCode.isNotEmpty() && rightCode.isNotEmpty() && leftCode == rightCode) {
            return true
        }

        return false
    }


    private fun teacherCompatible(left: CourseItem, right: CourseItem): Boolean {
        val leftTeacher = normalizedTeacher(left.teacher)
        val rightTeacher = normalizedTeacher(right.teacher)
        if (leftTeacher.isEmpty() || rightTeacher.isEmpty()) return true
        if (leftTeacher == rightTeacher) return true
        if (leftTeacher.contains(rightTeacher) || rightTeacher.contains(leftTeacher)) return true

        val leftCode = normalized(left.code)
        val rightCode = normalized(right.code)
        if (leftCode.isNotEmpty() && rightCode.isNotEmpty() && leftCode == rightCode) {
            return true
        }

        return false
    }

    private fun normalized(value: String?): String {
        return value
            ?.replace("\u00A0", " ")
            ?.replace(Regex("\\s+"), "")
            ?.trim()
            .orEmpty()
    }

    private fun normalizedTeacher(value: String?): String {
        val raw = value
            ?.replace("\u00A0", " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
        if (raw.isEmpty()) return ""

        val tokens = raw
            .split(Regex("[、,，/|；;]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { token ->
                token
                    .replace(Regex("^(教师|老师|Teacher)[:： ]*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\(.*?\\)|（.*?）"), "")
                    .replace(Regex("\\p{Cntrl}"), "")
                    .trim()
            }
            .filterNot { token ->
                token.isBlank() ||
                    token.contains("周") ||
                    token.contains("节") ||
                    token.contains("星期") ||
                    token.contains("课程") ||
                    token.contains("上课") ||
                    token.contains("教室") ||
                    token.contains("地点") ||
                    token.any { it.isDigit() }
            }
            .distinct()
            .sorted()

        if (tokens.isNotEmpty()) {
            return tokens.joinToString("|")
        }

        return normalized(raw)
    }

    private fun copyCourse(source: CourseItem): CourseItem {
        return CourseItem().apply {
            code = source.code
            name = source.name
            weeks = source.weeks.toMutableList()
            teacher = source.teacher
            classroom = source.classroom
            dow = source.dow
            begin = source.begin
            last = source.last
        }
    }

    private fun shouldDebug(left: CourseItem, right: CourseItem): Boolean {
        if (left.dow != DEBUG_DOW || right.dow != DEBUG_DOW) return false
        if (!left.weeks.contains(DEBUG_WEEK) || !right.weeks.contains(DEBUG_WEEK)) return false
        return true
    }

    private fun mergeBlockReason(left: CourseItem, right: CourseItem): String {
        if (left.dow != right.dow) return "dow"
        if (!sameCourseIdentity(left, right)) return "identity"
        if (!teacherCompatible(left, right)) return "teacher"
        if (left.weeks.distinct().sorted() != right.weeks.distinct().sorted()) return "weeks"

        val leftEndPeriod = left.begin + left.last - 1
        if (leftEndPeriod + 1 != right.begin) return "period"

        val leftClassroom = normalized(left.classroom)
        val rightClassroom = normalized(right.classroom)
        if (leftClassroom.isNotEmpty() && rightClassroom.isNotEmpty() && leftClassroom != rightClassroom) {
            return "classroom"
        }

        val leftCode = normalized(left.code)
        val rightCode = normalized(right.code)
        if (leftCode.isNotEmpty() && rightCode.isNotEmpty() && leftCode != rightCode) {
            return "code"
        }

        return "unknown"
    }

    private fun debugCourseIdentity(course: CourseItem): String {
        return "name=${course.name.orEmpty()} begin=${course.begin} last=${course.last} weeks=${course.weeks.sorted()} teacher=${course.teacher.orEmpty()} classroom=${course.classroom.orEmpty()} code=${course.code.orEmpty()}"
    }

    private fun parseXszykbCourseRow(row: JSONObject, weekHint: Int?): CourseItem? {
        val ksjc = row.optInt("KSJC", -1)
        val jsjc = row.optInt("JSJC", -1)
        if (ksjc <= 0) return null
        val key = row.optString("KEY", "")
        val dow = Regex("xq(\\d+)_", RegexOption.IGNORE_CASE)
            .find(key)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: -1
        if (dow !in 1..7) return null

        val sksj = row.optString("SKSJ", "").trim()
        val name = extractXszykbName(sksj)
        if (name.isBlank()) return null

        val (teacher, classroom) = extractTeacherAndClassroomFromSksj(sksj)
        val codeRaw = extractCourseCodeFromRwh(row.optString("RWH", ""))
        val code = CourseCodeUtils.normalize(codeRaw) ?: codeRaw

        val weeks = parseWeeksFromZc(row.optString("ZC", ""), weekHint)
        val effectiveJsjc = if (jsjc >= ksjc) jsjc else ksjc

        return CourseItem().apply {
            this.code = code
            this.name = name
            this.teacher = teacher
            this.classroom = classroom
            this.dow = dow
            this.begin = ksjc
            this.last = (effectiveJsjc - ksjc + 1).coerceAtLeast(1)
            this.weeks = weeks.toMutableList()
        }
    }

    private fun extractXszykbName(sksj: String): String {
        if (sksj.isBlank()) return ""
        return sksj
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.substringBefore("备注:")
            ?.trim()
            .orEmpty()
    }

    private fun extractTeacherAndClassroomFromSksj(sksj: String): Pair<String?, String?> {
        if (sksj.isBlank()) return Pair(null, null)
        val bracketParts = Regex("\\[([^\\]]+)]")
            .findAll(sksj)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()

        var teacher: String? = null
        var classroom: String? = null
        for (part in bracketParts) {
            val isWeekLike = part.contains("周") || part.contains("Week", ignoreCase = true)
            val isPeriodLike = part.contains("节")
            if (isWeekLike || isPeriodLike) continue
            if (classroom == null && looksLikeClassroom(part)) {
                classroom = part
                continue
            }
            if (teacher == null && !looksLikeClassroom(part)) {
                teacher = part
            }
        }
        return Pair(teacher, classroom)
    }

    private fun looksLikeClassroom(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.contains("田径场") || text.contains("实验室") || text.contains("教室")) return true
        if (text.matches(Regex("^[A-Za-z]\\d{2,}.*$"))) return true
        if (text.matches(Regex("^[TGHKAtghka]?\\d{3,}.*$"))) return true
        if (text.contains("楼") || text.contains("馆") || text.contains("场")) return true
        return false
    }

    private fun extractCourseCodeFromRwh(rwh: String): String {
        if (rwh.isBlank()) return ""
        val parts = rwh.split("-")
        if (parts.size >= 5) return parts[3].trim()
        return ""
    }

    private fun parseWeeksFromZc(zc: String, weekHint: Int?): List<Int> {
        if (zc.isNotBlank()) {
            val weeks = mutableListOf<Int>()
            for (i in 1 until zc.length) {
                if (zc[i] == '1') weeks.add(i)
            }
            if (weeks.isNotEmpty()) return weeks
        }
        return if (weekHint != null) listOf(weekHint) else emptyList()
    }

    private fun extractTeacher(kc: org.json.JSONObject, courseName: String?, kbxx: String): String? {
        val keys = listOf("SKJS", "JSXM", "RKJS", "JS", "JSMC", "JSMC1", "JSMC2")
        for (key in keys) {
            val v = kc.optString(key, "").trim()
            if (v.isNotEmpty()) return v
        }
        val lines = kbxx.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.contains("[") && it.contains("]") }
        val cleaned = if (courseName.isNullOrBlank()) {
            lines
        } else {
            lines.filterNot { it.replace(" ", "") == courseName.replace(" ", "") }
        }
        if (cleaned.isNotEmpty()) {
            val raw = cleaned.joinToString(" / ")
            return raw.replace(Regex("^(教师|老师|Teacher)[:： ]*"), "").trim()
        }
        return null
    }

    private fun extractSelectedTeacher(subject: org.json.JSONObject): String? {
        val keys = listOf(
            "DGJSMC", "dgjsmc",
            "SKJS", "SKJSXM", "SKJSMC", "SKJSXX",
            "JSXM", "JSMC", "RKJS", "JS",
            "skjs", "skjsxm", "skjsmc", "skjsxx",
            "jsxm", "jsmc", "rkjs", "js"
        )
        for (key in keys) {
            val v = subject.optString(key, "").trim()
            if (v.isNotEmpty()) return v
        }
        return null
    }

    private fun optStringFirst(subject: org.json.JSONObject, keys: List<String>): String {
        for (key in keys) {
            val v = subject.optString(key, "").trim()
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private fun extractYxkcList(jo: JSONObject?): org.json.JSONArray? {
        if (jo == null) return null
        val keys = listOf("yxkcList", "content", "data", "rows", "list")
        for (key in keys) {
            val arr = jo.optJSONArray(key)
            if (arr != null && arr.length() > 0) return arr
        }
        val dataObj = jo.optJSONObject("data")
        if (dataObj != null) {
            for (key in keys) {
                val arr = dataObj.optJSONArray(key)
                if (arr != null && arr.length() > 0) return arr
            }
        }
        val contentObj = jo.optJSONObject("content")
        if (contentObj != null) {
            val it = contentObj.keys()
            while (it.hasNext()) {
                val key = it.next()
                val arr = contentObj.optJSONArray(key) ?: continue
                if (arr.length() == 0) continue
                if (key.contains("yxkc", ignoreCase = true)) {
                    return arr
                }
                val first = arr.optJSONObject(0)
                if (first == null) return arr
                if (first.has("kcmc") || first.has("kcdm") || first.has("dgjsmc") || first.has("DGJSMC")) {
                    return arr
                }
                val scanLimit = minOf(arr.length(), 5)
                for (i in 1 until scanLimit) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (obj.has("kcmc") || obj.has("kcdm") || obj.has("dgjsmc") || obj.has("DGJSMC")) {
                        return arr
                    }
                }
            }
        }
        return null
    }

    private fun buildTermDisplayName(yearName: String?, termName: String?): String {
        val year = yearName?.trim().orEmpty()
        val term = termName?.trim().orEmpty()
        if (year.isEmpty()) return term
        if (term.isEmpty()) return year
        return if (term.contains(year)) term else "$year $term"
    }

    // ================================================================ 课表时间结构
    @SuppressLint("SimpleDateFormat")
    override fun getScheduleStructure(
        term: TermItem,
        isUndergraduate: Boolean?,
        token: EASToken
    ): LiveData<DataState<MutableList<TimePeriodInDay>>> {
        if (token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()) {
            return getShenzhenWebScheduleStructure(token, term)
        }
        val res = MutableLiveData<DataState<MutableList<TimePeriodInDay>>>()
        Thread {
            try {
                val kbBody = """{"xn":"${term.yearCode}","xq":"${term.termCode}","zc":"1","type":"json"}"""
                val kbResp = jsonPost(token, "/app/Kbcx/query", kbBody)
                val kbJo = JsonUtils.getJsonObject(kbResp.body())
                val contentArr = kbJo?.optJSONArray("content")
                val df = SimpleDateFormat("HH:mm", Locale.getDefault())
                val slots: MutableList<TimePeriodInDay?> = mutableListOf()
                if (contentArr != null) {
                    for (i in 0 until contentArr.length()) {
                        val obj = contentArr.optJSONObject(i) ?: continue
                        val jcList = obj.optJSONArray("jcList") ?: continue
                        var maxPeriod = 0
                        for (j in 0 until jcList.length()) {
                            val jc = jcList.optJSONObject(j) ?: continue
                            val jsjc = jc.optInt("JSJC", 0)
                            if (jsjc > maxPeriod) maxPeriod = jsjc
                        }
                        if (maxPeriod <= 0) continue
                        val defaults = defaultScheduleStructure()
                        if (maxPeriod <= defaults.size) {
                            res.postValue(DataState(defaults.take(maxPeriod).toMutableList()))
                            return@Thread
                        }
                        while (slots.size < maxPeriod) slots.add(null)
                        for (j in 0 until jcList.length()) {
                            val jc = jcList.optJSONObject(j) ?: continue
                            val ks = jc.optInt("KSJC", 0)
                            val js = jc.optInt("JSJC", 0)
                            val count = js - ks + 1
                            val sj = jc.optString("SJ", "")
                            val parts = sj.split("—", "–", "-")
                            if (count <= 0 || parts.size < 2) continue
                            try {
                                val start = df.parse(parts[0].trim()) ?: continue
                                val end = df.parse(parts[1].trim()) ?: continue
                                val totalMinutes = ((end.time - start.time) / 60000L).toInt()
                                if (totalMinutes <= 0) continue
                                val perSlot = totalMinutes / count
                                if (perSlot <= 0) continue
                                for (idx in 0 until count) {
                                    val slotStart = (start.time / 60000L).toInt() + perSlot * idx
                                    val slotEnd = if (idx == count - 1) {
                                        (end.time / 60000L).toInt()
                                    } else {
                                        (start.time / 60000L).toInt() + perSlot * (idx + 1)
                                    }
                                    val from = TimeInDay(slotStart / 60, slotStart % 60)
                                    val to = TimeInDay(slotEnd / 60, slotEnd % 60)
                                    val pos = ks - 1 + idx
                                    if (pos in slots.indices) {
                                        slots[pos] = TimePeriodInDay(from, to)
                                    }
                                }
                            } catch (_: Exception) { LogUtils.w("Failed to parse slot structure") }
                        }
                        if (slots.any { it != null }) break
                    }
                }
                val result = if (slots.isNotEmpty() && slots.all { it != null }) {
                    slots.map { it!! }.toMutableList()
                } else {
                    val defaults = defaultScheduleStructure()
                    val size = maxOf(slots.size, defaults.size)
                    val filled = MutableList(size) { idx ->
                        slots.getOrNull(idx) ?: defaults.getOrNull(idx) ?: defaults.last()
                    }
                    filled
                }
                res.postValue(DataState(result))
            } catch (e: Exception) {
                LogUtils.e("getScheduleStructure: failed, error=${e.message}", e)
                res.postValue(DataState(defaultScheduleStructure()))
            }
        }.start()
        return res
    }

    private fun getShenzhenWebScheduleStructure(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<MutableList<TimePeriodInDay>>> {
        val result = MutableLiveData<DataState<MutableList<TimePeriodInDay>>>()
        Thread {
            try {
                val response = jwFormPost(
                    token,
                    "/component/queryKbjg",
                    mapOf(
                        "xn" to term.yearCode,
                        "xq" to term.termCode,
                        "pylx" to token.getStudentType()
                    )
                )
                if (isJwAuthenticationExpired(response)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val schedule = ShenzhenWebAcademicParser.parseScheduleStructure(response.body())
                if (response.statusCode() != 200 || schedule == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "节次结构解析失败"))
                } else {
                    result.postValue(DataState(schedule, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    @SuppressLint("SimpleDateFormat")
    private fun defaultScheduleStructure(): MutableList<TimePeriodInDay> {
        val slots = listOf(
            "08:30" to "09:20", "09:25" to "10:15",
            "10:30" to "11:20", "11:25" to "12:15",
            "14:00" to "14:50", "14:55" to "15:45",
            "16:00" to "16:50", "16:55" to "17:45",
            "18:45" to "19:35", "19:40" to "20:30",
            "20:45" to "21:35", "21:40" to "22:30"
        )
        val df = SimpleDateFormat("HH:mm")
        return slots.map { (s, e) ->
            val from = Calendar.getInstance().also { c -> c.timeInMillis = df.parse(s)!!.time }
            val to = Calendar.getInstance().also { c -> c.timeInMillis = df.parse(e)!!.time }
            TimePeriodInDay(TimeInDay(from), TimeInDay(to))
        }.toMutableList()
    }

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

    /**
     * 判断 token 是否像教室编号（纯 ASCII 字母+数字+_-）
     */
    private fun isLikelyClassroomToken(token: String): Boolean {
        val trimmed = token.trim()
        if (trimmed.isBlank()) return false
        // 必须同时包含字母和数字
        val hasLetter = trimmed.any { it.isLetter() && it in '\u0000'..'\u007F' }
        val hasDigit = trimmed.any { it.isDigit() }
        if (!hasLetter || !hasDigit) return false
        // 全部字符必须是 ASCII 字母、数字、-、_
        return trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    /**
     * 从 KBXX 第一行提取 sessionHint（非教室的 [] 内容）
     */
    private fun extractSessionHint(kbxx: String): String? {
        if (kbxx.isBlank()) return null
        val firstLine = kbxx.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return null
        val bracket = Regex("\\[([^\\]]+)]")
        for (match in bracket.findAll(firstLine)) {
            val content = match.groupValues.getOrNull(1)?.trim() ?: continue
            val cleaned = content.removeSuffix(".").removeSuffix("．").trim()
            if (cleaned.isBlank()) continue
            if (!isLikelyClassroomToken(cleaned)) {
                return cleaned
            }
        }
        return null
    }

    /**
     * 合成课程显示名
     */
    private fun composeCourseDisplayName(
        canonicalName: String?,
        fallbackName: String,
        sessionHint: String?
    ): String {
        val baseName = if (!canonicalName.isNullOrBlank()) canonicalName else fallbackName
        if (sessionHint.isNullOrBlank()) return baseName
        // 如果 baseName 已经包含 sessionHint，不再重复拼接
        if (baseName.contains(sessionHint)) return baseName
        return "$baseName [$sessionHint]"
    }

    /**
     * 从 KBXX 提取课程名（去掉 [] 和教室）
     */
    private fun normalizedCourseName(kbxx: String): String {
        if (kbxx.isBlank()) return ""
        val firstLine = kbxx.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return ""
        // 去掉所有 []
        return firstLine.replace(Regex("\\[[^\\]]*]"), "").trim()
    }

    /**
     * 调用 querykbrczong 总览接口
     */
    private fun fetchTimetableFromOverview(
        token: EASToken,
        term: TermItem
    ): List<CourseItem>? {
        return try {
            val body = """{"xn":"${term.yearCode}","xq":"${term.termCode}","type":"json"}"""
            val resp = jsonPost(token, "/app/kbrcbyapp/querykbrczong", body)
            val jo = JsonUtils.getJsonObject(resp.body())
            val content = jo?.optJSONArray("content") ?: return null
            val result = mutableListOf<CourseItem>()
            for (i in 0 until content.length()) {
                val course = content.optJSONObject(i) ?: continue
                val kbxx = course.optString("KBXX", "")
                val rawCode = course.optString("KCDM", "")
                val code = CourseCodeUtils.normalize(rawCode) ?: rawCode
                val xqj = course.optInt("XQJ", -1)
                val dj = course.optInt("DJ", -1)
                val ksjc = course.optInt("KSJC", -1)
                val jsjc = course.optInt("JSJC", -1)
                if (xqj !in 1..7 || dj <= 0) continue

                // 优先 KCMC，其次 KBXX
                val kcmc = course.optString("KCMC", "").trim()
                val kcmcEn = course.optString("KCMC_EN", "").trim()
                val canonicalName = kcmc.takeIf { it.isNotBlank() } ?: kcmcEn.takeIf { it.isNotBlank() }
                val fallbackName = normalizedCourseName(kbxx)
                val sessionHint = extractSessionHint(kbxx)
                val displayName = composeCourseDisplayName(canonicalName, fallbackName, sessionHint)

                val classroom = extractClassroomFromKbxx(kbxx) ?: ""
                val teacher = course.optString("JSXM", "").trim()
                val begin = if (ksjc > 0) ksjc else (dj - 1) * 2 + 1
                val end = if (jsjc >= ksjc) jsjc else begin + 1
                val last = end - begin + 1

                result.add(CourseItem().apply {
                    this.code = code
                    this.name = displayName
                    this.rawName = displayName
                    this.teacher = teacher
                    this.classroom = classroom
                    this.dow = xqj
                    this.begin = begin
                    this.last = last
                    this.weeks = mutableListOf() // 总览接口没有周次信息，需要后续补充
                })
            }
            result.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            LogUtils.e("fetchTimetableFromOverview: failed, error=${e.message}", e)
            null
        }
    }

    /**
     * 调用 querykbrcbyday 日课表接口，获取指定日期的课程详情
     */
    private fun fetchDaySchedule(
        token: EASToken,
        date: String
    ): List<DayScheduleItem> {
        return try {
            val body = """{"nyr":"$date"}"""
            val resp = jsonPost(token, "/app/kbrcbyapp/querykbrcbyday", body)
            val jo = JsonUtils.getJsonObject(resp.body())
            val content = jo?.optJSONArray("content") ?: return emptyList()
            val result = mutableListOf<DayScheduleItem>()
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                result.add(DayScheduleItem(
                    kcmc = item.optString("KCMC", "").trim(),
                    cdmc = item.optString("CDMC", "").trim(),
                    ksjc = item.optInt("KSJC", -1),
                    jsjc = item.optInt("JSJC", -1),
                    dj = item.optInt("DJ", -1),
                    xqj = item.optInt("XQJ", -1)
                ))
            }
            result
        } catch (e: Exception) {
            LogUtils.e("fetchDaySchedule: failed, error=${e.message}", e)
            emptyList()
        }
    }

    /**
     * 日课表数据项
     */
    private data class DayScheduleItem(
        val kcmc: String,
        val cdmc: String,
        val ksjc: Int,
        val jsjc: Int,
        val dj: Int,
        val xqj: Int
    )

    /**
     * 从日课表列表中匹配最佳 KCMC
     */
    private fun bestDayScheduleRawName(
        daySchedules: List<DayScheduleItem>,
        dow: Int,
        begin: Int,
        end: Int,
        classroom: String
    ): String? {
        val candidates = daySchedules.filter {
            it.xqj == dow && it.ksjc == begin && it.jsjc == end
        }
        if (candidates.isEmpty()) return null
        // 优先匹配教室
        val withClassroom = candidates.find { it.cdmc == classroom }
        return withClassroom?.kcmc?.takeIf { it.isNotBlank() }
            ?: candidates.firstOrNull { it.kcmc.isNotBlank() }?.kcmc
    }

}
