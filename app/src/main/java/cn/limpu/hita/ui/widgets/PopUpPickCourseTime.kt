package cn.limpu.hita.ui.widgets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.data.model.timetable.CourseTime
import cn.limpu.hita.utils.TimeTools
import com.limpu.style.widgets.MWheel3DView
import java.util.Calendar

class PopUpPickCourseTime(val timetable: Timetable) : BottomSheetDialogFragment() {

    enum class Mode {
        DATE_AND_PERIOD,
        DATE_ONLY,
        PERIOD_ONLY,
    }

    var onTimeSelectedListener: OnTimeSelectedListener? = null
    var initTimetable: Timetable? = null
    var initCourseTime: CourseTime? = null
    private var mode: Mode = Mode.DATE_AND_PERIOD

    interface OnTimeSelectedListener {
        fun onSelected(data: CourseTime)
    }

    fun setMode(mode: Mode): PopUpPickCourseTime {
        this.mode = mode
        return this
    }

    fun setInitialValue(timetable: Timetable?, courseTime: CourseTime?): PopUpPickCourseTime {
        initTimetable = timetable
        initCourseTime = courseTime
        return this
    }

    fun setSelectListener(ls: OnTimeSelectedListener): PopUpPickCourseTime {
        onTimeSelectedListener = ls
        return this
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                HitaComposeTheme() {
                    PickCourseTimeScreen(
                        timetable = timetable,
                        initTimetable = initTimetable,
                        initCourseTime = initCourseTime,
                        mode = mode,
                        onCancel = { dismiss() },
                        onConfirm = { courseTime ->
                            if (mode != Mode.PERIOD_ONLY && courseTime.weeks.isEmpty()) {
                                Toast.makeText(context, R.string.ade_pick_weeks, Toast.LENGTH_SHORT).show()
                            } else {
                                onTimeSelectedListener?.onSelected(courseTime)
                                dismiss()
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickCourseTimeScreen(
    timetable: Timetable,
    initTimetable: Timetable?,
    initCourseTime: CourseTime?,
    mode: PopUpPickCourseTime.Mode,
    onCancel: () -> Unit,
    onConfirm: (CourseTime) -> Unit
) {
    val tokens = HitaTheme.tokens
    val context = LocalContext.current

    val dowEntries = remember {
        context.resources.getStringArray(R.array.dow2).toList()
    }
    val periodEntries = remember {
        val periodTemp = context.getString(R.string.period)
        (0 until timetable.scheduleStructure.size).map {
            String.format(periodTemp, it + 1)
        }
    }

    val initDow = initCourseTime?.dow ?: 1
    val initFromPeriod = initCourseTime?.let { timetable.transformCourseNumber(it.period).first } ?: 1
    val initToPeriod = initCourseTime?.let { timetable.transformCourseNumber(it.period).second } ?: 1

    var selectedDow by remember { mutableIntStateOf(initDow) }
    var selectedFromPeriod by remember { mutableIntStateOf(initFromPeriod) }
    var selectedToPeriod by remember { mutableIntStateOf(initToPeriod) }

    // Auto-correct: start must never exceed end
    LaunchedEffect(selectedFromPeriod) {
        if (selectedFromPeriod > selectedToPeriod) {
            selectedToPeriod = selectedFromPeriod
        }
    }
    LaunchedEffect(selectedToPeriod) {
        if (selectedToPeriod < selectedFromPeriod) {
            selectedFromPeriod = selectedToPeriod
        }
    }

    val weekCount = remember {
        initTimetable?.getWeekNumber(initTimetable.endTime.time) ?: 20
    }
    val selectedWeeks = remember {
        val weeks = mutableStateListOf<Boolean>()
        for (i in 0 until weekCount) weeks.add(false)
        initCourseTime?.weeks?.forEach { week ->
            if (week - 1 < weeks.size) weeks[week - 1] = true
        }
        weeks
    }
    var extraWeeks by remember { mutableIntStateOf(0) }

    val schedule = timetable.scheduleStructure
    val bottomTimeText by remember {
        derivedStateOf {
            val startIdx = selectedFromPeriod - 1
            val endIdx = selectedToPeriod - 1
            if (startIdx in schedule.indices && endIdx in schedule.indices) {
                val st = schedule[startIdx].from
                val et = schedule[endIdx].to
                "${String.format("%02d:%02d", st.hour, st.minute)} - ${String.format("%02d:%02d", et.hour, et.minute)}"
            } else {
                ""
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(tokens.spacing.lg)
    ) {
        // Title row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.dialog_pick_course_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Card(
                onClick = onCancel,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Text(
                    text = stringResource(R.string.cancel),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = tokens.spacing.md, vertical = tokens.spacing.xs)
                )
            }
            Spacer(modifier = Modifier.width(tokens.spacing.xs))
            Card(
                onClick = {
                    val r = CourseTime()
                    r.dow = selectedDow
                    r.period = timetable.transformTimePeriod(selectedFromPeriod, selectedToPeriod)
                    r.weeks = selectedWeeks.mapIndexedNotNull { index, selected ->
                        if (selected) index + 1 else null
                    }
                    onConfirm(r)
                },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Text(
                    text = stringResource(R.string.confirm),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = tokens.spacing.md, vertical = tokens.spacing.xs)
                )
            }
        }

        // Wheel pickers — consume remaining scroll to prevent BottomSheet intercept
        val pickerNestedScrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource
                ): Offset = available
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .nestedScroll(pickerNestedScrollConnection)
                .padding(top = tokens.spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (mode != PopUpPickCourseTime.Mode.PERIOD_ONLY) {
                WheelPicker(
                    entries = dowEntries,
                    initialIndex = selectedDow - 1,
                    modifier = Modifier.weight(1f),
                    onItemSelected = { selectedDow = it + 1 }
                )
            }
            if (mode != PopUpPickCourseTime.Mode.DATE_ONLY) {
                WheelPicker(
                    entries = periodEntries,
                    initialIndex = selectedFromPeriod - 1,
                    modifier = Modifier.weight(1f),
                    onItemSelected = {
                        selectedFromPeriod = it + 1
                        if (selectedToPeriod < selectedFromPeriod) {
                            selectedToPeriod = selectedFromPeriod
                        }
                    }
                )
                Text(
                    text = stringResource(R.string.dialog_pick_course_to),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = tokens.spacing.xs)
                )
                WheelPicker(
                    entries = periodEntries,
                    initialIndex = selectedToPeriod - 1,
                    modifier = Modifier.weight(1f),
                    onItemSelected = {
                        selectedToPeriod = it + 1
                        if (selectedFromPeriod > selectedToPeriod) {
                            selectedFromPeriod = selectedToPeriod
                        }
                    }
                )
            }
        }

        // Time preview
        if (mode != PopUpPickCourseTime.Mode.DATE_ONLY) {
            Text(
                text = bottomTimeText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = tokens.spacing.sm),
                textAlign = TextAlign.Center,
            )
        }

        // Week picker
        if (mode != PopUpPickCourseTime.Mode.PERIOD_ONLY) {
            Text(
                text = stringResource(R.string.ade_pick_weeks),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = tokens.spacing.lg)
            )

            val totalWeeks = weekCount + extraWeeks * 5
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = tokens.spacing.xs)
                    .height(200.dp)
            ) {
                items(totalWeeks + 1) { index ->
                    if (index < totalWeeks) {
                        val isSelected = index < selectedWeeks.size && selectedWeeks[index]
                        Surface(
                            shape = RoundedCornerShape(tokens.radius.sm),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            modifier = Modifier
                                .padding(2.dp)
                                .clickable {
                                    if (index < selectedWeeks.size) {
                                        selectedWeeks[index] = !selectedWeeks[index]
                                    }
                                }
                        ) {
                            Text(
                                text = "${index + 1}",
                                fontSize = 14.sp,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = tokens.spacing.xs)
                            )
                        }
                    } else {
                        // "+" button
                        Surface(
                            shape = RoundedCornerShape(tokens.radius.sm),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .padding(2.dp)
                                .clickable { extraWeeks++ }
                        ) {
                            Text(
                                text = "＋",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = tokens.spacing.xs)
                            )
                        }
                    }
                }
            }

            // Selected dates preview
            val selectedDates = remember(selectedWeeks, selectedDow, selectedFromPeriod, selectedToPeriod) {
                val dates = mutableListOf<String>()
                selectedWeeks.forEachIndexed { index, selected ->
                    if (selected) {
                        initTimetable?.getTimestamps(
                            index + 1,
                            selectedDow,
                            selectedFromPeriod,
                            selectedToPeriod
                        )?.firstOrNull()?.let { timestamp ->
                            val c = Calendar.getInstance()
                            c.timeInMillis = timestamp
                            dates.add(TimeTools.getDateString(context, c, true, TimeTools.TTY_NONE))
                        }
                    }
                }
                dates
            }

            if (selectedDates.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = tokens.spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(tokens.spacing.xs)
                ) {
                    selectedDates.forEach { date ->
                        Surface(
                            shape = RoundedCornerShape(tokens.radius.sm),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = date,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    horizontal = tokens.spacing.sm,
                                    vertical = tokens.spacing.xs
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WheelPicker(
    entries: List<String>,
    initialIndex: Int,
    modifier: Modifier = Modifier,
    onItemSelected: (Int) -> Unit
) {
    AndroidView(
        modifier = modifier.height(120.dp),
        factory = { context ->
            MWheel3DView(context).apply {
                setEntries(entries)
                currentIndex = initialIndex
                setOnWheelChangedListener { _, _, newIndex ->
                    onItemSelected(newIndex)
                }
            }
        },
        update = { view ->
            if (view.currentIndex != initialIndex) {
                view.currentIndex = initialIndex
            }
            view.setOnTouchListener { _, _ ->
                view.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    )
}
