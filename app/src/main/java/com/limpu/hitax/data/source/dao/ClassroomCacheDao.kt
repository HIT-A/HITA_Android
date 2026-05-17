package com.limpu.hitax.data.source.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.limpu.hitax.data.model.classroom.ClassroomCacheEntity

@Dao
interface ClassroomCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveAllSync(entities: List<ClassroomCacheEntity>)

    @Query("DELETE FROM classroom_cache WHERE buildingId = :buildingId AND termYearCode = :termYearCode AND termTermCode = :termTermCode AND week = :week")
    fun deleteByQuerySync(buildingId: String, termYearCode: String, termTermCode: String, week: Int)

    @Query("SELECT * FROM classroom_cache WHERE buildingId = :buildingId AND termYearCode = :termYearCode AND termTermCode = :termTermCode AND week = :week")
    fun getByQuerySync(buildingId: String, termYearCode: String, termTermCode: String, week: Int): List<ClassroomCacheEntity>

    @Query("SELECT DISTINCT buildingId, buildingName FROM classroom_cache WHERE termYearCode = :termYearCode AND termTermCode = :termTermCode AND week = :week")
    fun getCachedBuildingsSync(termYearCode: String, termTermCode: String, week: Int): List<CachedBuilding>

    @Query("SELECT DISTINCT week FROM classroom_cache WHERE buildingId = :buildingId AND termYearCode = :termYearCode AND termTermCode = :termTermCode")
    fun getCachedWeeksSync(buildingId: String, termYearCode: String, termTermCode: String): List<Int>

    @Query("DELETE FROM classroom_cache WHERE cachedAt < :beforeTimestamp")
    fun deleteOldCachesSync(beforeTimestamp: Long)

    @Query("SELECT COUNT(*) FROM classroom_cache WHERE termYearCode = :termYearCode AND termTermCode = :termTermCode AND week = :week")
    fun getCacheCountSync(termYearCode: String, termTermCode: String, week: Int): Int

    @Query("SELECT * FROM classroom_cache")
    fun getAllSync(): List<ClassroomCacheEntity>

    data class CachedBuilding(
        val buildingId: String,
        val buildingName: String,
    )
}
