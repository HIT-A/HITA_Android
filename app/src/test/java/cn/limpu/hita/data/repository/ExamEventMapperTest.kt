package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.timetable.EventItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExamEventMapperTest {
    @Test
    fun identityKey_matchesLegacyAndPrefixedNames() {
        val exam = ExamItem().apply {
            courseName = "高等数学"
            examDate = "2026-01-10"
            examTime = "08:30-10:30"
            examLocation = "正心楼 101"
        }
        val mapped = ExamEventMapper.toEvent(exam, "eas")!!
        val legacy = EventItem().apply {
            type = EventItem.TYPE.EXAM
            name = "高等数学"
            place = "正心楼 101"
            from = mapped.from
            to = mapped.to
        }

        assertEquals(ExamEventMapper.identityKey(legacy), ExamEventMapper.identityKey(mapped))
    }

    @Test
    fun toEvent_rejectsEmptyOrInvalidExamTime() {
        val emptyDate = ExamItem().apply {
            courseName = "高等数学"
            examDate = ""
            examTime = "08:30-10:30"
        }
        val invalidTime = ExamItem().apply {
            courseName = "高等数学"
            examDate = "2026-01-10"
            examTime = "08:30"
        }

        assertNull(ExamEventMapper.toEvent(emptyDate, "eas"))
        assertNull(ExamEventMapper.toEvent(invalidTime, "eas"))
    }

    @Test
    fun toEvent_trimsFieldsAndFormatsExamName() {
        val exam = ExamItem().apply {
            courseName = "  [考试]  高等数学  "
            examDate = "2026-01-10"
            examTime = " 08:30 - 10:30 "
            examLocation = " 正心楼   101 "
        }
        val mapped = ExamEventMapper.toEvent(exam, "eas")

        assertNotNull(mapped)
        assertEquals("[考试] 高等数学", mapped!!.name)
        assertEquals(" 正心楼   101 ", mapped.place)
    }
    @Test
    fun toEvent_rejectsOverflowTrailingTextAndNonPositiveDuration() {
        val invalid = listOf(
            "2026-02-30" to "08:30-10:30",
            "2026-13-01" to "08:30-10:30",
            "2026-01-10extra" to "08:30-10:30",
            "2026-01-10" to "24:00-25:00",
            "2026-01-10" to "08:60-10:30",
            "2026-01-10" to "08:30-10:30extra",
            "2026-01-10" to "10:30-08:30",
            "2026-01-10" to "08:30-08:30",
        )
        for ((date, time) in invalid) {
            val exam = ExamItem().apply { examDate = date; examTime = time }
            assertNull("$date $time", ExamEventMapper.toEvent(exam, "selected-table"))
        }
    }

    @Test
    fun toEvent_preservesValidLeapDayAndSelectedTimetable() {
        val exam = ExamItem().apply { examDate = "2028-02-29"; examTime = "08:30-10:30" }
        val mapped = ExamEventMapper.toEvent(exam, "selected-table")!!
        assertEquals("selected-table", mapped.timetableId)
        assertEquals(2 * 60 * 60 * 1000L, mapped.to.time - mapped.from.time)
    }

}
