package cn.limpu.hita.data.source.web.notice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampusNoticeParserTest {
    @Test
    fun parsesHanwebNewslistIntoContentUrls() {
        val html = """
            <html><body>
            <div class="Newslist">
              <ul>
                <li><span>教务处&nbsp;&nbsp;&nbsp;2026-09-22</span>【工作通知】<a href="content.jsp?urltype=news.NewsContentUrl&wbtreeid=1023&wbnewsid=9581" title="哈尔滨工业大学（深圳）关于2026年度研究生国家奖学金评选通知">截断标题</a></li>
                <li><span>2026-09-21</span>【公告公示】<a href="content.jsp?urltype=news.NewsContentUrl&wbtreeid=1028&wbnewsid=9590" title="关于宿舍区停网的通知">关于宿舍区停网的通知</a></li>
              </ul>
            </div>
            </body></html>
        """.trimIndent()
        val notices = CampusNoticeParser.parse(html, CampusNoticeParser.LIST_URL)
        assertEquals(2, notices.size)
        assertEquals("哈尔滨工业大学（深圳）关于2026年度研究生国家奖学金评选通知", notices[0].title)
        assertEquals("wbnews:9581", notices[0].id)
        assertEquals(
            "http://info.hitsz.edu.cn/content.jsp?urltype=news.NewsContentUrl&wbtreeid=1023&wbnewsid=9581",
            notices[0].url,
        )
        assertTrue(notices[0].pubDateMillis > 0L)
        assertEquals("wbnews:9590", notices[1].id)
        assertTrue(notices[1].url.contains("wbtreeid=1028"))
        assertTrue(notices[1].url.contains("wbnewsid=9590"))
        assertFalse(notices[0].title.contains("【工作通知】"))
    }

    @Test
    fun ignoresPortalHomeNavLinks() {
        val html = """
            <html><body>
            <ul>
              <li><a href="index.jsp" title="官网首页">官网首页</a></li>
              <li><a href="list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1028" title="政务公开">政务公开</a></li>
              <li><a href="https://www.hitsz.edu.cn/index.html">校区主页</a></li>
            </ul>
            <div class="head"><a href="content.jsp?urltype=tree.TreeTempUrl&wbtreeid=1028">政务公开</a></div>
            </body></html>
        """.trimIndent()
        val notices = CampusNoticeParser.parse(html, "http://info.hitsz.edu.cn/")
        assertTrue(notices.isEmpty())
    }

    @Test
    fun listPageUrlDoesNotHardcodeTotalpage() {
        assertEquals(
            "http://info.hitsz.edu.cn/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1053",
            CampusNoticeParser.listPageUrl(1),
        )
        assertEquals(
            "http://info.hitsz.edu.cn/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1053&PAGENUM=3",
            CampusNoticeParser.listPageUrl(3),
        )
        assertFalse(CampusNoticeParser.listPageUrl(2).contains("totalpage"))
    }

    @Test
    fun capsAtThirty() {
        val items = (1..40).joinToString("") { index ->
            """<li><span>2026-09-01</span><a href="content.jsp?urltype=news.NewsContentUrl&wbtreeid=1023&wbnewsid=${1000 + index}" title="通知标题$index">通知标题$index</a></li>"""
        }
        val html = """<html><body><div class="Newslist"><ul>$items</ul></div></body></html>"""
        val notices = CampusNoticeParser.parse(html, CampusNoticeParser.LIST_URL)
        assertEquals(30, notices.size)
        assertTrue(notices.all { it.id.startsWith("wbnews:") })
    }

    @Test
    fun noticeUrlRequiresNewsId() {
        assertTrue(
            CampusNoticeParser.isNoticeUrl(
                "http://info.hitsz.edu.cn/content.jsp?urltype=news.NewsContentUrl&wbtreeid=1028&wbnewsid=9590",
            ),
        )
        assertFalse(CampusNoticeParser.isNoticeUrl("http://info.hitsz.edu.cn/index.jsp"))
        assertFalse(
            CampusNoticeParser.isNoticeUrl(
                "http://info.hitsz.edu.cn/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1028",
            ),
        )
    }

    @Test
    fun parsesCapturedLiveListPage() {
        val html = javaClass.getResource("/hitsz_notice_list_p1.html")!!.readText(Charsets.UTF_8)
        val notices = CampusNoticeParser.parse(html, CampusNoticeParser.LIST_URL)
        assertEquals(11, notices.size)
        assertTrue(notices.none { it.title.contains("官网首页") || it.title.contains("政务公开") })
        assertTrue(notices.all { it.id.startsWith("wbnews:") })
        assertTrue(notices.all { it.url.startsWith("http://info.hitsz.edu.cn/content.jsp") })
        assertTrue(notices.all { it.url.contains("wbnewsid=") })
        assertTrue(notices.all { it.pubDateMillis > 0L })
    }
}

