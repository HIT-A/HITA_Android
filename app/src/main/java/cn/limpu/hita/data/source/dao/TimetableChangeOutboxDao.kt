package cn.limpu.hita.data.source.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import cn.limpu.hita.data.model.timetable.TimetableChangeOutboxEntity

@Dao
interface TimetableChangeOutboxDao {
    @Insert
    fun insert(operation: TimetableChangeOutboxEntity)

    @Query("SELECT * FROM timetable_change_outbox ORDER BY createdAtMillis, operationId")
    fun pending(): List<TimetableChangeOutboxEntity>

    @Query("DELETE FROM timetable_change_outbox WHERE operationId = :operationId")
    fun acknowledge(operationId: String)
}
