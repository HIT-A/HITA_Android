package cn.limpu.hita.data.repository

import android.app.Application
import cn.limpu.hita.data.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/** Narrow seam used by EAS import; the importer does not own timetable display wiring. */
internal fun interface TimetableImportGrouping {
    fun autoGroupAfterEasImportSync(timetableId: String)
}

@Singleton
internal class DefaultTimetableImportGrouping @Inject constructor(
    private val timetableRepository: TimetableRepository,
) : TimetableImportGrouping {
    override fun autoGroupAfterEasImportSync(timetableId: String) {
        timetableRepository.autoGroupAfterEasImportSync(timetableId)
    }
}

/** Compatibility-only adapter for explicit repository contract harnesses. */
internal class LegacyTimetableImportGrouping(
    application: Application,
    database: AppDatabase,
) : TimetableImportGrouping {
    private val delegate = TimetableRepository(
        application,
        database,
        ApplicationDisplayScopeSelector(application),
    )

    override fun autoGroupAfterEasImportSync(timetableId: String) {
        delegate.autoGroupAfterEasImportSync(timetableId)
    }
}
