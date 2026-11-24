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

    /** One token per selected local table, in the explicitly confirmed disclosure mode. */
    suspend fun tokensForMine(timetableIds: List<String>, nickname: String, mode: ShareMode): List<Pair<String, String>> =
        timetableIds.map { id ->
            val token = generate(id, nickname, mode)
            val name = withContext(Dispatchers.IO) { db.timetableDao().getTimetableByIdSync(id)?.name }.orEmpty()
            name to token
        }

    /**
     * 组合课表整体导出一个口令：成员课程合并成一份快照，作息与学期锚定最早开始的成员。
     * shareId 以 "group:<id>" 身份稳定，重复导出不换号。
     */
    suspend fun generateGroupToken(
        groupId: String, nickname: String, mode: ShareMode, expectedMemberIds: Set<String>,
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        db.runInTransaction(Callable {
            val group = db.timetableGroupDao().getGroupByIdSync(groupId)
                ?: throw ShareFormatException(ShareError.INVALID_FIELDS)
            val members = db.timetableDao().getTimetablesSync()
                .filter { it.groupId == groupId }.sortedBy { it.startTime.time }
            check(members.mapTo(hashSetOf()) { it.id } == expectedMemberIds) { "The group changed before export" }
            if (members.isEmpty()) throw ShareFormatException(ShareError.EMPTY_SCHEDULE)
            val anchor = members.first()
            val synthetic = cn.limpu.hita.data.model.timetable.Timetable().apply {
                id = group.id
                name = group.name
                code = anchor.code
                startTime = java.sql.Timestamp(members.minOf { it.startTime.time })
                endTime = java.sql.Timestamp(members.maxOf { it.endTime.time })
                scheduleStructure = anchor.scheduleStructure
            }
            val events = members.flatMap { member ->
                db.eventItemDao().getEventsOfTimetableSync(member.id)
                    .filter { it.type == cn.limpu.hita.data.model.timetable.EventItem.TYPE.CLASS }
                    .map { event ->
                        cn.limpu.hita.data.model.timetable.EventItem().apply {
                            type = event.type
                            source = event.source
                            name = event.name
                            place = event.place
                            teacher = event.teacher
                            subjectId = event.subjectId
                            timetableId = group.id
                            from = event.from
                            to = event.to
                        }
                    }
            }
            val identityKey = "group:${group.id}"
            val termKey = ShareTermResolver.resolve(synthetic.code, synthetic.startTime.time).key
            val identities = db.timetableShareDao()
            identities.insertIgnoreSync(ShareIdentityEntity(identityKey, termKey, UUID.randomUUID().toString()))
            val identity = checkNotNull(identities.getSync(identityKey, termKey))
            val snapshot = builder.build(synthetic, events, identity.shareId, nickname, mode)
            group.name to codec.encode(snapshot)
        })
    }
}
