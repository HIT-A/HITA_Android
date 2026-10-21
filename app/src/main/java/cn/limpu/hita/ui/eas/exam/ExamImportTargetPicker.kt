package cn.limpu.hita.ui.eas.exam

import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import cn.limpu.hita.R
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.repository.DisplayScopeResolver
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Always confirm the concrete destination, including when a group is displayed. */
internal fun FragmentActivity.pickExamImportTarget(onSelected: (String) -> Unit) {
    lifecycleScope.launch {
        val (tables, selectedIds) = withContext(Dispatchers.IO) {
            val database = AppDatabase.getDatabase(application)
            database.timetableDao().getTimetablesSync() to
                DisplayScopeResolver(application).currentSync()?.timetableIds.orEmpty()
        }
        if (isFinishing || isDestroyed) return@launch
        if (tables.isEmpty()) {
            Toast.makeText(this@pickExamImportTarget, R.string.exam_import_no_timetable, Toast.LENGTH_LONG).show()
            return@launch
        }
        val ordered = tables.sortedBy { if (it.id in selectedIds) 0 else 1 }
        var chosen = if (selectedIds.size == 1) ordered.indexOfFirst { it.id == selectedIds.single() } else -1
        val labels = ordered.map { it.name ?: getString(R.string.default_timetable_name) }.toTypedArray()
        val dialog = MaterialAlertDialogBuilder(this@pickExamImportTarget)
            .setTitle(R.string.exam_import_target)
            .setSingleChoiceItems(labels, chosen) { dialog, index ->
                chosen = index
                (dialog as androidx.appcompat.app.AlertDialog).getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = true
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (chosen in ordered.indices) onSelected(ordered[chosen].id)
            }.create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled = chosen in ordered.indices
        }
        dialog.show()
    }
}
