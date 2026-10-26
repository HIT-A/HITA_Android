package cn.limpu.hita.ui.subject

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.switchMap
import cn.limpu.hita.R
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.SubjectRepository
import cn.limpu.hita.data.repository.SubjectInfoUpdate
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.feature.timetable.write.TimetableWriteUseCaseFactory
import cn.limpu.hita.utils.TimeTools
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject

@HiltViewModel
class SubjectViewModel internal constructor(
    private val inputs: SubjectPageInputs,
    gateway: SubjectResourceGateway,
    text: SubjectResourceText,
) : ViewModel() {
    @Inject constructor(
        subjectRepository: SubjectRepository,
        timetableRepository: TimetableRepository,
        writeUseCaseFactory: TimetableWriteUseCaseFactory,
        easRepository: EASRepository,
        gateway: AndroidSubjectResourceGateway,
        @ApplicationContext context: Context,
    ) : this(
        pageInputs(subjectRepository, timetableRepository, writeUseCaseFactory, easRepository),
        gateway,
        SubjectResourceText(
            context.getString(R.string.course_readme_missing),
            context.getString(R.string.subject_resource_choose_card),
            context.getString(R.string.course_resource_failed),
            context.getString(R.string.subject_resource_empty_directory),
            context.getString(R.string.course_resource_open),
        ),
    )

    private val subjectController = MutableLiveData<String>()
    val subjectLiveData: LiveData<TermSubject> = subjectController.switchMap(inputs.subject)
    val timetableLiveData: LiveData<Timetable> = subjectLiveData.switchMap {
        if (it == null || it.id != subjectController.value) MutableLiveData() else inputs.timetable(it.timetableId)
    }
    val classesLiveData: LiveData<List<EventItem>> = subjectController.switchMap(inputs.classes)
    val teachersLiveData: LiveData<List<String>> = subjectLiveData.switchMap {
        if (it == null || it.id != subjectController.value) MutableLiveData() else inputs.teachers(it)
    }
    private val mutableScreen = MutableStateFlow(SubjectScreenState())
    val screen = mutableScreen.asStateFlow()
    val resources = SubjectResourceCoordinator(gateway, text, ::backfillCode)
    private var ownerVersion = 0L
    private data class OwnedNotice(val version: Long, val notice: SubjectWriteNotice)
    private val writeNotices = Channel<OwnedNotice>(Channel.BUFFERED)
    val notices = writeNotices.receiveAsFlow().filter { it.version == ownerVersion }.map { it.notice }

    private val subjectObserver = Observer<TermSubject> { subject ->
        if (subject != null && subject.id == subjectController.value) {
            mutableScreen.value = screen.value.copy(subject = subject.details())
            loadResources(subject)
        } else if (subject == null) {
            ownerVersion++
            resources.reset()
            mutableScreen.value = SubjectScreenState()
        }
    }
    private val classesObserver = Observer<List<EventItem>> { classes ->
        val id = subjectController.value
        val current = classes.orEmpty().filter { it.subjectId == id && it.type != EventItem.TYPE.TAG }
            .map(EventItem::subjectEditCopy)
        mutableScreen.value = screen.value.copy(
            classes = current,
            selectedEventIds = screen.value.selectedEventIds.intersect(current.map { it.id }.toSet()),
            courseExpanded = screen.value.courseExpanded && current.isNotEmpty(),
            courseProgress = if (current.isEmpty()) 0 else
                (current.count { TimeTools.passed(it.to) }.toFloat() * 100f / current.size).toInt(),
        )
        refreshVisibleClasses()
    }
    private val timetableObserver = Observer<Timetable> { timetable ->
        val subject = subjectLiveData.value
        if (subject?.id == subjectController.value && timetable?.id == subject?.timetableId) {
            mutableScreen.value = screen.value.copy(timetable = timetable)
        }
    }
    private val teachersObserver = Observer<List<String>> {
        if (subjectLiveData.value?.id == subjectController.value) {
            mutableScreen.value = screen.value.copy(teachers = it.orEmpty().toList())
        }
    }
    private val campusObserver = Observer<EASToken> {
        subjectLiveData.value?.takeIf { it.id == subjectController.value }?.let(::loadResources)
    }

    init {
        subjectLiveData.observeForever(subjectObserver)
        classesLiveData.observeForever(classesObserver)
        timetableLiveData.observeForever(timetableObserver)
        teachersLiveData.observeForever(teachersObserver)
        inputs.tokenChanges.observeForever(campusObserver)
    }

    fun startRefresh(id: String) {
        if (id == subjectController.value) {
            subjectLiveData.value?.takeIf { it.id == id }?.let(::loadResources)
            return
        }
        ownerVersion++
        resources.reset()
        mutableScreen.value = SubjectScreenState()
        subjectController.value = id
    }

    private fun loadResources(subject: TermSubject) {
        val campus = inputs.campus()
        mutableScreen.value = screen.value.copy(campus = campus.campus, subjectMetaSupported = campus.metaSupported)
        val key = SubjectResourceKey(subject.id, subject.code, subject.name, campus.campus, campus.hoaCampus)
        val previous = resources.state.value.key
        if (previous != null && previous != key) {
            ownerVersion++
            mutableScreen.value = screen.value.copy(selectedEventIds = emptySet(), writing = false)
        }
        resources.load(key)
    }

    private fun backfillCode(key: SubjectResourceKey, code: String) {
        val current = subjectLiveData.value ?: return
        if (key != resources.state.value.key || current.id != subjectController.value ||
            current.id != key.subjectId || current.name != key.name || current.code != key.code ||
            !current.code.isNullOrBlank() || inputs.campus().campus != key.campus) return
        inputs.saveSubject(current.id, SubjectInfoUpdate.FillCourseCode(key.name, code))
    }

    fun saveCredit(key: SubjectResourceKey, credit: Float) {
        val current = subjectLiveData.value ?: return
        if (resources.state.value.key != key || current.id != key.subjectId || !screen.value.subjectMetaSupported) return
        inputs.saveSubject(current.id, SubjectInfoUpdate.Credit(credit))
    }

    fun toggleCourseExpand() {
        mutableScreen.value = screen.value.copy(courseExpanded = !screen.value.courseExpanded)
        refreshVisibleClasses()
    }

    private fun refreshVisibleClasses() {
        val classes = screen.value.classes
        val visible = if (screen.value.courseExpanded) {
            classes + EventItem.getTagInstance("less")
        } else {
            classes.take(5) + if (classes.size > 5) listOf(EventItem.getTagInstance("more")) else emptyList()
        }
        val comparator = Comparator<EventItem> { a, b ->
            if (a.type == EventItem.TYPE.TAG && b.type == EventItem.TYPE.TAG) 0 else a.compareTo(b)
        }
        mutableScreen.value = screen.value.copy(visibleClasses = visible.sortedWith(comparator))
    }

    fun selectCourse(id: String, toggle: Boolean = true) {
        if (screen.value.classes.none { it.id == id }) return
        val selected = screen.value.selectedEventIds
        mutableScreen.value = screen.value.copy(selectedEventIds =
            if (toggle && id in selected) selected - id else selected + id)
    }

    fun clearSelection() {
        mutableScreen.value = screen.value.copy(selectedEventIds = emptySet())
    }

    fun updateCourses(key: SubjectResourceKey, list: Collection<EventItem>, skippedTime: Boolean) =
        writeCourses(key, list, updated = true, skippedTime = skippedTime)

    fun deleteCourses(key: SubjectResourceKey, list: Collection<EventItem>) =
        writeCourses(key, list, updated = false, skippedTime = false)

    private fun writeCourses(key: SubjectResourceKey, list: Collection<EventItem>, updated: Boolean, skippedTime: Boolean) {
        if (resources.state.value.key != key || screen.value.writing) return
        val liveIds = screen.value.classes.map { it.id }.toSet()
        val targets = list.filter { it.id in liveIds && it.subjectId == key.subjectId }.map(EventItem::subjectEditCopy)
        if (targets.isEmpty()) return
        val version = ownerVersion
        mutableScreen.value = screen.value.copy(writing = true)
        val completed: (Result<Unit>) -> Unit = { result ->
            if (version == ownerVersion && resources.state.value.key == key) {
                mutableScreen.value = screen.value.copy(
                    writing = false,
                    selectedEventIds = if (result.isSuccess) emptySet() else screen.value.selectedEventIds,
                )
                writeNotices.trySend(OwnedNotice(version, SubjectWriteNotice(updated, targets.size, result.isSuccess, skippedTime)))
            }
        }
        if (updated) inputs.update(targets, completed) else inputs.delete(targets, completed)
    }

    override fun onCleared() {
        ownerVersion++
        subjectLiveData.removeObserver(subjectObserver)
        classesLiveData.removeObserver(classesObserver)
        timetableLiveData.removeObserver(timetableObserver)
        teachersLiveData.removeObserver(teachersObserver)
        inputs.tokenChanges.removeObserver(campusObserver)
        resources.close()
        writeNotices.close()
        super.onCleared()
    }

    private fun TermSubject.details() = SubjectDetails(id, name, type, field, selectCategory, nature, credit)

    companion object {
        private fun pageInputs(subjects: SubjectRepository, timetables: TimetableRepository,
                               factory: TimetableWriteUseCaseFactory, eas: EASRepository): SubjectPageInputs {
            val writes = factory.create(timetables)
            return SubjectPageInputs(
                subject = subjects::getSubjectById,
                timetable = timetables::getTimetablesById,
                classes = timetables::getClassesOfSubject,
                teachers = { subjects.getTeachersOfSubject(it.timetableId, it.id) },
                tokenChanges = eas.observeEasToken(),
                campus = {
                    val campus = eas.getCurrentCampus()
                    SubjectCampusSnapshot(campus, eas.getHoaCampus(campus), eas.isSubjectMetaSupported(campus))
                },
                saveSubject = subjects::actionSaveSubjectInfo,
                update = { events, result -> writes.update(events, result) },
                delete = { events, result -> writes.delete(events, result) },
            )
        }
    }
}
