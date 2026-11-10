package cn.limpu.hita.data.source.web.notice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenbuNoticeParserTest {
    @Test
    fun parsesTodayHitListItems() {
        val html = """
            <html><body>
            <ul class="paragraph list-tooltip">
              <li><div class="pull-right"><span class="date">2小时前</span></div>
              <span class="title top"><span title="材料科学与工程学院关于开展兼职辅导员选聘工作的通知"><a href="/article/2026/09/27/132837" target="_blank">材料科学与工程学院关于开展兼职辅导员选聘工作的通知</a></a></span></li>
              <li><div class="pull-right"><span class="date">1天前</span></div>
              <span class="title top"><span title="关于征集未来产业领域科技成果的通知"><a href="/article/2026/09/24/132786" target="_blank">关于征集未来产业领域科技成果的通知</a></a></span></li>
              <li><a href="/department/52">材料科学与工程学院</a></li>
            </ul>
            </body></html>
        """.trimIndent()
        val notices = BenbuNoticeParser.parse(html, BenbuNoticeParser.LIST_URL)
        assertEquals(2, notices.size)
        assertEquals("hit:132837", notices[0].id)
        assertEquals("材料科学与工程学院关于开展兼职辅导员选聘工作的通知", notices[0].title)
        assertEquals("https://today.hit.edu.cn/article/2026/09/27/132837", notices[0].url)
        assertEquals("BENBU", notices[0].campus)
        assertTrue(notices[0].pubDateMillis > 0L)
        assertTrue(notices[1].pubDateMillis < notices[0].pubDateMillis)
    }

    @Test
    fun detailUrlUsesTheCampusBasePassedToParse() {
        val html = """
            <ul class="paragraph list-tooltip">
              <li><span class="title"><a href="/article/2026/11/10/133857" title="本部校园通知标题">本部校园通知标题</a></span></li>
            </ul>
        """.trimIndent()
        val notices = BenbuNoticeParser.parse(html, "https://today.hit.edu.cn/category/10")
        assertEquals(1, notices.size)
        assertEquals(
            "https://today.hit.edu.cn/article/2026/11/10/133857",
            notices[0].url,
        )
        assertFalse(notices[0].url.contains("info.hitsz.edu.cn"))
    }

    @Test
    fun dedupesAndSortsDescending() {
        val item = { id: Int, date: String ->
            """<li><span class="title top"><span title="通知标题$id"><a href="/article/$date/$id">通知标题$id</a></a></span></li>"""
        }
        val html = "<html><body><ul class=\"paragraph list-tooltip\">" +
            item(2, "2026/09/20") + item(1, "2026/09/25") + item(2, "2026/09/20") +
            "</ul></body></html>"
        val notices = BenbuNoticeParser.parse(html)
        assertEquals(2, notices.size)
        assertEquals("hit:1", notices[0].id)
    }

    @Test
    fun capsAtThirty() {
        val items = (1..40).joinToString("") { index ->
            """<li><span class="title top"><span title="通知标题$index"><a href="/article/2026/09/01/${130000 + index}">通知标题$index</a></a></span></li>"""
        }
        val html = """<html><body><ul class="paragraph list-tooltip">$items</ul></body></html>"""
        assertEquals(30, BenbuNoticeParser.parse(html).size)
    }

    @Test
    fun listPageUrlIsZeroBased() {
        assertEquals("https://today.hit.edu.cn/category/10", BenbuNoticeParser.listPageUrl(1))
        assertEquals("https://today.hit.edu.cn/category/10?page=1", BenbuNoticeParser.listPageUrl(2))
    }

    @Test
    fun ignoresOtherLayouts() {
        assertTrue(BenbuNoticeParser.parse("<html><body><div>empty</div></body></html>").isEmpty())
        assertFalse(BenbuNoticeParser.isPersistedNotice(cn.limpu.hita.data.model.notice.CampusNotice("BENBU", "wbnews:1", "t", "https://info.hitsz.edu.cn/x", 0L)))
    }
}
