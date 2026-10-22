package cn.limpu.hita.ui.eas.exam

import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import cn.limpu.hita.R
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.ExamEventMapper
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.utils.LogUtils

class ExamDetailFragment(
    private val exam: ExamItem,
    private val onEdit: ((ExamItem) -> Unit)? = null,
    private val onDelete: ((ExamItem) -> Unit)? = null
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
                    ExamDetailSheet(
                        exam = exam,
                        onImport = { (activity as? androidx.fragment.app.FragmentActivity)?.pickExamImportTarget { importExamToTimetable(it) } },
                        onEdit = onEdit?.let { callback ->
                            {
                                dismiss()
                                callback(exam)
                            }
                        },
                        onDelete = onDelete?.let { callback ->
                            {
                                dismiss()
                                callback(exam)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun importExamToTimetable(timetableId: String) {
        val context = context ?: return
        val host = activity ?: return
        Toast.makeText(context, "正在导入...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val appDatabase = AppDatabase.getDatabase(context.applicationContext as Application)
                val result = appDatabase.runInTransaction(java.util.concurrent.Callable {
                    val eventItemDao = appDatabase.eventItemDao()
                    val timetableDao = appDatabase.timetableDao()
                    val timetable = requireNotNull(timetableDao.getTimetableByIdSync(timetableId)) {
                        context.getString(R.string.exam_import_target_missing)
                    }
                    importExamEvent(context, timetable, eventItemDao)
                })

                host.runOnUiThread {
                    if (!isAdded || host.isFinishing || host.isDestroyed) return@runOnUiThread
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    if (result.success) {
                        LogUtils.d("✅ ${result.message}", "ExamDetailFragment")
                        dismiss()
                    } else {
                        LogUtils.e("❌ ${result.message}", null, "ExamDetailFragment")
                    }
                }
            } catch (e: Exception) {
                LogUtils.e("❌ 导入考试失败: ${e.message}", e, "ExamDetailFragment")
                host.runOnUiThread {
                    if (!isAdded || host.isFinishing || host.isDestroyed) return@runOnUiThread
                    Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private data class ImportResult(
        val success: Boolean,
        val message: String
    )

    private fun importExamEvent(
        context: android.content.Context,
        timetable: Timetable,
        eventItemDao: cn.limpu.hita.data.source.dao.EventItemDao
    ): ImportResult {
        val examEvent = ExamEventMapper.toEvent(exam, timetable.id, "ExamDetailFragment")
            ?: return ImportResult(false, "考试时间格式解析失败")
        val examKey = ExamEventMapper.identityKey(examEvent)
        val isDuplicate = eventItemDao.getExamEventsSync().any { event ->
            event.timetableId == timetable.id && ExamEventMapper.identityKey(event) == examKey
        }

        if (isDuplicate) {
            return ImportResult(false, context.getString(R.string.exam_import_already_in_target))
        }

        eventItemDao.insertEventSync(examEvent)
        return ImportResult(true, context.getString(R.string.exam_import_target_success, timetable.name.orEmpty()))
    }

}

@Composable
private fun ExamDetailSheet(
    exam: ExamItem,
    onImport: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    val tokens = HitaTheme.tokens
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(tokens.spacing.xl)
        ) {
            Text(
                text = exam.courseName ?: "未知课程",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(tokens.spacing.xl))
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.xl)) {
                DetailColumn(
                    label = stringResource(R.string.exam_time),
                    value = exam.examTime.orEmpty(),
                    modifier = Modifier.weight(1f)
                )
                DetailColumn(
                    label = stringResource(R.string.exam_location),
                    value = exam.examLocation.orEmpty(),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(tokens.spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.xl)) {
                DetailColumn(
                    label = stringResource(R.string.exam_campus),
                    value = exam.campusName.orEmpty(),
                    modifier = Modifier.weight(1f)
                )
                DetailColumn(
                    label = stringResource(R.string.exam_type),
                    value = exam.examType.orEmpty(),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(tokens.spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(tokens.spacing.xl)) {
                DetailColumn(
                    label = stringResource(R.string.exam_term),
                    value = exam.termName.orEmpty(),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(46.dp))
            OutlinedButton(
                onClick = onImport,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.spacing.xl),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(vertical = tokens.spacing.sm),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(text = "导入到课表", fontSize = 14.sp)
            }
            if (exam.isMemo() && onEdit != null && onDelete != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.spacing.xl, vertical = tokens.spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.sm)
                ) {
                    TextButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                        Text("编辑")
                    }
                    TextButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(modifier = Modifier.height(tokens.spacing.xl))
        }
    }
}

@Composable
private fun DetailColumn(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp
        )
        Text(
            text = value.ifBlank { "-" },
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
