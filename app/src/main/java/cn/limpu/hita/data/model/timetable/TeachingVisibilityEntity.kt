package cn.limpu.hita.data.model.timetable

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Personal visibility follows an occurrence across source moves and historical restoration. */
@Entity(tableName = "teaching_visibility", indices = [Index("timetableId"), Index("preferenceId")])
data class TeachingVisibilityEntity(
    @PrimaryKey val occurrenceKey: String,
    val timetableId: String,
    val preferenceId: String,
    val hidden: Int,
)
