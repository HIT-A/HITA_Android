package cn.limpu.hita.ui.timetable.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.limpu.hita.R
import cn.limpu.hita.feature.timetableshare.protocol.ShareMode
import cn.limpu.hita.utils.FileProviderUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 导出面板条目：组合课表整体或单份课表/好友课表。 */
data class TimetableExportItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val memberIds: Set<String> = setOf(key),
)

/**
 * 多选导出底部弹层：勾选课表后选择「课表口令」或「ICS 文件」。
 * 口令一份课表一个；ICS 一份课表一个文件。宿主负责生成与分享。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableExportSheet(
    items: List<TimetableExportItem>,
    initiallySelected: Set<String>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onExportToken: (List<TimetableExportItem>, ShareMode?) -> Unit,
    onExportIcs: (List<TimetableExportItem>) -> Unit,
    forwarding: Boolean = false,
) {
    var selected by remember(items) {
        mutableStateOf(items.map { it.key }.filter { it in initiallySelected }.toSet())
    }
    var confirmation by remember { mutableStateOf<ExportConfirmation?>(null) }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.timetable_export_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            Text(
                text = stringResource(R.string.timetable_export_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.timetable_export_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp)
                )
            } else {
                val allSelected = selected.size == items.size
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        selected = if (allSelected) emptySet() else items.map { it.key }.toSet()
                    }.padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = allSelected, onCheckedChange = null, enabled = !busy)
                    Text(
                        stringResource(R.string.timetable_export_select_all),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(items, key = { it.key }) { item ->
                        val checked = item.key in selected
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                selected = if (checked) selected - item.key else selected + item.key
                            }.padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null, enabled = !busy)
                            Column(Modifier.weight(1f)) {
                                Text(item.title, color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (item.subtitle.isNotBlank()) {
                                    Text(item.subtitle, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { confirmation = ExportConfirmation(items.filter { it.key in selected }, token = true) },
                    enabled = !busy && selected.isNotEmpty()
                ) { Text(stringResource(R.string.timetable_export_format_token)) }
                Button(
                    onClick = { confirmation = ExportConfirmation(items.filter { it.key in selected }, token = false) },
                    enabled = !busy && selected.isNotEmpty()
                ) { Text(stringResource(R.string.timetable_export_format_ics)) }
            }
        }
    }
    confirmation?.let { requested ->
        val unchanged = requested.items.all { it in items }
        var mode by remember(requested) { mutableStateOf(ShareMode.BUSY) }
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(R.string.timetable_audit_export_confirm)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text(stringResource(R.string.timetable_audit_export_scope, requested.items.sumOf { it.memberIds.size }))
                    }
                    items(requested.items, key = { it.key }) { item ->
                        Text("${item.title} · ${item.subtitle}")
                    }
                    if (requested.token && !forwarding) {
                        items(listOf(ShareMode.BUSY, ShareMode.FULL)) { option ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = mode == option, onClick = { mode = option })
                                Text(stringResource(if (option == ShareMode.BUSY) R.string.timetable_share_busy else R.string.timetable_share_full))
                            }
                        }
                    }
                    item {
                        Text(stringResource(when {
                            !requested.token -> R.string.timetable_audit_export_ics_fields
                            forwarding -> R.string.timetable_audit_export_forward_fields
                            mode == ShareMode.BUSY -> R.string.timetable_audit_export_busy_fields
                            else -> R.string.timetable_audit_export_full_fields
                        }))
                    }
                    if (!unchanged) item { Text(stringResource(R.string.timetable_audit_write_failed), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = unchanged && !busy, onClick = {
                    if (confirmation !== requested) return@Button
                    confirmation = null
                    if (requested.token) onExportToken(requested.items, if (forwarding) null else mode)
                    else onExportIcs(requested.items)
                }) { Text(stringResource(R.string.timetable_audit_export_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.button_cancel)) } },
        )
    }
}

private data class ExportConfirmation(val items: List<TimetableExportItem>, val token: Boolean)

/** 口令导出结果：逐条复制，或一键把全部口令拼成一段文本系统分享。 */
@Composable
fun TimetableExportTokensDialog(
    tokens: List<Pair<String, String>>,
    onCopy: (String) -> Unit,
    onShareAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timetable_export_tokens_title)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(tokens) { (name, token) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name.ifBlank { stringResource(R.string.timetable_share_action) },
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { onCopy(token) }) {
                                Text(stringResource(R.string.timetable_share_copy))
                            }
                        }
                        SelectionContainer {
                            Text(token, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onShareAll) { Text(stringResource(R.string.timetable_export_share_all)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.timetable_share_close)) } }
    )
}

/** 导出结果的系统分享/复制。多文件走 ACTION_SEND_MULTIPLE。 */
object TimetableExportSharer {
    fun shareIcsFiles(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { FileProviderUtils.getUriForFile(context, it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/calendar"
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/calendar"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.clipData = ClipData.newRawUri(context.getString(R.string.timetable_export_title), uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.timetable_export_title)))
    }

    suspend fun shareTokens(context: Context, tokens: List<Pair<String, String>>) {
        val (text, attachment) = withContext(Dispatchers.IO) {
            val inline = cn.limpu.hita.data.repository.TimetableTokenText.inlineText(tokens)
            inline to if (inline == null)
                cn.limpu.hita.data.repository.TimetableTokenText.write(context.cacheDir, tokens) else null
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            if (text != null) {
                putExtra(Intent.EXTRA_TEXT, text)
            } else {
                val uri = FileProviderUtils.getUriForFile(context, checkNotNull(attachment))
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(context.getString(R.string.timetable_export_title), uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.timetable_share_action)))
    }

    fun copyToken(context: Context, token: String) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.timetable_share_action), token))
        Toast.makeText(context, R.string.timetable_share_copied, Toast.LENGTH_SHORT).show()
    }
}

/** 导出面板入口图标按钮：与课表管理顶栏同一图标语义。 */
@Composable
fun TimetableExportAction(busy: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(36.dp).clickable(enabled = !busy, onClick = onClick)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_baseline_cloud_download_24),
            contentDescription = stringResource(R.string.export),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(8.dp)
        )
    }
}
