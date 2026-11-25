package cn.limpu.hita.ui.main.homework

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cn.limpu.hita.BuildConfig
import cn.limpu.hita.R
import cn.limpu.hita.data.repository.grader.GraderRepository
import cn.limpu.hita.data.repository.grader.GraderSubmitError
import cn.limpu.hita.data.source.web.grader.GraderAssignmentDetail
import cn.limpu.hita.data.source.web.grader.GraderSubmissionPolicy
import cn.limpu.hita.data.source.web.grader.GraderUploadFile
import cn.limpu.hita.ui.design.HitaComposeTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 从今日作业列表进入的提交页：展示作业要求、选文件、上传。
 *
 * 真正的校验与上传都在仓储层；这里只负责展示、本地预检提示与进度。
 */
@AndroidEntryPoint
class GraderSubmitActivity : AppCompatActivity() {
    @Inject lateinit var repository: GraderRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val assignmentId = intent.getStringExtra(EXTRA_ASSIGNMENT_ID)
        if (assignmentId.isNullOrBlank()) {
            finish()
            return
        }
        val fallbackTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val course = intent.getStringExtra(EXTRA_COURSE).orEmpty()
        val dueAt = intent.getLongExtra(EXTRA_DUE_AT, 0L).takeIf { it > 0L }
        val resubmit = intent.getBooleanExtra(EXTRA_RESUBMIT, false)
        title = getString(if (resubmit) R.string.grader_submit_resubmit_action else R.string.grader_submit_action)
        setContent {
            HitaComposeTheme {
                SubmitContent(assignmentId, fallbackTitle, course, dueAt, resubmit)
            }
        }
    }

    @Composable
    private fun SubmitContent(
        assignmentId: String,
        fallbackTitle: String,
        course: String,
        dueAt: Long?,
        resubmit: Boolean,
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
        var detail by remember { mutableStateOf<GraderAssignmentDetail?>(null) }
        var loading by remember { mutableStateOf(true) }
        var picked by remember { mutableStateOf<PickedFile?>(null) }
        var submitting by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf(0f) }
        var message by remember { mutableStateOf<Int?>(null) }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val meta = readFileMetadata(context, uri)
            if (meta == null) {
                message = R.string.grader_submit_error_read
                return@rememberLauncherForActivityResult
            }
            picked = PickedFile(uri, meta.first, meta.second)
            message = null
        }

        LaunchedEffect(assignmentId) {
            detail = repository.submissionDetail(assignmentId, fallbackTitle)
            loading = false
        }

        val pickedFile = picked
        val violation = if (detail != null && pickedFile != null) {
            GraderSubmissionPolicy.violation(detail!!, pickedFile.name, pickedFile.size)
        } else {
            null
        }
        val closed = detail?.submitAllowed == false
        val canSubmit = !submitting && pickedFile != null && violation == null && !closed && !loading

        fun submit() {
            val file = pickedFile ?: return
            submitting = true
            progress = 0f
            message = null
            scope.launch {
                val upload = GraderUploadFile(file.name, file.size) {
                    context.contentResolver.openInputStream(file.uri) ?: throw IOException("unreadable")
                }
                val error = repository.submit(assignmentId, upload) { uploaded, total ->
                    if (total > 0) mainExecutor.execute { progress = uploaded.toFloat() / total }
                }
                submitting = false
                if (error == null) {
                    trace("submit ok: ${file.name} (${file.size} bytes)")
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    progress = 0f
                    message = error.messageRes()
                    trace("submit failed: $error | ${repository.lastSubmitDiagnostic.orEmpty()}")
                }
            }
        }

        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    detail?.title?.ifBlank { fallbackTitle } ?: fallbackTitle,
                    style = MaterialTheme.typography.titleLarge,
                )
                if (course.isNotBlank()) {
                    Text(course, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                dueAt?.let {
                    Text(
                        stringResource(
                            R.string.homework_imported_deadline,
                            DEADLINE_FORMAT.format(Instant.ofEpochMilli(it)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (loading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.grader_submit_loading), style = MaterialTheme.typography.bodySmall)
                        } else {
                            val description = detail?.description
                            Text(stringResource(R.string.grader_submit_description), style = MaterialTheme.typography.titleSmall)
                            Text(
                                description?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.grader_submit_no_description),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val extensions = detail?.allowedExtensions.orEmpty()
                            Text(
                                if (extensions.isEmpty()) {
                                    stringResource(R.string.grader_submit_formats_any)
                                } else {
                                    stringResource(R.string.grader_submit_formats, extensions.joinToString("、"))
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val maxSize = detail?.maxFileSize
                            Text(
                                if (maxSize == null) {
                                    stringResource(R.string.grader_submit_max_size_unknown)
                                } else {
                                    stringResource(
                                        R.string.grader_submit_max_size,
                                        Formatter.formatShortFileSize(context, maxSize),
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (closed) {
                                Text(
                                    stringResource(R.string.grader_submit_closed),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { picker.launch(pickerMimeTypes(detail)) },
                        enabled = !submitting && !closed,
                    ) { Text(stringResource(R.string.grader_submit_pick)) }
                    Text(
                        pickedFile?.let {
                            stringResource(
                                R.string.grader_submit_file_meta,
                                it.name,
                                Formatter.formatShortFileSize(context, it.size),
                            )
                        } ?: stringResource(R.string.grader_submit_no_file),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (submitting) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                message?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                violation?.let {
                    Text(
                        stringResource(
                            when (it) {
                                GraderSubmissionPolicy.Violation.CLOSED -> R.string.grader_submit_closed
                                GraderSubmissionPolicy.Violation.TOO_LARGE -> R.string.grader_submit_too_large
                                GraderSubmissionPolicy.Violation.EXTENSION -> R.string.grader_submit_bad_extension
                            },
                            detail?.allowedExtensions.orEmpty().joinToString("、"),
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { finish() }, enabled = !submitting) {
                        Text(stringResource(R.string.homework_cancel))
                    }
                    Button(onClick = ::submit, enabled = canSubmit) {
                        Text(
                            stringResource(
                                if (resubmit) R.string.grader_submit_resubmit_action else R.string.grader_submit_action,
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * 提交轨迹。部分设备（vivo）关闭应用日志，Logcat 收不到诊断，debug 包写进私有目录：
     * `adb shell run-as cn.limpu.hita.diag cat files/grader-submit-trace.log`
     */
    private fun trace(message: String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val file = java.io.File(filesDir, TRACE_FILE)
            if (file.length() > TRACE_MAX_BYTES) file.delete()
            file.appendText("${TRACE_FORMAT.format(java.util.Date())} $message\n")
        }
    }

    private data class PickedFile(val uri: Uri, val name: String, val size: Long)

    private fun readFileMetadata(context: Context, uri: Uri): Pair<String, Long>? {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        val displayName = name ?: uri.lastPathSegment ?: return null
        if (size < 0) {
            size = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
        }
        if (size < 0) return null
        return displayName to size
    }

    /** 选文件时只列作业允许的类型；解析不出 MIME 时退回任意文件，交给扩展名硬拦。 */
    private fun pickerMimeTypes(detail: GraderAssignmentDetail?): Array<String> {
        val extensions = detail?.allowedExtensions.orEmpty()
        if (extensions.isEmpty()) return arrayOf("*/*")
        val types = extensions.mapNotNull { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }.distinct()
        return if (types.isEmpty()) arrayOf("*/*") else types.toTypedArray()
    }

    private fun GraderSubmitError.messageRes(): Int = when (this) {
        GraderSubmitError.AUTHENTICATION -> R.string.grader_submit_error_auth
        GraderSubmitError.NETWORK -> R.string.grader_submit_error_network
        GraderSubmitError.INVALID_RESPONSE -> R.string.grader_submit_error_invalid
        GraderSubmitError.STORAGE -> R.string.grader_submit_error_storage
        GraderSubmitError.BUSY -> R.string.grader_submit_error_busy
        GraderSubmitError.STORAGE_UNREACHABLE -> R.string.grader_submit_error_storage_unreachable
        GraderSubmitError.REJECTED -> R.string.grader_submit_rejected
    }

    companion object {
        private const val TRACE_FILE = "grader-submit-trace.log"
        private const val TRACE_MAX_BYTES = 128L * 1024
        private val TRACE_FORMAT = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

        const val EXTRA_ASSIGNMENT_ID = "extra_assignment_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_COURSE = "extra_course"
        const val EXTRA_DUE_AT = "extra_due_at"
        const val EXTRA_RESUBMIT = "extra_resubmit"

        private val DEADLINE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.of("Asia/Shanghai"))
    }
}
