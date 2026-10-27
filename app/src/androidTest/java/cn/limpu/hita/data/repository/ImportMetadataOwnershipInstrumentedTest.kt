package cn.limpu.hita.data.repository

import androidx.lifecycle.Observer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.repository.ImportMetadataHarness.Companion.date
import cn.limpu.hita.data.repository.ImportMetadataHarness.Companion.main
import cn.limpu.hita.data.repository.ImportMetadataHarness.Companion.next
import cn.limpu.hita.data.repository.ImportMetadataHarness.Companion.term
import cn.limpu.hita.data.model.timetable.Timetable
import com.limpu.component.data.DataState
import java.util.Calendar
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportMetadataOwnershipInstrumentedTest {
    @Test fun manualDateEditOutranksAnOutstandingRemoteDate() = ImportMetadataHarness().use { fixture ->
        val observer = Observer<DataState<Calendar>> {}
        main { fixture.model.startDateLiveData.observeForever(observer); fixture.model.changeSelectedTerm(term()) }
        try {
            val old = fixture.dates.next()
            main { fixture.model.changeStartDate(date(17)) }
            old.complete(date(3))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            main { assertEquals("Late remote date must not erase manual edit", date(17), fixture.model.startDateLiveData.value?.data) }
        } finally { main { fixture.model.startDateLiveData.removeObserver(observer) } }
    }

    @Test fun manualWeekShiftOutranksAnOutstandingRemoteDate() = ImportMetadataHarness().use { fixture ->
        val observer = Observer<DataState<Calendar>> {}
        main { fixture.model.startDateLiveData.observeForever(observer); fixture.model.changeSelectedTerm(term()) }
        try {
            val old = fixture.dates.next()
            main { fixture.model.changeStartDate(date(3)); fixture.model.shiftStartDateByWeek(1) }
            old.complete(date(24))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            main { assertEquals(date(10), fixture.model.startDateLiveData.value?.data) }
        } finally { main { fixture.model.startDateLiveData.removeObserver(observer) } }
    }

    @Test fun oldTermDateCannotOverwriteCurrentSelection() = ImportMetadataHarness().use { fixture ->
        val observer = Observer<DataState<Calendar>> {}
        main { fixture.model.startDateLiveData.observeForever(observer); fixture.model.changeSelectedTerm(term("1")) }
        try {
            val old = fixture.dates.next()
            main { fixture.model.changeSelectedTerm(term("2")) }
            val current = fixture.dates.next()
            current.complete(date(17)); old.complete(date(3))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            main { assertEquals(date(17), fixture.model.startDateLiveData.value?.data) }
        } finally { main { fixture.model.startDateLiveData.removeObserver(observer) } }
    }

    @Test fun savedBenbuCalibrationStillOverridesRemoteCalendar() = ImportMetadataHarness().use { fixture ->
        fixture.calibration.saveCalibration(term().getCode(), date(17).timeInMillis)
        val observer = Observer<DataState<Calendar>> {}
        main { fixture.model.startDateLiveData.observeForever(observer); fixture.model.changeSelectedTerm(term()) }
        try {
            fixture.dates.next().complete(date(3))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            main { assertEquals(date(17), fixture.model.startDateLiveData.value?.data); assertTrue(fixture.model.benbuCalibrationConfirmedLiveData.value == true) }
        } finally { main { fixture.model.startDateLiveData.removeObserver(observer) } }
    }

    @Test fun savedScheduleAvoidsRemoteAndRemainsEditable() = ImportMetadataHarness().use { fixture ->
        val original = Timetable().getDefaultTimeStructure()
        fixture.timetablePreferences.saveSchedules(original)
        val observer = Observer<DataState<MutableList<cn.limpu.hita.data.model.timetable.TimePeriodInDay>>> {}
        main { fixture.model.scheduleStructureLiveData.observeForever(observer); fixture.model.changeSelectedTerm(term()) }
        try {
            val edited = original.first().clone().apply { from.minute = (from.minute + 5) % 60 }
            main {
                assertEquals(original, fixture.model.scheduleStructureLiveData.value?.data)
                fixture.model.setStructureData(edited, 0)
                assertEquals(edited, fixture.model.scheduleStructureLiveData.value?.data?.first())
            }
            assertTrue("Saved preference must avoid unnecessary remote metadata", fixture.schedules.isEmpty())
            assertEquals(edited, fixture.timetablePreferences.getSchedule(true).first())
        } finally { main { fixture.model.scheduleStructureLiveData.removeObserver(observer) } }
    }
}
