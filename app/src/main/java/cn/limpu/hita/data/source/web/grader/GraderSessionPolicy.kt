package cn.limpu.hita.data.source.web.grader

import java.net.URI

internal object GraderSessionPolicy {
    const val ORIGIN = "https://grader.hitsz.edu.cn"
    const val LOGIN_URL = "$ORIGIN/api/login/sso"

    /** Ignore every other browser cookie; never accept a header containing control bytes. */
    fun sessionCookie(header: String): String? {
        if (header.any { it.code < 0x20 || it.code > 0x7e }) return null
        val matches = header.split(';').map { it.trim() }.filter { it.substringBefore('=') == "sessionid" }
        val value = matches.singleOrNull()?.substringAfter('=', "") ?: return null
        if (value.isEmpty() || value.any { it == '"' || it == ',' || it == ';' || it == '\\' || it == ' ' }) return null
        return "sessionid=$value"
    }

    fun isCandidatePage(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "grader.hitsz.edu.cn" &&
            (uri.port == -1 || uri.port == 443) && uri.rawUserInfo == null &&
            (uri.path == "/home" || uri.path.startsWith("/home/") ||
                uri.path == "/courses" || uri.path.startsWith("/courses/"))
    } catch (_: Exception) {
        false
    }
}
