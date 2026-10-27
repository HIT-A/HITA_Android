package cn.limpu.hita.data.repository

import android.app.Application
import androidx.annotation.WorkerThread
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.data.model.timetable.Timetable
import javax.inject.Inject

data class ExamImportResult(
    val totalCount: Int,
    val importedCount: Int,
    val duplicateCount: Int,
    val invalidCount: Int,
    val timetableName: String,
) {
    val skippedCount: Int get() = duplicateCount + invalidCount
}

class ExamImportTargetMissingException(val timetableId: String) :
    IllegalArgumentException("The target timetable no longer exists: $timetableId")

/** Shared mapping and deduplication, with the original transaction boundary of each entry point. */
class ExamImportRepository internal constructor(private val database: AppDatabase) {
    @Inject constructor(application: Application) : this(AppDatabase.getDatabase(application))

    private val eventItemDao = database.eventItemDao()

    /** Manual single/batch import: validate the current target and commit the whole batch together. */
    @WorkerThread
    fun importIntoTimetable(exams: List<ExamItem>, timetableId: String): ExamImportResult =
        database.runInTransaction<ExamImportResult> {
            val timetable = database.timetableDao().getTimetableByIdSync(timetableId)
                ?: throw ExamImportTargetMissingException(timetableId)
            importItems(exams, timetable, DeduplicationScope.TARGET_TIMETABLE)
        }

    /**
     * Automatic refresh owns tokenStateLock, TimetableMutationLock and the complete Room transaction.
     * Its cancellation/session guard runs after SQLite acquisition; global exam deduplication is retained.
     */
    @WorkerThread
    internal fun importAutomatically(exams: List<ExamItem>, timetable: Timetable): ExamImportResult =
        importItems(exams, timetable, DeduplicationScope.ALL_TIMETABLES)

    private enum class DeduplicationScope { TARGET_TIMETABLE, ALL_TIMETABLES }

    private fun importItems(
        exams: List<ExamItem>,
        timetable: Timetable,
        scope: DeduplicationScope,
    ): ExamImportResult {
        if (exams.isEmpty()) return ExamImportResult(0, 0, 0, 0, timetable.name.orEmpty())
        val existingExams = eventItemDao.getExamEventsSync()
        val existingKeys = when (scope) {
            DeduplicationScope.TARGET_TIMETABLE -> existingExams.filter { it.timetableId == timetable.id }
            DeduplicationScope.ALL_TIMETABLES -> existingExams
        }.mapTo(mutableSetOf()) { ExamEventMapper.identityKey(it) }
        var importedCount = 0
        var duplicateCount = 0
        var invalidCount = 0
        for (exam in exams) {
            val event = ExamEventMapper.toEvent(exam, timetable.id, "ExamImportRepository")
            if (event == null) {
                invalidCount++
                continue
            }
            if (!existingKeys.add(ExamEventMapper.identityKey(event))) {
                duplicateCount++
                continue
            }
            eventItemDao.insertEventSync(event)
            importedCount++
        }
        return ExamImportResult(exams.size, importedCount, duplicateCount, invalidCount, timetable.name.orEmpty())
    }
}
