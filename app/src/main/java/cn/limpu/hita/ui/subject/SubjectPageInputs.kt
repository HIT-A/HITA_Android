package cn.limpu.hita.ui.subject

import androidx.lifecycle.LiveData
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.SubjectInfoUpdate

internal data class SubjectCampusSnapshot(val campus: EASToken.Campus, val hoaCampus: String, val metaSupported: Boolean)

/** The production and component tests use the same ViewModel, observation and write entry points. */
internal class SubjectPageInputs(
    val subject: (String) -> LiveData<TermSubject>,
    val timetable: (String) -> LiveData<Timetable>,
    val classes: (String) -> LiveData<List<EventItem>>,
    val teachers: (TermSubject) -> LiveData<List<String>>,
    val tokenChanges: LiveData<EASToken>,
    val campus: () -> SubjectCampusSnapshot,
    val saveSubject: (String, SubjectInfoUpdate) -> Unit,
    val update: (Collection<EventItem>, (Result<Unit>) -> Unit) -> Unit,
    val delete: (Collection<EventItem>, (Result<Unit>) -> Unit) -> Unit,
)

data class SubjectDetails(
    val id: String,
    val name: String,
    val type: TermSubject.TYPE,
    val field: String?,
    val selectCategory: String?,
    val nature: String?,
    val credit: Float,
)

data class SubjectScreenState(
    val subject: SubjectDetails? = null,
    val timetable: Timetable? = null,
    val teachers: List<String> = emptyList(),
    val classes: List<EventItem> = emptyList(),
    val visibleClasses: List<EventItem> = emptyList(),
    val selectedEventIds: Set<String> = emptySet(),
    val courseExpanded: Boolean = false,
    val courseProgress: Int = 0,
    val campus: EASToken.Campus = EASToken.Campus.SHENZHEN,
    val subjectMetaSupported: Boolean = false,
    val writing: Boolean = false,
)

data class SubjectWriteNotice(val updated: Boolean, val count: Int, val success: Boolean, val skippedTime: Boolean = false)

/** Avoid mutating Room-owned objects before a write succeeds. */
internal fun EventItem.subjectEditCopy() = EventItem().also { copy ->
    copy.id = id; copy.type = type; copy.source = source; copy.name = name
    copy.place = place; copy.teacher = teacher; copy.subjectId = subjectId; copy.timetableId = timetableId
    copy.from = java.sql.Timestamp(from.time); copy.to = java.sql.Timestamp(to.time)
    copy.fromNumber = fromNumber; copy.lastNumber = lastNumber
    copy.createdAt = java.sql.Timestamp(createdAt.time); copy.color = color
}

