package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.utils.CourseNameUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Timestamp

class EasImportIdentityTest {
    @Test
    fun subjectLookupKeys_useStructuredCodeAndCanonicalNames() {
        val keys = EasImportIdentity.subjectLookupKeys(
            code = "MATH1001",
            normalizedName = "高等数学",
            rawName = "高等数学（A）"
        )

        assertTrue("code:MATH1001" in keys)
        assertTrue("name:高等数学" in keys)
        assertTrue("name:高等数学（A）" in keys)
    }

    @Test
    fun subjectLookupKeys_mergeExistingShenzhenTeachingClassWithCanonicalCourse() {
        val rawName = "计算机设计与实践 1/E班"
        val keys = EasImportIdentity.subjectLookupKeys(
            code = null,
            normalizedName = CourseNameUtils.normalize(rawName),
            rawName = rawName
        )

        assertTrue("name:计算机设计与实践" in keys)
        assertTrue("name:计算机设计与实践 1/E班" in keys)
    }

    @Test
    fun subjectsMatch_doesNotGlueDifferentCodesThatShareANormalizedName() {
        assertTrue(
            EasImportIdentity.subjectsMatch("MATH-A", "高等数学（A）", "MATH-A", "高等数学A")
        )
        assertFalse(
            EasImportIdentity.subjectsMatch("MATH-A", "高等数学（A）", "MATH-B", "高等数学（B）")
        )
        assertTrue(
            EasImportIdentity.subjectsMatch(null, "计算机设计与实践 1/E班", "", "计算机设计与实践")
        )
    }

    @Test
    fun classEventIdentityKey_matchesSameClassAcrossRepeatedImports() {
        val first = classEvent()
        val repeated = classEvent().apply {
            id = "different-row-id"
            subjectId = "different-subject-id"
            createdAt = Timestamp(987654321L)
        }

        assertEquals(
            EasImportIdentity.classEventIdentityKey(first),
            EasImportIdentity.classEventIdentityKey(repeated)
        )
    }

    @Test
    fun classEventIdentityKey_keepsDifferentTimeSlotsSeparate() {
        val first = classEvent()
        val second = classEvent().apply {
            from = Timestamp(2000L)
            to = Timestamp(3000L)
        }

        assertNotEquals(
            EasImportIdentity.classEventIdentityKey(first),
            EasImportIdentity.classEventIdentityKey(second)
        )
    }

    @Test
    fun classEventIdentityKey_keepsFreeTimeExperimentsOnTheSameDaySeparate() {
        val morning = classEvent().apply {
            name = "电路实验"
            fromNumber = 0
            lastNumber = 0
            from = Timestamp.valueOf("2026-10-20 08:00:00")
            to = Timestamp.valueOf("2026-10-20 10:00:00")
        }
        val afternoon = classEvent().apply {
            name = morning.name
            fromNumber = 0
            lastNumber = 0
            from = Timestamp.valueOf("2026-10-20 13:00:00")
            to = Timestamp.valueOf("2026-10-20 15:00:00")
        }
        assertEquals(
            2,
            listOf(morning, afternoon).map(EasImportIdentity::classEventIdentityKey).toSet().size
        )
    }

    @Test
    fun classEventIdentityKey_keepsDifferentExperimentsAtTheSameTimeSeparate() {
        val circuit = classEvent().apply {
            name = "电路实验"
            fromNumber = 0
            lastNumber = 0
            from = Timestamp.valueOf("2026-10-20 08:00:00")
            to = Timestamp.valueOf("2026-10-20 10:00:00")
        }
        val electromagnetism = classEvent().apply {
            name = "电磁实验"
            fromNumber = 0
            lastNumber = 0
            from = circuit.from
            to = circuit.to
        }
        assertNotEquals(
            EasImportIdentity.classEventIdentityKey(circuit),
            EasImportIdentity.classEventIdentityKey(electromagnetism)
        )
    }

    private fun classEvent(): EventItem {
        return EventItem().apply {
            type = EventItem.TYPE.CLASS
            source = EventItem.SOURCE_EAS_IMPORT
            name = "高等数学（A）"
            place = "正心楼 101"
            teacher = "张三"
            timetableId = "term-2026-spring"
            subjectId = "subject-1"
            from = Timestamp(1000L)
            to = Timestamp(2000L)
            fromNumber = 1
            lastNumber = 2
        }
    }
}
