package cn.limpu.hita.ui.event.add

import cn.limpu.hita.data.model.timetable.CourseTime
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import com.limpu.component.data.DataState
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.ScheduleEventCreator
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.utils.ColorTools
import dagger.hilt.android.lifecycle.HiltViewModel
import java.sql.Timestamp
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class AddEventViewModel @Inject constructor(
    private val eventRepo: TimetableRepository,
) : ViewModel() {
    enum class ContentMode {
        EXISTING,
        CUSTOM
    }

    enum class DateMode {
        SINGLE,
        WEEKLY
    }

    enum class TimeMode {
        CLOCK,
        PERIOD
    }

    val contentModeLiveData = MutableLiveData(ContentMode.CUSTOM)
    val dateModeLiveData = MutableLiveData(DateMode.SINGLE)
    val timeModeLiveData = MutableLiveData(TimeMode.PERIOD)
    val timetableLiveData = MutableLiveData<DataState<Timetable>>()
    val selectedEventLiveData = MutableLiveData<DataState<EventItem>>()
    val timeRangeLiveDate = MediatorLiveData<DataState<CourseTime>>()
    val customDateLiveData = MutableLiveData<DataState<Long>>()
    val customTimePeriodLiveData = MutableLiveData<DataState<TimePeriodInDay>>()
    val customFromToLiveData = MediatorLiveData<DataState<Pair<Long, Long>>>()
    val nameLiveData = MediatorLiveData<String?>()

    val locationLiveData = MediatorLiveData<DataState<String>>()
    val teacherLiveData = MediatorLiveData<DataState<String>>()

    val doneLiveData = MediatorLiveData<Boolean>()

    var addSubject: Boolean = false
    private var editingEvent: EventItem? = null

    init {
        doneLiveData.addSource(contentModeLiveData) { checkDone() }
        doneLiveData.addSource(dateModeLiveData) { checkDone() }
        doneLiveData.addSource(timeModeLiveData) { checkDone() }
        doneLiveData.addSource(selectedEventLiveData) { checkDone() }
        doneLiveData.addSource(nameLiveData) { checkDone() }
        doneLiveData.addSource(timetableLiveData) { checkDone() }
        doneLiveData.addSource(timeRangeLiveDate) { checkDone() }
        doneLiveData.addSource(customFromToLiveData) { checkDone() }

        timeRangeLiveDate.addSource(timetableLiveData) {
            if (it.state == DataState.STATE.SUCCESS) {
                if (initCourseT != null) {
                    timeRangeLiveDate.value = DataState(initCourseT!!)
                    initCourseT = null
                } else if (timeRangeLiveDate.value?.state != DataState.STATE.SUCCESS) {
                    // Only reset to NOTHING if user hasn't made a manual selection yet.
                    // Prevents async timetable load (Path B) from overwriting a time
                    // selection already made via mergeCourseTimeSelection.
                    timeRangeLiveDate.value = DataState(DataState.STATE.NOTHING)
                }
            } else {
                timeRangeLiveDate.value = DataState(DataState.STATE.FETCH_FAILED)
            }
        }

        customDateLiveData.value = DataState(DataState.STATE.NOTHING)
        customTimePeriodLiveData.value = DataState(DataState.STATE.NOTHING)
        selectedEventLiveData.value = DataState(DataState.STATE.NOTHING)

        customFromToLiveData.addSource(customDateLiveData) {
            refreshCustomFromTo()
        }
        customFromToLiveData.addSource(customTimePeriodLiveData) {
            refreshCustomFromTo()
        }
    }

    fun setContentMode(mode: ContentMode) {
        if (contentModeLiveData.value == mode) return
        contentModeLiveData.value = mode
        if (mode == ContentMode.CUSTOM) {
            selectedEventLiveData.value = DataState(DataState.STATE.NOTHING)
            nameLiveData.value = ""
            locationLiveData.value = DataState(DataState.STATE.NOTHING)
            teacherLiveData.value = DataState(DataState.STATE.NOTHING)
        }
        checkDone()
    }

    fun setDateMode(mode: DateMode) {
        if (dateModeLiveData.value == mode) return
        dateModeLiveData.value = mode
        checkDone()
    }

    fun setTimeMode(mode: TimeMode) {
        if (timeModeLiveData.value == mode) return
        timeModeLiveData.value = mode
        checkDone()
    }

    fun mergeCourseTimeSelection(selection: CourseTime, dateOnly: Boolean) {
        val current = timeRangeLiveDate.value?.data
        val merged = CourseTime().apply {
            if (dateOnly) {
                dow = selection.dow
                weeks = selection.weeks
                period = current?.period ?: TimePeriodInDay(TimeInDay(0, 0), TimeInDay(0, 0))
            } else {
                dow = current?.dow ?: selection.dow
                weeks = current?.weeks ?: selection.weeks
                period = selection.period
            }
        }
        timeRangeLiveDate.value = DataState(merged)
    }

    fun selectExistingEvent(event: EventItem) {
        contentModeLiveData.value = ContentMode.EXISTING
        selectedEventLiveData.value = DataState(event)
        nameLiveData.value = event.name
        if (!event.place.isNullOrBlank()) {
            locationLiveData.value = DataState(event.place!!)
        }
        if (!event.teacher.isNullOrBlank()) {
            teacherLiveData.value = DataState(event.teacher!!)
        }
        checkDone()
    }

    private fun refreshCustomFromTo() {
        val date = customDateLiveData.value
        val period = customTimePeriodLiveData.value
        if (date?.state == DataState.STATE.SUCCESS && period?.state == DataState.STATE.SUCCESS) {
            val cFrom = Calendar.getInstance()
            cFrom.timeInMillis = date.data ?: 0L
            cFrom.set(Calendar.HOUR_OF_DAY, period.data?.from?.hour ?: 0)
            cFrom.set(Calendar.MINUTE, period.data?.from?.minute ?: 0)
            cFrom.set(Calendar.SECOND, 0)
            cFrom.set(Calendar.MILLISECOND, 0)

            val cTo = Calendar.getInstance()
            cTo.timeInMillis = date.data ?: 0L
            cTo.set(Calendar.HOUR_OF_DAY, period.data?.to?.hour ?: 0)
            cTo.set(Calendar.MINUTE, period.data?.to?.minute ?: 0)
            cTo.set(Calendar.SECOND, 0)
            cTo.set(Calendar.MILLISECOND, 0)

            if (cTo.timeInMillis > cFrom.timeInMillis) {
                customFromToLiveData.value = DataState(Pair(cFrom.timeInMillis, cTo.timeInMillis))
            } else {
                customFromToLiveData.value = DataState(DataState.STATE.NOTHING)
            }
        } else if (date?.state == DataState.STATE.FETCH_FAILED || period?.state == DataState.STATE.FETCH_FAILED) {
            customFromToLiveData.value = DataState(DataState.STATE.FETCH_FAILED)
        } else {
            customFromToLiveData.value = DataState(DataState.STATE.NOTHING)
        }
    }

    private fun checkDone() {
        val contentReady = when (contentModeLiveData.value ?: ContentMode.CUSTOM) {
            ContentMode.EXISTING -> selectedEventLiveData.value?.state == DataState.STATE.SUCCESS
            ContentMode.CUSTOM -> !nameLiveData.value.isNullOrBlank()
        }
        val dateReady = when (dateModeLiveData.value ?: DateMode.SINGLE) {
            DateMode.SINGLE -> customDateLiveData.value?.state == DataState.STATE.SUCCESS
            DateMode.WEEKLY -> timeRangeLiveDate.value?.state == DataState.STATE.SUCCESS &&
                !timeRangeLiveDate.value?.data?.weeks.isNullOrEmpty()
        }
        val timeReady = when (timeModeLiveData.value ?: TimeMode.PERIOD) {
            TimeMode.CLOCK -> customTimePeriodLiveData.value?.state == DataState.STATE.SUCCESS
            TimeMode.PERIOD -> timeRangeLiveDate.value?.state == DataState.STATE.SUCCESS &&
                isValidPeriod(timeRangeLiveDate.value?.data?.period)
        }
        doneLiveData.value = timetableLiveData.value?.state == DataState.STATE.SUCCESS &&
            contentReady &&
            dateReady &&
            timeReady
    }

    var initSubject: TermSubject? = null
    var initCourseT: CourseTime? = null
    fun init(
        addSubject: Boolean,
        timetable: Timetable?,
        subject: TermSubject?,
        courseTime: CourseTime?,
        eventToEdit: EventItem? = null,
    ) {
        editingEvent = eventToEdit
        initCourseT = courseTime
        initSubject = subject
        this.addSubject = addSubject

        if (timetable == null) {
            val observer = object : Observer<List<Timetable>> {
                override fun onChanged(value: List<Timetable>) {
                    val t = value
                    // Prefer EAS timetable (with non-null code) covering the current date,
                    // matching the logic used by TimetableFragment.getCurrentTimetableAndWeek().
                    val now = System.currentTimeMillis()
                    val easTimetables = t.filter { !it.code.isNullOrEmpty() }
                    val best = if (easTimetables.isNotEmpty()) {
                        easTimetables.minByOrNull { it.getWeekNumber(now).takeIf { w -> w > 0 } ?: Int.MAX_VALUE }
                    } else {
                        t.firstOrNull()
                    }
                    if (best != null) {
                        timetableLiveData.postValue(DataState(best))
                    } else {
                        timetableLiveData.postValue(DataState(DataState.STATE.NOTHING))
                    }
                    eventRepo.getTimetables().removeObserver(this)
                }
            }
            eventRepo.getTimetables().observeForever(observer)
        } else {
            timetableLiveData.value = DataState(timetable)
            courseTime?.let {
                val from = timetable.getTimestamps(it.weeks.firstOrNull() ?: 1, it.dow, it.period).firstOrNull()
                from?.let { ms ->
                    customDateLiveData.value = DataState(startOfDay(ms))
                }
            }
        }

        if (courseTime == null) {
            customTimePeriodLiveData.value = DataState(DataState.STATE.NOTHING)
        } else {
            customTimePeriodLiveData.value = DataState(courseTime.period.clone())
        }
        subject?.let {
            setContentMode(ContentMode.CUSTOM)
            nameLiveData.value = it.name
            if (!it.teacher.isNullOrBlank()) {
                teacherLiveData.value = DataState(it.teacher!!)
            }
        }
        eventToEdit?.let {
            setContentMode(ContentMode.CUSTOM)
            dateModeLiveData.value = DateMode.SINGLE
            timeModeLiveData.value = if (it.fromNumber > 0 && it.lastNumber > 0) {
                TimeMode.PERIOD
            } else {
                TimeMode.CLOCK
            }
            nameLiveData.value = it.name
            customDateLiveData.value = DataState(startOfDay(it.from.time))
            customTimePeriodLiveData.value = DataState(TimePeriodInDay(TimeInDay(it.from), TimeInDay(it.to)))
            if (!it.place.isNullOrBlank()) {
                locationLiveData.value = DataState(it.place!!)
            }
            if (!it.teacher.isNullOrBlank()) {
                teacherLiveData.value = DataState(it.teacher!!)
            }
            checkDone()
        }
    }

    fun setCustomDate(dateMs: Long) {
        customDateLiveData.value = DataState(dateMs)
    }

    fun setCustomTimePeriod(from: TimeInDay, to: TimeInDay) {
        customTimePeriodLiveData.value = DataState(TimePeriodInDay(from, to))
    }

    fun createEvent() {
        timetableLiveData.value?.data?.let { timetable ->
            if (editingEvent != null) {
                updateEditingEvent(timetable)
                return
            }
            val subjectToSave: TermSubject?
            val selectedEvent = selectedEventLiveData.value?.data
            val weekly = dateModeLiveData.value == DateMode.WEEKLY
            val content = if (contentModeLiveData.value == ContentMode.EXISTING && selectedEvent != null) {
                subjectToSave = if (weekly && selectedEvent.subjectId.isBlank()) {
                    newManualSubject(timetable, selectedEvent.name)
                } else {
                    null
                }
                ScheduleEventCreator.Content(
                    name = selectedEvent.name.trim(),
                    place = locationLiveData.value?.data ?: selectedEvent.place.orEmpty(),
                    teacher = teacherLiveData.value?.data ?: selectedEvent.teacher.orEmpty(),
                    subjectId = subjectToSave?.id ?: selectedEvent.subjectId,
                    type = if (subjectToSave != null) EventItem.TYPE.CLASS else selectedEvent.type,
                )
            } else if (addSubject) {
                val subject = newManualSubject(timetable, nameLiveData.value.orEmpty())
                subjectToSave = subject
                ScheduleEventCreator.Content(
                    name = subject.name,
                    place = locationLiveData.value?.data ?: "",
                    teacher = teacherLiveData.value?.data ?: "",
                    subject = subject,
                    type = EventItem.TYPE.CLASS,
                )
            } else if (initSubject != null) {
                val subject = initSubject ?: return
                subjectToSave = null
                ScheduleEventCreator.Content(
                    name = subject.name,
                    place = locationLiveData.value?.data ?: "",
                    teacher = teacherLiveData.value?.data ?: "",
                    subjectId = subject.id,
                    type = EventItem.TYPE.CLASS,
                )
            } else if (weekly) {
                val subject = newManualSubject(timetable, nameLiveData.value.orEmpty())
                subjectToSave = subject
                ScheduleEventCreator.Content(
                    name = subject.name,
                    place = locationLiveData.value?.data ?: "",
                    teacher = teacherLiveData.value?.data ?: "",
                    subject = subject,
                )
            } else {
                subjectToSave = null
                ScheduleEventCreator.Content(
                    name = nameLiveData.value?.trim().orEmpty(),
                    place = locationLiveData.value?.data ?: "",
                    teacher = teacherLiveData.value?.data ?: "",
                    type = EventItem.TYPE.OTHER,
                )
            }
            val result = buildResult(timetable, content) ?: return
            ScheduleEventCreator.persistAsync(result, eventRepo, subjectToSave)
        }
    }

    private fun newManualSubject(timetable: Timetable, name: String): TermSubject =
        TermSubject().apply {
            this.name = name.trim()
            timetableId = timetable.id
            color = ColorTools.colorForName(this.name)
        }

    private fun updateEditingEvent(timetable: Timetable) {
        val original = editingEvent ?: return
        val dateMode = dateModeLiveData.value ?: DateMode.SINGLE
        val timeMode = timeModeLiveData.value ?: TimeMode.PERIOD
        val ranges = buildFixedRanges(timetable, dateMode, timeMode) ?: return
        val range = ranges.firstOrNull() ?: return
        val edited = original.apply {
            name = nameLiveData.value?.trim().orEmpty()
            place = locationLiveData.value?.data ?: ""
            teacher = teacherLiveData.value?.data ?: ""
            timetableId = timetable.id
            from = Timestamp(range.fromMs)
            to = Timestamp(range.toMs)
            fromNumber = range.fromNumber
            lastNumber = range.lastNumber
        }
        eventRepo.actionUpdateEvent(edited)
    }

    private fun buildResult(
        timetable: Timetable,
        content: ScheduleEventCreator.Content,
    ): ScheduleEventCreator.Result? {
        val dateMode = dateModeLiveData.value ?: DateMode.SINGLE
        val timeMode = timeModeLiveData.value ?: TimeMode.PERIOD
        return when {
            dateMode == DateMode.WEEKLY && timeMode == TimeMode.PERIOD -> {
                val courseTime = timeRangeLiveDate.value?.data ?: return null
                ScheduleEventCreator.buildEvents(
                    timetable = timetable,
                    content = content,
                    courseTime = courseTime,
                    source = EventItem.SOURCE_MANUAL,
                )
            }

            else -> {
                val ranges = buildFixedRanges(timetable, dateMode, timeMode) ?: return null
                ScheduleEventCreator.buildEvents(
                    timetable = timetable,
                    content = content,
                    ranges = ranges,
                    source = EventItem.SOURCE_MANUAL,
                )
            }
        }
    }

    private fun buildFixedRanges(
        timetable: Timetable,
        dateMode: DateMode,
        timeMode: TimeMode,
    ): List<ScheduleEventCreator.FixedRange>? {
        val period = when (timeMode) {
            TimeMode.CLOCK -> customTimePeriodLiveData.value?.data ?: return null
            TimeMode.PERIOD -> timeRangeLiveDate.value?.data?.period ?: return null
        }
        val courseNumbers = if (timeMode == TimeMode.PERIOD) {
            timetable.transformCourseNumber(period)
        } else {
            0 to 0
        }
        return when (dateMode) {
            DateMode.SINGLE -> {
                val date = customDateLiveData.value?.data ?: return null
                listOf(buildRange(date, period, courseNumbers))
            }

            DateMode.WEEKLY -> {
                val courseTime = timeRangeLiveDate.value?.data ?: return null
                courseTime.weeks.map { week ->
                    val startOfDay = timetable.startTime.time +
                        (week - 1).toLong() * 7L * 24L * 60L * 60L * 1000L +
                        (courseTime.dow - 1).toLong() * 24L * 60L * 60L * 1000L
                    buildRange(startOfDay, period, courseNumbers)
                }
            }
        }
    }

    private fun buildRange(
        startOfDayMs: Long,
        period: TimePeriodInDay,
        courseNumbers: Pair<Int, Int>,
    ): ScheduleEventCreator.FixedRange {
        val fromNumber = courseNumbers.first
        val toNumber = courseNumbers.second
        return ScheduleEventCreator.FixedRange(
            fromMs = startOfDayMs + period.from.toMills(),
            toMs = startOfDayMs + period.to.toMills(),
            fromNumber = fromNumber,
            lastNumber = if (fromNumber > 0 && toNumber >= fromNumber) toNumber - fromNumber + 1 else 0,
        )
    }

    private fun startOfDay(ms: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun isValidPeriod(period: TimePeriodInDay?): Boolean {
        return period != null && period.to.toMills() > period.from.toMills()
    }
}
