package cn.limpu.hita.feature.timetablecompare

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TimetableComparisonEngineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 10, 5)
    private val periods = listOf(
        ComparisonPeriod(510, 615), ComparisonPeriod(630, 735),
        ComparisonPeriod(840, 945), ComparisonPeriod(960, 1065),
        ComparisonPeriod(1125, 1230), ComparisonPeriod(1245, 1350),
    )
    private fun at(hour: Int, minute: Int, day: Int = 0): Long =
        date.plusDays(day.toLong()).atStartOfDay(zone).plusHours(hour.toLong())
            .plusMinutes(minute.toLong()).toInstant().toEpochMilli()
    private fun span(sh: Int, sm: Int, eh: Int, em: Int) = TimeSpan(at(sh, sm), at(eh, em))
    private fun schedule(occupied: List<TimeSpan> = emptyList()) =
        ComparisonSchedule(TimeSpan(at(0, 0), at(0, 0, 1)), periods, occupied)
    private fun compare(own: ComparisonSchedule = schedule(), friend: ComparisonSchedule = schedule(),
        request: ComparisonRequest = ComparisonRequest(at(0, 0))) =
        TimetableComparisonEngine().compare(own, friend, request)

    @Test fun wholePeriodsWithoutRestGaps() {
        val day = compare(schedule(listOf(span(9, 0, 10, 0))),
            schedule(listOf(span(9, 30, 10, 30)))).days.first()
        assertEquals(listOf(span(8, 30, 10, 15)), day.conflicts)
        assertEquals(listOf(span(10, 30, 12, 15), span(14, 0, 15, 45),
            span(16, 0, 17, 45), span(18, 45, 20, 30), span(20, 45, 22, 30)), day.free)
    }

    @Test fun emptyCoursesGiveSixSeparateFreePeriods() {
        val result = compare()
        assertEquals(7, result.days.size)
        assertEquals(date.plusDays(6), result.days.last().date)
        assertEquals(listOf(span(8,30,10,15),span(10,30,12,15),span(14,0,15,45),
            span(16,0,17,45),span(18,45,20,30),span(20,45,22,30)), result.days.first().free)
        assertTrue(result.days.drop(1).all { it.slots.all { slot -> slot.status == PeriodStatus.UNKNOWN } })
    }

    @Test fun endpointTouchDoesNotOccupyPreviousPeriod() {
        assertEquals(PeriodStatus.FREE, compare(schedule(listOf(span(10,15,10,30)))).days.first().slots.first().status)
    }

    @Test fun disjointCoursesWithinSamePeriodConflict() {
        assertEquals(listOf(span(8,30,10,15)), compare(schedule(listOf(span(8,30,9,0))),
            schedule(listOf(span(9,30,10,0)))).days.first().conflicts)
    }

    @Test fun tenSecondsOccupyWholePeriod() {
        val own = schedule(listOf(TimeSpan(at(9,0), at(9,0) + 10000)))
        assertEquals(PeriodStatus.OWN_ONLY, compare(own).days.first().slots.first().status)
        assertEquals(PeriodStatus.FRIEND_ONLY, compare(friend = own).days.first().slots.first().status)
    }

    @Test fun rangeFiltersWholePeriodsWithoutClippingOrRenumbering() {
        val day = compare(request = ComparisonRequest(at(0,0), 540, 1350)).days.first()
        assertEquals(5, day.slots.size)
        assertEquals(1, day.slots.first().index)
        assertEquals(span(10,30,12,15), day.slots.first().span)
        assertTrue(compare(request = ComparisonRequest(at(0,0), 616, 629)).days.all { it.slots.isEmpty() })
    }

    @Test fun partialValidityMakesWholePeriodUnknown() {
        val own = schedule().copy(valid = TimeSpan(at(0,0), at(12,0)))
        val day = compare(own).days.first()
        assertEquals(PeriodStatus.FREE, day.slots[0].status)
        assertEquals(PeriodStatus.UNKNOWN, day.slots[1].status)
        assertEquals(listOf(span(8,30,10,15)), day.windows)
    }

    @Test fun noCommonValidityHasNoFreeOrConflicts() {
        val result = compare(friend = schedule().copy(valid = TimeSpan(at(0,0,1), at(0,0,2))))
        assertTrue(result.days.all { it.free.isEmpty() && it.conflicts.isEmpty() && it.windows.isEmpty() })
    }

    @Test fun crossingMidnightOccupiesNextDayAndActualDate() {
        val own = schedule(listOf(TimeSpan(at(23,0), at(9,0,1))))
            .copy(valid = TimeSpan(at(0,0), at(0,0,7)))
        val friend = schedule().copy(valid = TimeSpan(at(0,0), at(0,0,7)))
        val result = compare(own, friend)
        assertEquals(PeriodStatus.FREE, result.days[0].slots[0].status)
        assertEquals(PeriodStatus.OWN_ONLY, result.days[1].slots[0].status)
        assertEquals(PeriodStatus.FREE, result.days[2].slots[0].status)
    }

    @Test fun duplicateAndNestedCoursesCountOneConflict() {
        val courses = listOf(span(8,30,10,15), span(8,30,10,15), span(9,0,9,30))
        assertEquals(listOf(span(8,30,10,15)), compare(schedule(courses), schedule(courses)).days.first().conflicts)
    }

    @Test fun outsideStructureCoursesCreateNoSlots() {
        val day = compare(schedule(listOf(span(12,15,14,0), span(7,0,8,0)))).days.first()
        assertEquals(6, day.free.size)
        assertTrue(day.conflicts.isEmpty())
    }

    @Test fun completeStructureMustMatchEvenOutsideSelectedRange() {
        val different = listOf(periods.mapIndexed { i, p -> if (i == 5) p.copy(startMinute = 1246) else p },
            periods.dropLast(1), listOf(ComparisonPeriod(510,555), ComparisonPeriod(555,615)) + periods.drop(1))
        different.forEach {
            assertFalse(PeriodStructure.isCompatible(periods, it))
            assertThrows(IncompatiblePeriodsException::class.java) {
                compare(friend = schedule().copy(periods = it), request = ComparisonRequest(at(0,0),510,615))
            }
        }
        assertTrue(PeriodStructure.isCompatible(periods, periods.toList()))
    }

    @Test fun invalidStructuresAreRejected() {
        val invalid = listOf(emptyList(), periods.reversed(), listOf(ComparisonPeriod(510,630), ComparisonPeriod(615,735)),
            listOf(ComparisonPeriod(-1,10)), listOf(ComparisonPeriod(0,1441)),
            listOf(ComparisonPeriod(510,510)), listOf(ComparisonPeriod(600,500)))
        invalid.forEach {
            assertFalse(PeriodStructure.isValid(it))
            assertFalse(PeriodStructure.isCompatible(it, it))
            assertThrows(IllegalArgumentException::class.java) { compare(schedule().copy(periods = it)) }
        }
        assertTrue(PeriodStructure.isValid(listOf(ComparisonPeriod(0,720),ComparisonPeriod(720,1440))))
    }

    @Test fun malformedRecordsRejectedEvenOutsideDisplayedWeek() {
        listOf(TimeSpan(at(0,0,20),at(0,0,20)), TimeSpan(at(0,0,21),at(0,0,20))).forEach {
            assertThrows(IllegalArgumentException::class.java) { compare(schedule(listOf(it))) }
            assertThrows(IllegalArgumentException::class.java) { compare(friend = schedule(listOf(it))) }
            assertThrows(IllegalArgumentException::class.java) { compare(schedule().copy(valid = it)) }
            assertThrows(IllegalArgumentException::class.java) { compare(friend = schedule().copy(valid = it)) }
        }
    }

    @Test fun requestsRequireCampusMondayMidnightAndValidSameDayRange() {
        listOf(ComparisonRequest(at(1,0)), ComparisonRequest(at(0,0,1)),
            ComparisonRequest(at(0,0),-1,1350), ComparisonRequest(at(0,0),510,1441),
            ComparisonRequest(at(0,0),510,510), ComparisonRequest(at(0,0),1350,510)).forEach {
            assertThrows(IllegalArgumentException::class.java) { compare(request = it) }
        }
    }

    @Test fun systemTimezoneDoesNotMoveCampusPeriods() {
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
            assertEquals(span(8,30,10,15), compare().days.first().slots.first().span)
        } finally { java.util.TimeZone.setDefault(previous) }
    }

    @Test fun validityStartAndEndAreExactHalfOpenBoundaries() {
        val day = compare(schedule().copy(valid = TimeSpan(at(8,30), at(10,15)))).days.first()
        assertEquals(listOf(span(8,30,10,15)),day.free)
        assertEquals(PeriodStatus.UNKNOWN, compare(schedule().copy(valid = TimeSpan(at(8,30)+1,at(10,15)))).days.first().slots[0].status)
    }
}
