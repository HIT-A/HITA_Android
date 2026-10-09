package cn.limpu.hita.data.repository

import androidx.lifecycle.LiveData
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.model.timetable.share.ShareIdentityEntity
import cn.limpu.hita.feature.timetableshare.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.Callable
import javax.inject.Inject

data class ShareSummary(val name: String, val termName: String, val courseCount: Int,
    val occurrenceCount: Int, val mode: ShareMode = ShareMode.FULL)

class TimetableSharingRepository @Inject constructor(private val db: AppDatabase) {
    private val codec = TimetableShareCodec()
    private val builder = ShareSnapshotBuilder()
    private val summaryBuilder = ShareSummaryBuilder(builder)

    fun observePersonalTimetables(): LiveData<List<Timetable>> = db.timetableDao().getTimetables()

    suspend fun describe(timetableId: String, mode: ShareMode = ShareMode.FULL): ShareSummary = withContext(Dispatchers.IO) {
        db.runInTransaction(Callable {
            val timetable = db.timetableDao().getTimetableByIdSync(timetableId)
                ?: throw ShareFormatException(ShareError.INVALID_FIELDS)
            val events = db.eventItemDao().getEventsOfTimetableSync(timetableId)
            summaryBuilder.build(timetable, events, mode)
        })
    }

    suspend fun generate(timetableId: String, nickname: String, mode: ShareMode): String = withContext(Dispatchers.IO) {
        val snapshot = db.runInTransaction(Callable {
            val timetable = db.timetableDao().getTimetableByIdSync(timetableId)
                ?: throw ShareFormatException(ShareError.INVALID_FIELDS)
            // Selected personal timetable and its events are read under one Room transaction.
            val events = db.eventItemDao().getEventsOfTimetableSync(timetableId)
            val candidate = builder.build(timetable, events, UUID.randomUUID().toString(), nickname, mode)
            val termKey = ShareTermResolver.resolve(timetable.code, timetable.startTime.time).key
            val identities = db.timetableShareDao()
            identities.insertIgnoreSync(ShareIdentityEntity(timetableId, termKey, candidate.shareId))
            val identity = checkNotNull(identities.getSync(timetableId, termKey))
            candidate.copy(shareId = identity.shareId)
        })
        codec.encode(snapshot)
    }
}
