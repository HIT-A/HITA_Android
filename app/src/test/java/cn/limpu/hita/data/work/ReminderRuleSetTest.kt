package cn.limpu.hita.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderRuleSetTest {
    private val memory = MemoryPreferences()
    private val store = CourseReminderRules(memory)

    @Test
    fun `missing rules migrate from the legacy minutes value`() {
        memory.putInt("minutes", 5)

        val rules = store.rules()

        assertEquals(listOf(5), rules.leads)
        // 断言迁移结果的语义，而不是 JSON 字段的字面顺序：新增字段不该让这条测试变红。
        val migrated = ReminderRules.decode(memory.getString("rules_v1"))
        assertEquals(listOf(5), migrated?.leads)
        assertEquals(ReminderRuleSet.DEFAULT_END_LEADS, migrated?.endLeads)
        assertEquals(5, memory.getInt("minutes", 0))
    }

    @Test
    fun `leads are deduplicated sorted and clipped within one accepted set`() {
        // 去重后只有 0 / 5 / 15 三档，裁剪 200→120 与 -3→0 后仍不超过上限。
        store.saveRules(ReminderRuleSet(leads = listOf(200, 5, 5, -3, 0), periodOverrides = emptyMap()))

        assertEquals(listOf(0, 5, 120), store.rules().leads)
    }

    @Test
    fun `more than three leads are rejected and the previous rules stay`() {
        store.saveRules(ReminderRuleSet(leads = listOf(15), periodOverrides = emptyMap()))

        val rejected = store.saveRules(ReminderRuleSet(leads = listOf(0, 5, 15, 30), periodOverrides = emptyMap()))

        assertFalse(rejected)
        assertEquals(listOf(15), store.rules().leads)
    }

    @Test
    fun `period override replaces the general leads for that section`() {
        val rules = ReminderRuleSet(leads = listOf(5, 15), periodOverrides = mapOf(3 to 30, 9 to -1))

        assertEquals(listOf(30), rules.leadsFor(3))
        assertEquals(listOf(5, 15), rules.leadsFor(1))
        assertEquals(listOf(0), rules.leadsFor(9))
    }

    @Test
    fun `exam leads stay inside the supported preset and ignore duplicates`() {
        store.saveRules(ReminderRuleSet(leads = listOf(15), examLeads = listOf(10, 1440, 30, 1440, 60)))

        assertEquals(listOf(30, 60, 1440), store.rules().examLeads)
        assertTrue(store.saveRules(store.rules().copy(examLeads = emptyList())))
        assertEquals(emptyList<Int>(), store.rules().examLeads)
    }
}
