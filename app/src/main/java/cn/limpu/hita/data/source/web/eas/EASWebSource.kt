package cn.limpu.hita.data.source.web.eas

import android.util.Base64
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import cn.limpu.hita.utils.LogUtils
import com.limpu.component.data.DataState
import cn.limpu.hita.data.model.eas.CourseSelectionCourseResult
import cn.limpu.hita.data.model.eas.CourseSelectionJob
import cn.limpu.hita.data.model.eas.CourseSelectionJobCourse
import cn.limpu.hita.data.model.eas.CourseItem
import cn.limpu.hita.data.model.eas.CourseScoreItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.eas.ScoreQueryResult
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogPage
import cn.limpu.hita.data.model.eas.ShenzhenCourseAttachment
import cn.limpu.hita.data.model.eas.ShenzhenGradeAnalysis
import cn.limpu.hita.data.model.eas.ShenzhenGradeCourse
import cn.limpu.hita.data.model.eas.ShenzhenHistoricalFailureReport
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogItem
import cn.limpu.hita.data.model.eas.ShenzhenCourseRecommendationResult
import cn.limpu.hita.data.model.eas.ShenzhenCreditProgress
import cn.limpu.hita.data.model.eas.ShenzhenCreditRequirement
import cn.limpu.hita.data.model.eas.ShenzhenRecommendationOptions
import cn.limpu.hita.data.model.eas.ShenzhenSelectionPool
import cn.limpu.hita.data.model.eas.ShenzhenTrainingPlan
import cn.limpu.hita.data.model.eas.ShenzhenTrainingPlanDetail
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.source.web.service.EASService
import cn.limpu.hita.data.repository.EasCredentialReloginPolicy
import cn.limpu.hita.data.source.preference.EasCredential
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.utils.JsonUtils
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher

internal fun interface ShenzhenCourseSelectionTransport {
    @Throws(IOException::class)
    fun execute(request: Connection): Connection.Response
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
    private val examApiHostOverride: String? = null,
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
    private val academicSource = ShenzhenAcademicWebSource(
        authedFormPost = ::authedFormPost,
        jwFormPost = ::jwFormPost,
        jwJsonPost = ::jwJsonPost,
        jwSelectionFormPostOnce = ::jwSelectionFormPostOnce,
        jsonPost = ::jsonPost,
        isAuthExpiredResponse = ::isAuthExpiredResponse,
        isAuthExpiredJson = ::isAuthExpiredJson,
        isJwAuthenticationExpired = ::isJwAuthenticationExpired,
        mergeShenzhenCourseSelectionCookies = ::mergeShenzhenCourseSelectionCookies,
        onTokenRefreshed = onTokenRefreshed,
        buildTermDisplayName = timetableSource::buildTermDisplayName,
        shenzhenSelectedSubjectsForm = timetableSource::shenzhenSelectedSubjectsForm,
        requestShenzhenWebSelectedSubjects = timetableSource::requestShenzhenWebSelectedSubjects,
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

    // ================================================================ 学业入口
    override fun getAllTerms(token: EASToken): LiveData<DataState<List<TermItem>>> =
        academicSource.getAllTerms(token)

    override suspend fun getAllTermsOnce(token: EASToken): DataState<List<TermItem>> =
        academicSource.getAllTermsOnce(token)

    fun getShenzhenWebTerms(token: EASToken): LiveData<DataState<List<TermItem>>> =
        academicSource.getShenzhenWebTerms(token)

    /** Single Web-only term query, retaining the independent Shenzhen Web session route. */
    suspend fun getShenzhenWebTermsOnce(token: EASToken): DataState<List<TermItem>> =
        academicSource.getShenzhenWebTermsOnce(token)

    fun queryShenzhenAvailableCourses(
        token: EASToken,
        term: TermItem,
        pool: ShenzhenSelectionPool,
        keyword: String,
        page: Int,
        pageSize: Int
    ): LiveData<DataState<ShenzhenCourseCatalogPage>> =
        academicSource.queryShenzhenAvailableCourses(token, term, pool, keyword, page, pageSize)

    @WorkerThread
    fun submitShenzhenCourseOnce(
        token: EASToken,
        job: CourseSelectionJob,
        course: CourseSelectionJobCourse
    ): CourseSelectionCourseResult =
        academicSource.submitShenzhenCourseOnce(token, job, course)

    @WorkerThread
    fun getShenzhenSelectedRequestIdsOnce(
        token: EASToken,
        job: CourseSelectionJob
    ): Set<String> =
        academicSource.getShenzhenSelectedRequestIdsOnce(token, job)

    fun queryShenzhenSchoolCourses(
        token: EASToken,
        term: TermItem,
        studentType: String,
        keyword: String,
        page: Int,
        pageSize: Int
    ): LiveData<DataState<ShenzhenCourseCatalogPage>> =
        academicSource.queryShenzhenSchoolCourses(token, term, studentType, keyword, page, pageSize)

    fun getShenzhenCourseRecommendations(
        token: EASToken,
        term: TermItem,
        pools: List<ShenzhenSelectionPool>,
        options: ShenzhenRecommendationOptions,
        requirements: List<ShenzhenCreditRequirement> = emptyList()
    ): LiveData<DataState<ShenzhenCourseRecommendationResult>> =
        academicSource.getShenzhenCourseRecommendations(token, term, pools, options, requirements)

    fun getShenzhenCourseAttachments(
        token: EASToken,
        courseId: String,
        taskNumber: String
    ): LiveData<DataState<List<ShenzhenCourseAttachment>>> =
        academicSource.getShenzhenCourseAttachments(token, courseId, taskNumber)

    fun getShenzhenGradeCourses(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<List<ShenzhenGradeCourse>>> =
        academicSource.getShenzhenGradeCourses(token, term)

    fun getShenzhenGradeAnalysis(
        token: EASToken,
        course: ShenzhenGradeCourse
    ): LiveData<DataState<ShenzhenGradeAnalysis>> =
        academicSource.getShenzhenGradeAnalysis(token, course)

    fun getShenzhenHistoricalTeacherFailureRates(
        token: EASToken,
        referenceTerm: TermItem,
        studentType: String,
        referenceCourse: ShenzhenCourseCatalogItem,
        yearsBack: Int = 2
    ): LiveData<DataState<ShenzhenHistoricalFailureReport>> =
        academicSource.getShenzhenHistoricalTeacherFailureRates(
            token,
            referenceTerm,
            studentType,
            referenceCourse,
            yearsBack,
        )

    fun getShenzhenTrainingPlans(
        token: EASToken
    ): LiveData<DataState<List<ShenzhenTrainingPlan>>> =
        academicSource.getShenzhenTrainingPlans(token)

    fun getShenzhenCreditProgress(
        token: EASToken,
        selectedTerm: TermItem? = null,
        includeDetails: Boolean = true,
        includeCourseRecords: Boolean = true,
        trackCoursesOnly: Boolean = false
    ): LiveData<DataState<ShenzhenCreditProgress>> =
        academicSource.getShenzhenCreditProgress(
            token,
            selectedTerm,
            includeDetails,
            includeCourseRecords,
            trackCoursesOnly,
        )

    fun getShenzhenTrainingPlanCourses(
        token: EASToken,
        plan: ShenzhenTrainingPlan
    ): LiveData<DataState<ShenzhenTrainingPlanDetail>> =
        academicSource.getShenzhenTrainingPlanCourses(token, plan)

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

    override suspend fun getStartDateOnce(token: EASToken, term: TermItem): DataState<Calendar> =
        timetableSource.getStartDateOnce(token, term)

    override suspend fun getScheduleStructureOnce(
        term: TermItem,
        isUndergraduate: Boolean?,
        token: EASToken,
    ): DataState<MutableList<TimePeriodInDay>> =
        timetableSource.getScheduleStructureOnce(term, isUndergraduate, token)

    override suspend fun getSubjectsOfTermOnce(token: EASToken, term: TermItem): DataState<MutableList<TermSubject>> =
        timetableSource.getSubjectsOfTermOnce(token, term)

    override suspend fun getTimetableOfTermOnce(term: TermItem, token: EASToken): DataState<List<CourseItem>> =
        timetableSource.getTimetableOfTermOnce(term, token)

    suspend fun getShenzhenCoursePlanningStartDateOnce(token: EASToken, term: TermItem): DataState<Calendar> =
        timetableSource.getShenzhenCoursePlanningStartDateOnce(token, term)

    suspend fun getShenzhenCoursePlanningScheduleStructureOnce(
        token: EASToken,
        term: TermItem,
    ): DataState<MutableList<TimePeriodInDay>> =
        timetableSource.getShenzhenCoursePlanningScheduleStructureOnce(token, term)

    // ================================================================ 成绩入口
    override fun getPersonalScores(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType
    ): LiveData<DataState<List<CourseScoreItem>>> =
        academicSource.getPersonalScores(term, token, testType)

    fun getPersonalScoresWithSummary(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType
    ): LiveData<DataState<ScoreQueryResult>> =
        academicSource.getPersonalScoresWithSummary(term, token, testType)

    // ================================================================ 教学楼列表
    override fun getTeachingBuildings(token: EASToken): LiveData<DataState<List<BuildingItem>>> {
        val useWebSession = token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()
        val result = MutableLiveData<DataState<List<BuildingItem>>>()
        Thread {
            val state = try {
                requestTeachingBuildings(token, useWebSession)
            } catch (cancelled: CancellationException) {
                // The legacy callback API still reports every Exception as a terminal failure.
                DataState(DataState.STATE.FETCH_FAILED, if (useWebSession) cancelled.message else null)
            }
            result.postValue(state)
        }.start()
        return result
    }

    override suspend fun getTeachingBuildingsOnce(token: EASToken): DataState<List<BuildingItem>> {
        val useWebSession = token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()
        return withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            requestTeachingBuildings(token, useWebSession) { context.ensureActive() }
        }
    }

    private fun requestTeachingBuildings(
        token: EASToken,
        useWebSession: Boolean,
        checkCancelled: () -> Unit = {},
    ): DataState<List<BuildingItem>> {
        if (useWebSession) return requestShenzhenWebTeachingBuildings(token, checkCancelled)
        return try {
            checkCancelled()
            val resp = authedFormPost(token, "/app/commapp/queryjxllist")
            checkCancelled()
            val jo = JsonUtils.getJsonObject(resp.body())
            val content = jo?.optJSONArray("content")
            val buildings = mutableListOf<BuildingItem>()
            for (i in 0 until (content?.length() ?: 0)) {
                val item = content?.optJSONObject(i) ?: continue
                val building = BuildingItem()
                building.name = item.optString("MC")
                building.id = item.optString("DM")
                buildings.add(building)
            }
            DataState(buildings)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DataState(DataState.STATE.FETCH_FAILED)
        }
    }

    private fun requestShenzhenWebTeachingBuildings(
        token: EASToken,
        checkCancelled: () -> Unit,
    ): DataState<List<BuildingItem>> = try {
        initializeShenzhenWebClassroomContext(token, checkCancelled)
        checkCancelled()
        val response = jwFormPost(token, "/pksd/queryjxlList", refererPath = "/cdkb/querycdzy")
        checkCancelled()
        if (isJwAuthenticationExpired(response)) {
            DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效")
        } else {
            val buildings = ShenzhenWebAcademicParser.parseBuildings(response.body())
            if (response.statusCode() != 200 || buildings == null) {
                DataState(DataState.STATE.FETCH_FAILED, "教学楼列表解析失败")
            } else {
                DataState(buildings, DataState.STATE.SUCCESS)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        DataState(DataState.STATE.FETCH_FAILED, error.message)
    }

    private fun initializeShenzhenWebClassroomContext(
        token: EASToken,
        checkCancelled: () -> Unit = {},
    ) {
        listOf("/pksd/queryxiaoquList", "/pksd/querycdlbList", "/kbfbsz/querydqxnxq").forEach { path ->
            checkCancelled()
            val response = jwFormPost(token, path, refererPath = "/cdkb/querycdzy")
            checkCancelled()
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
        val useWebSession = token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()
        val result = MutableLiveData<DataState<List<ClassroomItem>>>()
        Thread {
            result.postValue(requestEmptyClassroom(token, term, building, weeks, useWebSession))
        }.start()
        return result
    }

    override suspend fun queryEmptyClassroomOnce(
        token: EASToken,
        term: TermItem,
        building: BuildingItem,
        weeks: List<String>,
    ): DataState<List<ClassroomItem>> {
        val useWebSession = token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()
        val snapshot = EmptyClassroomQuerySnapshot(term, building, weeks)
        return withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val result = requestEmptyClassroom(
                token, snapshot.term, snapshot.building, snapshot.weeks, useWebSession,
            ) { context.ensureActive() }
            context.ensureActive()
            result
        }
    }

    private fun requestEmptyClassroom(
        token: EASToken,
        term: TermItem,
        building: BuildingItem,
        weeks: List<String>,
        useWebSession: Boolean,
        checkCancelled: (() -> Unit)? = null,
    ): DataState<List<ClassroomItem>> {
        if (useWebSession) return requestShenzhenWebEmptyClassroom(token, term, building, weeks, checkCancelled)
        val resMap = linkedMapOf<String, ClassroomItem>()
        return try {
            checkCancelled?.invoke()
            val week = (weeks.firstOrNull()?.trim() ?: "").ifBlank { "1" }
            val dates = resolveWeekDates(token, term, week, checkCancelled)
            checkCancelled?.invoke()
            if (dates.isEmpty()) {
                throw IllegalStateException("未获取到 ${term.name} 第${week}周的日期")
            }
            val datePairs = dates.mapIndexed { index, date -> index + 1 to date }

            for ((dow, date) in datePairs) {
                checkCancelled?.invoke()
                val resp = authedFormPost(
                    token, "/app/kbrcbyapp/querycdzyxx",
                    mapOf("nyr" to date, "jxl" to building.id)
                )
                checkCancelled?.invoke()
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
                checkCancelled?.invoke()
            }
            checkCancelled?.invoke()
            DataState(resMap.values.toList())
        } catch (error: Exception) {
            if (checkCancelled != null) {
                checkCancelled()
                if (error is CancellationException) throw error
            }
            LogUtils.e("queryEmptyClassroom: failed, error=${error.message}", error)
            DataState(DataState.STATE.FETCH_FAILED)
        }
    }

    private fun requestShenzhenWebEmptyClassroom(
        token: EASToken,
        term: TermItem,
        building: BuildingItem,
        weeks: List<String>,
        checkCancelled: (() -> Unit)?,
    ): DataState<List<ClassroomItem>> = try {
        checkCancelled?.invoke()
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
        checkCancelled?.invoke()
        val leftResponse = jwFormPost(
            token,
            "/cdkb/querycdzyleftzhou?sf_request_type=ajax",
            form + mapOf("pageNum" to "1", "pageSize" to "500"),
            "/cdkb/querycdzy"
        )
        checkCancelled?.invoke()
        if (isJwAuthenticationExpired(leftResponse)) {
            DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效")
        } else {
            checkCancelled?.invoke()
            val rightResponse = jwFormPost(
                token,
                "/cdkb/querycdzyrightzhou?sf_request_type=ajax",
                form,
                "/cdkb/querycdzy"
            )
            checkCancelled?.invoke()
            if (isJwAuthenticationExpired(rightResponse)) {
                DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效")
            } else {
                val classrooms = ShenzhenWebAcademicParser.parseClassrooms(
                    leftResponse.body(),
                    rightResponse.body()
                )
                checkCancelled?.invoke()
                if (leftResponse.statusCode() != 200 || rightResponse.statusCode() != 200 || classrooms == null) {
                    DataState(DataState.STATE.FETCH_FAILED, "空教室数据解析失败")
                } else {
                    DataState(classrooms, DataState.STATE.SUCCESS)
                }
            }
        }
    } catch (error: Exception) {
        if (checkCancelled != null) {
            checkCancelled()
            if (error is CancellationException) throw error
        }
        DataState(DataState.STATE.FETCH_FAILED, error.message)
    }

    private fun resolveWeekDates(
        token: EASToken,
        term: TermItem,
        week: String,
        checkCancelled: (() -> Unit)?,
    ): List<String> {
        val matrixDates = try {
            checkCancelled?.invoke()
            val kbBody = """{"xn":"${term.yearCode}","xq":"${term.termCode}","zc":"$week","type":"json"}"""
            val kbResp = jsonPost(token, "/app/Kbcx/query", kbBody)
            checkCancelled?.invoke()
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
            checkCancelled?.invoke()
            dates
        } catch (error: Exception) {
            // Legacy callbacks may throw CE here and still fall back; only the suspend entry propagates it.
            if (checkCancelled != null) {
                checkCancelled()
                if (error is CancellationException) throw error
            }
            emptyList()
        }
        checkCancelled?.invoke()
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
            ?: fetchOverviewTermDates(token, term, checkCancelled).also { dates ->
                checkCancelled?.invoke()
                if (dates.isNotEmpty()) overviewTermDatesCache[term.id] = dates
            }
        checkCancelled?.invoke()
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
    private fun fetchOverviewTermDates(
        token: EASToken,
        term: TermItem,
        checkCancelled: (() -> Unit)?,
    ): List<String> {
        val dates = linkedSetOf<String>()
        var stagnantCount = 0
        val anchor = ShenzhenAcademicWeekResolver.approximateAnchor(term)
        for (weekOffset in 0 until 26) {
            checkCancelled?.invoke()
            val queryDate = anchor.plusWeeks(weekOffset.toLong()).toString()
            val response = authedFormPost(
                token,
                "/app/kbrcbyapp/querykbrczong",
                mapOf("nyr" to queryDate)
            )
            checkCancelled?.invoke()
            val content = JsonUtils.getJsonObject(response.body())?.optJSONArray("content")
            val sizeBefore = dates.size
            for (index in 0 until (content?.length() ?: 0)) {
                val row = content?.optJSONObject(index) ?: continue
                if (row.optString("XN").trim() != term.yearCode) continue
                if (row.optString("XQ").trim() != term.termCode) continue
                val date = row.optString("RQ").trim()
                if (date.isNotEmpty()) dates.add(date)
            }
            checkCancelled?.invoke()
            stagnantCount = if (dates.size == sizeBefore) stagnantCount + 1 else 0
            if (dates.isNotEmpty() && stagnantCount >= 4) break
        }
        checkCancelled?.invoke()
        return dates.sorted()
    }


    // ================================================================ 考试信息（深圳校区新接口）
    override fun getExamItems(token: EASToken, term: TermItem?): LiveData<DataState<List<ExamItem>>> {
        val useWebSession = token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()
        val result = MutableLiveData<DataState<List<ExamItem>>>()
        Thread {
            val state = try {
                requestExamItems(token, term, useWebSession)
            } catch (cancelled: CancellationException) {
                DataState(DataState.STATE.FETCH_FAILED, if (useWebSession) cancelled.message else cancelled.message ?: "查询考试失败")
            }
            result.postValue(state)
        }.start()
        return result
    }

    override suspend fun getExamItemsOnce(token: EASToken, term: TermItem?): DataState<List<ExamItem>> {
        val useWebSession = token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()
        return withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            requestExamItems(token, term, useWebSession) { context.ensureActive() }
        }
    }

    private fun requestExamItems(
        token: EASToken,
        term: TermItem?,
        useWebSession: Boolean,
        checkCancelled: () -> Unit = {},
    ): DataState<List<ExamItem>> = if (useWebSession) {
        requestShenzhenWebExamItems(token, term, checkCancelled)
    } else {
        requestShenzhenApiExamItems(token, term, checkCancelled)
    }

    private fun requestShenzhenApiExamItems(
        token: EASToken,
        term: TermItem?,
        checkCancelled: () -> Unit,
    ): DataState<List<ExamItem>> {
        try {
            checkCancelled()
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
            val examHost = examApiHostOverride?.trimEnd('/') ?: "https://mjw.hitsz.edu.cn"
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
            checkCancelled()

            LogUtils.d("getExamItems: HTTP ${response.statusCode()}, body length=${response.body().length}")

            if (isAuthExpiredResponse(response)) {
                return DataState(DataState.STATE.NOT_LOGGED_IN, "深圳教务会话已失效")
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
                    return DataState(examList, DataState.STATE.SUCCESS)
                } else {
                    val errorMsg = jsonResponse.optString("msg", "查询失败")
                    LogUtils.e("getExamItems: API error, code=${jsonResponse.optInt("code")}, msg=$errorMsg")
                    return DataState(DataState.STATE.FETCH_FAILED, errorMsg)
                }
            } else {
                LogUtils.e("getExamItems: HTTP ${response.statusCode()}")
                return DataState(DataState.STATE.FETCH_FAILED, "网络请求失败 HTTP ${response.statusCode()}")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            LogUtils.e("getExamItems: ${e.javaClass.simpleName} ${e.message}", e)
            return DataState(DataState.STATE.FETCH_FAILED, e.message ?: "查询考试失败")
        }
    }

    private fun requestShenzhenWebExamItems(
        token: EASToken,
        term: TermItem?,
        checkCancelled: () -> Unit,
    ): DataState<List<ExamItem>> {
        checkCancelled()
        if (term == null) {
            return DataState(DataState.STATE.FETCH_FAILED, "请选择学期后再查询考试安排")
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
            checkCancelled()
            if (isJwAuthenticationExpired(response)) {
                return DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效")
            }
            val exams = ShenzhenWebAcademicParser.parseExams(response.body(), term)
            if (response.statusCode() != 200 || exams == null) {
                return DataState(DataState.STATE.FETCH_FAILED, "考试数据解析失败")
            } else {
                return DataState(exams, DataState.STATE.SUCCESS)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return DataState(DataState.STATE.FETCH_FAILED, error.message)
        }
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
