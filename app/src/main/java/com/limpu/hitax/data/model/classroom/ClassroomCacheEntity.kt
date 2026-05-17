package com.limpu.hitax.data.model.classroom

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "classroom_cache",
    primaryKeys = ["buildingId", "termYearCode", "termTermCode", "week", "name"],
    indices = [
        Index(value = ["termYearCode", "termTermCode", "week"]),
        Index(value = ["cachedAt"]),
    ]
)
data class ClassroomCacheEntity(
    val buildingId: String,
    val buildingName: String,
    val termYearCode: String,
    val termTermCode: String,
    val week: Int,
    val name: String,
    val capacity: Int,
    val specialClassroom: String?,
    val scheduleJson: String,
    val cachedAt: Long,
)
