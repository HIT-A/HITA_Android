package cn.limpu.hita.data.source.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import cn.limpu.hita.data.model.timetable.EventEditHistoryEntity

@Dao
interface EventEditHistoryDao {
    @Insert
    fun insert(entry: EventEditHistoryEntity)

    @Query("SELECT * FROM event_edit_history WHERE timetableId = :timetableId ORDER BY createdAt DESC, id DESC")
    fun historyOfTimetableSync(timetableId: String): List<EventEditHistoryEntity>

    @Query("SELECT * FROM event_edit_history WHERE id = :id")
    fun getByIdSync(id: String): EventEditHistoryEntity?

    @Query(
        """DELETE FROM event_edit_history WHERE id IN (
            SELECT id FROM event_edit_history ORDER BY createdAt ASC, id ASC LIMIT :overflow
        )""",
    )
    fun deleteOldestSync(overflow: Int)

    @Query("SELECT COUNT(*) FROM event_edit_history")
    fun countSync(): Int
}
