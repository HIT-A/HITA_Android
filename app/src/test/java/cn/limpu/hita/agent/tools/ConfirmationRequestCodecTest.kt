package cn.limpu.hita.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 确认请求要跨越 IO 线程与 UI 线程，只能靠 trace 的文本 payload。
 * 这些用例锁住编解码的一致性，以及「普通 trace 文本不会被误认为确认请求」。
 */
class ConfirmationRequestCodecTest {

    private val request = ConfirmationRequest(
        title = "添加到课表",
        detailLines = listOf("课程：高等数学", "时间：周一 08:00-09:40"),
        confirmLabel = "添加",
        cancelLabel = "取消",
    )

    @Test
    fun `encode then decode keeps every field`() {
        val restored = ConfirmationRequestCodec.decode(ConfirmationRequestCodec.encode(request))

        assertEquals(request, restored)
    }

    @Test
    fun `plain trace text is not treated as a confirmation request`() {
        assertNull(ConfirmationRequestCodec.decode("观察: 已找到 3 条结果"))
        assertNull(ConfirmationRequestCodec.decode(""))
        assertNull(ConfirmationRequestCodec.decode(null))
        assertNull(ConfirmationRequestCodec.decode("{}"))
    }

    @Test
    fun `empty title is rejected so the dialog never renders blank`() {
        assertNull(ConfirmationRequestCodec.decode("""{"title":"   ","detailLines":[]}"""))
    }

    @Test
    fun `blank detail lines are dropped and labels fall back`() {
        val restored = ConfirmationRequestCodec.decode(
            """{"title":"提交评价","detailLines":["第一行","","  "],"confirmLabel":"","cancelLabel":""}""",
        )

        assertEquals("提交评价", restored?.title)
        assertEquals(listOf("第一行"), restored?.detailLines)
        assertEquals("执行", restored?.confirmLabel)
        assertEquals("取消", restored?.cancelLabel)
    }
}
