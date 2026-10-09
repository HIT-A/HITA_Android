package cn.limpu.hita.data.model.timetable.share

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "timetable_share_identity", primaryKeys = ["timetableId", "termKey"],
    indices = [Index(value = ["shareId"], unique = true)])
data class ShareIdentityEntity(val timetableId: String, val termKey: String, val shareId: String)
