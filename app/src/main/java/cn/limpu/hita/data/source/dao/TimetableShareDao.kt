package cn.limpu.hita.data.source.dao

import androidx.room.*
import cn.limpu.hita.data.model.timetable.share.ShareIdentityEntity

@Dao
interface TimetableShareDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIgnoreSync(identity: ShareIdentityEntity): Long

    @Query("SELECT * FROM timetable_share_identity WHERE timetableId = :timetableId AND termKey = :termKey")
    fun getSync(timetableId: String, termKey: String): ShareIdentityEntity?
}
