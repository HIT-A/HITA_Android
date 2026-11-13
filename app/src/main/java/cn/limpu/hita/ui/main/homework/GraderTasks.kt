package cn.limpu.hita.ui.main.homework

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.data.model.grader.GraderTask
import cn.limpu.hita.data.repository.grader.GraderError
import cn.limpu.hita.data.repository.grader.GraderState

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GraderConnectionCard(
    state: GraderState,
    onLogin: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    empty: Boolean,
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.grader_title), style = MaterialTheme.typography.titleMedium)
            if (state.accountId == null) {
                Text(stringResource(R.string.grader_login_hint), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(R.string.grader_connected, state.accountName.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.grader_local_completion_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.syncing || !state.ready) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.error?.let { error ->
                Text(stringResource(when (error) {
                    GraderError.AUTHENTICATION -> R.string.grader_authentication_error
                    GraderError.NETWORK -> R.string.grader_network_error
                    GraderError.STORAGE -> R.string.grader_storage_error
                    GraderError.INVALID_RESPONSE -> R.string.grader_response_error
                }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (state.ready && state.accountId != null && empty && state.error == null && !state.syncing) {
                Text(stringResource(R.string.grader_empty), style = MaterialTheme.typography.bodyMedium)
            }
            // 刷新失败（网络/响应异常）时也给一个就地重新登录入口：
            // 会话可能已经失效，但用户不该被弹窗打断，只是多一个可选按钮。
            val failedRefresh = state.accountId != null && !state.needsLogin && state.error != null
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.accountId == null || state.needsLogin) {
                    TextButton(onClick = onLogin, enabled = !state.syncing) {
                        Text(stringResource(if (state.accountId == null) R.string.grader_login_title else R.string.grader_relogin))
                    }
                }
                if (state.accountId != null) {
                    if (!state.needsLogin) {
                        TextButton(onClick = onRefresh, enabled = !state.syncing) {
                            Text(stringResource(R.string.grader_refresh))
                        }
                    }
                    if (failedRefresh) {
                        TextButton(onClick = onLogin, enabled = !state.syncing) {
                            Text(stringResource(R.string.grader_relogin))
                        }
                    }
                    TextButton(onClick = onDisconnect, enabled = !state.syncing) {
                        Text(stringResource(R.string.grader_disconnect))
                    }
                } else if (state.error == GraderError.STORAGE) {
                    TextButton(onClick = onRefresh, enabled = !state.syncing) {
                        Text(stringResource(R.string.homework_retry))
                    }
                }
            }
        }
    }
}

@Composable
internal fun GraderTaskRow(task: GraderTask, busy: Boolean, onComplete: (Boolean) -> Unit) {
    val deadline = remember(task.dueAt) { formatGraderDeadline(task.dueAt) }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = task.completed, onCheckedChange = onComplete, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = task.title })
            Column(Modifier.weight(1f).padding(vertical = 12.dp, horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(task.title,
                    textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None)
                if (task.courseTitle.isNotBlank()) {
                    Text(task.courseTitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.grader_deadline, deadline), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
