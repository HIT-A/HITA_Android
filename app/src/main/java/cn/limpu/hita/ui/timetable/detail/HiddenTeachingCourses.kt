package cn.limpu.hita.ui.timetable.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.data.model.timetable.EventItem
import java.text.DateFormat
import java.util.Date

@Composable
internal fun HiddenTeachingCourses(events: List<EventItem>, onShow: (EventItem) -> Unit) {
    var open by remember { mutableStateOf(false) }
    if (events.isNotEmpty()) TextButton(onClick = { open = true }) {
        Text(stringResource(R.string.teaching_hidden_count, events.size))
    }
    if (open) AlertDialog(onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.teaching_hidden_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (events.isEmpty()) item { Text(stringResource(R.string.teaching_hidden_empty)) }
                items(events, key = { it.id }) { event ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(event.name)
                            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.from.time)),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onShow(event) }) { Text(stringResource(R.string.teaching_show)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.timetable_history_close)) } })
}
