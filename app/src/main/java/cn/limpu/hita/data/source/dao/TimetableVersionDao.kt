package cn.limpu.hita.data.source.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cn.limpu.hita.data.model.timetable.TimetableVersionEntity
import cn.limpu.hita.data.model.timetable.TimetableVersionSummary
import cn.limpu.hita.data.model.timetable.TimetableSourceBaselineEntity

@Dao
interface TimetableVersionDao {
    @Query("SELECT id,revision,committedAt,reason,restoredFrom,fingerprint FROM timetable_version WHERE ownerKey=:owner AND timetableId=:table ORDER BY revision DESC")
    fun summaries(owner: String, table: String): List<TimetableVersionSummary>
    @Query("SELECT * FROM timetable_version WHERE ownerKey=:owner AND timetableId=:table AND id=:id")
    fun get(owner: String, table: String, id: String): TimetableVersionEntity?
    @Insert fun insert(version: TimetableVersionEntity)
    @Query("DELETE FROM timetable_version WHERE ownerKey=:owner AND timetableId=:table AND id NOT IN (SELECT id FROM timetable_version WHERE ownerKey=:owner AND timetableId=:table ORDER BY revision DESC LIMIT 5)")
    fun trim(owner: String, table: String)
    @Query("DELETE FROM timetable_version WHERE timetableId NOT IN (SELECT id FROM timetable)")
    fun pruneDeletedTables()
    @Query("DELETE FROM timetable_source_baseline WHERE timetableId NOT IN (SELECT id FROM timetable)")
    fun pruneDeletedSources()
    @Query("SELECT * FROM timetable_source_baseline WHERE ownerKey=:owner AND timetableId=:table")
    fun source(owner: String, table: String): TimetableSourceBaselineEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun saveSource(state: TimetableSourceBaselineEntity)
    @Query("DELETE FROM timetable_version") fun clearVersions()
    @Query("DELETE FROM timetable_source_baseline") fun clearSources()
}
