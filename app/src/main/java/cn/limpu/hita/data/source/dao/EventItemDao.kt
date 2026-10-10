package cn.limpu.hita.data.source.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.ui.timetable.detail.TeacherInfo

@Dao
interface EventItemDao {
    @Query("SELECT * FROM events WHERE timetableId = :id AND type = 'CLASS'")
    fun observeClassesFlow(id: String): kotlinx.coroutines.flow.Flow<List<EventItem>>

    /**
     * 批量保存事件。
     *
     * EventItem.id 是主键；导入链路会先生成稳定事件集合，再用 REPLACE 写入。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveEvents(data: List<EventItem>)


    /**
     * 重新导入某个 EAS 学期课表时，只替换课程事件。
     *
     * 不删除考试：考试导入可能来自独立入口，且考试不一定随课程重新导入。
     * 不删除手动事件：用户自行添加的活动必须保留。
     */
    @Query("DELETE FROM events WHERE timetableId is :timetableId AND source is 'EAS_IMPORT' AND type is 'CLASS'")
    fun deleteCourseFromTimetable(timetableId: String)

    /**
     * 主课表 / 时间线使用的跨课表时间范围查询。
     *
     * 注意：这里故意不限制 timetableId；显示层或 Repository 需要根据当前场景做去重/过滤。
     */
    @Query("SELECT * FROM events WHERE `from` < :toT AND `to` > :fromT")
    fun getEventsDuring(fromT: Long, toT: Long): LiveData<List<EventItem>>

    @Query("SELECT * FROM events WHERE `from` >= :fromT order by `from` asc LIMIT :limit")
    fun getEventsAfter(fromT: Long, limit: Int): LiveData<List<EventItem>>

    @Query("SELECT * FROM events WHERE `from` >= :fromT AND `from` < :toT AND type is 'EXAM' order by `from` asc")
    fun getExamsDuring(fromT: Long, toT: Long): LiveData<List<EventItem>>

    @Query("SELECT * FROM events WHERE `from` < :toT AND `to` > :fromT")
    fun getEventsDuringSync(fromT: Long, toT: Long): List<EventItem>

    @Query("SELECT * FROM events WHERE `from` >= :fromT AND `from` < :toT AND type is 'EXAM' order by `from` asc")
    fun getExamsDuringSync(fromT: Long, toT: Long): List<EventItem>

    @Query("SELECT * FROM events WHERE `from` < :toT AND `to` > :fromT")
    fun getEventsDurin(fromT: Long, toT: Long): LiveData<List<EventItem>>


    @Query("SELECT * FROM events WHERE subjectId is :subjectId")
    fun getClassesOfSubjectSync(subjectId: String): List<EventItem>


    @Query("SELECT * FROM events WHERE subjectId is :subjectId ORDER BY `from`")
    fun getClassesOfSubject(subjectId: String): LiveData<List<EventItem>>


    @Query("SELECT DISTINCT teacher,subject.name FROM events,subject WHERE events.timetableId is:timetableId  AND subject.timetableId is:timetableId  AND events.subjectId is subject.id AND events.teacher is NOT NULL")
    fun getTeachersOfTimetable(timetableId: String): LiveData<MutableList<TeacherInfo>>

    @Query("SELECT DISTINCT teacher FROM events WHERE subjectId is :subjectId AND timetableId is :timetableId AND  teacher is NOT NULL")
    fun getTeachersOfSubject(timetableId: String, subjectId: String): LiveData<List<String>>


    @Query("SELECT count(*) from events where subjectId is :subjectId")
    fun countClassesOfSubject(subjectId: String): LiveData<Int>

    @Query("SELECT count(*) from events where subjectId is :subjectId and `to` < :ts")
    fun countClassesBeforeTimeOfSubject(subjectId: String, ts: Long): LiveData<Int>


    @Query("DELETE from events where timetableId in (:ids)")
    fun deleteEventsFromTimetablesSync(ids: List<String>)

    @Query("select id from events where timetableId in (:ids)")
    fun getEventIdsFromTimetablesSync(ids: List<String>):List<String>

    @Query("select id from events where timetableId is :timetableId")
    fun getEventIdsOfTimetableSync(timetableId: String):List<String>

    @Query("select * from events where id in (:ids)")
    fun getEventInIdsSync(ids: List<String>):List<EventItem>

    @Query("select * from events where timetableId is :timetableId")
    fun getEventsOfTimetableSync(timetableId: String):List<EventItem>

    /** 历史默认课表清理使用：判断一个课表是否为空。 */
    @Query("select count(*) from events where timetableId is :timetableId")
    fun countEventsOfTimetableSync(timetableId: String): Int

    /**
     * 历史默认课表清理使用。
     *
     * 如果一个默认课表里存在手动事件、考试或非 EAS 课程，就不能自动删除；
     * 只有纯 EAS 课程重复表才允许被清理。
     */
    @Query("select count(*) from events where timetableId is :timetableId and not (source is 'EAS_IMPORT' and type is 'CLASS')")
    fun countNonImportedClassEventsOfTimetableSync(timetableId: String): Int

    /** 历史默认课表清理和显示去重使用：只取 EAS 导入课程。 */
    @Query("select * from events where timetableId is :timetableId and source is 'EAS_IMPORT' and type is 'CLASS'")
    fun getImportedClassEventsOfTimetableSync(timetableId: String): List<EventItem>

    /** 考试去重跨课表判断，避免同一场考试被导入到多张课表后重复提醒。 */
    @Query("SELECT * FROM events WHERE type is 'EXAM'")
    fun getExamEventsSync(): List<EventItem>

    @Query("select * from events where timetableId is :timetableId order by name asc, `from` asc")
    fun getEventsOfTimetable(timetableId: String): LiveData<List<EventItem>>

    @Query("select * from events where timetableId is :timetableId and `from` < :toMs and `to` > :fromMs order by `from` asc")
    fun getEventsOfTimetableDuringSync(timetableId: String, fromMs: Long, toMs: Long): List<EventItem>

    @Query("select * from events where `from` < :toMs and `to` > :fromMs order by `from` asc")
    fun getEventsOfAllTimetablesDuringSync(fromMs: Long, toMs: Long): List<EventItem>


    @Query("SELECT * from events where type is 'CLASS' and timetableId is :timetableId and fromNumber is :fromNumber")
    fun getClassAtFromNumberSync(
        timetableId: String,
        fromNumber: Int
    ): List<EventItem>

    @Query("SELECT * from events where type is 'CLASS' and timetableId is :timetableId and fromNumber+lastNumber-1 is :toNumber")
    fun getClassAtToNumberSync(
        timetableId: String,
        toNumber: Int
    ): List<EventItem>


    @Query("DELETE from events where subjectId in (:ids)")
    fun deleteEventsFromSubjectsSync(ids: List<String>)

    @Query("delete from events where id in (:ids)")
    fun deleteEventsInIdsSync(ids: List<String>)

    @Insert
    fun addEvents(data:List<EventItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertEventSync(event: EventItem)

    @Update
    fun updateEventSync(event: EventItem)

    @Update
    fun updateEventsSync(events: List<EventItem>)

    /**
     * 将某课表的所有课程时间加上offset
     */
    @Query("update events set `from` = (`from`+:offset) , `to` = (`to` + :offset) where  timetableId is :timetableId and type is 'CLASS'")
    fun updateClassesAddOffset(timetableId: String, offset: Long)


    @Query("SELECT * FROM events WHERE name LIKE '%' || :keyword || '%' ORDER BY `from` DESC LIMIT 50")
    fun searchEventsByNameSync(keyword: String): List<EventItem>

    @Query("delete from events")
    fun clear()
}
