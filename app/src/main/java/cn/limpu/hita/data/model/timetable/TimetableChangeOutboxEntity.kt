package cn.limpu.hita.data.model.timetable

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Committed course mutations whose snapshot/change-state files still need publication. */
@Entity(tableName = "timetable_change_outbox")
data class TimetableChangeOutboxEntity(
    @PrimaryKey val operationId: String,
    val payload: String,
    val createdAtMillis: Long,
)
