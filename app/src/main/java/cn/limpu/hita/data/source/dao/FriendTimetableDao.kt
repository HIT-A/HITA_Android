package cn.limpu.hita.data.source.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import cn.limpu.hita.data.model.timetable.share.FriendTimetableEntity

@Dao
interface FriendTimetableDao {
    @Query("SELECT * FROM friend_timetable ORDER BY updatedAt DESC, shareId ASC")
    fun observeAll(): LiveData<List<FriendTimetableEntity>>

    @Query("SELECT * FROM friend_timetable ORDER BY updatedAt DESC, shareId ASC")
    fun observeAllFlow(): kotlinx.coroutines.flow.Flow<List<FriendTimetableEntity>>

    @Query("SELECT * FROM friend_timetable WHERE shareId = :shareId")
    fun observe(shareId: String): LiveData<FriendTimetableEntity?>

    @Query("SELECT * FROM friend_timetable WHERE shareId = :shareId")
    fun getSync(shareId: String): FriendTimetableEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertSync(friend: FriendTimetableEntity)

    @Update
    fun updateSync(friend: FriendTimetableEntity)

    @Query("UPDATE friend_timetable SET remark = :remark WHERE shareId = :shareId")
    fun renameSync(shareId: String, remark: String?)

    @Query("DELETE FROM friend_timetable WHERE shareId = :shareId")
    fun deleteSync(shareId: String)
}
