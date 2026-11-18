package cn.limpu.hita.ui.main.timeline

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.repository.*
import cn.limpu.hita.data.repository.today.TodayReadState
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TodayExamProjectionTest {
    private val window = TodayDateWindow.from(Instant.parse("2026-11-17T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private val now = window.start + 8 * 3_600_000
    private fun row(time: String? = "09:00-11:00", date: String? = "2026-11-17", term: String? = "term") =
        ExamReadItem("数学", date, time, "A101", term, "期末")
    private fun source(items: List<ExamReadItem>, target: String? = "table") = ExamReadSnapshot("owner", 1,
        mapOf("term" to TodayReadState.Ready(ExamReadResult("term", target, items))))
    private fun project(source: ExamReadSnapshot = ExamReadSnapshot(), events: List<EventItem> = emptyList(), owner: String? = "owner", generation: Long = 1) =
        TodayExamProjection.project(window, now, events, setOf("table"), source, owner, generation)
    @Test fun `unknown remote does not claim all exams are known and unbound rows stay separate`() {
        assertTrue(project().coverage.isEmpty())
        val empty = project(source(emptyList()))
        assertEquals(TodaySourceStatus.READY, empty.coverage.single().status)
        assertTrue(empty.remaining.isEmpty())
        val unbound = project(source(listOf(row()), null))
        assertTrue(unbound.remaining.isEmpty())
        assertEquals(1, unbound.unbound.size)
        assertNull(unbound.unbound.single().timetableId)
    }
    @Test fun `only confirmed date and target participate and ended exams stay separate`() {
        val result = project(source(listOf(row(), row(null), row(date = null), row("06:00-08:00"), row(term = "other-term"))))
        assertEquals(1, result.remaining.size)
        assertEquals(1, result.timeToConfirm.size)
        assertNull(result.timeToConfirm.single().start)
        assertEquals(1, result.unbound.size)
        assertEquals(1, result.ended.size)
        assertTrue(project(source(listOf(row()), "another-target")).remaining.isEmpty())
    }
    @Test fun `current timetable includes past and future exams beyond the agenda window`() {
        fun exam(date: String, id: String, table: String = "table", hidden: Int = 0) =
            requireNotNull(ExamEventMapper.toEvent(row(date = date).toExamItem(), table, zoneId = window.zoneId)).apply {
                this.id = id; this.hidden = hidden
            }
        val events = listOf(exam("2026-10-31", "past"), exam("2026-12-06", "future"),
            exam("2026-12-07", "hidden", hidden = 1), exam("2026-12-08", "other", "other-table"))
        val summary = project(events = events)
        assertEquals(listOf("future"), summary.remaining.map { it.id })
        assertEquals(listOf("past"), summary.ended.map { it.id })
        assertEquals(2, summary.importedCount)
        assertTrue(TodayProjection.project(window, events, now).allToday.isEmpty())
    }
    @Test fun `bound remote past and future exams deduplicate imported records in both groups`() {
        val past = row(date = "2026-10-31")
        val future = row(date = "2026-12-06")
        val imported = listOf(past, future).map { requireNotNull(ExamEventMapper.toEvent(it.toExamItem(), "table", zoneId = window.zoneId)) }
        val remote = source(listOf(past, future))
        val raw = project(remote)
        assertEquals(1, raw.remaining.size)
        assertEquals(1, raw.ended.size)
        val merged = project(remote, imported)
        assertEquals(listOf(imported[1].id), merged.remaining.map { it.id })
        assertEquals(listOf(imported[0].id), merged.ended.map { it.id })
        assertTrue(project(remote, owner = "another").ended.isEmpty())
    }
    @Test fun `raw records dedupe imported EAS exams but never personal exams with same title`() {
        val mapped = requireNotNull(ExamEventMapper.toEvent(row().toExamItem(), "table", zoneId = window.zoneId)).apply { id = "eas" }
        val personal = requireNotNull(ExamEventMapper.toEvent(row().toExamItem(), "table", zoneId = window.zoneId)).apply {
            id = "personal"; source = EventItem.SOURCE_MANUAL
        }
        val result = project(source(listOf(row())), listOf(mapped, personal))
        assertEquals(2, result.importedCount)
        assertEquals(setOf("eas", "personal"), result.remaining.map { it.id }.toSet())
    }
    @Test fun `account or session mismatch rejects all raw source data`() {
        val remote = source(listOf(row(), row(null)))
        assertTrue(project(remote, owner = "another").remaining.isEmpty())
        assertTrue(project(remote, generation = 2).coverage.isEmpty())
        assertTrue(project(remote, owner = null).timeToConfirm.isEmpty())
    }
    @Test fun `retained failure remains labelled and truncation does not imply complete coverage`() {
        val retained = ExamReadResult("term", "table", listOf(row()), truncated = true)
        val result = project(ExamReadSnapshot("owner", 1, mapOf("term" to TodayReadState.Failed(retained, IllegalStateException("synthetic")))))
        assertEquals(TodaySourceStatus.FAILED, result.coverage.single().status)
        assertTrue(result.coverage.single().truncated)
        assertTrue(result.remaining.single().retained)
    }
    @Test fun `duplicate all term and single term queries do not double count same raw occurrence`() {
        val result = project(ExamReadSnapshot("owner", 1, mapOf(
            "term" to TodayReadState.Ready(ExamReadResult("term", "table", listOf(row(), row().copy(examType = "另一分类")))),
            "*" to TodayReadState.Ready(ExamReadResult("term", "table", listOf(row()))))))
        assertEquals(1, result.remaining.size)
    }
    @Test fun `mapper uses explicit zone consistently with the day window`() {
        val exam = row().toExamItem()
        val utc = requireNotNull(ExamEventMapper.toEvent(exam, "table", zoneId = ZoneId.of("UTC")))
        val shanghai = requireNotNull(ExamEventMapper.toEvent(exam, "table", zoneId = window.zoneId))
        assertEquals(8 * 3_600_000L, utc.from.time - shanghai.from.time)
    }
}
