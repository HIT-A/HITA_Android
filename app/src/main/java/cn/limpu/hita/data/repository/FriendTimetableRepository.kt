package cn.limpu.hita.data.repository

import androidx.lifecycle.LiveData
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.share.FriendTimetableEntity
import cn.limpu.hita.feature.timetableshare.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable
import javax.inject.Inject

class FriendTimetableRepository @Inject constructor(private val db: AppDatabase) {
    private val friends = db.friendTimetableDao()
    private val codec = TimetableShareCodec()

    fun observeFriends(): LiveData<List<FriendTimetableEntity>> = friends.observeAll()
    fun observeFriendsFlow() = friends.observeAllFlow()
    fun observeFriend(shareId: String): LiveData<FriendTimetableEntity?> = friends.observe(shareId)

    suspend fun preview(message: String): FriendImportPreview = withContext(Dispatchers.IO) {
        val snapshot = codec.decodeMessage(message)
        makePreview(snapshot, codec.digest(snapshot), friends.getSync(snapshot.shareId))
    }

    suspend fun confirm(preview: FriendImportPreview): FriendSaveResult = withContext(Dispatchers.IO) {
        // Revalidate even caller-created previews, and store only canonical complete snapshots.
        val json = codec.canonicalJson(preview.snapshot)
        val snapshot = codec.decodeJson(json)
        val digest = codec.digest(snapshot)
        db.runInTransaction(Callable<FriendSaveResult> {
            val current = friends.getSync(snapshot.shareId)
            if (current?.contentDigest != preview.expectedDigest) {
                return@Callable FriendSaveResult.NeedsConfirmation(makePreview(snapshot, digest, current))
            }
            if (current?.contentDigest == digest) {
                return@Callable FriendSaveResult.Unchanged(snapshot.shareId)
            }
            val now = System.currentTimeMillis()
            val entity = FriendTimetableEntity(snapshot.shareId, snapshot.nickname, current?.remark,
                snapshot.term.termName, snapshot.term.startMillis, snapshot.term.endMillis,
                snapshot.mode.name, json, digest, current?.importedAt ?: now, now)
            if (current == null) friends.insertSync(entity) else friends.updateSync(entity)
            FriendSaveResult.Saved(snapshot.shareId)
        })
    }

    suspend fun rename(shareId: String, remark: String?) = withContext(Dispatchers.IO) {
        friends.renameSync(shareId, remark)
    }

    suspend fun delete(shareId: String) = withContext(Dispatchers.IO) { friends.deleteSync(shareId) }

    private fun makePreview(snapshot: SharedTimetable, digest: String, current: FriendTimetableEntity?) =
        FriendImportPreview(snapshot, FriendImportPolicy.classify(digest, current?.contentDigest),
            current?.contentDigest, current?.remark)
}
