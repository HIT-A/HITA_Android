package cn.limpu.hita.data.source.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cn.limpu.hita.data.model.timetable.TeachingVisibilityEntity

@Dao
interface TeachingVisibilityDao {
    @Query("SELECT * FROM teaching_visibility WHERE timetableId=:table")
    fun all(table: String): List<TeachingVisibilityEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun save(value: TeachingVisibilityEntity)
    @Query("UPDATE teaching_visibility SET hidden=:hidden WHERE preferenceId=:id")
    fun setHidden(id: String, hidden: Int)
    @Query("UPDATE teaching_visibility SET preferenceId=:toId, hidden=:hidden WHERE preferenceId=:fromId")
    fun merge(fromId: String, toId: String, hidden: Int)
    @Query("DELETE FROM teaching_visibility WHERE timetableId NOT IN (SELECT id FROM timetable)") fun prune()
    @Query("DELETE FROM teaching_visibility") fun clear()
}
