package cn.limpu.hita.data.source.web.eas

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

/** Pure classifier for login pages and expired Shenzhen EAS sessions. */
internal object ShenzhenWebAuthenticationClassifier {
    fun isExpired(statusCode: Int, responseUrl: String?, body: String): Boolean {
        if (statusCode in 300..399 || statusCode == 401 || statusCode == 403) return true

        val uri = responseUrl?.let { runCatching { URI(it) }.getOrNull() }
        val host = uri?.host.orEmpty().lowercase(Locale.ROOT)
        val path = uri?.path.orEmpty().lowercase(Locale.ROOT)
        if (
            host == "ids.hit.edu.cn" ||
            host == "ids-hit-edu-cn-s.hitsz.edu.cn" ||
            // 校外代理（jw-hitsz-edu-cn）已经挂到 aTrust 网关后面：网关 302 到 trust 域名后
            // 返回的是网关自己的 HTML，既不是学期列表也不是登录页，必须按会话失效处理。
            host == "trust.hitsz.edu.cn" ||
            path.contains("/authserver/login") ||
            path.contains("/authentication/require") ||
            path.contains("/session/invalid") ||
            path.contains("/controller/v1/public/verify")
        ) {
            return true
        }

        val json = runCatching { JsonParser().parse(body) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
        if (json != null) {
            val code = json.intValue("code")
            if (code == 401 || code == 403 || code == 2005) return true
            val message = listOf("msg", "message", "content", "error_description")
                .mapNotNull { key -> json.stringValue(key) }
                .joinToString(" ")
            return containsExpirationMarker(message)
        }

        if (containsExpirationMarker(body)) return true

        val document = runCatching { Jsoup.parse(body) }.getOrNull() ?: return false
        val title = document.title().lowercase(Locale.ROOT)
        val text = document.body()?.text().orEmpty()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
        val hasLoginForm = document.select(
            "input[name=mm], input[name=password], form[action*=login], form[action*=authentication]"
        ).isNotEmpty()
        val isJavaScriptChallenge = text.contains("your browser does not support javascript") ||
            text.contains("javascript is disabled in your browser") ||
            text.contains("please enable javascript") ||
            text.contains("enable javascript to continue") ||
            title.contains("just a moment") ||
            title.contains("attention required")

        return isJavaScriptChallenge || hasLoginForm ||
            title.contains("统一身份认证") ||
            title.contains("atrust") ||
            text.contains("atrust 2.0") ||
            title.contains("登录") ||
            text.contains("请登录") ||
            text.contains("未登录")
    }

    private fun containsExpirationMarker(value: String): Boolean {
        val normalized = value.lowercase(Locale.ROOT)
        return normalized.contains("not logged") ||
            normalized.contains("login required") ||
            normalized.contains("unauthorized") ||
            normalized.contains("token expired") ||
            normalized.contains("invalid token") ||
            normalized.contains("token invalid") ||
            normalized.contains("token无效") ||
            normalized.contains("token失效") ||
            normalized.contains("token过期") ||
            value.contains("未登录") ||
            value.contains("重新登录") ||
            value.contains("页面过期") ||
            value.contains("会话已失效") ||
            value.contains("身份认证") ||
            value.contains("登录页") ||
            value.contains("璁块棶鐨勬湇鍔") ||
            value.contains("鐧诲綍椤")
    }

    private fun JsonObject.intValue(key: String): Int? {
        val value = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return runCatching { value.asInt }.getOrNull()
    }

    private fun JsonObject.stringValue(key: String): String? {
        val value = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        return runCatching { value.asString.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
