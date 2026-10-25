package cn.limpu.hita.ui.subject

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.switchMap
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.SubjectRepository
import cn.limpu.hita.data.repository.TimetableRepository
import cn.limpu.hita.feature.timetable.write.TimetableWriteUseCaseFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SubjectViewModel @Inject constructor(
    private val subjectRepository: SubjectRepository,
    private val timetableRepository: TimetableRepository,
    writeUseCaseFactory: TimetableWriteUseCaseFactory,
) : ViewModel() {
    private val timetableWrites = writeUseCaseFactory.create(timetableRepository)

    /**
     * LiveData区
     */
    private val subjectController = MutableLiveData<String>()
    val subjectLiveData: LiveData<TermSubject> = subjectController.switchMap{
        return@switchMap subjectRepository.getSubjectById(it)
    }
    val timetableLiveData:LiveData<Timetable> = subjectLiveData.switchMap{
        return@switchMap timetableRepository.getTimetablesById(it.timetableId)
    }

    val classesLiveData: LiveData<List<EventItem>> = subjectController.switchMap {
        return@switchMap timetableRepository.getClassesOfSubject(it)
    }

    val teachersLiveData: LiveData<List<String>> = subjectLiveData.switchMap{
        return@switchMap subjectRepository.getTeachersOfSubject(it.timetableId, it.id)
    }


    fun startRefresh(id: String) {
        subjectController.value = id
    }

    fun startSaveSubject() {
        subjectLiveData.value?.let { it1 -> subjectRepository.actionSaveSubjectInfo(it1) }
    }

    fun deleteCourses(list: Collection<EventItem>) {
        if (list.isEmpty()) return
        timetableWrites.delete(list)
    }

    fun updateCourses(list: Collection<EventItem>) {
        if (list.isEmpty()) return
        timetableWrites.update(list)
    }

    fun deleteAllCourses() {
        val all = classesLiveData.value.orEmpty()
        if (all.isNotEmpty()) {
            timetableWrites.delete(all)
        }
    }
}
