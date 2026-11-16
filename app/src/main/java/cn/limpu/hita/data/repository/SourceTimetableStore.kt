package cn.limpu.hita.data.repository

import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.TimetableSourceBaselineEntity
import com.google.gson.Gson

/** Called inside the same transaction as import/adoption. Baselines are independent of version retention. */
internal class SourceTimetableStore(private val db: AppDatabase, private val gson: Gson = Gson()) {
    fun state(owner: String, table: String): SourceTimetableState? = db.timetableVersionDao().source(owner, table)?.let {
        require(it.payload.toByteArray().size <= TimetableVersionStore.MAX_BYTES)
        requireNotNull(gson.fromJson(it.payload, SourceTimetableState::class.java)).also { state -> require(state.format == 1) }
    }
    fun save(owner: String, table: String, state: SourceTimetableState) {
        val json = gson.toJson(state)
        require(json.toByteArray().size <= TimetableVersionStore.MAX_BYTES) { "课表源数据超过存储容量" }
        db.timetableVersionDao().saveSource(TimetableSourceBaselineEntity(owner, table, json))
    }
    fun resolve(updates: List<PendingCourseUpdate>, handled: Set<String>, currentOwner: String) {
        updates.filter { !it.ownerKey.isNullOrBlank() && it.rows.any { row -> row.fingerprint in handled } }.groupBy { it.ownerKey to it.timetableId }.forEach { (key, group) ->
            check(key.first == currentOwner) { "账号已改变，请重新刷新课表" }
            val state = requireNotNull(state(key.first, key.second))
            save(key.first, key.second, SourceTimetableDiff.resolve(state, group, handled))
        }
    }
}
