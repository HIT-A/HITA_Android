package cn.limpu.hita.ui.subject

import cn.limpu.hita.data.model.resource.ExternalResourceEntry
import cn.limpu.hita.data.model.resource.ResourceSource
import java.net.URLEncoder

sealed interface SubjectResourceDestination {
    data class Browser(val url: String) : SubjectResourceDestination
    data class Markdown(val url: String, val title: String) : SubjectResourceDestination
    data class Download(val url: String, val name: String) : SubjectResourceDestination
}

object SubjectResourceOpenPolicy {
    fun destination(entry: ExternalResourceEntry): SubjectResourceDestination? {
        val url = entry.downloadUrl
        if (url.startsWith("https://fireworks.jwyihao.top")) {
            return SubjectResourceDestination.Browser(url)
        }
        val rawUrl = if (entry.path.isNotBlank()) {
            val repo = when (entry.source) {
                ResourceSource.HITCS -> "HITLittleZheng/HITCS"
                ResourceSource.FIREWORKS -> "HIT-Fireworks/fireworks-notes-society"
            }
            val encodedPath = entry.path.split("/").joinToString("/") { segment ->
                URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
            "https://raw.githubusercontent.com/$repo/main/$encodedPath"
        } else if (url.isNotBlank() && !url.startsWith("https://fireworks.")) {
            url
        } else {
            return null
        }
        val downloadUrl = "https://ghproxy.net/$rawUrl"
        return if (entry.name.endsWith(".md", ignoreCase = true)) {
            SubjectResourceDestination.Markdown(downloadUrl, entry.name)
        } else {
            SubjectResourceDestination.Download(downloadUrl, entry.name)
        }
    }
}
