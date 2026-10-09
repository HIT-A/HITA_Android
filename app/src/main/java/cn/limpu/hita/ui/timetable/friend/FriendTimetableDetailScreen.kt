package cn.limpu.hita.ui.timetable.friend

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.*
import cn.limpu.hita.feature.timetableshare.protocol.ShareMode
import cn.limpu.hita.feature.timetableshare.protocol.SharedOccurrence
import cn.limpu.hita.ui.main.timetable.ReadOnlyTimetableWeek
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
fun FriendTimetableDetailScreen(state: FriendDetailState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.friend_timetable_back)) }
        when (state) {
            FriendDetailState.Loading -> CircularProgressIndicator(Modifier.padding(24.dp))
            FriendDetailState.Deleted -> Text(stringResource(R.string.friend_timetable_deleted), Modifier.padding(16.dp))
            is FriendDetailState.Error -> Text(stringResource(state.resource), Modifier.padding(16.dp))
            is FriendDetailState.Ready -> FriendWeekContent(state)
        }
    }
}

@Composable
private fun FriendWeekContent(state: FriendDetailState.Ready) {
    val snapshot = state.snapshot
    var monday by rememberSaveable(snapshot.shareId, snapshot.term.startMillis, snapshot.term.endMillis) {
        mutableLongStateOf(FriendWeekProjection.initialMonday(snapshot, System.currentTimeMillis()))
    }
    var selected by remember(snapshot) { mutableStateOf<SharedOccurrence?>(null) }
    val busyLabel = stringResource(R.string.friend_timetable_busy)
    val events = remember(snapshot, monday, busyLabel) { FriendWeekProjection.events(snapshot, monday, busyLabel) }
    val periods = remember(snapshot.periods) { snapshot.periods.map {
        TimePeriodInDay(TimeInDay(it.startMinute / 60, it.startMinute % 60), TimeInDay(it.endMinute / 60, it.endMinute % 60))
    } }
    Text(state.row.remark?.takeIf { it.isNotBlank() } ?: snapshot.nickname,
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.friend_timetable_week_title, snapshot.term.termName, FriendWeekProjection.weekNumber(snapshot, monday)),
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { monday = FriendWeekProjection.moveWeek(snapshot, monday, -1) },
            enabled = monday > FriendWeekProjection.firstMonday(snapshot)) { Text(stringResource(R.string.friend_timetable_previous_week)) }
        TextButton(onClick = { monday = FriendWeekProjection.moveWeek(snapshot, monday, 1) },
            enabled = monday < FriendWeekProjection.moveWeek(snapshot, monday, Int.MAX_VALUE)) { Text(stringResource(R.string.friend_timetable_next_week)) }
    }
    if (events.isEmpty()) Text(stringResource(R.string.friend_timetable_empty_week), Modifier.padding(16.dp))
    ReadOnlyTimetableWeek(
        startDate = monday, events = events, scheduleStructure = periods,
        onPreviousWeek = { monday = FriendWeekProjection.moveWeek(snapshot, monday, -1) },
        onNextWeek = { monday = FriendWeekProjection.moveWeek(snapshot, monday, 1) },
        onEventClick = { selected = FriendWeekProjection.originalOccurrence(snapshot, it) },
        displayTimeZone = FriendWeekProjection.ZONE, expandEventHourRange = true,
    )
    selected?.let { occurrence ->
        val full = snapshot.mode == ShareMode.FULL
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val from = formatter.format(Instant.ofEpochMilli(occurrence.startMillis).atZone(FriendWeekProjection.ZONE))
        val to = formatter.format(Instant.ofEpochMilli(occurrence.endMillis).atZone(FriendWeekProjection.ZONE))
        AlertDialog(onDismissRequest = { selected = null },
            title = { Text(if (full) snapshot.courses[requireNotNull(occurrence.courseIndex)] else busyLabel) },
            text = { Column {
                Text(stringResource(R.string.friend_timetable_interval, from, to))
                if (full && !occurrence.place.isNullOrBlank()) Text(occurrence.place)
            } },
            confirmButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.friend_timetable_close)) } })
    }
}
