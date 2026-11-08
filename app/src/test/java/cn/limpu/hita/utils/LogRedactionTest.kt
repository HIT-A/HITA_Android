package cn.limpu.hita.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志脱敏规则是安全审计里唯一被多个模块共用的隐私边界：任何一处回退都会把
 * ticket / cookie / token / 学号写进 logcat。这里锁定「只留结构信息」的契约。
 */
class LogRedactionTest {

    @Test
    fun `url keeps scheme host port and path but drops query fragment and userinfo`() {
        val raw = "https://user:pass@jw.hitsz.edu.cn:8443/authentication/main?ticket=ST-123&service=x#frag"
        assertEquals(
            "https://jw.hitsz.edu.cn:8443/authentication/main",
            LogRedaction.url(raw)
        )
    }

    @Test
    fun `url drops the cas ticket that identifies a login attempt`() {
        val redacted = LogRedaction.url("http://ids.hit.edu.cn/authserver/login?ticket=ST-42-abc")
        assertFalse(redacted.contains("ST-42-abc"))
        assertEquals("http://ids.hit.edu.cn/authserver/login", redacted)
    }

    @Test
    fun `url of malformed input never echoes raw value`() {
        val redacted = LogRedaction.url("not a url with secret token")
        assertFalse(redacted.contains("secret"))
        assertTrue(redacted.contains("unparsable"))
    }

    @Test
    fun `url handles blank and null`() {
        assertEquals("", LogRedaction.url(null))
        assertEquals("", LogRedaction.url("  "))
    }

    @Test
    fun `cookie header keeps names and value lengths only`() {
        val redacted = LogRedaction.cookieHeader("JSESSIONID=abcdef123456; HIT=zzz")
        assertFalse(redacted.contains("abcdef123456"))
        assertFalse(redacted.contains("zzz"))
        assertTrue(redacted.contains("JSESSIONID=<12 chars>"))
        assertTrue(redacted.contains("HIT=<3 chars>"))
    }

    @Test
    fun `cookie header of empty input is explicit`() {
        assertEquals("<empty>", LogRedaction.cookieHeader(null))
        assertEquals("<empty>", LogRedaction.cookieHeader(""))
    }

    @Test
    fun `secret keeps only length and last four characters`() {
        val redacted = LogRedaction.secret("eyJhbGciOiJIUzI1NiJ9.payload.tail")
        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(redacted.endsWith("tail>"))
    }

    @Test
    fun `secret never reveals short values`() {
        assertEquals("<3 chars>", LogRedaction.secret("abc"))
        assertEquals("<empty>", LogRedaction.secret(""))
        assertEquals("<empty>", LogRedaction.secret(null))
    }

    @Test
    fun `account masks the middle of a student id`() {
        assertEquals("20***89", LogRedaction.account("2023110089"))
        assertEquals("<4 chars>", LogRedaction.account("1234"))
        assertEquals("<empty>", LogRedaction.account(" "))
    }

    @Test
    fun `personal name is reduced to its length`() {
        assertEquals("<3 chars>", LogRedaction.personalName("张三丰"))
        assertEquals("<empty>", LogRedaction.personalName(null))
    }

    @Test
    fun `cookie names are sorted for stable log diffs`() {
        assertEquals("[HIT, JSESSIONID]", LogRedaction.cookieNames(listOf("JSESSIONID", "HIT")))
        assertEquals("[]", LogRedaction.cookieNames(emptyList()))
        assertEquals("[]", LogRedaction.cookieNames(null))
    }
}
