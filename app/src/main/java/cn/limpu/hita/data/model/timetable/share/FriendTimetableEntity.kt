package cn.limpu.hita.data.model.timetable.share

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "friend_timetable")
data class FriendTimetableEntity(
    @PrimaryKey val shareId: String,
    val nickname: String,
    val remark: String?,
    val termName: String,
    val startMillis: Long,
    val endMillis: Long,
    val mode: String,
    val snapshotJson: String,
    val contentDigest: String,
    val importedAt: Long,
    val updatedAt: Long
)
