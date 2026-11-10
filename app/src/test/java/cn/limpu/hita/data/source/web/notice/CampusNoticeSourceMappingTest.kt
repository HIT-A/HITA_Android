package cn.limpu.hita.data.source.web.notice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampusNoticeSourceMappingTest {
    @Test
    fun eachCampusKeepsItsOwnListAndDetailBase() {
        val shenzhen = CampusNoticeParser.parse(
            """
            <div class="Newslist">
              <a href="content.jsp?urltype=news.NewsContentUrl&wbtreeid=1023&wbnewsid=9581" title="深圳校园通知标题">深圳校园通知标题</a>
            </div>
            """.trimIndent(),
            CampusNoticeParser.LIST_URL,
        )
        val benbu = BenbuNoticeParser.parse(
            """
            <ul class="paragraph list-tooltip">
              <li><span class="title"><a href="/article/2026/11/10/133857" title="本部校园通知标题">本部校园通知标题</a></span></li>
            </ul>
            """.trimIndent(),
            BenbuNoticeParser.LIST_URL,
        )
        val weihai = WeihaiNoticeParser.parse(
            """
            <div class="list_list_wrap"><ul>
              <li><span class="news-time2">2026-11-10</span><a href="/2026/1110/c1024a222516/page.htm" title="威海校园通知标题">威海校园通知标题</a></li>
            </ul></div>
            """.trimIndent(),
            WeihaiNoticeParser.LIST_URL,
        )

        assertEquals(
            "http://info.hitsz.edu.cn/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1053",
            CampusNoticeParser.LIST_URL,
        )
        assertEquals(
            "http://info.hitsz.edu.cn/content.jsp?urltype=news.NewsContentUrl&wbtreeid=1023&wbnewsid=9581",
            shenzhen.single().url,
        )
        assertEquals(
            "https://today.hit.edu.cn/category/10",
            BenbuNoticeParser.LIST_URL,
        )
        assertEquals(
            "https://today.hit.edu.cn/article/2026/11/10/133857",
            benbu.single().url,
        )
        assertEquals(
            "http://today.hitwh.edu.cn/1024/list.htm",
            WeihaiNoticeParser.LIST_URL,
        )
        assertEquals(
            "http://today.hitwh.edu.cn/2026/1110/c1024a222516/page.htm",
            weihai.single().url,
        )

        assertEquals(
            setOf("info.hitsz.edu.cn", "today.hit.edu.cn", "today.hitwh.edu.cn"),
            setOf(shenzhen.single().url, benbu.single().url, weihai.single().url)
                .map { it.substringAfter("://").substringBefore('/') }
                .toSet(),
        )
        assertTrue(shenzhen.single().url.startsWith(CampusNoticeParser.PORTAL_URL))
        assertTrue(benbu.single().url.startsWith(BenbuNoticeParser.PORTAL_URL))
        assertTrue(weihai.single().url.startsWith(WeihaiNoticeParser.PORTAL_URL))
        assertFalse(benbu.single().url.contains(CampusNoticeParser.PORTAL_URL))
        assertFalse(weihai.single().url.contains(CampusNoticeParser.PORTAL_URL))
    }
}
