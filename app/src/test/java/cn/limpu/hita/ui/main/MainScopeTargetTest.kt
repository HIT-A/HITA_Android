package cn.limpu.hita.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class MainScopeTargetTest {
    private val start = 1_700_000_000_000L
    private val week = 604_800_000L

    @Test fun termStartAndEndAreBothInclusive() {
        val end = start + 2 * week
        assertEquals(start, mainScopeTargetMonday(start, end, start))
        assertEquals(end, mainScopeTargetMonday(start, end, end))
        assertEquals(start + week, mainScopeTargetMonday(start, end, end - 1))
    }

    @Test fun datesOutsideTermReturnItsFirstWeek() {
        val end = start + 3 * week
        assertEquals(start, mainScopeTargetMonday(start, end, start - 1))
        assertEquals(start, mainScopeTargetMonday(start, end, end + 1))
    }

    @Test fun targetIsAnOffsetFromScopeStartWithoutCalendarRounding() {
        val now = start + week + 123_456L
        assertEquals(start + week, mainScopeTargetMonday(start, start + 5 * week, now))
        assertEquals(start, mainScopeTargetMonday(start, start + 5 * week, start + week - 1))
    }

    @Test fun emptyOrSingleInstantTermKeepsItsStart() {
        assertEquals(start, mainScopeTargetMonday(start, start - 1, start + week))
        assertEquals(start, mainScopeTargetMonday(start, start, start))
    }
}
