package cn.limpu.hita.data.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamReminderScheduleTest {
    private val now = 1_700_000_000_000L

    @Test
    fun `delay matches the earliest unsent exam lead`() {
        val exam = exam("final", hoursFromNow = 25)

        val next = ExamReminderSchedule.nextDue(
            exams = listOf(exam),
            now = now,
            leads = listOf(30, 60, 720, 1440),
            sentKeys = setOf("final#start#1440"),
        )

        assertEquals("final#start#720", next?.key)
        assertEquals(13L * 60L * 60L * 1000L, next?.delayMs(now))
    }

    @Test
    fun `expired exams and already passed leads are not scheduled`() {
        val expired = exam("yesterday", hoursFromNow = -1)
        val soon = exam("soon", hoursFromNow = 2)

        val next = ExamReminderSchedule.nextDue(
            exams = listOf(expired, soon),
            now = now,
            leads = listOf(30, 1440),
            sentKeys = emptySet(),
        )

        assertEquals("soon#start#30", next?.key)
        assertNull(
            ExamReminderSchedule.nextDue(
                exams = listOf(expired),
                now = now,
                leads = listOf(30),
                sentKeys = emptySet(),
            )
        )
    }

    @Test
    fun `a sent exam lead is not selected again`() {
        val exam = exam("final", hoursFromNow = 2)

        assertNull(
            ExamReminderSchedule.nextDue(
                exams = listOf(exam),
                now = now,
                leads = listOf(30),
                sentKeys = setOf("final#start#30"),
            )
        )
    }

    @Test
    fun `empty leads do not create a worker`() {
        assertFalse(ExamReminderSchedule.shouldSchedule(emptyList()))
        assertTrue(ExamReminderSchedule.shouldSchedule(listOf(1440)))
    }

    @Test
    fun `due now returns every lead that has come due and skips sent ones`() {
        // 考试在 2 小时后：24h 与 12h 档已过点（但因为是「已错过」不会被 nextDue 选中），
        // 1h 与 30m 档还没到。这里只断言 dueNow 的语义：只取 <= now 的档位。
        val exam = exam("soon", hoursFromNow = 2)

        val due = ExamReminderSchedule.dueNow(
            exams = listOf(exam),
            now = now,
            leads = listOf(24 * 60, 12 * 60, 60, 30),
            sentKeys = emptySet(),
        )

        // 24h 与 12h 的触发点都远早于 now（超过 30 分钟窗口），属于「已经错过的档位」，
        // 与 nextDue 一致地不补发，因此 dueNow 也必须为空。
        assertEquals(emptyList<ReminderDue>(), due)
    }

    @Test
    fun `due now fires the lead that just became due`() {
        // 考试在 50 分钟后：1h（60 分钟）档的触发点已经过去，30m 档尚未到。
        val exam = exam("soon", hoursFromNow = 1).let {
            // 覆盖成 50 分钟
            it.copy(startMillis = now + 50L * 60L * 1000L)
        }

        val due = ExamReminderSchedule.dueNow(
            exams = listOf(exam),
            now = now,
            leads = listOf(60, 30),
            sentKeys = emptySet(),
        )

        assertEquals(listOf("soon#start#60"), due.map { it.key })
    }

    @Test
    fun `due now skips an already sent lead`() {
        val exam = exam("soon", hoursFromNow = 1).let {
            it.copy(startMillis = now + 50L * 60L * 1000L)
        }

        val due = ExamReminderSchedule.dueNow(
            exams = listOf(exam),
            now = now,
            leads = listOf(60, 30),
            sentKeys = setOf("soon#start#60"),
        )

        assertEquals(emptyList<ReminderDue>(), due)
    }

    private fun exam(id: String, hoursFromNow: Long) = ReminderEvent(
        id = id,
        startMillis = now + hoursFromNow * 60L * 60L * 1000L,
        section = null,
    )
}
