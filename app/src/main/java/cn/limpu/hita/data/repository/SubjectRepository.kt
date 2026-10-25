package cn.limpu.hita.data.repository

import android.app.Application
import androidx.lifecycle.LiveData
import javax.inject.Inject
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.map
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TeacherInfo
import cn.limpu.hita.utils.ColorTools
import java.util.concurrent.Executors

class SubjectRepository @Inject constructor(application: Application) {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val eventItemDao = AppDatabase.getDatabase(application).eventItemDao()
    private val subjectDao = AppDatabase.getDatabase(application).subjectDao()
    private val timetableDao = AppDatabase.getDatabase(application).timetableDao()

    /**
     * 获取所有科目及其进度
     */
    fun getSubjects(timetableId: String): LiveData<List<TermSubject>> {
        return subjectDao.getSubjects(timetableId)
    }

    fun getAllSubjects(): LiveData<List<TermSubject>> {
        return subjectDao.getAllSubjects()
    }

    fun getSubjectById(subjectId: String): LiveData<TermSubject> {
        return subjectDao.getSubjectById(subjectId)
    }

    fun getSubjectColors(subjectId: String): LiveData<TermSubject> {
        return subjectDao.getSubjectById(subjectId)
    }

    fun getTeachersInfo(timetableId: String): LiveData<MutableList<TeacherInfo>> {
        return eventItemDao.getTeachersOfTimetable(timetableId).map{
            val result = mutableListOf<TeacherInfo>()
            val map = mutableMapOf<String, TeacherInfo?>()
            for (t in it) {
                val names = splitTeachers(t.name)
                if (names.isEmpty()) continue
                for (name in names) {
                    if (map[name] == null) {
                        val info = TeacherInfo().apply {
                            this.name = name
                            this.subjectName = t.subjectName
                        }
                        map[name] = info
                    } else {
                        val existed = map[name]
                        val merged = listOf(existed?.subjectName, t.subjectName)
                            .filter { !it.isNullOrBlank() }
                            .joinToString(" ")
                        existed?.subjectName = merged
                    }
                }
            }
            for (x in map.values) {
                x?.let { it1 -> result.add(it1) }
            }
            return@map result
        }
    }

    fun getTeachersOfSubject(timetableId: String, subjectId: String): LiveData<List<String>> {
        return eventItemDao.getTeachersOfSubject(timetableId, subjectId).map { list ->
            val result = linkedSetOf<String>()
            for (raw in list) {
                splitTeachers(raw).forEach { name ->
                    if (name.isNotBlank()) result.add(name)
                }
            }
            result.toList()
        }
    }

    private fun splitTeachers(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(Regex("[,，、/／]"))
            .map { sanitizeTeacherToken(it) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun sanitizeTeacherToken(token: String): String {
        var value = token.trim()
        if (value.isBlank()) return ""

        if (value.startsWith("【") && value.contains("】")) {
            val close = value.indexOf('】')
            if (close >= 0 && close < value.length - 1) {
                value = value.substring(close + 1)
            }
        }

        value = value
            .replace(Regex("^第[一二三四五六七八九十0-9]+批"), "")
            .trimStart('/', '／', ' ', '\t')
            .trim()

        if (value == "第") return ""
        if (value.contains("课程") && value.length > 6) return ""
        return value
    }

    /**
     * 动作：保存科目信息
     */
    fun actionSaveSubjectInfo(subject: TermSubject) {
        executor.execute {
            subjectDao.saveSubjectSync(subject)
        }
    }

    @androidx.annotation.WorkerThread
    fun saveSubjectSync(subject: TermSubject) {
        subjectDao.saveSubjectSync(subject)
    }

    fun actionResetRecentSubjectColors() {
        executor.execute {
            val timetable =
                timetableDao.getTimetableClosestToTimestampSync(System.currentTimeMillis())
            timetable?.let {
                val subjects = subjectDao.getSubjectsSync(it.id)
                for (s in subjects) {
                    s.color = ColorTools.colorForName(s.name)
                }
                subjectDao.saveSubjectsSync(subjects)
            }

        }
    }

    fun actionResetSubjectColors(timetableId: String) {
        executor.execute {
            val subjects = subjectDao.getSubjectsSync(timetableId)
            for (s in subjects) {
                s.color = ColorTools.colorForName(s.name)
            }
            subjectDao.saveSubjectsSync(subjects)
        }
    }


    /**
     * 动作：删除科目及其事件
     */
    fun actionDeleteSubjects(subjects: List<TermSubject>) {
        executor.execute {
            subjectDao.deleteSubjectsSync(subjects)
            val ids = subjects.getIds()
            eventItemDao.deleteEventsFromSubjectsSync(ids)
        }
    }
    fun actionChangeSubjectColor(subjectId:String,color:Int) {
        executor.execute {
            subjectDao.changeSubjectColorSync(subjectId,color)
        }
    }

    /**
     * 计算某一科目的进度
     * @return pair.first = 已完成数目,pair.second = 总数目
     *
     */
    fun getProgressOfSubject(subjectId: String, ts: Long): LiveData<Pair<Int, Int>> {
        val res = MediatorLiveData<Pair<Int, Int>>()
        var innerSource: LiveData<Int>? = null
        res.addSource(eventItemDao.countClassesOfSubject(subjectId)) { total ->
            innerSource?.let { res.removeSource(it) }
            val next = eventItemDao.countClassesBeforeTimeOfSubject(subjectId, ts)
            innerSource = next
            res.addSource(next) { finished ->
                res.value = Pair(finished, total)
            }
        }
        return res
    }

}

fun List<TermSubject>.getIds(): List<String> {
    val ids = mutableListOf<String>()
    for (s in this) {
        ids.add(s.id)
    }
    return ids
}
