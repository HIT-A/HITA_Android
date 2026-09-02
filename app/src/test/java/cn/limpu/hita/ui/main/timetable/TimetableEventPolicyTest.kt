package cn.limpu.hita.ui.main.timetable

import cn.limpu.hita.data.model.timetable.EventItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableEventPolicyTest {
    @Test
    fun `course plan and followed school events are read only`() {
        assertTrue(
            TimetableEventPolicy.isReadOnlyProjection(
                EventItem().apply { source = "${EventItem.SOURCE_COURSE_PLAN}:owner" }
            )
        )
        assertTrue(
            TimetableEventPolicy.isReadOnlyProjection(
                EventItem().apply { source = "${EventItem.SOURCE_FOLLOWED_SCHOOL}:owner" }
            )
        )
    }

    @Test
    fun `manual and imported events remain editable`() {
        assertFalse(
            TimetableEventPolicy.isReadOnlyProjection(
                EventItem().apply { source = EventItem.SOURCE_MANUAL }
            )
        )
        assertFalse(
            TimetableEventPolicy.isReadOnlyProjection(
                EventItem().apply { source = EventItem.SOURCE_EAS_IMPORT }
            )
        )
    }
}
