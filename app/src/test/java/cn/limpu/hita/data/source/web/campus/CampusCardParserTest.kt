package cn.limpu.hita.data.source.web.campus

import cn.limpu.hita.data.model.campus.campusCardYuan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 深圳信息门户校园卡响应的解析规则。
 *
 * 全部 fixture 都是脱敏样本：金额是编造的，卡号/证件号一类字段根本没有出现在样本里，
 * 因为解析器也不解码它们。字段名与 iOS `CampusCardPortalClient` 的 DTO 一一对应。
 */
class CampusCardParserTest {

    // region 余额与卡状态

    @Test
    fun `bill summary reads balance status and processed time`() {
        val summary = CampusCardParser.billSummary(
            """
            {"code":0,"msg":"success","data":{
              "cardbalance":"123456",
              "flagname":"正常",
              "procdatetime":"2026/11/15 08:30:00"
            }}
            """.trimIndent(),
        )
        assertEquals(123456, summary.balanceCents)
        assertEquals("正常", summary.statusText)
        assertEquals("2026/11/15 08:30:00", summary.processedAtText)
        assertNull(summary.spending)
        assertEquals("1234.56", campusCardYuan(summary.balanceCents))
    }

    @Test
    fun `bill summary trims blank optional fields to null`() {
        val summary = CampusCardParser.billSummary(
            """{"code":0,"data":{"cardbalance":" 500 ","flagname":"  ","procdatetime":""}}""",
        )
        assertEquals(500, summary.balanceCents)
        assertNull(summary.statusText)
        assertNull(summary.processedAtText)
    }

    @Test
    fun `balance is reported in cents with two decimals`() {
        assertEquals("0.07", campusCardYuan(7))
        assertEquals("0.00", campusCardYuan(0))
        assertEquals("-12.30", campusCardYuan(-1230))
        assertEquals("100.00", campusCardYuan(10000))
    }

    @Test
    fun `missing token in bill summary requires sign in`() {
        val failure = failureOf {
            CampusCardParser.billSummary("""{"code":500,"msg":"参数缺失"}""")
        }
        assertTrue(failure is CampusCardSignInRequiredException)
    }

    @Test
    fun `unexpected business code is a remote error not a sign in prompt`() {
        val failure = failureOf {
            CampusCardParser.billSummary("""{"code":1001,"msg":"系统维护"}""")
        }
        assertTrue(failure is CampusCardRemoteException)
        assertEquals("系统维护", failure.message)
    }

    @Test
    fun `summary without data block is rejected instead of showing zero`() {
        val failure = failureOf { CampusCardParser.billSummary("""{"code":0,"msg":"success"}""") }
        assertTrue(failure is CampusCardRemoteException)
    }

    @Test
    fun `non json payload is rejected`() {
        assertTrue(failureOf { CampusCardParser.billSummary("<html>login</html>") } is CampusCardRemoteException)
    }

    @Test
    fun `response without code field is not treated as success`() {
        assertTrue(
            failureOf { CampusCardParser.billSummary("""{"data":{"cardbalance":"1"}}""")
            } is CampusCardRemoteException,
        )
    }

    @Test
    fun `token failure classifier follows ios semantics`() {
        assertTrue(CampusCardParser.isTokenFailure(401, null))
        assertTrue(CampusCardParser.isTokenFailure(500, "参数缺失"))
        assertTrue(CampusCardParser.isTokenFailure(500, "invalid token"))
        assertTrue(CampusCardParser.isTokenFailure(500, "请先登录"))
        assertFalse(CampusCardParser.isTokenFailure(500, "系统维护"))
        assertFalse(CampusCardParser.isTokenFailure(0, null))
        assertFalse(CampusCardParser.isTokenFailure(null, null))
    }

    // endregion

    // region 消费汇总

    @Test
    fun `spending summary reads yesterday week and month`() {
        val spending = CampusCardParser.spending(
            """
            {"code":0,"data":{"yesterdayAmount":"12.50","weekAmount":"88.00","monthAmount":"320.40"}}
            """.trimIndent(),
        )
        assertNotNull(spending)
        assertEquals("12.50", spending!!.yesterdayText)
        assertEquals("88.00", spending.weekText)
        assertEquals("320.40", spending.monthText)
    }

    @Test
    fun `spending summary failure degrades to null instead of throwing`() {
        assertNull(CampusCardParser.spending("""{"code":500,"msg":"参数缺失"}"""))
        assertNull(CampusCardParser.spending("""{"code":0}"""))
        assertNull(CampusCardParser.spending("not json"))
    }

    // endregion

    // region 流水

    @Test
    fun `transactions parse string and numeric amounts alike`() {
        val page = CampusCardParser.transactions(
            """
            {"code":0,"msg":"success","total":3,"rows":[
              {"tranamt":"-1250","effectdate":"2026/11/14 12:03:11","mercname":"一食堂","tranname":"消费"},
              {"tranamt":-800,"effectdate":"2026-11-13 18:20:00","mercname":" 二食堂 ","tranname":"消费"},
              {"tranamt":"5000","effectdate":"2026/11/12 09:00:00","mercname":"圈存机","tranname":"充值"}
            ]}
            """.trimIndent(),
            page = 1,
        )
        assertEquals(3, page.total)
        assertEquals(listOf(-1250, -800, 5000), page.items.map { it.amountCents })
        assertEquals("一食堂", page.items[0].merchant)
        assertEquals("二食堂", page.items[1].merchant)
        assertEquals("消费", page.items[0].method)
        assertNotNull(page.items[0].occurredAtMillis)
        assertNotNull(page.items[1].occurredAtMillis)
        assertEquals(3, page.items.map { it.id }.distinct().size)
    }

    @Test
    fun `empty rows give an empty page not a fabricated entry`() {
        val page = CampusCardParser.transactions("""{"code":0,"total":0,"rows":[]}""", page = 1)
        assertTrue(page.items.isEmpty())
        assertEquals(0, page.total)
    }

    @Test
    fun `missing rows array is still an empty page`() {
        val page = CampusCardParser.transactions("""{"code":0,"total":0}""", page = 2)
        assertTrue(page.items.isEmpty())
        assertEquals(0, page.total)
    }

    @Test
    fun `total falls back to parsed row count when absent`() {
        val page = CampusCardParser.transactions(
            """{"code":0,"rows":[{"tranamt":"-100","effectdate":"2026/11/14 12:00:00"}]}""",
            page = 1,
        )
        assertEquals(1, page.items.size)
        assertEquals(1, page.total)
    }

    @Test
    fun `unrecognised time format keeps raw text and null timestamp`() {
        val page = CampusCardParser.transactions(
            """{"code":0,"total":1,"rows":[{"tranamt":"-100","effectdate":"15/11/2026 中午","mercname":"一食堂"}]}""",
            page = 1,
        )
        val item = page.items.single()
        assertEquals("15/11/2026 中午", item.occurredAtText)
        assertNull(item.occurredAtMillis)
        assertEquals(-100, item.amountCents)
    }

    @Test
    fun `transactions require sign in when token is missing`() {
        assertTrue(
            failureOf { CampusCardParser.transactions("""{"code":500,"msg":"参数缺失"}""", page = 1) }
                is CampusCardSignInRequiredException,
        )
    }

    // endregion

    // region ssologin

    @Test
    fun `ssologin returns the token string`() {
        assertEquals("TOKEN-abc_123", CampusCardParser.token("""{"code":0,"data":"TOKEN-abc_123"}"""))
    }

    @Test
    fun `ssologin code 500 is a sign in failure`() {
        assertTrue(
            failureOf { CampusCardParser.token("""{"code":500,"msg":"参数缺失"}""") }
                is CampusCardSignInRequiredException,
        )
    }

    @Test
    fun `ssologin without data fails instead of returning blank token`() {
        assertTrue(
            failureOf { CampusCardParser.token("""{"code":0}""") } is CampusCardRemoteException,
        )
    }

    // endregion

    // region 金额与时间工具

    @Test
    fun `cents parses integers decimals and junk`() {
        assertEquals(1234, CampusCardParser.cents("1234"))
        assertEquals(1234, CampusCardParser.cents(" 1234 "))
        assertEquals(12, CampusCardParser.cents("12.4"))
        assertEquals(0, CampusCardParser.cents(""))
        assertEquals(0, CampusCardParser.cents(null))
        assertEquals(0, CampusCardParser.cents("abc"))
    }

    @Test
    fun `portal dates parse in the campus time zone`() {
        // 2026-11-15 08:30:00 +08:00 == 2026-11-15T00:30:00Z
        assertEquals(
            1_794_702_600_000L,
            CampusCardParser.parsePortalDate("2026/11/15 08:30:00"),
        )
        assertEquals(
            CampusCardParser.parsePortalDate("2026/11/15 08:30:00"),
            CampusCardParser.parsePortalDate("2026-11-15 08:30:00"),
        )
        assertEquals(
            CampusCardParser.parsePortalDate("2026/11/15 08:30:00"),
            CampusCardParser.parsePortalDate("20261115083000"),
        )
        assertNull(CampusCardParser.parsePortalDate("明天"))
        assertNull(CampusCardParser.parsePortalDate(""))
    }

    // endregion

    private fun failureOf(block: () -> Unit): CampusCardParseException {
        try {
            block()
        } catch (failure: CampusCardParseException) {
            return failure
        }
        fail("应当抛出解析异常")
        error("unreachable")
    }
}
