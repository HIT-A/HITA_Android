package cn.limpu.hita.data.source.web.geniusta

import com.google.gson.JsonObject
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class GeniusTaAuthenticationException : Exception("GeniusTA authentication required")
internal class GeniusTaNetworkException : Exception("GeniusTA request failed")

internal class GeniusTaSession(val id: String, val cookieValue: String, val expiresAt: Long) {
    init {
        require(isGeniusTaId(id))
        require(cookieValue.length in 1..4096 && cookieValue.all {
            // RFC 6265 cookie-octet: no controls, whitespace, semicolons, or quotes.
            it == '!' || it in '#'..'+' || it in '-'..':' || it in '<'..'[' || it in ']'..'~'
        })
    }

    fun expired(now: Long = System.currentTimeMillis()): Boolean = expiresAt <= now
    fun cookieHeader(): String = "${cookieName(id)}=$cookieValue"

    companion object {
        fun cookieName(id: String): String = "gta_prod_session_$id"
    }
}

internal class GeniusTaConnection(val accountId: String, val session: GeniusTaSession)

/** Dedicated HTTPS client; credentials are never sent through the app's other clients or WebView. */
internal class GeniusTaWebSource(private val calls: Call.Factory = client) {
    fun login(username: String, password: String): GeniusTaConnection {
        if (username.isBlank() || password.isEmpty()) throw GeniusTaAuthenticationException()
        val credentials = JsonObject().apply {
            addProperty("username", username)
            addProperty("password", password)
        }
        // The login endpoint enforces the same-origin headers used by its sign-in page.
        val request = request("auth", "login")
            .header("Origin", originAddress)
            .header("Referer", "$originAddress/login")
            .post(credentials.toString().toRequestBody(json))
            .build()
        return execute(request) { response ->
            val login = GeniusTaParser.login(body(response))
            val cookie = Cookie.parseAll(origin, response.headers).singleOrNull {
                it.name == GeniusTaSession.cookieName(login.sessionId)
            }?.takeIf { it.secure && it.httpOnly && it.path == "/" && it.domain == origin.host }
                ?: throw GeniusTaInvalidResponseException()
            val session = try {
                GeniusTaSession(login.sessionId, cookie.value, minOf(login.expiresAt, cookie.expiresAt))
            } catch (_: IllegalArgumentException) {
                throw GeniusTaInvalidResponseException()
            }
            if (session.expired()) throw GeniusTaAuthenticationException()
            GeniusTaConnection(login.accountId, session)
        }
    }

    fun verify(connection: GeniusTaConnection) {
        if (connection.session.expired() ||
            GeniusTaParser.accountId(get(connection.session, "auth", "me")) != connection.accountId
        ) throw GeniusTaAuthenticationException()
    }

    fun assignments(session: GeniusTaSession, checkActive: () -> Unit): List<GeniusTaAssignment> {
        checkActive()
        val courses = GeniusTaParser.courses(get(session, "courses"))
        val assignments = mutableListOf<GeniusTaAssignment>()
        val ids = mutableSetOf<String>()
        for (course in courses) {
            checkActive()
            for (assignment in GeniusTaParser.assignments(get(session, "courses", course.id, "assignments"), course)) {
                if (!ids.add(assignment.id)) throw GeniusTaInvalidResponseException()
                assignments += assignment
            }
        }
        return assignments
    }

    private fun get(session: GeniusTaSession, vararg path: String): String {
        val request = request(*path)
            .header("X-GeniusTA-Session", session.id)
            .header("Cookie", session.cookieHeader())
            .get().build()
        return execute(request, ::body)
    }

    private fun request(vararg path: String): Request.Builder {
        val url = origin.newBuilder().addPathSegments("api/v1").apply {
            path.forEach { addPathSegment(it) }
        }.build()
        return Request.Builder().url(url).header("Accept", "application/json")
    }

    private inline fun <T> execute(request: Request, read: (Response) -> T): T = try {
        calls.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> read(response)
                response.code == 401 || response.code == 403 -> throw GeniusTaAuthenticationException()
                request.method == "POST" && (response.code == 400 || response.code == 422) -> throw GeniusTaAuthenticationException()
                response.code == 429 || response.code >= 500 -> throw GeniusTaNetworkException()
                else -> throw GeniusTaInvalidResponseException()
            }
        }
    } catch (_: IOException) {
        throw GeniusTaNetworkException()
    }

    private fun body(response: Response): String {
        val body = response.body ?: throw GeniusTaInvalidResponseException()
        if (body.contentLength() > maxResponseBytes) throw GeniusTaInvalidResponseException()
        val source = body.source()
        if (source.request(maxResponseBytes + 1)) throw GeniusTaInvalidResponseException()
        return source.readUtf8()
    }

    private companion object {
        const val originAddress = "https://geniusta.hitsz.edu.cn:10086"
        val origin = "$originAddress/".toHttpUrl()
        val json = "application/json; charset=utf-8".toMediaType()
        const val maxResponseBytes = 4L * 1024 * 1024
        val client = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
