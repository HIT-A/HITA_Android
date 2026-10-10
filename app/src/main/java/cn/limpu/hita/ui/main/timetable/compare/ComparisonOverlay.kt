package cn.limpu.hita.ui.main.timetable.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.limpu.hita.R
import cn.limpu.hita.feature.timetablecompare.*

/** One dated column, with coordinates shared with the ordinary course renderer. */
@Composable
fun ComparisonOverlay(day: ComparisonDay, startHour: Int, dpPerMinute: Dp,
    showFree: Boolean, showConflicts: Boolean, onSelect: (TimeSpan, Boolean) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        visibleComparisonSlots(day,showFree,showConflicts).forEach { slot ->
            val conflict = slot.status == PeriodStatus.CONFLICT
            val pos = position(slot.span,day.date,startHour)
            val color = if (conflict) Color(0xFFD33131) else Color(0xFF23774D)
            val label = stringResource(if (conflict) R.string.comparison_conflict_short else R.string.comparison_free_short)
            val description = stringResource(R.string.comparison_overlay_description,day.date.toString(),slot.index+1,label)
            Box(Modifier.offset(y=(pos.topMinute*dpPerMinute.value).dp)
                .fillMaxWidth().height((pos.durationMinute*dpPerMinute.value).dp)
                .then(if (conflict) Modifier.border(2.dp,color)
                    else Modifier.semantics { contentDescription = description }.background(color.copy(alpha=0.16f)).clickable { onSelect(slot.span,false) })) {
                // Only the badge handles red-layer taps; course bodies remain clickable below it.
                Text(label,color=color,fontSize=9.sp,maxLines=1,
                    modifier=Modifier.align(if (conflict) Alignment.BottomEnd else Alignment.TopStart)
                        .background(Color.White.copy(alpha=0.90f))
                        .then(if (conflict) Modifier.semantics { contentDescription = description }.clickable { onSelect(slot.span,true) } else Modifier)
                        .padding(horizontal=2.dp))
            }
        }
    }
}
