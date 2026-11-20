package cn.limpu.hita.data.model.timetable

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One committed course edit. `beforeJson` keeps a physically deleted manual course recoverable. */
@Entity(
    tableName = "event_edit_history",
    indices = [Index(value = ["timetableId"])],
)
data class EventEditHistoryEntity(
    @PrimaryKey val id: String,
    val timetableId: String,
    val eventId: String,
    val action: String,
    val beforeJson: String?,
    val afterJson: String?,
    val createdAt: Long,
)
