package cn.limpu.hita.feature.timetableshare.protocol

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

/** Synthetic measurements only: no real timetable, raw token or payload is printed. */
class ShareTokenSizeTest {
    private val codec = TimetableShareCodec()

    @Test fun realisticSyntheticTermsPreserveEveryOccurrenceInBothModes() {
        listOf(4, 8, 16).forEach { courses ->
            val base = fullFixture()
            val events = (0 until 16).flatMap { week ->
                (0 until courses).map { course ->
                    val start = base.occurrences.single().startMillis +
                        (week * 7L + course % 5) * 86400000L + (course / 5) * 7200000L
                    SharedOccurrence(start, start + 6300000L, course, "教学楼${course % 3 + 1}-${100 + course}")
                }
            }
            val full = base.copy(courses = (0 until courses).map { "合成课程${it + 1}" }, occurrences = events)
            measure(full, courses, events.size)
            val busy = full.copy(mode = ShareMode.BUSY, courses = emptyList(),
                occurrences = events.map { SharedOccurrence(it.startMillis, it.endMillis) })
            measure(busy, 0, events.size)
            val json = codec.canonicalJson(busy)
            assertFalse(json.contains("\"c\""))
            assertFalse(json.contains("合成课程"))
            assertFalse(json.contains("教学楼"))
        }
    }

    @Test fun denseTermFitsTokenLimitWithoutDroppingOccurrences() {
        val base = fullFixture()
        val dense = base.copy(occurrences = (0 until 100).map { day ->
            SharedOccurrence(1788136200000L + day * 86400000L,
                1788142500000L + day * 86400000L, 0, "A101")
        })
        measure(dense, 1, 100)
        measure(dense.copy(mode = ShareMode.BUSY, courses = emptyList(),
            occurrences = dense.occurrences.map { SharedOccurrence(it.startMillis, it.endMillis) }), 0, 100)
    }

    private fun measure(snapshot: SharedTimetable, courseCount: Int, occurrenceCount: Int) {
        val token = codec.encode(snapshot)
        val decoded = codec.decodeMessage(token)
        assertTrue(token.length <= ShareLimits.TOKEN_CHARS)
        assertEquals(courseCount, decoded.courses.size)
        assertEquals(occurrenceCount, decoded.occurrences.size)
        assertEquals(codec.decodeJson(codec.canonicalJson(snapshot)), decoded)
        val jsonBytes = codec.canonicalJson(snapshot).toByteArray(Charsets.UTF_8).size
        val compressedBytes = Base64.getUrlDecoder().decode(token.removePrefix("HITA1:")).size
        println("SYNTHETIC mode=${snapshot.mode} courses=$courseCount occurrences=$occurrenceCount jsonBytes=$jsonBytes compressedBytes=$compressedBytes tokenChars=${token.length}")
    }
}
