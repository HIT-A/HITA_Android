package cn.limpu.hita.ui.main.timetable.compare

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.feature.timetablecompare.*
import cn.limpu.hita.feature.timetableshare.protocol.*
import java.time.*
import java.sql.Timestamp
import org.junit.Assert.*
import org.junit.Test

class ComparisonProjectionTest {
    private val date = LocalDate.of(2026, 10, 5)
    private fun at(day: LocalDate = date, minute: Int) = day.atStartOfDay(CAMPUS_ZONE).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
    private fun span(a: Int, b: Int) = TimeSpan(at(minute=a), at(minute=b))
    @Test fun fullPositionsKeepRestGapsAndMidnight() {
        assertEquals(ComparisonSegmentPosition(30.0,105.0), position(span(510,615),date,8))
        assertEquals(ComparisonSegmentPosition(150.0,105.0), position(span(630,735),date,8))
        assertEquals(ComparisonSegmentPosition(360.0,105.0), position(span(840,945),date,8))
        assertEquals(ComparisonSegmentPosition(900.0,60.0), position(span(1380,1440),date,8))
    }
    @Test fun unknownAndSingleBusyHaveNoOverlay() {
        val day=ComparisonDay(date, PeriodStatus.entries.mapIndexed { i,s -> ComparedPeriod(i,span(i*100,i*100+60),s) })
        assertEquals(listOf(PeriodStatus.FREE,PeriodStatus.CONFLICT), visibleComparisonSlots(day,true,true).map { it.status })
        assertTrue(visibleComparisonSlots(day,false,false).isEmpty())
    }
    @Test fun bridgePreservesDisplayedMondayDateAcrossZones() {
        val ny=ZoneId.of("America/New_York")
        val monday=date.atStartOfDay(ny).toInstant().toEpochMilli()
        assertEquals(at(minute=0), comparisonMonday(monday,ny))
        assertEquals(monday, ordinaryMonday(at(minute=0),ny))
        val sundayNight=date.minusDays(1).atTime(23,59).atZone(ny).toInstant().toEpochMilli()
        assertEquals(at(date.minusDays(7),0),comparisonMonday(sundayNight,ny))
    }
    @Test fun selectedRawCoursesUseActualWeekAndSplitMidnightWithoutMutation() {
        fun event(id: String, start: Long, end: Long)=EventItem().apply { this.id=id; timetableId="own"; from=Timestamp(start); to=Timestamp(end); name=id }
        val crossing=event("cross",at(date.minusDays(1),1380),at(minute=30))
        val odd=event("odd",at(date.plusDays(7),510),at(date.plusDays(7),615))
        val split=event("split",at(minute=1380),at(date.plusDays(1),60))
        val result=projectComparisonCourses(listOf(crossing,odd,split),at(minute=0))
        assertEquals(3,result.size)
        assertEquals(listOf("cross","split","split"),result.map { it.original.id })
        assertEquals(3,result.map { it.display.id }.distinct().size)
        assertEquals(at(minute=0),result.first().display.from.time)
        assertEquals(at(date.minusDays(1),1380),crossing.from.time)
        assertEquals(result.map { it.display.id },projectComparisonCourses(listOf(crossing,odd,split),at(minute=0)).map { it.display.id })
        assertTrue(result.all { it.display.color != 0 })
    }
    @Test fun fullDetailsMatchWholeSlotEvenWithoutActualPairOverlapAndBusyNeverReadsDictionary() {
        val own=EventItem().apply { name="own"; from=Timestamp(at(minute=510)); to=Timestamp(at(minute=540)) }
        val poison=object: AbstractList<String>() { override val size:Int get()=error("dictionary accessed"); override fun get(index:Int):String=error("dictionary accessed") }
        fun snapshot(mode: ShareMode, names: List<String>)=SharedTimetable("id","friend",mode,SharedTerm("","",at(minute=0),at(date.plusDays(7),0)),emptyList(),names,listOf(SharedOccurrence(at(minute=570),at(minute=600),0,"private place")))
        val full=comparisonSlotDetails(span(510,615),listOf(own),snapshot(ShareMode.FULL,listOf("friend course")))
        assertEquals("own",full.own.single().name)
        assertEquals("friend course",full.friend.single().name)
        val busy=comparisonSlotDetails(span(510,615),listOf(own),snapshot(ShareMode.BUSY,poison))
        assertNull(busy.friend.single().name)
        assertNull(busy.friend.single().place)
    }
    @Test fun noticeUsesOnlyVisibleCoursesAndDetectsRestGapPortions() {
        fun event(a: Int,b: Int)=EventItem().apply { from=Timestamp(at(minute=a)); to=Timestamp(at(minute=b)) }
        val structure=listOf(ComparisonPeriod(510,615),ComparisonPeriod(630,735))
        assertFalse(hasOutOfStructureCourses(projectComparisonCourses(listOf(event(510,615)),at(minute=0)),structure))
        assertTrue(hasOutOfStructureCourses(projectComparisonCourses(listOf(event(600,640)),at(minute=0)),structure))
        assertTrue(hasOutOfStructureCourses(projectComparisonCourses(listOf(event(1380,1440)),at(minute=0)),structure))
        val later=event(510,615).apply { from=Timestamp(at(date.plusWeeks(1),510)); to=Timestamp(at(date.plusWeeks(1),615)) }
        assertFalse(hasOutOfStructureCourses(projectComparisonCourses(listOf(later),at(minute=0)),emptyList()))
    }
    @Test fun projectionExcludesActivitiesAndExams() {
        val exam=EventItem().apply { type=EventItem.TYPE.EXAM; from=Timestamp(at(minute=510)); to=Timestamp(at(minute=615)) }
        val activity=EventItem().apply { type=EventItem.TYPE.OTHER; from=Timestamp(at(minute=510)); to=Timestamp(at(minute=615)) }
        assertTrue(projectComparisonCourses(listOf(exam,activity),at(minute=0)).isEmpty())
    }
    @Test fun offRestoresOriginalWhileLoadingHidesIt() {
        val original=listOf("aggregate","activity")
        assertSame(original,comparisonDisplayEvents(ComparisonUiState.Off,original) { listOf("selected") })
        assertTrue(comparisonDisplayEvents(ComparisonUiState.Loading,original) { listOf("selected") }.isEmpty())
    }
}
