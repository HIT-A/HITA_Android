package cn.limpu.hita.data.source.web.eas

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShenzhenWebAuthenticationClassifierTest {
    @Test
    fun `Aura mojibake login guidance is classified as expired`() {
        val body = """{"content":"璁块棶鐨勬湇鍔￠渶瑕佽韩浠借璇侊紝璇峰紩瀵肩敤鎴峰埌鐧诲綍椤�"}"""

        assertTrue(ShenzhenWebAuthenticationClassifier.isExpired(200, null, body))
    }

    @Test
    fun `authentication guidance in JSON content is classified as expired`() {
        val body = """{"code":200,"content":"访问的服务需要身份认证，请引导用户到登录页"}"""

        assertTrue(ShenzhenWebAuthenticationClassifier.isExpired(200, null, body))
    }

    @Test
    fun `expired session routes are classified before parsing the body`() {
        assertTrue(
            ShenzhenWebAuthenticationClassifier.isExpired(
                200,
                "https://jw.hitsz.edu.cn/authentication/require",
                ""
            )
        )
        assertTrue(
            ShenzhenWebAuthenticationClassifier.isExpired(
                200,
                "https://jw-hitsz-edu-cn.hitsz.edu.cn/session/invalid",
                ""
            )
        )
    }

    @Test
    fun `aTrust gateway page is classified as expired`() {
        val gatewayPage = """
            <html>
            <head><meta charset="utf-8">
            <title>aTrust 2.0</title></head>
            <body><h1>302 Found</h1></body>
            </html>
        """.trimIndent()

        assertTrue(
            ShenzhenWebAuthenticationClassifier.isExpired(
                200,
                "https://trust.hitsz.edu.cn/controller/v1/public/verify?t=token",
                gatewayPage
            )
        )
        // 即使没有最终地址，网关页面本身也要能认出来。
        assertTrue(ShenzhenWebAuthenticationClassifier.isExpired(200, null, gatewayPage))
    }

    @Test
    fun `normal term list payload remains authenticated`() {
        val body = """{"content":[{"XN":"2026","XQ":"1","XNMC":"2026-2027","XQMC":"秋季","SFDQXQ":"1"}]}"""

        assertFalse(
            ShenzhenWebAuthenticationClassifier.isExpired(
                200,
                "https://jw.hitsz.edu.cn/component/queryXnxq",
                body
            )
        )
    }

    @Test
    fun `normal empty exam response remains authenticated`() {
        val body = """{"code":200,"content":{"total":0,"list":[]}}"""

        assertFalse(
            ShenzhenWebAuthenticationClassifier.isExpired(
                200,
                "https://jw.hitsz.edu.cn/kscxtj/queryXsksByxhList",
                body
            )
        )
    }
}
