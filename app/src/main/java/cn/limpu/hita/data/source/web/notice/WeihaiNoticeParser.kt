package cn.limpu.hita.data.source.web.notice

import cn.limpu.hita.data.model.notice.CampusNotice
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 威海「今日哈工大」通知公告（/1024/list.htm，网维 CMS）。
 * 每页 14 条，分页 /1024/list{N}.htm；日期为 span.news-time2 绝对日期。
 */
object WeihaiNoticeParser {
    const val PORTAL_URL = "http://today.hitwh.edu.cn/"
    const val LIST_URL = "${PORTAL_URL}1024/list.htm"
    const val MAX_NOTICES = 30
    const val PAGES_FOR_CAP = 3
    const val PARSER_VERSION = 1
    const val CAMPUS = "WEIHAI"

    private val pageRegex = Regex("""/(\d{4,})/c\d+a(\d+)/page\.htm""")
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }
    }

    fun listPageUrl(page: Int): String {
        val safe = page.coerceAtLeast(1)
        return if (safe == 1) LIST_URL else "${PORTAL_URL}1024/list$safe.htm"
    }

    fun parse(html: String, baseUrl: String = LIST_URL): List<CampusNotice> {
        val doc = Jsoup.parse(html, baseUrl)
        val root = doc.selectFirst("div.list_list_wrap") ?: return emptyList()
        val byId = LinkedHashMap<String, CampusNotice>()
        for (item in root.select("ul > li")) {
            val anchor = item.selectFirst("a[href]") ?: continue
            val raw = anchor.attr("href").substringBefore('#').trim()
            val match = pageRegex.find(raw) ?: continue
            val articleId = match.groupValues[2]
            val title = anchor.attr("title").ifBlank { anchor.text() }
                .replace('\u00a0', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
            if (title.length < 4) continue
            if (!raw.startsWith("/") || raw.startsWith("//") || raw.contains('@') || raw.contains('\\')) {
                continue
            }
            val id = "hitwh:$articleId"
            if (id in byId) continue
            val dateText = item.selectFirst("span.news-time2")?.text().orEmpty().trim()
            byId[id] = CampusNotice(
                campus = CAMPUS,
                id = id,
                title = title,
                url = PORTAL_URL + raw.removePrefix("/"),
                pubDateMillis = parseDateMillis(dateText),
            )
        }
        return byId.values
            .sortedWith(compareByDescending<CampusNotice> { it.pubDateMillis }.thenBy { it.title })
            .take(MAX_NOTICES)
    }

    fun isPersistedNotice(notice: CampusNotice): Boolean =
        notice.campus == CAMPUS && notice.id.startsWith("hitwh:") &&
            pageRegex.containsMatchIn(notice.url)

    private fun parseDateMillis(text: String): Long =
        runCatching { dateFormat.get()!!.parse(text)?.time }.getOrNull() ?: 0L
}