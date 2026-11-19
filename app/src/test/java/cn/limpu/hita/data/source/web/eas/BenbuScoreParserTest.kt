package cn.limpu.hita.data.source.web.eas

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenbuScoreParserTest {
    @Test
    fun `blank and unrelated pages are not score results`() {
        assertFalse(BenbuScoreParser.isScoreResultPage(Jsoup.parse("")))
        assertFalse(BenbuScoreParser.isScoreResultPage(Jsoup.parse("<script>window.location='/login'</script>")))
        assertFalse(BenbuScoreParser.isScoreResultPage(Jsoup.parse("<table><tr><td>系统维护</td></tr></table>")))
    }

    @Test
    fun `score table without rows and explicit empty result remain valid`() {
        val header = "<table><tr><th>课程名称</th><th>最终成绩</th></tr></table>"
        assertTrue(BenbuScoreParser.isScoreResultPage(Jsoup.parse(header)))
        assertTrue(BenbuScoreParser.isScoreResultPage(Jsoup.parse("<p>暂无成绩</p>")))
    }


    @Test
    fun `fractional course credit is preserved`() {
        assertEquals(1.5f, parseScoreCredit("1.5"), 0f)
    }
}
