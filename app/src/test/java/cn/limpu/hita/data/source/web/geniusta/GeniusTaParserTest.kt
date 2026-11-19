package cn.limpu.hita.data.source.web.geniusta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class GeniusTaParserTest {
    private val grouped = GeniusTaCourse("test-course", "Synthetic Course", "group-a")
    private val ungrouped = grouped.copy(sectionId = null)
    private val baseDue = "2026-04-09T00:00:00Z"
    private val midnightDue = "2026-04-04T00:15:00+08:00"
    private val dayBeforeUtc = Instant.parse("2026-04-03T16:15:00Z").toEpochMilli()

    @Test fun applicableSectionWinsAndOffsetIsAppliedAcrossMidnight() {
        val assignment = row(sections = """[
            {"section_id":"group-b","due_date":"$baseDue"},
            {"section_id":"group-a","due_date":"$midnightDue"}
        ]""", ungroupedSchedule = """{"due_date":null}""")
        val tasks = GeniusTaParser.assignments(envelope(assignment), grouped)
        assertEquals(listOf(GeniusTaAssignment("test-assignment", "Synthetic Assignment", grouped.title, dayBeforeUtc)), tasks)
    }

    @Test fun noMatchingSectionUsesBaseAndOnlyUngroupedStudentUsesUngroupedOverride() {
        val assignment = row(sections = """[{"section_id":"group-b","due_date":null}]""",
            ungroupedSchedule = """{"due_date":"$midnightDue"}""")
        assertEquals(Instant.parse(baseDue).toEpochMilli(), GeniusTaParser.assignments(envelope(assignment), grouped).single().dueAt)
        assertEquals(dayBeforeUtc, GeniusTaParser.assignments(envelope(assignment), ungrouped).single().dueAt)
        assertEquals(Instant.parse(baseDue).toEpochMilli(), GeniusTaParser.assignments(envelope(row()), ungrouped).single().dueAt)
    }

    @Test fun explicitNullDeadlineDoesNotFallBackToBaseOrLateDeadline() {
        assertEquals(emptyList<GeniusTaAssignment>(), GeniusTaParser.assignments(envelope(row(
            sections = """[{"section_id":"group-a","due_date":null}]""",
        )), grouped))
        assertEquals(emptyList<GeniusTaAssignment>(), GeniusTaParser.assignments(envelope(row(
            ungroupedSchedule = """{"due_date":null}""",
        )), ungrouped))
        assertEquals(emptyList<GeniusTaAssignment>(), GeniusTaParser.assignments(envelope(row(due = null)), ungrouped))
    }

    @Test fun onlyKnownUnsubmittedAndReleasedRowsWithDatesAreImported() {
        val accepted = listOf("not_submitted", "late_unsubmitted", "makeup_unsubmitted", "closed_unsubmitted")
        val rejected = listOf("submitted", "late", "makeup", "update_required", "unknown", null)
        val rows = (accepted + rejected).mapIndexed { index, status ->
            row(id = "assignment-$index", status = status, due = "2001-01-01T00:00:00Z")
        } + row(id = "unreleased", released = false) + row(id = "undated", due = null)
        assertEquals(accepted.indices.map { "assignment-$it" }, GeniusTaParser.assignments(envelope(*rows.toTypedArray()), grouped).map { it.id })
    }

    @Test fun roleAndSectionComeFromTheVerifiedWireFields() {
        val json = """{"data":[
            {"public_id":"test-course","name":"Synthetic Course","current_user_role":"student","current_user_section_id":"group-a"},
            {"public_id":"ungrouped-course","name":"Ungrouped Course","current_user_role":"student","current_user_section_id":null},
            {"public_id":"staff-course","name":"Staff Course","current_user_role":"teacher","current_user_section_id":null}
        ]}"""
        assertEquals(listOf(grouped, GeniusTaCourse("ungrouped-course", "Ungrouped Course", null)), GeniusTaParser.courses(json))
    }

    @Test fun malformedDeadlineOrEnvelopeDoesNotLookLikeSuccessfulEmptyRefresh() {
        assertThrows(GeniusTaInvalidResponseException::class.java) {
            GeniusTaParser.assignments(envelope(row(due = "2026-04-09T00:00:00")), grouped)
        }
        assertThrows(GeniusTaInvalidResponseException::class.java) {
            GeniusTaParser.assignments(envelope(row(sections = """[{"section_id":"group-a"}]""")), grouped)
        }
        assertThrows(GeniusTaInvalidResponseException::class.java) {
            GeniusTaParser.courses("""{data:[]}""")
        }
    }

    @Test fun deeplyNestedEnvelopeIsRejectedBeforeRecursiveDecoding() {
        val nested = "[".repeat(80) + "null" + "]".repeat(80)
        assertThrows(GeniusTaInvalidResponseException::class.java) {
            GeniusTaParser.courses("""{"data":[],"extra":$nested}""")
        }
    }

    private fun envelope(vararg rows: String) = """{"data":[${rows.joinToString()}]}"""

    private fun row(
        id: String = "test-assignment",
        status: String? = "not_submitted",
        released: Boolean = true,
        due: String? = baseDue,
        sections: String = "[]",
        ungroupedSchedule: String = "null",
    ) = """{
        "public_id":"$id", "title":"Synthetic Assignment", "is_released":$released,
        "due_date":${due?.let { "\"$it\"" } ?: "null"}, "late_due_date":"2026-04-10T00:00:00Z",
        "section_schedules":$sections, "ungrouped_schedule":$ungroupedSchedule,
        "submission_state":${status?.let { """{"status":"$it"}""" } ?: "null"}
    }"""
}
