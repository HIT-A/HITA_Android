package cn.limpu.hita.data.source.web.notice

import cn.limpu.hita.data.model.notice.CampusNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeihaiNoticeParserTest {
    @Test
    fun parsesTodayHitwhListItems() {
        val html = """
            <html><body>
            <div class="list_list_wrap"><div id="wp_news_w10002">
            <ul>
              <li><span class="news-time2">2026-09-24</span><a href='/2026/0924/c1024a220789/page.htm' target='_blank' title='2026年泽世·德才兼备奖学金评选通知'>2026年泽世·德才兼备奖学金评选通知</a></li>
              <li><span class="news-time2">2026-09-23</span><a href='/2026/0923/c1024a220763/page.htm' target='_blank' title='关于2026秋季学期文化素质讲座学分申请的通知'>关于2026秋季学期文化素质讲座学分申请的通...</a></li>
              <li><a href="/xxxwwbjgf/list.htm">校区新闻网编辑规范</a></li>
            </ul>
            </div></div>
            </body></html>
        """.trimIndent()
        val notices = WeihaiNoticeParser.parse(html, WeihaiNoticeParser.LIST_URL)
        assertEquals(2, notices.size)
        assertEquals("hitwh:220789", notices[0].id)
        // 截断的文本标题必须由 title 属性补全
        assertEquals("关于2026秋季学期文化素质讲座学分申请的通知", notices[1].title)
        assertEquals("http://today.hitwh.edu.cn/2026/0924/c1024a220789/page.htm", notices[0].url)
        assertEquals("WEIHAI", notices[0].campus)
        assertTrue(notices[0].pubDateMillis > notices[1].pubDateMillis)
    }

    @Test
    fun detailUrlUsesTheCampusBasePassedToParse() {
        val html = """
            <div class="list_list_wrap"><ul>
              <li><span class="news-time2">2026-11-10</span><a href="/2026/1110/c1024a222516/page.htm" title="威海校园通知标题">威海校园通知标题</a></li>
            </ul></div>
        """.trimIndent()
        val notices = WeihaiNoticeParser.parse(html, "http://today.hitwh.edu.cn/1024/list.htm")
        assertEquals(1, notices.size)
        assertEquals(
            "http://today.hitwh.edu.cn/2026/1110/c1024a222516/page.htm",
            notices[0].url,
        )
        assertFalse(notices[0].url.contains("info.hitsz.edu.cn"))
    }

    @Test
    fun capsAtThirtyAndKeepsAbsoluteDates() {
        val items = (1..40).joinToString("") { index ->
            """<li><span class="news-time2">2026-09-01</span><a href='/2026/0901/c1024a${200000 + index}/page.htm' title='通知标题$index'>通知标题$index</a></li>"""
        }
        val html = """<html><body><div class="list_list_wrap"><ul>$items</ul></div></body></html>"""
        val notices = WeihaiNoticeParser.parse(html)
        assertEquals(30, notices.size)
        assertTrue(notices.all { it.pubDateMillis > 0L })
    }

    @Test
    fun listPageUrlAppendsPageNumber() {
        assertEquals("http://today.hitwh.edu.cn/1024/list.htm", WeihaiNoticeParser.listPageUrl(1))
        assertEquals("http://today.hitwh.edu.cn/1024/list2.htm", WeihaiNoticeParser.listPageUrl(2))
    }

    @Test
    fun dropsHrefThatIsNotAPathOnTheNoticeHost() {
        val html = """
            <div class="list_list_wrap"><ul>
              <li><a href="@evil.example/2026/0101/c1024a9/page.htm" title="坏链接标题足够长">坏链接标题足够长</a><span class="news-time2">2026-01-01</span></li>
              <li><a href="/2026/0102/c1024a10/page.htm" title="正常通知标题足够">正常通知标题足够</a><span class="news-time2">2026-01-02</span></li>
            </ul></div>
        """.trimIndent()
        val notices = WeihaiNoticeParser.parse(html)
        assertEquals(1, notices.size)
        assertEquals("http://today.hitwh.edu.cn/2026/0102/c1024a10/page.htm", notices[0].url)
    }

    @Test
    fun isPersistedNoticeChecksCampusAndUrl() {
        val ok = CampusNotice("WEIHAI", "hitwh:220789", "t", "http://today.hitwh.edu.cn/2026/0924/c1024a220789/page.htm", 0L)
        val wrongCampus = ok.copy(campus = "BENBU")
        val wrongUrl = ok.copy(url = "http://today.hitwh.edu.cn/1024/list.htm")
        assertTrue(WeihaiNoticeParser.isPersistedNotice(ok))
        assertFalse(WeihaiNoticeParser.isPersistedNotice(wrongCampus))
        assertFalse(WeihaiNoticeParser.isPersistedNotice(wrongUrl))
    }
}
