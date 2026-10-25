package cn.limpu.hita.ui.eas.classroom.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import cn.limpu.hita.R
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.utils.TimeTools

class EmptyClassroomDetailFragment(
    private val term: TermItem,
    private val week: Int,
    private val classroom: ClassroomItem,
    private val scheduleStructure: List<TimePeriodInDay>
) : BottomSheetDialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, com.limpu.style.R.style.TransparentBottomSheetDialogTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                HitaComposeTheme() {
                    EmptyClassroomDetailSheet(
                        term = term,
                        week = week,
                        classroom = classroom,
                        scheduleStructure = scheduleStructure
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyClassroomDetailSheet(
    term: TermItem,
    week: Int,
    classroom: ClassroomItem,
    scheduleStructure: List<TimePeriodInDay>
) {
    val tokens = HitaTheme.tokens
    val context = LocalContext.current
    val dows = stringArrayResource(R.array.dow2).toList()
    var selectedDow by remember {
        mutableIntStateOf((TimeTools.getDow(System.currentTimeMillis()) - 1).coerceIn(0, 6))
    }
    val rows = remember(selectedDow, classroom, scheduleStructure) {
        buildRows(context, selectedDow + 1, classroom, scheduleStructure)
    }
    val termName = term.termName.trim().ifBlank { term.name }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = tokens.spacing.xl,
                        top = tokens.spacing.xl,
                        end = tokens.spacing.xl
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.empty_classroom_format, classroom.name),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 24.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                ) {
                    Text(
                        text = "$termName${stringResource(R.string.week_title, week)}",
                        modifier = Modifier.padding(
                            horizontal = tokens.spacing.sm,
                            vertical = tokens.spacing.xs
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = tokens.spacing.lg,
                        start = tokens.spacing.lg,
                        end = tokens.spacing.lg
                    ),
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
            ) {
                dows.forEachIndexed { index, label ->
                    DowTab(
                        text = label,
                        selected = selectedDow == index,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedDow = index }
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(top = tokens.spacing.lg),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = tokens.spacing.lg)
            ) {
                items(rows) { row ->
                    EmptyClassroomDetailRow(row)
                }
            }
            Spacer(modifier = Modifier.height(tokens.spacing.xl))
        }
    }
}

@Composable
private fun DowTab(
    text: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(32.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        }
    ) {
        BoxCenterText(
            text = text,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BoxCenterText(text: String, color: Color) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = color, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun EmptyClassroomDetailRow(row: ClassroomScheduleRow) {
    val tokens = HitaTheme.tokens
    val isFree = row.state.contains("空")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = tokens.spacing.lg,
                vertical = tokens.spacing.sm
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = row.number,
            modifier = Modifier.weight(0.9f),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            maxLines = 1
        )
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.weight(1.4f)
        ) {
            Text(
                text = row.time,
                modifier = Modifier.padding(
                    horizontal = tokens.spacing.sm,
                    vertical = tokens.spacing.xs
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                maxLines = 1
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Surface(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = if (isFree) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.outlineVariant
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(36.dp)
            ) {
                Text(
                    text = row.state,
                    color = if (isFree) MaterialTheme.colorScheme.primary else Color.Gray,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private data class ClassroomScheduleRow(
    val time: String,
    val number: String,
    val state: String
)

private fun buildRows(
    context: android.content.Context,
    dow: Int,
    classroom: ClassroomItem,
    scheduleStructure: List<TimePeriodInDay>
): List<ClassroomScheduleRow> {
    return scheduleStructure.mapIndexed { index, timePeriod ->
        val number = index + 1
        var state = "空"
        for (item in classroom.scheduleList) {
            if (
                item.optString("XQJ") == dow.toString() &&
                item.optString("XJ") == number.toString()
            ) {
                val jy = item.optString("JYBJ")
                val pk = item.optString("PKBJ")
                state = when {
                    jy.isNotBlank() && jy != "null" -> jy
                    pk.isNotBlank() && pk != "null" -> pk
                    else -> state
                }
                break
            }
        }
        ClassroomScheduleRow(
            time = "${timePeriod.from} - ${timePeriod.to}",
            number = context.getString(R.string.number_schedule, number),
            state = state
        )
    }
}
