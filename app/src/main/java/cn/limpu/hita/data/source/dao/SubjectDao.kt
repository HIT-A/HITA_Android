package cn.limpu.hita.data.source.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import cn.limpu.hita.data.model.timetable.SubjectColor
import cn.limpu.hita.data.model.timetable.TermSubject

@Dao
interface SubjectDao {

    @Query("SELECT * FROM subject WHERE id is :id")
    fun getSubjectById(id: String): LiveData<TermSubject>

    @Query("SELECT * FROM subject WHERE id in (:ids)")
    fun getSubjectsInIdsSync(ids: List<String>): List<TermSubject>


    @Query("SELECT * FROM subject WHERE timetableId is :timetableId AND name is :name")
    fun getSubjectByName(timetableId: String, name: String?): TermSubject?

    @Query("SELECT * FROM subject WHERE timetableId is :timetableId")
    fun getSubjectsSync(timetableId: String): List<TermSubject>

    @Query("SELECT * FROM subject WHERE timetableId is :timetableId ORDER BY credit DESC")
    fun getSubjects(timetableId: String): LiveData<List<TermSubject>>


    @Query("select id,color from subject where id in (:ids)")
    fun getSubjectColorsWithId(ids: Set<String>): LiveData<List<SubjectColor>>

    /** 显示目标全量课程颜色：周视图颜色分配器的输入,不随周窗口变化。 */
    @Query("select id,color from subject where timetableId in (:timetableIds)")
    fun getSubjectColorsOfTimetables(timetableIds: List<String>): LiveData<List<SubjectColor>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveSubjectSync(data: TermSubject)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveSubjectsSync(data: List<TermSubject>)

    @Query("DELETE from subject where timetableId in (:ids)")
    fun deleteSubjectsFromTimetablesSync(ids: List<String>)

    @Query("delete from subject where id in (:ids)")
    fun deleteSubjectsInIdsSync(ids: List<String>)

    /** Retain subjects referenced by manual sessions, including legacy cross-table references. */
    @Query("DELETE FROM subject WHERE timetableId = :timetableId AND id IN (:ids) AND NOT EXISTS (SELECT 1 FROM events WHERE events.subjectId = subject.id)")
    fun deleteUnusedSubjectsInTimetableSync(timetableId: String, ids: List<String>)

    @Query("update subject set color = :color where id =:id")
    fun changeSubjectColorSync(id: String, color: Int)

    @Query("select id from subject where timetableId in (:ids)")
    fun getSubjectIdsOfTimetablesSync(ids: List<String>): List<String>

    @Delete
    fun deleteSubjectsSync(subjects: List<TermSubject>)

    @Query("delete from subject")
    fun clear()

    @Query("SELECT * FROM subject")
    fun getAllSubjectsSync(): List<TermSubject>

    @Query("SELECT * FROM subject")
    fun getAllSubjects(): LiveData<List<TermSubject>>
}