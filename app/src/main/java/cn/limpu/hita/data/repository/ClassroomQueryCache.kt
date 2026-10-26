package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomCacheEntity
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.source.dao.ClassroomCacheDao
import org.json.JSONArray
import org.json.JSONObject

/** Synchronous cache boundary shared by the classroom query entry points. */
internal class ClassroomQueryCache(private val dao: ClassroomCacheDao) {
    fun readRows(term: TermItem, buildingItem: BuildingItem, week: Int): List<ClassroomCacheEntity> =
        dao.getByQuerySync(buildingItem.id, term.yearCode, term.termCode, week)

    fun save(
        term: TermItem,
        buildingItem: BuildingItem,
        week: Int,
        classrooms: List<ClassroomItem>,
    ) {
        dao.deleteByQuerySync(buildingItem.id, term.yearCode, term.termCode, week)
        if (classrooms.isEmpty()) return
        val cachedAt = System.currentTimeMillis()
        val entities = classrooms.map { classroom ->
            ClassroomCacheEntity(
                buildingId = buildingItem.id,
                buildingName = buildingItem.name.orEmpty(),
                termYearCode = term.yearCode,
                termTermCode = term.termCode,
                week = week,
                name = classroom.name,
                capacity = classroom.capacity,
                specialClassroom = classroom.specialClassroom,
                scheduleJson = JSONArray(classroom.scheduleList).toString(),
                cachedAt = cachedAt,
            )
        }
        dao.saveAllSync(entities)
        dao.deleteOldCachesSync(cachedAt - 180L * 24L * 60L * 60L * 1000L)
    }

    companion object {
        fun decode(rows: List<ClassroomCacheEntity>): List<ClassroomItem> = rows.map { row ->
            ClassroomItem().also { classroom ->
                classroom.id = row.name
                classroom.name = row.name
                classroom.capacity = row.capacity
                classroom.specialClassroom = row.specialClassroom
                val arr = JSONArray(row.scheduleJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: JSONObject()
                    classroom.scheduleList.add(obj)
                }
            }
        }
    }
}
