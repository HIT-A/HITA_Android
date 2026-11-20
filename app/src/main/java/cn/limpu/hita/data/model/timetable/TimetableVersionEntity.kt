package cn.limpu.hita.data.model.timetable

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "timetable_version", indices = [Index(value = ["ownerKey", "timetableId", "revision"], unique = true)])
data class TimetableVersionEntity(
    @PrimaryKey val id: String,
    val ownerKey: String,
    val timetableId: String,
    val revision: Long,
    val parentId: String?,
    val committedAt: Long,
    val reason: String,
    val restoredFrom: String?,
    val fingerprint: String,
    val payload: String,
)

/** Lists deliberately exclude the potentially large snapshot payload. */
data class TimetableVersionSummary(
    val id: String,
    val revision: Long,
    val committedAt: Long,
    val reason: String,
    val restoredFrom: String?,
    val fingerprint: String,
)

@Entity(tableName = "timetable_source_baseline", primaryKeys = ["ownerKey", "timetableId"])
data class TimetableSourceBaselineEntity(val ownerKey: String, val timetableId: String, val payload: String)
