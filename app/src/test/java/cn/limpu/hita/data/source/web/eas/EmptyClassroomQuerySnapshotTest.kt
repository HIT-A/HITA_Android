package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.eas.TermItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyClassroomQuerySnapshotTest {
    @Test
    fun callerMutationsCannotChangeCapturedTermBuildingOrWeekOrder() {
        val term = TermItem("2025-2026", "学年原名", "2", "春季").apply {
            id = "remote-term-id"
            name = "自定义学期名"
            isCurrent = true
        }
        val building = BuildingItem().apply { id = " 001 "; name = "第一教学楼" }
        val weeks = mutableListOf(" 2 ", "9", "2")

        val captured = EmptyClassroomQuerySnapshot(term, building, weeks)
        term.yearCode = "2026-2027"
        term.yearName = "新学年名"
        term.termCode = "1"
        term.termName = "秋季"
        term.id = "new-id"
        term.name = "新学期名"
        term.isCurrent = false
        building.id = "99"
        building.name = "另一教学楼"
        weeks.clear()
        weeks.add("34")

        assertNotSame(term, captured.term)
        assertNotSame(building, captured.building)
        assertEquals("2025-2026", captured.term.yearCode)
        assertEquals("学年原名", captured.term.yearName)
        assertEquals("2", captured.term.termCode)
        assertEquals("春季", captured.term.termName)
        assertEquals("remote-term-id", captured.term.id)
        assertEquals("自定义学期名", captured.term.name)
        assertTrue(captured.term.isCurrent)
        assertEquals(" 001 ", captured.building.id)
        assertEquals("第一教学楼", captured.building.name)
        assertEquals(listOf(" 2 ", "9", "2"), captured.weeks)
    }

    @Test
    fun emptyWeeksAndUnnamedBuildingStayEmptyWithoutSynthesizedDefaults() {
        val building = BuildingItem()
        val weeks = mutableListOf<String>()
        val captured = EmptyClassroomQuerySnapshot(TermItem("", "", "", ""), building, weeks)

        building.id = "new-building"
        building.name = "new-name"
        weeks.add("5")

        assertEquals("", captured.building.id)
        assertNull(captured.building.name)
        assertTrue(captured.weeks.isEmpty())
    }
}
