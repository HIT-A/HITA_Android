package cn.limpu.hita.data.source.web.campus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空间预约请求签名。核心断言是接口文档 §4.3 的可复现验算样例：
 * 同一批参数必须得到同一个 `signature`，任何拼接顺序或注入参数差异都会让它失配。
 */
class SilentPodSignerTest {

    /** 文档 §4.3 的公开样例（无隐私数据）。 */
    private val docNonce = "eee6d667042c179bd2f2c61333090f1c"
    private val docTrackingId = "791120cede0eef3d27bbcb76d9235fa5"

    @Test
    fun `document sample produces the documented signature`() {
        val signature = SilentPodSigner.signatureOf(
            query = listOf("limit" to "15", "page" to "1"),
            nonce = docNonce,
            trackingId = docTrackingId,
        )
        assertEquals("3275513cadf10ea76a7a5cda38ae4948", signature)
    }

    @Test
    fun `canonical string matches documented order without separators`() {
        assertEquals(
            "lang=zh_CNlimit=15nonce=$docNonce" + "page=1trackingId=$docTrackingId",
            SilentPodSigner.canonicalString(
                query = listOf("limit" to "15", "page" to "1"),
                nonce = docNonce,
                trackingId = docTrackingId,
            ),
        )
    }

    @Test
    fun `canonical string sorts keys by utf8 bytes`() {
        // 大写字母（0x41）按字节序排在 '_'（0x5F）和小写字母（0x61）之前。
        val canonical = SilentPodSigner.canonicalString(
            query = listOf("selectedDate" to "2026-11-15", "Page" to "1", "_pad" to "x"),
            nonce = docNonce,
            trackingId = docTrackingId,
        )
        assertEquals(
            "Page=1_pad=xlang=zh_CNnonce=${docNonce}selectedDate=2026-11-15trackingId=$docTrackingId",
            canonical,
        )
    }

    @Test
    fun `duplicate query keys keep only the first value`() {
        val canonical = SilentPodSigner.canonicalString(
            query = listOf("page" to "1", "page" to "2"),
            nonce = docNonce,
            trackingId = docTrackingId,
        )
        assertTrue(canonical.contains("page=1"))
        assertTrue(!canonical.contains("page=2"))
    }

    @Test
    fun `empty values are excluded from the signature`() {
        val canonical = SilentPodSigner.canonicalString(
            query = listOf("keyword" to "", "page" to "1"),
            nonce = docNonce,
            trackingId = docTrackingId,
        )
        assertTrue(!canonical.contains("keyword"))
    }

    @Test
    fun `signature is 32 lowercase hex chars`() {
        val signature = SilentPodSigner.signatureOf(
            query = listOf("page" to "1", "limit" to "10"),
            nonce = SilentPodSigner.newNonce("seed-a"),
            trackingId = SilentPodSigner.newNonce("seed-b"),
        )
        assertEquals(32, signature.length)
        assertTrue(signature.all { it in "0123456789abcdef" })
    }

    @Test
    fun `md5 hex matches known vectors`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", SilentPodSigner.md5Hex(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", SilentPodSigner.md5Hex("abc"))
    }

    @Test
    fun `signed url carries injected params signature and escaped values`() {
        val url = SilentPodSigner.signedUrl(
            baseUrl = "https://www.utszlib.edu.cn/space-reservation-fg/x-space-reservation-api",
            path = "/web/spaces/",
            query = listOf("selectedDate" to "2026-11-15", "ruleIsNull" to "false"),
            nonce = docNonce,
            trackingId = docTrackingId,
        )
        assertTrue(url.startsWith(
            "https://www.utszlib.edu.cn/space-reservation-fg/x-space-reservation-api/web/spaces?"
        ))
        assertTrue(url.contains("selectedDate=2026-11-15"))
        assertTrue(url.contains("lang=zh_CN"))
        assertTrue(url.contains("nonce=$docNonce"))
        assertTrue(url.contains("trackingId=$docTrackingId"))
        assertTrue(url.contains("signature="))
        // 签名必须与 canonicalString + salt 的结果一致，而不是随手拼的。
        val expected = SilentPodSigner.signatureOf(
            query = listOf("selectedDate" to "2026-11-15", "ruleIsNull" to "false"),
            nonce = docNonce,
            trackingId = docTrackingId,
        )
        assertTrue(url.endsWith("signature=$expected"))
    }

    @Test
    fun `signed url percent-encodes spaces as twenty`() {
        val url = SilentPodSigner.signedUrl(
            baseUrl = "https://example.invalid/api",
            path = "web/spaces",
            query = listOf("keyword" to "静音 仓"),
            nonce = docNonce,
            trackingId = docTrackingId,
        )
        assertTrue(url.contains("keyword=%E9%9D%99%E9%9F%B3%20%E4%BB%93"))
        assertTrue(!url.contains("keyword=静音+仓"))
    }

    @Test
    fun `post body fields join the signature and change it`() {
        val withoutBody = SilentPodSigner.signatureOf(
            query = emptyList(),
            nonce = docNonce,
            trackingId = docTrackingId,
        )
        val withBody = SilentPodSigner.signatureOf(
            query = emptyList(),
            nonce = docNonce,
            trackingId = docTrackingId,
            bodyFields = linkedMapOf("reason" to "个人行程调整"),
        )

        // 同样的注入参数，只要 body 字段参与签名，结果必须不同；
        // 否则服务端会因签名不含 body 而拒绝写请求。
        assertNotEquals(withoutBody, withBody)
        assertTrue(
            SilentPodSigner.canonicalString(
                query = emptyList(),
                nonce = docNonce,
                trackingId = docTrackingId,
                bodyFields = linkedMapOf("reason" to "abc"),
            ).contains("reason=abc"),
        )
    }
}
