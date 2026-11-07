package cn.limpu.hita.data.source.web.eas.graduate

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.source.web.eas.WeihaiGraduateSessionPolicy
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Session material is invalid; callers must ask the user to re-authenticate. */
internal class GraduateAuthenticationException : Exception("Graduate session expired")

/** Upstream or transport failure that must not clear a verified session. */
internal class GraduateUpstreamException : Exception("Graduate academic service unavailable")

/** Structurally invalid or inconsistent business payload. */
internal class GraduateInvalidResponseException(message: String = "Invalid graduate academic response") :
    Exception(message)

internal interface GraduateHttpTransport {
    fun form(path: String, fields: Map<String, String> = emptyMap(), referer: String = DEFAULT_REFERER): String
    fun json(path: String, body: String, referer: String = DEFAULT_REFERER): String
    fun get(path: String, referer: String = DEFAULT_REFERER): String

    companion object {
        const val DEFAULT_REFERER = "/authentication/main"
        const val ROLE_CODE = "02"
        const val ACCEPT = "application/json, text/javascript, */*; q=0.01"
    }
}

/**
 * Jsoup-backed transport for the graduate WebVPN session.
 *
 * Every path is resolved against the fixed graduate proxy root; response cookies are merged back
 * into the token so the existing session generation guard can reject late results after logout.
 */
internal class GraduateJsoupTransport(
    private val token: EASToken,
    private val onCookiesUpdated: ((EASToken) -> Unit)? = null,
    private val timeoutMillis: Int = 15_000,
) : GraduateHttpTransport {

    override fun form(path: String, fields: Map<String, String>, referer: String): String =
        send(path, Connection.Method.POST, referer) { request -> request.data(fields) }

    override fun json(path: String, body: String, referer: String): String =
        send(path, Connection.Method.POST, referer) { request ->
            request.header("Content-Type", "application/json;charset=UTF-8").requestBody(body)
        }

    override fun get(path: String, referer: String): String =
        send(path, Connection.Method.GET, referer) { request -> request }

    /** 身份探针需要 JSON 接受头与 POST，与 iOS 连接器一致。 */
    fun identity(): String =
        send("/user/me?vpn-12-o2-yjsgl.hitwh.edu.cn", Connection.Method.POST, "/authentication/main") { request ->
            request.header("Accept", "application/json")
        }

    private fun send(
        path: String,
        method: Connection.Method,
        referer: String,
        configure: (Connection) -> Connection,
    ): String {
        require(path.startsWith("/") && !path.startsWith("//")) {
            "Graduate paths are relative to the fixed proxy root"
        }
        val request = Jsoup.connect(WeihaiGraduateSessionPolicy.BASE_URL + path)
            .cookies(token.cookies)
            .header("Accept", GraduateHttpTransport.ACCEPT)
            .header("RoleCode", GraduateHttpTransport.ROLE_CODE)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", WeihaiGraduateSessionPolicy.BASE_URL + referer)
            .method(method)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .followRedirects(false)
            .maxBodySize(MAX_BODY_BYTES)
            .timeout(timeoutMillis)
        val response = try {
            configure(request).execute()
        } catch (_: Exception) {
            throw GraduateUpstreamException()
        }
        if (response.cookies().isNotEmpty()) {
            token.cookies.putAll(response.cookies())
            onCookiesUpdated?.invoke(token)
        }
        val body = response.body()
        return when {
            isAuthenticationFailure(response.statusCode(), response.url().toString(), body) ->
                throw GraduateAuthenticationException()
            response.statusCode() !in 200..299 -> throw GraduateUpstreamException()
            else -> body
        }
    }

    companion object {
        const val MAX_BODY_BYTES = 16 * 1024 * 1024

        /** Dormant re-login templates are ignored; only rendered login content means expiry. */
        fun isAuthenticationFailure(status: Int, url: String, body: String): Boolean {
            if (status in 300..399 || status == 401 || status == 403) return true
            if (status >= 500) return false
            val visible = if (body.trimStart().startsWith("<")) {
                runCatching {
                    val document: Document = Jsoup.parse(body)
                    document.select("script, style, template").remove()
                    document.body()?.text().orEmpty()
                }.getOrDefault(body)
            } else {
                body
            }
            val lower = visible.lowercase()
            if (lower.contains("统一身份认证") || lower.contains("请输入密码")) return true
            val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
            return path.contains("/login") || path.contains("/cas") ||
                path.contains("authserver") || path.contains("authentication/require")
        }
    }
}

/** Business envelope used by graduate grade endpoints: `code` 0/200 plus `content`. */
internal object GraduateEnvelope {
    fun content(body: String): com.google.gson.JsonObject {
        val root = runCatching { com.google.gson.JsonParser().parse(body).asJsonObject }
            .getOrElse { throw GraduateInvalidResponseException() }
        val code = root.get("code")
        if (code != null && code.isJsonPrimitive && code.asJsonPrimitive.isNumber) {
            val value = code.asInt
            if (value != 0 && value != 200) throw GraduateUpstreamException()
        }
        val content = root.get("content")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw GraduateInvalidResponseException()
        content.get("success")?.takeIf { it.isJsonPrimitive }?.let {
            if (it.asJsonPrimitive.isBoolean && !it.asBoolean) throw GraduateUpstreamException()
        }
        return content
    }
}
