package cn.limpu.hita.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CourseReminderScheduleTest {
    private val now = 1_700_000_000_000L
    // 本类只覆盖课前提醒：显式关闭下课前提醒，避免默认值变化污染断言。
    private val rules = ReminderRuleSet(
        leads = listOf(5, 15),
        periodOverrides = mapOf(3 to 30),
        endLeads = emptyList(),
    )

    @Test
    fun `next trigger is the smallest positive delay among event and lead pairs`() {
        val soon = course("soon", startOffsetMinutes = 20, section = 1)
        val overridden = course("afternoon", startOffsetMinutes = 40, section = 3)

        val next = CourseReminderSchedule.nextDue(
            events = listOf(soon, overridden),
            now = now,
            rules = rules,
            sentKeys = emptySet(),
        )

        assertEquals("soon#start#15", next?.key)
        assertEquals(5L * 60_000L, next?.delayMs(now))
    }

    @Test
    fun `sent event and lead pairs are skipped without blocking the next lead`() {
        val event = course("soon", startOffsetMinutes = 20, section = 1)

        val next = CourseReminderSchedule.nextDue(
            events = listOf(event),
            now = now,
            rules = rules,
            sentKeys = setOf("soon#start#15"),
        )

        assertEquals("soon#start#5", next?.key)
        assertEquals(15L * 60_000L, next?.delayMs(now))
    }

    @Test
    fun `a trigger that is already due fires immediately and past events are dropped`() {
        val due = course("due", startOffsetMinutes = 10, section = 1)
        val past = course("past", startOffsetMinutes = -1, section = 1)

        val next = CourseReminderSchedule.nextDue(
            events = listOf(due, past),
            now = now,
            rules = rules,
            sentKeys = emptySet(),
        )

        assertEquals("due#start#15", next?.key)
        assertEquals(0L, next?.delayMs(now))
        assertNull(
            CourseReminderSchedule.nextDue(
                events = listOf(past),
                now = now,
                rules = rules,
                sentKeys = emptySet(),
            )
        )
    }

    private fun course(id: String, startOffsetMinutes: Long, section: Int) = ReminderEvent(
        id = id,
        startMillis = now + startOffsetMinutes * 60_000L,
        section = section,
    )

    private fun courseWithEnd(
        id: String,
        startOffsetMinutes: Long,
        endOffsetMinutes: Long,
        section: Int = 1,
    ) = ReminderEvent(
        id = id,
        startMillis = now + startOffsetMinutes * 60_000L,
        section = section,
        endMillis = now + endOffsetMinutes * 60_000L,
    )

    @Test
    fun `end-of-class reminder fires relative to the end time`() {
        val rules = ReminderRuleSet(leads = emptyList(), endLeads = listOf(5))
        val event = courseWithEnd("lab", startOffsetMinutes = 10, endOffsetMinutes = 100)

        val next = CourseReminderSchedule.nextDue(
            events = listOf(event),
            now = now,
            rules = rules,
            sentKeys = emptySet(),
        )

        assertEquals("lab#end#5", next?.key)
        assertEquals(ReminderPhase.END, next?.phase)
        assertEquals(95L * 60_000L, next?.delayMs(now))
    }

    @Test
    fun `start and end reminders for the same lead do not shadow each other`() {
        val rules = ReminderRuleSet(leads = listOf(5), endLeads = listOf(5))
        val event = courseWithEnd("same", startOffsetMinutes = 10, endOffsetMinutes = 40)

        val candidates = CourseReminderSchedule.candidatesFor(event, rules)

        assertEquals(2, candidates.size)
        assertEquals(2, candidates.map { it.key }.distinct().size)
        // 课前 5 分钟先到，因此 nextDue 应选它。
        val next = CourseReminderSchedule.nextDue(listOf(event), now, rules, emptySet())
        assertEquals("same#start#5", next?.key)
    }

    @Test
    fun `a course without an end time never produces end reminders`() {
        val rules = ReminderRuleSet(leads = listOf(5), endLeads = listOf(5))
        val event = course("noEnd", startOffsetMinutes = 10, section = 1)

        val candidates = CourseReminderSchedule.candidatesFor(event, rules)

        assertEquals(listOf("noEnd#start#5"), candidates.map { it.key })
    }

    @Test
    fun `empty end leads disables the end reminder entirely`() {
        val rules = ReminderRuleSet(leads = listOf(5), endLeads = emptyList())
        val event = courseWithEnd("quiet", startOffsetMinutes = 10, endOffsetMinutes = 40)

        val candidates = CourseReminderSchedule.candidatesFor(event, rules)

        assertEquals(listOf("quiet#start#5"), candidates.map { it.key })
    }

    @Test
    fun `legacy rules without endLeads decode to the default end reminder`() {
        // 旧版本写入的快照没有 endLeads 字段。
        val legacy = """{"leads":[15],"periodOverrides":{},"examLeads":[]}"""

        val decoded = ReminderRules.decode(legacy)

        assertEquals(listOf(15), decoded?.leads)
        assertEquals(ReminderRuleSet.DEFAULT_END_LEADS, decoded?.endLeads)
    }

    @Test
    fun `more than three end leads are rejected`() {
        val tooMany = ReminderRuleSet(
            leads = listOf(15),
            endLeads = listOf(5, 10, 15, 20),
        )

        assertNull(ReminderRules.normalize(tooMany))
    }
}
