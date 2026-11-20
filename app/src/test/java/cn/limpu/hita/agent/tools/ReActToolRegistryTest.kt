package cn.limpu.hita.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注册表必须区分「已声明状态的工具」与「只返回文本的旧工具」，
 * 否则写入类工具的成功/失败会被统计成 unknown。
 *
 * [ReActToolInput] 需要 Android `Application` 与真实仓储，纯 JVM 测试无法构造，
 * 因此这里只覆盖不执行工具的状态判定路径；执行路径由 instrumented 测试覆盖。
 */
class ReActToolRegistryTest {

    @Test
    fun `outcome tool is declared`() {
        val registry = ReActToolRegistry()
        registry.register("writer", OutcomeReActTool { ReActToolOutcome.Succeeded("done") })

        assertTrue(registry.declaresOutcome("writer"))
    }

    @Test
    fun `legacy tool is not declared`() {
        val registry = ReActToolRegistry()
        registry.register("reader", ReActTool { "text" })

        assertFalse(registry.declaresOutcome("reader"))
    }

    @Test
    fun `registration replaces the previous contract`() {
        val registry = ReActToolRegistry()
        registry.register("writer", ReActTool { "legacy" })
        assertFalse(registry.declaresOutcome("writer"))

        registry.register("writer", OutcomeReActTool { ReActToolOutcome.Succeeded("new") })
        assertTrue(registry.declaresOutcome("writer"))

        registry.register("writer", ReActTool { "legacy-again" })
        assertFalse(registry.declaresOutcome("writer"))
    }

    @Test
    fun `lookup is normalized`() {
        val registry = ReActToolRegistry()
        registry.register("Add Activity", OutcomeReActTool { ReActToolOutcome.Succeeded("x") })

        assertTrue(registry.declaresOutcome("add_activity"))
        assertTrue(registry.declaresOutcome("ADD-ACTIVITY"))
    }

    @Test
    fun `registered tools appear in names`() {
        val registry = ReActToolRegistry()
        registry.register("writer", OutcomeReActTool { ReActToolOutcome.Succeeded("x") })

        assertEquals(listOf("writer"), registry.names())
    }

    @Test
    fun `tool names are normalized`() {
        assertEquals("add_activity", ReActToolRegistry.normalizeName("Add Activity"))
        assertEquals("crawl_status", ReActToolRegistry.normalizeName("crawl-status"))
    }
}
