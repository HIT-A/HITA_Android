package cn.limpu.hita.ui.event

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.TimetableChangeStore
import cn.limpu.hita.data.repository.TimetableMutationLock
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.data.source.preference.EasCredentialStore
import cn.limpu.hita.data.source.preference.TimetablePreferenceSource
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaCoursePaletteDialog
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.design.hitaCourseColor
import cn.limpu.hita.ui.subject.SubjectActivity
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.CourseResourceLinker
import cn.limpu.hita.utils.TimeTools
import com.limpu.style.ThemeTools
import dagger.hilt.android.AndroidEntryPoint
import java.util.Calendar

@AndroidEntryPoint
class EventItemFragment : Fragment() {

    protected val viewModel: EventItemViewModel by viewModels()

    interface EventParent {
        fun callDismiss()
    }

    var eventParent: EventParent? = null

    private val easRepository by lazy {
        val appContext = requireActivity().application.applicationContext
        val easPreferenceSource = EasPreferenceSource(appContext)
        EASRepository(
            requireActivity().application,
            easPreferenceSource,
            EasCredentialStore(appContext),
            TimetablePreferenceSource(appContext, easPreferenceSource),
            TimetableChangeStore(requireActivity().application),
            TimetableMutationLock()
        )
    }
    private val hoaCampus by lazy { easRepository.getHoaCampus() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                HitaComposeTheme() {
                    EventItemScreen(
                        viewModel = viewModel,
                        onSubjectClick = { event, subject ->
                            if (requireContext() !is SubjectActivity) {
                                ActivityUtils.startSubjectActivity(requireContext(), event.subjectId)
                            }
                        },
                        onSubjectLongClick = { subject ->
                            CourseResourceLinker.openReadme(
                                context = requireContext(),
                                owner = viewLifecycleOwner,
                                courseCodeRaw = subject.code,
                                courseNameRaw = subject.name,
                                campus = hoaCampus,
                            )
                        },
                        onColorPick = { _, color ->
                            viewModel.changeSubjectColor(color)
                        },
                        onDelete = {
                            viewModel.delete()
                            eventParent?.callDismiss()
                        },
                        onTeacherClick = { name ->
                            ActivityUtils.startCourseResourceSearchActivity(
                                requireContext(),
                                query = name,
                                mode = ActivityUtils.CourseResourceMode.VIEW,
                            )
                        },
                        onTeacherLongClick = { name ->
                            ActivityUtils.startTeacherHomepageSearch(requireContext(), name)
                        }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        arguments?.let {
            @Suppress("DEPRECATION")
            viewModel.eventItemLiveData.value = it.getSerializable("event") as? EventItem
        }
    }

    companion object {
        fun newInstance(eventItem: EventItem, parent: EventParent): EventItemFragment {
            val res = EventItemFragment()
            val b = Bundle()
            b.putSerializable("event", eventItem)
            res.arguments = b
            res.eventParent = parent
            return res
        }
    }
}

@Composable
private fun EventItemScreen(
    viewModel: EventItemViewModel,
    onSubjectClick: (EventItem, cn.limpu.hita.data.model.timetable.TermSubject) -> Unit,
    onSubjectLongClick: (cn.limpu.hita.data.model.timetable.TermSubject) -> Unit,
    onColorPick: (cn.limpu.hita.data.model.timetable.TermSubject, Int) -> Unit,
    onDelete: () -> Unit,
    onTeacherClick: (String) -> Unit,
    onTeacherLongClick: (String) -> Unit
) {
    val tokens = HitaTheme.tokens
    val context = LocalContext.current
    val eventItem by viewModel.eventItemLiveData.observeAsState()
    val subject by viewModel.subjectLiveData.observeAsState()
    val progress by viewModel.progressLiveData.observeAsState()

    val event = eventItem ?: return
    val canEditCourseColor = HitaTheme.preferenceStyle == ThemeTools.STYLE.CLASSIC
    val courseColor = hitaCourseColor(
        courseKey = event.subjectId.ifBlank { event.name },
        storedColor = subject?.color ?: event.color,
    )
    val courseLike = event.type == EventItem.TYPE.CLASS || event.type == EventItem.TYPE.EXAM
    val teachers = splitTeachers(event.teacher)
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCourseColorPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = tokens.spacing.sm)
    ) {
        // Name row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = courseLike) {
                    if (courseLike) onSubjectClick(event, subject ?: return@clickable)
                }
                .padding(
                    horizontal = tokens.spacing.lg,
                    vertical = tokens.spacing.sm
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = event.name ?: stringResource(R.string.none),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (courseLike) {
                Text(
                    text = stringResource(R.string.dialog_view_course_detail),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            val subj = subject ?: return@clickable
                            onSubjectClick(event, subj)
                        }
                        .padding(tokens.spacing.xs)
                )
            }
        }

        // Teacher section
        if (courseLike) {
            DetailRow(
                iconRes = R.drawable.ic_bo_teacher,
                label = stringResource(R.string.dialog_teacher)
            ) {
                if (teachers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.none),
                        fontSize = 19.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                } else {
                    teachers.forEach { name ->
                        TeacherRow(
                            name = name,
                            clickable = true,
                            onClick = { onTeacherClick(name) },
                            onLongClick = { onTeacherLongClick(name) }
                        )
                    }
                }
            }
        }

        // Location
        DetailRow(
            iconRes = R.drawable.ic_bo_location,
            label = if (courseLike) stringResource(R.string.dialog_classroom) else stringResource(R.string.exam_location)
        ) {
            Text(
                text = event.place ?: stringResource(R.string.none),
                fontSize = 19.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Time
        DetailRow(
            iconRes = R.drawable.ic_bo_time,
            label = stringResource(R.string.dialog_time)
        ) {
            Text(
                text = stringResource(
                    R.string.event_duration_text,
                    TimeInDay(event.from).toString(),
                    TimeInDay(event.to).toString()
                ),
                fontSize = 19.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Period/Number
        val numberStr = (event.fromNumber until event.fromNumber + event.lastNumber)
            .joinToString(separator = ", ")
        if (numberStr.isNotEmpty()) {
            DetailRow(
                iconRes = R.drawable.ic_bo_time,
                label = stringResource(R.string.dialog_period)
            ) {
                Text(
                    text = numberStr,
                    fontSize = 19.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Date
        DetailRow(
            iconRes = R.drawable.ic_bo_date,
            label = stringResource(R.string.dialog_date)
        ) {
            val c = Calendar.getInstance()
            c.timeInMillis = event.from.time
            Text(
                text = TimeTools.getDateString(context, c, false, TimeTools.TTY_FOLLOWING),
                fontSize = 19.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Progress
        if (courseLike) {
            val progressValue = progress?.let { (current, total) ->
                if (total > 0) ((current + 1).toFloat() / total.toFloat()) else 0f
            } ?: 0f
            val progressText = progress?.let { (current, _) ->
                stringResource(R.string.dialog_this_course_p, current + 1)
            } ?: ""

            DetailRow(
                iconRes = R.drawable.ic_bo_distribution,
                label = stringResource(R.string.dialog_this_course_s)
            ) {
                Text(
                    text = progressText,
                    fontSize = 19.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                LinearProgressIndicator(
                    progress = { progressValue },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = tokens.spacing.xs)
                        .height(10.dp),
                    color = courseColor,
                    trackColor = courseColor.copy(alpha = 0.18f),
                )
            }
        }

        // Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = tokens.spacing.lg,
                    end = tokens.spacing.lg,
                    top = tokens.spacing.sm,
                    bottom = tokens.spacing.sm
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.weight(1f))
            if (courseLike && canEditCourseColor) {
                Button(
                    onClick = {
                        if (subject != null) showCourseColorPicker = true
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        text = stringResource(R.string.course_change_color),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Spacer(modifier = Modifier.width(tokens.spacing.sm))
            }
            IconButton(
                onClick = { showDeleteDialog = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_delete_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    val colorSubject = subject
    if (showCourseColorPicker && colorSubject != null) {
        HitaCoursePaletteDialog(
            selectedColor = courseColor,
            onSelected = { color ->
                onColorPick(colorSubject, color.toArgb())
                showCourseColorPicker = false
            },
            onDismiss = { showCourseColorPicker = false },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.dialog_title_sure_delete)) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                Button(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun DetailRow(
    iconRes: Int,
    label: String,
    content: @Composable () -> Unit
) {
    val tokens = HitaTheme.tokens

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = tokens.spacing.lg,
                vertical = tokens.spacing.sm
            ),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier
                .size(20.dp)
                .padding(top = 2.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = tokens.spacing.sm)
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

@Composable
private fun TeacherRow(
    name: String,
    clickable: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val tokens = HitaTheme.tokens

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = clickable) { onClick() }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            fontSize = 19.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        if (clickable) {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_search_24),
                contentDescription = null,
                modifier = Modifier
                    .padding(start = tokens.spacing.xs)
                    .size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun splitTeachers(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(Regex("[,，、]"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
