package cn.limpu.hita.data.source.web.notice

import cn.limpu.hita.data.model.notice.CampusNotice
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object CampusNoticeParser {
    /**
     * 深圳校园通知门户。
     *
     * 服务端只在 `http://info.hitsz.edu.cn/` 上工作：https 由另一个前置
     * （`VAppServer/6.0.0`）应答，登录回跳的 service 参数也是 http，改为 https
     * 会让 CAS 回跳与 cookie 落在不同 scheme 上。v3.2.1 起统一用 http。
     */
    const val PORTAL_URL = "http://info.hitsz.edu.cn/"
    const val LIST_PATH = "list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1053"
    const val LIST_URL = PORTAL_URL + LIST_PATH
    const val SHENZHEN_CAMPUS = "SHENZHEN"
    const val MAX_NOTICES = 30
    const val PAGES_FOR_CAP = 3
    const val PARSER_VERSION = 2

    private val dateRegex = Regex("""(20\d{2})[-/.年](\d{1,2})[-/.月](\d{1,2})""")
    private val newsIdRegex = Regex("""wbnewsid=(\d+)""", RegexOption.IGNORE_CASE)
    private val treeIdRegex = Regex("""wbtreeid=(\d+)""", RegexOption.IGNORE_CASE)
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }
    }

    fun listPageUrl(page: Int): String {
        val safe = page.coerceAtLeast(1)
        return if (safe == 1) LIST_URL else "$LIST_URL&PAGENUM=$safe"
    }

    fun parse(html: String, baseUrl: String = LIST_URL): List<CampusNotice> {
        val doc = Jsoup.parse(html, baseUrl)
        val root = doc.selectFirst("div.Newslist, .Newslist") ?: return emptyList()
        val byId = LinkedHashMap<String, CampusNotice>()
        for (anchor in root.select("a[href]")) {
            val notice = parseAnchor(anchor) ?: continue
            val existing = byId[notice.id]
            if (existing == null || (notice.pubDateMillis > 0L && existing.pubDateMillis <= 0L)) {
                byId[notice.id] = notice
            }
        }
        return byId.values
            .sortedWith(compareByDescending<CampusNotice> { it.pubDateMillis }.thenBy { it.title })
            .take(MAX_NOTICES)
    }

    fun isCasLoginUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("ids.hit.edu.cn") ||
            lower.contains("/authserver/") ||
            lower.contains("caslogin")
    }

    fun isNoticeListUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("info.hitsz.edu.cn") &&
            lower.contains("wbtreeid=1053") &&
            !isCasLoginUrl(url)
    }

    internal fun isNoticeUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        if (lower.startsWith("javascript:")) return false
        if (isCasLoginUrl(url)) return false
        return newsIdRegex.containsMatchIn(lower) &&
            (lower.contains("newscontenturl") || lower.contains("content.jsp"))
    }

    fun parseDateMillis(text: String): Long {
        val match = dateRegex.find(text) ?: return 0L
        val year = match.groupValues[1]
        val month = match.groupValues[2].padStart(2, '0')
        val day = match.groupValues[3].padStart(2, '0')
        return runCatching { dateFormat.get()!!.parse("$year-$month-$day")?.time }.getOrNull() ?: 0L
    }

    fun newsIdFromUrl(url: String): String? {
        val raw = rawNewsId(url) ?: return null
        return "wbnews:$raw"
    }

    fun canonicalContentUrl(url: String): String {
        val newsId = rawNewsId(url) ?: return url.substringBefore('#').trim()
        val treeId = treeIdRegex.find(url)?.groupValues?.get(1) ?: "1023"
        return "${PORTAL_URL}content.jsp?urltype=news.NewsContentUrl&wbtreeid=$treeId&wbnewsid=$newsId"
    }

    fun isPersistedNotice(notice: CampusNotice): Boolean =
        notice.id.startsWith("wbnews:") && isNoticeUrl(notice.url)

    private fun parseAnchor(anchor: Element): CampusNotice? {
        val rawUrl = anchor.absUrl("href").ifBlank { anchor.attr("href") }.substringBefore('#').trim()
        if (!isNoticeUrl(rawUrl)) return null
        val url = canonicalContentUrl(rawUrl)
        val title = anchor.attr("title").ifBlank { anchor.text() }
            .replace('\u00a0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (title.length < 4) return null
        val container = anchor.parent()
        val millis = parseDateMillis(container?.selectFirst("span")?.text().orEmpty())
            .takeIf { it > 0L }
            ?: parseDateMillis(container?.text().orEmpty())
        val id = newsIdFromUrl(url) ?: return null
        return CampusNotice(
            campus = SHENZHEN_CAMPUS,
            id = id,
            title = title,
            url = url,
            pubDateMillis = millis,
        )
    }

    private fun rawNewsId(url: String): String? =
        newsIdRegex.find(url)?.groupValues?.get(1)
}
