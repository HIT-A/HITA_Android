package cn.limpu.hita.ui.main.timetable.compare

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableSocialSheet(onDismiss: () -> Unit, onShare: () -> Unit, onImport: () -> Unit,
    onFriends: () -> Unit, onCompare: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.timetable_social_title), style = MaterialTheme.typography.titleLarge)
            listOf(R.string.timetable_social_share to onShare, R.string.timetable_social_import to onImport,
                R.string.friend_timetable_title to onFriends, R.string.comparison_config_title to onCompare).forEach { (title, action) ->
                TextButton(onClick = action, modifier = Modifier.fillMaxWidth()) { Text(stringResource(title)) }
            }
        }
    }
}
