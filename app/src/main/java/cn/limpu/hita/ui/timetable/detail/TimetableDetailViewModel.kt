package cn.limpu.hita.ui.timetable.detail

import cn.limpu.hita.data.model.timetable.TeacherInfo
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.switchMap
import com.limpu.component.data.StringTrigger
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.repository.SubjectRepository
import cn.limpu.hita.data.repository.TimetableRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TimetableDetailViewModel @Inject constructor(
    private val timetableRepository: TimetableRepository,
    private val subjectsRepository: SubjectRepository
) : ViewModel() {


    /**
     * 数据区
     */
    private val timetableController: MutableLiveData<StringTrigger> = MutableLiveData()

    val subjectsLiveData: LiveData<List<TermSubject>> =
        timetableController.switchMap{
            return@switchMap subjectsRepository.getSubjects(it.data)
        }
    val teacherInfoLiveData: LiveData<MutableList<TeacherInfo>> =
        timetableController.switchMap{
            return@switchMap subjectsRepository.getTeachersInfo(it.data)
        }
    val timetableLiveData: LiveData<Timetable> = timetableController.switchMap{
        return@switchMap timetableRepository.getTimetablesById(it.data)
    }

    private val exportController = MutableLiveData<Timetable>()
    val exportToICSResult =  exportController.switchMap{
        return@switchMap timetableRepository.exportToICS(it.name?:"课表",it.id)
    }


    /**
     * 方法
     */

    fun getSubjectProgress(subjectId: String): LiveData<Pair<Int, Int>> {
        return subjectsRepository.getProgressOfSubject(subjectId, System.currentTimeMillis())
    }

    fun startRefresh(id: String) {
        timetableController.value = StringTrigger.getActioning(id)
    }

    fun startSaveTimetableInfo() {
        timetableLiveData.value?.let { timetableRepository.actionSaveTimetable(it) }
    }

    fun startChangeTimetableStructure(tp: TimePeriodInDay, position: Int) {
        timetableLiveData.value?.let {
            timetableRepository.actionChangeTimetableStructure(it, tp, position)
        }
    }

    fun startChangeTimetableStartTime(startTime: Long) {
        timetableLiveData.value?.let {
            timetableRepository.actionChangeTimetableStartDate(it, startTime)
        }
    }

    fun startResetSubjectColors() {
        timetableLiveData.value?.let {
            subjectsRepository.actionResetSubjectColors(it.id)
        }
    }

    fun startDeleteSubjects(subjects: Collection<TermSubject>) {
        subjectsRepository.actionDeleteSubjects(subjects.toList())
    }

    fun startChangeSubjectColor(subjectId:String,color:Int) {
        subjectsRepository.actionChangeSubjectColor(subjectId,color)
    }
    fun exportToIcs() {
        timetableLiveData.value?.let {
            exportController.value = it
        }

    }


}