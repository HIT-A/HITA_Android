package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.feature.timetableshare.protocol.SharedOccurrence
import cn.limpu.hita.feature.timetableshare.protocol.SharedPeriod
import cn.limpu.hita.feature.timetableshare.protocol.SharedTerm
import cn.limpu.hita.feature.timetableshare.protocol.SharedTimetable
import cn.limpu.hita.feature.timetableshare.protocol.ShareMode
import cn.limpu.hita.utils.ColorTools
import cn.limpu.hita.feature.timetableshare.protocol.TimetableShareCodec
import java.sql.Timestamp
import java.util.UUID
import java.io.FilterInputStream
import java.io.InputStream
import cn.limpu.hita.feature.timetableshare.protocol.ShareError
import cn.limpu.hita.feature.timetableshare.protocol.ShareFormatException
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.data.ParserException
import net.fortuna.ical4j.model.component.VEvent

/**
 * 课表口令快照与「我的课表」三表行之间的双向转换。
 *
 * - 口令 → 我的课表：仅 FULL 模式（BUSY 无课程详情，由调用方拦截）。
 * - ICS bundle → 口令快照：用于把 ICS 文件导入为好友课表；shareId 按昵称稳定生成，
 *   重复导入同名文件视为同一条目更新。
 */
object SharedTimetableImporter {
    internal const val FRIEND_ICS_BYTES = 8 * 1024 * 1024

    /** Friend imports are bounded before the legacy calendar parser allocates its model. */
    internal fun readFriendIcs(input: InputStream, sourceName: String, nickname: String): SharedTimetable {
        val calendar = try {
            object : FilterInputStream(input) {
                private var count = 0
                private fun counted(size: Int): Int {
                    if (size > 0) count += size
                    if (count > FRIEND_ICS_BYTES) throw ShareFormatException(ShareError.TOO_LONG)
                    return size
                }
                override fun read(): Int = super.read().also { counted(if (it >= 0) 1 else 0) }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    counted(super.read(buffer, offset, minOf(length, FRIEND_ICS_BYTES - count + 1)))
            }.use { CalendarBuilder().build(it) }
        } catch (wrapped: ParserException) {
            // ical4j 把流读取期抛出的异常包成 ParserException；还原大小上限错误以免调用方丢失 TOO_LONG 文案。
            throw generateSequence<Throwable>(wrapped) { it.cause }
                .filterIsInstance<ShareFormatException>().firstOrNull() ?: wrapped
        }
        return buildSnapshotFromIcs(IcsImportBundleBuilder.build(
            calendar.components.filterIsInstance<VEvent>(), sourceName), nickname)
    }

    /** 口令快照(FULL) → Timetable + TermSubject + EventItem。课程色走既有 colorForName。 */
    fun buildBundleFromShare(snapshot: SharedTimetable): IcsImportBundle {
        require(snapshot.mode == ShareMode.FULL) { "busy snapshot has no course detail" }
        TimetableShareCodec().validate(snapshot)
        val timetable = Timetable().apply {
            name = snapshot.term.timetableName.ifBlank { snapshot.term.termName }
            startTime = Timestamp(snapshot.term.startMillis)
            endTime = Timestamp(snapshot.term.endMillis)
            scheduleStructure = snapshot.periods.map(::toTimePeriod)
        }
        val subjects = snapshot.courses.mapIndexed { index, courseName ->
            TermSubject().apply {
                timetableId = timetable.id
                name = courseName
                color = ColorTools.colorForName(courseName)
                // 课程顺序即快照课程编号,保持稳定便于排查。
                code = "SHARE_$index"
            }
        }
        val events = snapshot.occurrences.map { occurrence ->
            val subject = subjects[requireNotNull(occurrence.courseIndex)]
            EventItem().apply {
                type = EventItem.TYPE.CLASS
                source = EventItem.SOURCE_SHARE_IMPORT
                name = subject.name
                place = occurrence.place.orEmpty()
                teacher = ""
                subjectId = subject.id
                timetableId = timetable.id
                from = Timestamp(occurrence.startMillis)
                to = Timestamp(occurrence.endMillis)
            }
        }
        return IcsImportBundle(timetable, subjects, events)
    }

    /** ICS 导入结果 → 好友课表快照(FULL)。昵称取文件名(去扩展名)。 */
    fun buildSnapshotFromIcs(bundle: IcsImportBundle, nickname: String): SharedTimetable {
        val subjectNames = bundle.subjects.map { it.name }
        val nameBySubjectId = bundle.subjects.associate { it.id to it.name }
        val courseIndexByName = subjectNames.withIndex().associate { it.value to it.index }
        val courseIndexBySubjectId = bundle.subjects.mapIndexed { index, subject -> subject.id to index }.toMap()
        val occurrences = bundle.events
            .filter { it.type == EventItem.TYPE.CLASS }
            .map { event ->
                val courseName = nameBySubjectId[event.subjectId] ?: event.name
                val courseIndex = courseIndexBySubjectId[event.subjectId] ?: courseIndexByName[courseName]
                    ?: throw IllegalArgumentException("ICS class has no course")
                SharedOccurrence(
                    startMillis = event.from.time,
                    endMillis = event.to.time,
                    courseIndex = courseIndex,
                    place = event.place?.takeIf { it.isNotBlank() },
                )
            }
        val timetableName = bundle.timetable.name ?: nickname
        return SharedTimetable(
            shareId = stableShareId(nickname),
            nickname = nickname,
            mode = ShareMode.FULL,
            term = SharedTerm(
                timetableName = timetableName,
                termName = timetableName,
                startMillis = bundle.timetable.startTime.time,
                endMillis = bundle.timetable.endTime.time,
            ),
            periods = bundle.timetable.scheduleStructure.map(::toSharedPeriod),
            courses = subjectNames,
            occurrences = occurrences,
        )
    }

    /** 同名文件重复导入映射到同一条目,走既有更新确认流程。 */
    private fun stableShareId(nickname: String): String =
        UUID.nameUUIDFromBytes("ics-friend:$nickname".toByteArray(Charsets.UTF_8)).toString()

    private fun toTimePeriod(period: SharedPeriod): TimePeriodInDay =
        TimePeriodInDay(
            TimeInDay(period.startMinute / 60, period.startMinute % 60),
            TimeInDay(period.endMinute / 60, period.endMinute % 60),
        )

    private fun toSharedPeriod(period: TimePeriodInDay): SharedPeriod =
        SharedPeriod(
            startMinute = period.from.hour * 60 + period.from.minute,
            endMinute = period.to.hour * 60 + period.to.minute,
        )
}
