package cn.limpu.hita.ui.main.timeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodayScopePicker(state: TodayUiState, modifier: Modifier = Modifier, action: (TodayAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val name = state.scopeName ?: return
    val accessibilityLabel = stringResource(R.string.today_switch_scope, name)
    TextButton(onClick = { open = true }, shape = RoundedCornerShape(50),
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = accessibilityLabel },
        colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant), contentPadding = PaddingValues(horizontal = 12.dp)) {
        Text(name, style = todayMeta(), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        Icon(painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24), null, Modifier.padding(start = 4.dp).size(16.dp).rotate(90f))
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, sheetState = sheetState) {
        Text(stringResource(R.string.today_choose), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(state.scopeOptions, key = { "${cn.limpu.hita.data.model.timetable.DisplayScope.typeOf(it.scope)}:${it.scope.id}" }) { option ->
                Row(Modifier.fillMaxWidth().semantics { selected = option.selected }.clickable {
                    open = false
                    if (!option.selected) action(TodayAction.SelectScope(option.scope))
                }.heightIn(min = 64.dp).padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(option.name, style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (option.selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (option.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        if (option.memberCount > 1) Text(stringResource(R.string.timetable_group_member_count, option.memberCount),
                            style = todayLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (option.selected) Icon(painterResource(R.drawable.ic_baseline_check_24), null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            item("manage") {
                HorizontalDivider()
                TextButton(onClick = { open = false; action(TodayAction.SelectTimetable) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.title_timetable_manager)) }
            }
        }
    }
}
