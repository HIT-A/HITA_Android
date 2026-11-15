package cn.limpu.hita.ui.subject

import android.net.Uri

/**
 * Course-resource links may leave the app only for an allowlisted https host.
 * Campus intranet pages and the useful-links page do not use this policy.
 */
object ExternalLinkPolicy {
    private val allowedHosts = setOf(
        "github.com",
        "raw.githubusercontent.com",
        "fireworks.jwyihao.top",
        "hoa.moe",
        "api.hita.limpu.cn",
        "ghproxy.net",
    )

    /**
     * @return the canonical URL when it may be opened, otherwise null
     */
    fun allowed(raw: String): String? {
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: return null
        if (uri.userInfo != null) return null
        if (!hostAllowed(host)) return null
        if (host == "ghproxy.net" && !proxiedTargetAllowed(uri)) return null
        return uri.toString()
    }

    private fun hostAllowed(host: String): Boolean =
        allowedHosts.any { allowed -> host == allowed || host.endsWith(".$allowed") }

    /** A download proxy is only as safe as the address it forwards to. */
    private fun proxiedTargetAllowed(uri: Uri): Boolean {
        val forwarded = uri.path.orEmpty().removePrefix("/").substringBefore('?').substringBefore('#')
        if (!forwarded.startsWith("https://") && !forwarded.startsWith("http://")) return false
        val target = Uri.parse(forwarded)
        if (!target.scheme.equals("https", ignoreCase = true)) return false
        val host = target.host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: return false
        return host != "ghproxy.net" && hostAllowed(host)
    }
}
