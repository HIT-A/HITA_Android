package cn.limpu.hita.data.source.web.eas

import java.net.URI
import com.google.gson.JsonParser

/** iOS connector-backed protocol; pure entry/profile rules are usable by offline fixtures. */
internal object WeihaiGraduateSessionPolicy {
    const val BASE_URL = "https://webvpn.hitwh.edu.cn/https/77726476706e69737468656265737421e9fd529b2b7e60596a1f81e29d51367b6311"
    const val TICKET_COOKIE = "wengine_vpn_ticketwebvpn_hitwh_edu_cn"
    const val UNSUPPORTED = "暂不支持威海研究生教务查询"
    private val base = URI(BASE_URL)
    private val landingPaths = setOf(base.path + "/authentication/main", base.path + "/student_index")

    fun isAuthenticatedLanding(url: String, cookies: Map<String, String>): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host == base.host && uri.port == -1 &&
            uri.userInfo == null && uri.path in landingPaths && !cookies[TICKET_COOKIE].isNullOrBlank()
    }

    data class Profile(val account: String, val name: String?)

    fun parseProfile(body: String): Profile? {
        val json = runCatching { JsonParser().parse(body).asJsonObject }.getOrNull() ?: return null
        fun field(key: String): String? {
            val value = json.get(key) ?: return null
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
            return value.asString.trim().takeIf { it.isNotEmpty() }
        }
        val account = field("yhdm") ?: field("username") ?: return null
        val roles = json.get("rolecode")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (field("pylx") != "2" || field("sfxs") != "1" ||
            roles.none { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == "02" }) return null
        return Profile(account, field("xm"))
    }

    fun isAuthenticationFailure(status: Int, url: String, body: String): Boolean {
        if (status in 300..399 || status == 401 || status == 403) return true
        if (status >= 500) return false
        val path = runCatching { URI(url).path }.getOrNull().orEmpty().lowercase()
        return path.contains("/login") || path.contains("/cas") ||
            path.contains("authserver") || path.contains("authentication/require") ||
            body.contains("<form", true) &&
            (body.contains("password", true) || body.contains("统一身份认证"))
    }
}
