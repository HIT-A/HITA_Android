package cn.limpu.hita.utils

import cn.limpu.hita.data.model.eas.TermItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class TermUtilsTest {
    @Test
    fun `import picker recent filter keeps 2026 summer and sorts it before spring`() {
        val spring = TermItem("2025-2026", "2025-2026", "2", "春季")
        val summer = TermItem("2025-2026", "2025-2026", "3", "夏季")
        val old = TermItem("2021-2022", "2021-2022", "1", "秋季")

        val result = TermUtils.filterRecentTerms(listOf(spring, old, summer), currentYear = 2026)

        assertEquals(listOf("2025-2026-3", "2025-2026-2"), result.map { it.id })
    }

    @Test
    fun `student term filter keeps enrollment and later terms newest first`() {
        val beforeEnrollment = TermItem("2021-2022", "2021-2022", "2", "春季")
        val enrollmentFall = TermItem("2022-2023", "2022-2023", "1", "秋季")
        val currentSpring = TermItem("2025-2026", "2025-2026", "2", "春季").apply {
            isCurrent = true
        }
        val currentSummer = TermItem("2025-2026", "2025-2026", "3", "夏季")
        val future = TermItem("2026-2027", "2026-2027", "1", "秋季")

        val result = TermUtils.filterTermsForStudent(
            listOf(enrollmentFall, future, currentSpring, beforeEnrollment, currentSummer),
            grade = "2022级",
            currentYear = 2026,
            currentMonth = Calendar.JULY
        )

        assertEquals(
            listOf("2025-2026-3", "2025-2026-2", "2022-2023-1"),
            result.map { it.id }
        )
    }

    @Test
    fun `course selection term can advance from current summer to next autumn`() {
        val currentSummer = TermItem("2025-2026", "2025-2026", "3", "夏季")
        val selectionAutumn = TermItem("2026-2027", "2026-2027", "1", "秋季").apply {
            isCurrent = true
        }

        val result = TermUtils.filterTermsForStudent(
            listOf(currentSummer, selectionAutumn),
            grade = "2024级",
            currentYear = 2026,
            currentMonth = Calendar.JULY
        )

        assertEquals(listOf("2026-2027-1", "2025-2026-3"), result.map { it.id })
    }

    @Test
    fun `stale default selection must not hide spring in a later academic year`() {
        val selectedOldSummer = TermItem("2024-2025", "2024-2025", "3", "夏季").apply {
            isCurrent = true
        }
        val spring = TermItem("2025-2026", "2025-2026", "2", "春季")
        val futureAutumn = TermItem("2026-2027", "2026-2027", "1", "秋季")

        val result = TermUtils.filterTermsForStudent(
            listOf(selectedOldSummer, spring, futureAutumn),
            grade = "2024级",
            currentYear = 2026,
            currentMonth = Calendar.MARCH
        )

        assertEquals(listOf(spring.id, selectedOldSummer.id), result.map { it.id })
    }

    @Test
    fun `conflicting page selections use the latest marked academic year regardless of order`() {
        val oldSelection = TermItem("2024-2025", "2024-2025", "3", "夏季").apply {
            isCurrent = true
        }
        val spring = TermItem("2025-2026", "2025-2026", "2", "春季")
        val newlyOpenedAutumn = TermItem("2026-2027", "2026-2027", "1", "秋季").apply {
            isCurrent = true
        }

        for (terms in listOf(
            listOf(oldSelection, spring, newlyOpenedAutumn),
            listOf(newlyOpenedAutumn, spring, oldSelection)
        )) {
            val result = TermUtils.filterTermsForStudent(
                terms,
                grade = "2024级",
                currentYear = 2026,
                currentMonth = Calendar.JULY
            )

            assertEquals(listOf(newlyOpenedAutumn.id, spring.id, oldSelection.id), result.map { it.id })
        }
    }

    @Test
    fun `student filter retains spring in the current calendar academic year`() {
        val selectedOldAutumn = TermItem("2024-2025", "2024-2025", "1", "秋季").apply {
            isCurrent = true
        }
        val currentSpring = TermItem("2026-2027", "2026-2027", "2", "春季")
        val beforeEnrollment = TermItem("2023-2024", "2023-2024", "2", "春季")

        val result = TermUtils.filterTermsForStudent(
            listOf(selectedOldAutumn, currentSpring, beforeEnrollment),
            grade = "2024级",
            currentYear = 2026,
            currentMonth = Calendar.OCTOBER
        )

        assertEquals(listOf(currentSpring.id, selectedOldAutumn.id), result.map { it.id })
    }

    @Test
    fun `timetable import keeps future terms exposed by academic system`() {
        val current = TermItem("2025-2026", "2025-2026", "3", "夏季").apply {
            isCurrent = true
        }
        val future = TermItem("2026-2027", "2026-2027", "1", "秋季")
        val old = TermItem("2021-2022", "2021-2022", "1", "秋季")

        val result = TermUtils.filterTermsForTimetableImport(
            listOf(current, old, future),
            grade = "2022级"
        )

        assertEquals(listOf("2026-2027-1", "2025-2026-3"), result.map { it.id })
    }

    @Test
    fun `derives enrollment grade from student id`() {
        assertEquals("2025级", TermUtils.enrollmentGradeFromStudentId("2025123456", currentYear = 2026))
        assertEquals("2025级", TermUtils.enrollmentGradeFromStudentId(" 2025 123456 ", currentYear = 2026))
        assertNull(TermUtils.enrollmentGradeFromStudentId(null, currentYear = 2026))
        assertNull(TermUtils.enrollmentGradeFromStudentId("", currentYear = 2026))
        assertNull(TermUtils.enrollmentGradeFromStudentId("abcd1234", currentYear = 2026))
        assertNull(TermUtils.enrollmentGradeFromStudentId("1999123456", currentYear = 2026))
    }

    @Test
    fun `derived grade keeps out of enrollment terms hidden`() {
        val autumn2026 = TermItem("2026-2027", "2026-2027", "1", "秋季")
        val spring2025 = TermItem("2025-2026", "2025-2026", "2", "春季")
        val beforeEnrollment = TermItem("2024-2025", "2024-2025", "1", "秋季")

        val grade = TermUtils.enrollmentGradeFromStudentId("2025123456", currentYear = 2026)
        val result = TermUtils.filterTermsForStudent(
            listOf(autumn2026, spring2025, beforeEnrollment),
            grade = grade,
            currentYear = 2026,
            currentMonth = Calendar.OCTOBER
        )

        assertEquals(listOf("2026-2027-1", "2025-2026-2"), result.map { it.id })
    }
}
