package cn.limpu.hita.ui.event.add

import android.app.Application
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.TimetableRepository
import com.limpu.component.data.DataState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.sql.Timestamp

@RunWith(AndroidJUnit4::class)
class ManualCourseCreationInstrumentedTest {
    @Test fun weeklyManualEventsShareOneSubject() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val db = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        try {
            val timetable = Timetable().apply {
                name = "Test"
                startTime = Timestamp(0)
                endTime = Timestamp(10_000_000_000L)
            }
            db.timetableDao().saveTimetableSync(timetable)
            val viewModel = AddEventViewModel(TimetableRepository(application, db))
            val courseTime = CourseTime().apply {
                dow = 1
                weeks = listOf(1, 2)
                period = TimePeriodInDay(TimeInDay(18, 45), TimeInDay(20, 30))
            }
            instrumentation.runOnMainSync {
                viewModel.init(false, timetable, null, null)
                viewModel.setDateMode(AddEventViewModel.DateMode.WEEKLY)
                viewModel.nameLiveData.value = "自习"
                viewModel.timeRangeLiveDate.value = DataState(courseTime)
                viewModel.createEvent()
            }

            val subjects = awaitSubjects(db)
            assertEquals(1, subjects.size)
            val events = db.eventItemDao().getClassesOfSubjectSync(subjects.single().id)
            assertEquals(2, events.size)
            assertTrue(events.all { it.type == EventItem.TYPE.CLASS })
            assertTrue(events.all { it.subjectId == subjects.single().id })
        } finally {
            db.close()
        }
    }

    private fun awaitSubjects(db: AppDatabase): List<cn.limpu.hita.data.model.timetable.TermSubject> {
        repeat(100) {
            db.subjectDao().getAllSubjectsSync().takeIf { it.isNotEmpty() }?.let { return it }
            Thread.sleep(50)
        }
        return db.subjectDao().getAllSubjectsSync()
    }
}
