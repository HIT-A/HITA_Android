package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.eas.TermItem
import java.lang.reflect.Proxy
import org.jsoup.Connection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenbuExamAuthenticationTest {
    private val source = BenbuEASWebSource()
    private val term = TermItem("2026-2027", "2026-2027", "1", "秋季")
    private val examTable = """
        <table class='exam-list'>
          <tr><th>课程名称</th><th>考试时间</th><th>考试类型</th></tr>
          <tr><td>大学物理</td><td>2026-10-29 09:00-11:00</td><td>期中</td></tr>
        </table>
    """.trimIndent()

    private fun expired(body: String, status: Int = 200): Boolean {
        val response = Proxy.newProxyInstance(
            Connection.Response::class.java.classLoader,
            arrayOf(Connection.Response::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "statusCode" -> status
                else -> error("Unexpected call: ${method.name}")
            }
        } as Connection.Response
        val method = BenbuEASWebSource::class.java.getDeclaredMethod(
            "isAuthExpiredResponse", Connection.Response::class.java, String::class.java
        ).apply { isAccessible = true }
        return method.invoke(source, response, body) as Boolean
    }

    @Test fun `recognized exam table is not expired because navigation mentions login`() {
        val html = "<html><head><title>考试查询</title></head><body>" +
            "<nav>当前登录用户　统一身份认证服务</nav>" + examTable + "</body></html>"
        assertEquals(1, BenbuExamParser.parse(html, "期中", term).size)
        assertFalse("A valid exam response must reach the parser", expired(html))
    }

    @Test fun `exam response without login navigation is usable`() {
        assertFalse(expired(examTable))
    }

    @Test fun `empty recognized table remains usable with authentication footer`() {
        val html = "<footer>统一身份认证服务</footer>" +
            "<table><tr><th>课程名称</th><th>考试日期</th></tr></table>"
        assertFalse(expired(html))
        assertTrue(BenbuExamParser.parse(html, "期中", term).isEmpty())
    }

    @Test fun `real login form remains expired even alongside an exam table`() {
        assertTrue(expired("<form action='/login'><input name='password'></form>" + examTable))
    }

    @Test fun `authentication title remains expired even alongside an exam table`() {
        assertTrue(expired("<html><title>统一身份认证登录</title><body>$examTable</body></html>"))
    }

    @Test fun `unauthorized status remains expired even with a valid exam table`() {
        assertTrue(expired(examTable, 401))
        assertTrue(expired(examTable, 403))
    }

    @Test fun `expired session message without exam table remains expired`() {
        assertTrue(expired("<p>未登录，请重新登录</p>"))
    }

    @Test fun `unrelated course table does not mask a login response`() {
        val html = "<p>未登录，请重新登录</p>" +
            "<table><tr><th>课程名称</th><th>上课时间</th></tr></table>"
        assertTrue(expired(html))
    }

    @Test fun `javascript challenge remains a recovery response`() {
        assertTrue(expired("<html><title>Just a moment</title><body>Please enable JavaScript</body></html>"))
    }
}
