package cn.limpu.hita.ui.main.timetable.compare

import cn.limpu.hita.feature.timetablecompare.ComparisonPeriod
import org.junit.Assert.*
import org.junit.Test

class ComparisonConfigTest {
    private val periods = listOf(ComparisonPeriod(510,615), ComparisonPeriod(630,735))
    @Test fun defaultRangeAndSwitches() {
        val config = ComparisonConfig("own", "friend")
        assertTrue(config.isValid()); assertTrue(config.showFree); assertTrue(config.showConflicts)
        assertNull(comparisonConfigError(config, periods, periods))
    }
    @Test fun invalidRangesAndIds() {
        listOf(ComparisonConfig("", "f"), ComparisonConfig("o", ""),
            ComparisonConfig("o","f",510,510), ComparisonConfig("o","f",600,510),
            ComparisonConfig("o","f",-1,1350), ComparisonConfig("o","f",510,1441))
            .forEach { assertFalse(it.isValid()) }
    }
    @Test fun completeStructureMustMatch() {
        assertEquals(ConfigProblem.INCOMPATIBLE, comparisonConfigError(ComparisonConfig("o","f"), periods,
            listOf(ComparisonPeriod(510,610), ComparisonPeriod(630,735))))
        assertEquals(ConfigProblem.INVALID_STRUCTURE, comparisonConfigError(ComparisonConfig("o","f"), emptyList(),periods))
    }
    @Test fun gapAndPartialPeriodCannotConfirm() {
        assertEquals(ConfigProblem.NO_COMPLETE_PERIOD, comparisonConfigError(ComparisonConfig("o","f",615,630),periods,periods))
        assertEquals(ConfigProblem.NO_COMPLETE_PERIOD, comparisonConfigError(ComparisonConfig("o","f",520,610),periods,periods))
        assertNull(comparisonConfigError(ComparisonConfig("o","f",510,615),periods,periods))
    }
    @Test fun sameIdentityStructureChangesAreNotSuppressed() {
        val table = cn.limpu.hita.data.model.timetable.Timetable().apply {
            id = "own"; name = "term"; code = "term"
            startTime = java.sql.Timestamp(1); endTime = java.sql.Timestamp(100)
            scheduleStructure = listOf(cn.limpu.hita.data.model.timetable.TimePeriodInDay(
                cn.limpu.hita.data.model.timetable.TimeInDay(8,30), cn.limpu.hita.data.model.timetable.TimeInDay(10,15)))
        }
        val before = comparisonPersonalChoice(table)
        table.scheduleStructure = listOf(cn.limpu.hita.data.model.timetable.TimePeriodInDay(
            cn.limpu.hita.data.model.timetable.TimeInDay(8,30), cn.limpu.hita.data.model.timetable.TimeInDay(10,0)))
        val after = comparisonPersonalChoice(table)
        assertNotEquals(before, after)
        assertEquals(ConfigProblem.INCOMPATIBLE, comparisonConfigError(ComparisonConfig("own","friend"), after.periods, before.periods))
        table.startTime = java.sql.Timestamp(2)
        assertNotEquals(after, comparisonPersonalChoice(table))
    }
    @Test fun malformedRawTimeCannotNormalizeIntoValidCandidate() {
        val table = cn.limpu.hita.data.model.timetable.Timetable().apply {
            startTime = java.sql.Timestamp(1); endTime = java.sql.Timestamp(100)
            scheduleStructure = listOf(cn.limpu.hita.data.model.timetable.TimePeriodInDay(
                cn.limpu.hita.data.model.timetable.TimeInDay(8,30), cn.limpu.hita.data.model.timetable.TimeInDay(10,15)))
        }
        // The period constructor normalizes its input; mutate afterward to exercise raw validation.
        table.scheduleStructure.single().from.minute = 60
        assertFalse(comparisonPersonalChoice(table).valid)
    }
    @Test fun timeParsingIsStrict() {
        assertEquals(510, parseComparisonTime("08:30")); assertEquals(1440, parseComparisonTime("24:00"))
        listOf("8:3", "24:01", "25:00", "12:60", "abc").forEach { assertNull(parseComparisonTime(it)) }
    }
}
